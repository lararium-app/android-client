package app.lararium.hearth.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * State-machine tests for the live approver subscription: exact JSON-RPC
 * sequencing against the frozen daemon contract (auth.hello id 1,
 * custos.cards.subscribe id 2, custos.card / custos.gone / custos.resync
 * notifications), plus reconnect backoff shape.
 */
class CustosLiveLogicTest {

    private fun eventsOf(logic: CustosLiveLogic, frame: String) = logic.onFrame(frame)

    @Test
    fun helloOkAdvancesToSubscribe() {
        val logic = CustosLiveLogic()
        assertEquals(listOf<LiveEvent>(LiveEvent.HelloOk), eventsOf(logic, """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""))
        assertEquals(CustosLiveLogic.Phase.AWAIT_SUBSCRIBE, logic.phase)
    }

    @Test
    fun helloErrorStallsAtHello() {
        val logic = CustosLiveLogic()
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, """{"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"unauthorized"}}"""))
        assertEquals(CustosLiveLogic.Phase.AWAIT_HELLO, logic.phase)
    }

    @Test
    fun snapshotCompletesHandshake() {
        val logic = CustosLiveLogic()
        logic.onFrame("""{"id":1,"result":{"ok":true}}""")
        val events = eventsOf(logic, """{"id":2,"result":{"cards":[{"id":"c1","cell":"desk","cred":"ssh","dest":"box","tool":"rm","review":"drop"}]}}""")
        assertEquals(1, events.size)
        val snapshot = events[0] as LiveEvent.Snapshot
        assertEquals("c1", snapshot.cards.single().id)
        assertEquals("desk", snapshot.cards.single().cell)
        assertEquals(CustosLiveLogic.Phase.READY, logic.phase)
    }

    @Test
    fun emptySnapshotIsReadyWithNoCards() {
        val logic = CustosLiveLogic()
        logic.onFrame("""{"id":1,"result":{"ok":true}}""")
        val events = eventsOf(logic, """{"id":2,"result":{"cards":[]}}""")
        assertEquals(emptyList<LiveEvent>(), (events[0] as LiveEvent.Snapshot).cards)
        assertEquals(CustosLiveLogic.Phase.READY, logic.phase)
    }

    @Test
    fun methodNotFoundMarksUnsupported() {
        val logic = CustosLiveLogic()
        logic.onFrame("""{"id":1,"result":{"ok":true}}""")
        val events = eventsOf(logic, """{"id":2,"error":{"code":-32601,"message":"method not found"}}""")
        assertEquals(listOf<LiveEvent>(LiveEvent.Unsupported), events)
    }

    @Test
    fun notificationsIgnoredBeforeReady() {
        val logic = CustosLiveLogic()
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, """{"method":"custos.card","params":{"id":"c1","state":"pending"}}"""))
    }

    @Test
    fun pendingCardPushUpserts() {
        val logic = readyLogic()
        val events = eventsOf(logic, """{"method":"custos.card","params":{"id":"c9","state":"pending","cell":"kitchen","cred":"","dest":"","tool":"cam","review":"peek"}}""")
        val upsert = events.single() as LiveEvent.Upsert
        assertEquals("c9", upsert.card.id)
        assertEquals("kitchen", upsert.card.cell)
        assertEquals("peek", upsert.card.review)
    }

    @Test
    fun fullWireShapeCardPushUpserts() {
        val logic = readyLogic()
        val events = eventsOf(logic, """{"method":"custos.card","params":{"id":"c9","cell":"k","cred":"cr","tool":"t","dest":"d","review":"r","age_s":3,"expires_in_s":117}}""")
        val upsert = events.single() as LiveEvent.Upsert
        assertEquals("c9", upsert.card.id)
        assertEquals("d", upsert.card.dest)
    }

    @Test
    fun goneNotificationRemovesById() {
        val logic = readyLogic()
        assertEquals(
            listOf<LiveEvent>(LiveEvent.Gone("c4")),
            eventsOf(logic, """{"method":"custos.gone","params":{"id":"c4","state":"expired","reason":"ttl"}}""")
        )
    }

    @Test
    fun resyncNotificationTriggersReload() {
        val logic = readyLogic()
        assertEquals(listOf<LiveEvent>(LiveEvent.ResyncRequired), eventsOf(logic, """{"method":"custos.resync","params":{}}"""))
    }

    @Test
    fun unknownMethodsAndGarbageAreIgnored() {
        val logic = readyLogic()
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, """{"method":"something.else","params":{"id":"x"}}"""))
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, "not json at all"))
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, """{"id":99,"result":{}}"""))
        assertEquals(CustosLiveLogic.Phase.READY, logic.phase)
    }

    @Test
    fun goneWithoutIdIsIgnored() {
        val logic = readyLogic()
        assertEquals(emptyList<LiveEvent>(), eventsOf(logic, """{"method":"custos.gone","params":{}}"""))
    }

    @Test
    fun requestsCarryExpectedShape() {
        val logic = CustosLiveLogic()
        val hello = JSONObject(logic.helloRequest("tok/+=\"x"))
        assertEquals("2.0", hello.getString("jsonrpc"))
        assertEquals(1, hello.getInt("id"))
        assertEquals("auth.hello", hello.getString("method"))
        assertEquals("tok/+=\u0022x", hello.getJSONObject("params").getString("token"))
        val subscribe = JSONObject(logic.subscribeRequest())
        assertEquals(2, subscribe.getInt("id"))
        assertEquals("custos.cards.subscribe", subscribe.getString("method"))
    }

    @Test
    fun backoffDoublesToCeiling() {
        assertEquals(2_000L, CustosLiveLogic.backoffMs(1))
        assertEquals(4_000L, CustosLiveLogic.backoffMs(2))
        assertEquals(8_000L, CustosLiveLogic.backoffMs(3))
        assertEquals(16_000L, CustosLiveLogic.backoffMs(4))
        assertEquals(32_000L, CustosLiveLogic.backoffMs(5))
        assertEquals(60_000L, CustosLiveLogic.backoffMs(6))
        assertEquals(60_000L, CustosLiveLogic.backoffMs(50))
    }

    private fun readyLogic(): CustosLiveLogic {
        val logic = CustosLiveLogic()
        logic.onFrame("""{"id":1,"result":{"ok":true}}""")
        logic.onFrame("""{"id":2,"result":{"cards":[]}}""")
        return logic
    }
}
