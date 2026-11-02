package app.lararium.hearth.rails.ui

import app.lararium.hearth.rails.ActionSheetLogic
import app.lararium.hearth.rails.ActionState
import app.lararium.hearth.rails.DispatchedAction
import app.lararium.hearth.rails.ReceiptQueue
import app.lararium.hearth.rails.service.RailToolExecutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SheetPresenterTest {

    @Before
    fun setUp() {
        InFlightActions.clear()
    }

    @Test
    fun testCountdownTransitionsAt60000Boundary() {
        var now = 100_000L
        val logic = ActionSheetLogic(clock = { now })
        val action = DispatchedAction(
            actionId = "act-countdown",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0100") }
        )
        val receiptQueue = ReceiptQueue()
        var finished = false

        val presenter = SheetPresenter(
            action = action,
            logic = logic,
            receiptQueue = receiptQueue,
            onFinish = { finished = true }
        )

        assertEquals("60s", presenter.countdownLabel())
        assertFalse(presenter.isTerminal())
        assertFalse(finished)

        // Advance 30 seconds
        now += 30_000L
        assertFalse(presenter.onTick())
        assertEquals("30s", presenter.countdownLabel())
        assertFalse(presenter.isTerminal())

        // Advance to 59,500ms
        now = 159_500L
        assertFalse(presenter.onTick())
        assertEquals("1s", presenter.countdownLabel())
        assertFalse(presenter.isTerminal())

        // Advance to exactly 60,000ms boundary
        now = 160_000L
        val becameTerminal = presenter.onTick()
        assertTrue("onTick at 60_000ms boundary must transition to terminal", becameTerminal)
        assertTrue(presenter.isTerminal())
        assertEquals("Blocked", presenter.countdownLabel())
        assertTrue("Timed out action must trigger onFinish", finished)

        // Verify receipt enqueued
        val pending = receiptQueue.pending()
        assertEquals(1, pending.size)
        assertEquals("act-countdown", pending[0].actionId)
        assertEquals(false, pending[0].body.getBoolean("ok"))
        assertEquals("denied_local", pending[0].body.getString("error"))
    }

    @Test
    fun testBackPressedDeniesAction() {
        val action = DispatchedAction(
            actionId = "act-back",
            tool = "device.sms.send",
            args = JSONObject().apply { put("text", "test message") }
        )
        InFlightActions.put(action)

        val receiptQueue = ReceiptQueue()
        var finished = false

        val presenter = SheetPresenter(
            action = action,
            receiptQueue = receiptQueue,
            onFinish = { finished = true }
        )

        presenter.onBackPressed()

        assertTrue(presenter.isTerminal())
        assertTrue(presenter.currentState() is ActionState.Denied)
        assertTrue(finished)

        // Verify InFlightActions cleared
        assertNull(InFlightActions.get("act-back"))

        // Verify receipt enqueued
        val pending = receiptQueue.pending()
        assertEquals(1, pending.size)
        assertEquals(false, pending[0].body.getBoolean("ok"))
        assertEquals("denied_local", pending[0].body.getString("error"))
    }

    @Test
    fun testTerminalOnceGuarantee() {
        val action = DispatchedAction(
            actionId = "act-terminal-once",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0100") }
        )
        val receiptQueue = ReceiptQueue()
        var finishCallCount = 0

        val presenter = SheetPresenter(
            action = action,
            receiptQueue = receiptQueue,
            onFinish = { finishCallCount++ }
        )

        presenter.onApprove()
        assertEquals(1, finishCallCount)
        assertEquals(1, receiptQueue.pending().size)

        // Calling approve again or deny or tick must be no-ops
        presenter.onApprove()
        presenter.onDeny()
        presenter.onBackPressed()
        presenter.onTick()

        assertEquals("finish should only be invoked once", 1, finishCallCount)
        assertEquals("receipt should only be enqueued once", 1, receiptQueue.pending().size)
    }

    @Test
    fun testReceiptBodyWiredMockExecutorCallOrder() {
        val action = DispatchedAction(
            actionId = "act-wire-order",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "+15551234567") }
        )

        val callOrder = mutableListOf<String>()

        val mockExecutor = object : RailToolExecutor {
            override fun canHandle(tool: String): Boolean = (tool == "device.dialer.call")
            override suspend fun execute(action: DispatchedAction): Pair<Boolean, String> {
                callOrder.add("executor")
                return Pair(true, "call_placed")
            }
        }

        val receiptQueue = ReceiptQueue()
        var finished = false

        val presenter = SheetPresenter(
            action = action,
            executors = listOf(mockExecutor),
            receiptQueue = receiptQueue,
            receiptEnqueuer = { id, body ->
                callOrder.add("enqueue")
                receiptQueue.enqueue(id, body)
            },
            onFinish = { finished = true }
        )

        presenter.onApprove()

        assertTrue(finished)
        // Strict assertion: executor is called FIRST, THEN enqueue
        assertEquals(listOf("executor", "enqueue"), callOrder)

        val pending = receiptQueue.pending()
        assertEquals(1, pending.size)
        val receipt = pending[0].body
        assertEquals(true, receipt.getBoolean("ok"))
        assertEquals("call_placed", receipt.getString("result"))
        assertFalse(receipt.has("error"))
    }

    @Test
    fun testReceiptBodyWiredExecutorFailure() {
        val action = DispatchedAction(
            actionId = "act-fail",
            tool = "device.sms.send",
            args = JSONObject().apply { put("text", "hi") }
        )

        val mockExecutor = object : RailToolExecutor {
            override fun canHandle(tool: String): Boolean = (tool == "device.sms.send")
            override suspend fun execute(action: DispatchedAction): Pair<Boolean, String> {
                return Pair(false, "permission_revoked")
            }
        }

        val receiptQueue = ReceiptQueue()
        val presenter = SheetPresenter(
            action = action,
            executors = listOf(mockExecutor),
            receiptQueue = receiptQueue
        )

        presenter.onApprove()

        val pending = receiptQueue.pending()
        assertEquals(1, pending.size)
        val receipt = pending[0].body
        assertEquals(false, receipt.getBoolean("ok"))
        assertEquals("permission_revoked", receipt.getString("error"))
        assertFalse(receipt.has("result"))
    }
}
