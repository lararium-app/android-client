package app.lararium.hearth.rails.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.lararium.MainActivity
import app.lararium.R
import app.lararium.hearth.rails.ActionSheetLogic
import app.lararium.hearth.rails.DeviceStreamState
import app.lararium.hearth.rails.DeviceStreamStateMachine
import app.lararium.hearth.rails.OfflineCause
import app.lararium.hearth.rails.RailsWire
import app.lararium.hearth.rails.ReceiptQueue
import app.lararium.hearth.rails.SeqTracker
import app.lararium.hearth.rails.StreamEvent
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dedicated foreground service maintaining device rails stream liveness and push sync (F3, spec A2).
 *
 * Foreground service type: dataSync
 * Hosts:
 * - Persistent SSE stream client and epoch-guarded reconnect supervisor
 * - ConnectivityManager.NetworkCallback for network transitions
 * - SyncEngine instances for enabled read rails (calendar, contacts, calllog, notifications)
 * - ReceiptQueue drain worker for double-gated action resolutions
 */
class DeviceRailService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "lararium_device_rails"
        const val CHANNEL_NAME = "Lararium device rails"

        val receiptQueue = ReceiptQueue()
        val streamState = kotlinx.coroutines.flow.MutableStateFlow<DeviceStreamState>(DeviceStreamState.Idle)

        @Volatile
        var isRunning: Boolean = false
            private set

        fun ensureRunning(context: Context) {
            val store = DevicePairingStore(context)
            val hasHearth = try {
                SecureKeyManager(context).getHearthEndpoint() != null
            } catch (_: Exception) {
                false
            }
            if (store.isPaired() || hasHearth) {
                val intent = Intent(context, DeviceRailService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stopIfIdle(context: Context) {
            val store = DevicePairingStore(context)
            if (!store.isPaired()) {
                context.stopService(Intent(context, DeviceRailService::class.java))
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val stateMachine = DeviceStreamStateMachine()
    val seqTracker = SeqTracker()
    val receiptQueue get() = DeviceRailService.receiptQueue
    val actionSheetLogic = ActionSheetLogic()

    private lateinit var pairingStore: DevicePairingStore
    private lateinit var streamClient: DeviceStreamClient
    private lateinit var receiptDrain: ReceiptDrain

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var supervisorJob: Job? = null
    private var reconnectDelayJob: Job? = null
    private var receiptDrainJob: Job? = null
    private var periodicSyncJob: Job? = null

    private val syncEngines = mutableListOf<SyncEngine>()
    private val dataSources = mutableListOf<RailDataSource>()
    private val toolExecutors = mutableListOf<RailToolExecutor>()

    override fun onCreate() {
        super.onCreate()
        pairingStore = DevicePairingStore(this)
        createNotificationChannel()

        streamClient = DeviceStreamClient(
            endpointProvider = { pairingStore.getEndpoint() },
            stateMachine = stateMachine
        )

        receiptDrain = ReceiptDrain(
            receiptQueue = receiptQueue,
            endpointProvider = { pairingStore.getEndpoint() }
        )

        toolExecutors.add(DialerToolExecutor(this))
        toolExecutors.add(SmsToolExecutor(this))

        setupDataSources()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!pairingStore.isPaired()) {
            val keyManager = try {
                SecureKeyManager(this)
            } catch (_: Exception) {
                null
            }
            val hasHearth = keyManager?.getHearthEndpoint() != null
            if (hasHearth) {
                val initialNotification = buildNotification("Registering device rails…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
                isRunning = true

                serviceScope.launch {
                    val result = DevicePairingController.autoRegister(this@DeviceRailService)
                    if (result.isSuccess) {
                        updateNotification(stateMachine.state)
                        startSupervisor()
                        startReceiptDrainWorker()
                        startPeriodicSync()
                    } else {
                        android.util.Log.w(
                            "DeviceRailService",
                            "Auto-registration failed: ${result.error}. Retrying next service start."
                        )
                        stopSelf()
                    }
                }
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val initialNotification = buildNotification(stateText(stateMachine.state))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        isRunning = true

        startSupervisor()
        startReceiptDrainWorker()
        startPeriodicSync()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        unregisterNetworkCallback()
        dataSources.forEach { it.unregisterObserver() }
        serviceScope.cancel()
    }

    private fun setupDataSources() {
        val calendarSource = CalendarDataSource(this)
        val contactsSource = ContactsDataSource(this)
        val callLogSource = CallLogDataSource(this)
        val notificationsSource = NotificationsDataSource(this)
        val healthSource = HealthDataSource(this)

        dataSources.addAll(listOf(calendarSource, contactsSource, callLogSource, notificationsSource, healthSource))

        val calendarEngine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { calendarSource.snapshot() },
            seqTracker = seqTracker,
            endpointProvider = { pairingStore.getEndpoint() },
            coroutineScope = serviceScope
        )
        calendarSource.registerObserver { calendarEngine.notifyDataChanged() }

        val contactsEngine = SyncEngine(
            rail = "contacts",
            snapshotProvider = { contactsSource.snapshot() },
            seqTracker = seqTracker,
            endpointProvider = { pairingStore.getEndpoint() },
            coroutineScope = serviceScope
        )
        contactsSource.registerObserver { contactsEngine.notifyDataChanged() }

        val callLogEngine = SyncEngine(
            rail = "calllog",
            snapshotProvider = { callLogSource.snapshot() },
            seqTracker = seqTracker,
            endpointProvider = { pairingStore.getEndpoint() },
            coroutineScope = serviceScope
        )
        callLogSource.registerObserver { callLogEngine.notifyDataChanged() }

        val notificationsEngine = SyncEngine(
            rail = "notifications",
            snapshotProvider = { notificationsSource.snapshot() },
            seqTracker = seqTracker,
            endpointProvider = { pairingStore.getEndpoint() },
            coroutineScope = serviceScope
        )
        notificationsSource.registerObserver { notificationsEngine.notifyDataChanged() }

        val healthEngine = SyncEngine(
            rail = "health",
            snapshotProvider = { healthSource.snapshot() },
            seqTracker = seqTracker,
            endpointProvider = { pairingStore.getEndpoint() },
            coroutineScope = serviceScope
        )

        syncEngines.addAll(listOf(calendarEngine, contactsEngine, callLogEngine, notificationsEngine, healthEngine))
    }

    private fun startSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = serviceScope.launch {
            while (isActive) {
                if (!pairingStore.isPaired()) {
                    stopSelf()
                    break
                }

                if (stateMachine.state is DeviceStreamState.RepairRequired) {
                    updateNotification(stateMachine.state)
                    // Terminal until re-paired; halt retries completely
                    break
                }

                updateNotification(stateMachine.state)

                try {
                    streamClient.openStream { event, data ->
                        handleStreamFrame(event, data)
                    }
                } catch (_: Exception) {}

                updateNotification(stateMachine.state)

                val currentState = stateMachine.state
                if (currentState is DeviceStreamState.Reconnecting) {
                    reconnectDelayJob = launch {
                        delay(currentState.nextDelayMs)
                    }
                    reconnectDelayJob?.join()
                } else if (currentState is DeviceStreamState.RepairRequired) {
                    updateNotification(currentState)
                    break
                }
            }
        }
    }

    private fun handleStreamFrame(event: String?, data: org.json.JSONObject) {
        val streamEvent = RailsWire.parseStreamEvent(event, data)
        when (streamEvent) {
            is StreamEvent.ActionDispatch -> {
                app.lararium.hearth.rails.ui.InFlightActions.put(streamEvent.action, this)
            }
            is StreamEvent.PairRequired -> {
                stateMachine.onFailure(OfflineCause.PairRequired)
                streamState.value = DeviceStreamState.RepairRequired
                updateNotification(stateMachine.state)
            }
            StreamEvent.Unknown -> {}
        }
    }

    private fun startReceiptDrainWorker() {
        if (receiptDrainJob?.isActive == true) return
        receiptDrainJob = serviceScope.launch {
            while (isActive) {
                if (receiptQueue.pending().isNotEmpty()) {
                    receiptDrain.drainOnce()
                }
                delay(5_000L)
            }
        }
    }

    private fun startPeriodicSync() {
        if (periodicSyncJob?.isActive == true) return
        periodicSyncJob = serviceScope.launch {
            while (isActive) {
                syncEngines.forEach { engine ->
                    try {
                        engine.sync()
                    } catch (_: Exception) {}
                }
                delay(15 * 60 * 1000L) // 15 minutes reconciliation
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    stateMachine.onFailure(OfflineCause.NoNetwork)
                    updateNotification(stateMachine.state)
                }

                override fun onAvailable(network: Network) {
                    // Instantly trigger reconnect on network switch/restore
                    if (stateMachine.state is DeviceStreamState.Reconnecting) {
                        reconnectDelayJob?.cancel()
                    }
                }
            }
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun updateNotification(state: DeviceStreamState) {
        streamState.value = state
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(stateText(state)))
    }

    private fun stateText(state: DeviceStreamState): String = when (state) {
        is DeviceStreamState.Live -> "Live"
        is DeviceStreamState.Connecting -> "Connecting"
        is DeviceStreamState.Reconnecting -> "Reconnecting (${state.attempt})"
        is DeviceStreamState.RepairRequired -> "Repair required"
        is DeviceStreamState.Idle -> "Idle"
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val publicNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setContentText("Lararium device link active")
            .setSmallIcon(R.drawable.ic_assistant)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notification for device rails foreground service"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }
}
