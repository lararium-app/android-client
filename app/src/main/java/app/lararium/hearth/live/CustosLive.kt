package app.lararium.hearth.live

import android.content.Context
import app.lararium.hearth.Endpoint
import app.lararium.hearth.LarariumClient
import app.lararium.security.SecureKeyManager
import app.lararium.hearth.watch.CardInfo
import app.lararium.hearth.watch.WatchLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Holds the daemon's live approver subscription (auth.hello +
 * custos.cards.subscribe on /v1/ws) for as long as the app is in the
 * foreground with a paired hearth, and streams card deltas to the UI.
 *
 * The REST poll (approvals screen pull, watch worker) stays the backstop:
 * this channel only makes pending cards appear the instant they exist and
 * vanish the instant they resolve, without polling the whole tray.
 */
object CustosLive {

    /** One UI-visible change to the pending-card set. */
    sealed class Delta {
        data class ReplaceAll(val cards: List<CardInfo>) : Delta()
        data class Upsert(val card: CardInfo) : Delta()
        data class Gone(val id: String) : Delta()
    }

    private val _deltas = MutableSharedFlow<Delta>(extraBufferCapacity = 64)
    val deltas: SharedFlow<Delta> = _deltas

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var activeSocket: WsSocket? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop(context.applicationContext) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        // Blocking socket reads are not cancellation-aware; closing the socket
        // interrupts the read immediately instead of at the next timeout.
        activeSocket?.closeQuietly()
        activeSocket = null
        _connected.value = false
    }

    private class UnsupportedDaemonException : Exception()
    private class RepairRequiredException : Exception()

    private suspend fun runLoop(app: Context) {
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                val epTriple = SecureKeyManager(app).getHearthEndpoint()
                if (epTriple == null) {
                    // Not paired (or not yet): quiet idle; stop() also fires on unpair.
                    delay(5_000L)
                    continue
                }
                val endpoint = Endpoint(epTriple.first, epTriple.second, epTriple.third)
                try {
                    runSession(app, endpoint)
                    attempt = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedDaemonException) {
                    return // daemon predates the subscription; retry next foreground
                } catch (e: RepairRequiredException) {
                    return // token revoked/invalid: pairing itself is broken
                } catch (e: Exception) {
                    attempt++
                }
                delay(CustosLiveLogic.backoffMs(attempt))
            }
        } finally {
            _connected.value = false
        }
    }

    private suspend fun runSession(app: Context, endpoint: Endpoint) = withContext(Dispatchers.IO) {
        val ws = WsConnector.open(endpoint)
        activeSocket = ws
        // A silent link is a dead link: pings every 25s keep proxies open, and
        // 90s with zero inbound bytes means the tunnel dropped underneath us.
        ws.socket.soTimeout = 90_000
        val logic = CustosLiveLogic()
        val writeLock = Any()
        fun sendFrame(frame: ByteArray) = synchronized(writeLock) {
            ws.output.write(frame)
            ws.output.flush()
        }
        var closeCode = -1
        try {
            sendFrame(WsCodec.encodeText(logic.helloRequest(endpoint.token), WsCodec.newMaskKey()))
            _connected.value = true
            val reader = WsFrameReader(ws.input)
            val pingJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(25_000L)
                    try {
                        sendFrame(WsCodec.encode(WsCodec.OP_PING, ByteArray(0), WsCodec.newMaskKey()))
                    } catch (_: Exception) {
                        return@launch // session teardown handles the rest
                    }
                }
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val frame = reader.readMessage { pongPayload ->
                        try {
                            sendFrame(WsCodec.encode(WsCodec.OP_PONG, pongPayload, WsCodec.newMaskKey()))
                        } catch (_: Exception) {
                        }
                    }
                    if (frame.opcode == WsCodec.OP_CLOSE) {
                        closeCode = if (frame.payload.size >= 2) {
                            ((frame.payload[0].toInt() and 0xFF) shl 8) or
                                (frame.payload[1].toInt() and 0xFF)
                        } else {
                            1005
                        }
                        if (closeCode == CustosLiveLogic.CLOSE_UNAUTHORIZED ||
                            closeCode == CustosLiveLogic.CLOSE_POLICY_VIOLATION
                        ) {
                            throw RepairRequiredException()
                        }
                        return@withContext
                    }
                    if (frame.opcode != WsCodec.OP_TEXT) continue
                    val events = logic.onFrame(String(frame.payload, Charsets.UTF_8))
                    for (event in events) {
                        when (event) {
                            LiveEvent.HelloOk ->
                                sendFrame(WsCodec.encodeText(logic.subscribeRequest(), WsCodec.newMaskKey()))
                            is LiveEvent.Snapshot -> _deltas.emit(Delta.ReplaceAll(event.cards))
                            is LiveEvent.Upsert -> _deltas.emit(Delta.Upsert(event.card))
                            is LiveEvent.Gone -> _deltas.emit(Delta.Gone(event.id))
                            LiveEvent.ResyncRequired -> {
                                val body = LarariumClient(endpoint).custosCards()
                                _deltas.emit(Delta.ReplaceAll(WatchLogic.parseCards(body)))
                            }
                            LiveEvent.Unsupported -> throw UnsupportedDaemonException()
                        }
                    }
                }
            } finally {
                pingJob.cancelAndJoinQuietly()
            }
        } finally {
            try {
                if (closeCode == -1) {
                    sendFrame(WsCodec.encodeClose(1000, WsCodec.newMaskKey()))
                }
            } catch (_: Exception) {
            }
            if (activeSocket === ws) activeSocket = null
            ws.closeQuietly()
            _connected.value = false
        }
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        try {
            cancelAndJoin()
        } catch (_: Exception) {
        }
    }
}
