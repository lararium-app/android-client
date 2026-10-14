package app.lararium.hearth.watch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for pure-Kotlin WatchLogic (cards parsing/diffing, event scanning, truncation).
 */
class WatchLogicTest {

    // -----------------------------------------------------------------------
    // parseCards
    // -----------------------------------------------------------------------

    @Test
    fun parseCards_fullShape() {
        val json = JSONObject().apply {
            put(
                "cards",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "card-123")
                            put("cell", "cell-alpha")
                            put("cred", "cred-prod")
                            put("dest", "https://api.example.com")
                            put("tool", "bash")
                            put("review", "Review tool execution on cluster")
                            put("age_s", 12)
                            put("expires_in_s", 300)
                        }
                    )
                }
            )
        }.toString()

        val cards = parseCards(json)
        assertEquals(1, cards.size)
        val card = cards[0]
        assertEquals("card-123", card.id)
        assertEquals("cell-alpha", card.cell)
        assertEquals("cred-prod", card.cred)
        assertEquals("https://api.example.com", card.dest)
        assertEquals("bash", card.tool)
        assertEquals("Review tool execution on cluster", card.review)
    }

    @Test
    fun parseCards_laneMissingFields() {
        // Worker card: tool set, dest empty.
        // Parked-egress: dest set, tool empty.
        val json = JSONObject().apply {
            put(
                "cards",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "worker-1")
                            put("tool", "python_repl")
                            // dest missing
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("id", "egress-1")
                            put("dest", "s3.amazonaws.com")
                            // tool missing
                        }
                    )
                }
            )
        }.toString()

        val cards = parseCards(json)
        assertEquals(2, cards.size)

        assertEquals("worker-1", cards[0].id)
        assertEquals("python_repl", cards[0].tool)
        assertEquals("", cards[0].dest)
        assertEquals("", cards[0].cell)
        assertEquals("", cards[0].cred)
        assertEquals("", cards[0].review)

        assertEquals("egress-1", cards[1].id)
        assertEquals("", cards[1].tool)
        assertEquals("s3.amazonaws.com", cards[1].dest)
        assertEquals("", cards[1].cell)
        assertEquals("", cards[1].cred)
        assertEquals("", cards[1].review)
    }

    @Test
    fun parseCards_skipsNonObjectAndBlankId() {
        val json = JSONObject().apply {
            put(
                "cards",
                JSONArray().apply {
                    put("not an object")
                    put(42)
                    put(
                        JSONObject().apply {
                            put("id", "   ") // blank id
                            put("tool", "sh")
                        }
                    )
                    put(
                        JSONObject().apply {
                            // missing id
                            put("tool", "sh")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("id", "valid-1")
                            put("tool", "curl")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("id", "valid-2")
                            put("dest", "example.org")
                        }
                    )
                }
            )
        }.toString()

        val cards = parseCards(json)
        assertEquals(2, cards.size)
        assertEquals("valid-1", cards[0].id)
        assertEquals("valid-2", cards[1].id)
    }

    @Test
    fun parseCards_garbageBody_returnsEmptyList() {
        assertEquals(emptyList<CardInfo>(), parseCards("not valid json"))
        assertEquals(emptyList<CardInfo>(), parseCards(""))
        assertEquals(emptyList<CardInfo>(), parseCards("{bad json"))
        assertEquals(emptyList<CardInfo>(), parseCards("[]"))
        assertEquals(emptyList<CardInfo>(), parseCards("{}"))
        assertEquals(emptyList<CardInfo>(), parseCards("""{"cards": "not-an-array"}"""))
    }

    // -----------------------------------------------------------------------
    // diffCards
    // -----------------------------------------------------------------------

    @Test
    fun diffCards_newCardAlerts() {
        val c1 = CardInfo(id = "c1", cell = "", cred = "", dest = "", tool = "toolA", review = "")
        val c2 = CardInfo(id = "c2", cell = "", cred = "", dest = "destB", tool = "", review = "")
        val pending = listOf(c1, c2)
        val notified = emptySet<String>()

        val diff = diffCards(pending, notified)
        assertEquals(listOf(c1, c2), diff.notify)
        assertTrue(diff.prune.isEmpty())
    }

    @Test
    fun diffCards_alreadyNotifiedDoesNotAlert() {
        val c1 = CardInfo(id = "c1", cell = "", cred = "", dest = "", tool = "toolA", review = "")
        val c2 = CardInfo(id = "c2", cell = "", cred = "", dest = "destB", tool = "", review = "")
        val pending = listOf(c1, c2)
        val notified = setOf("c1")

        val diff = diffCards(pending, notified)
        assertEquals(listOf(c2), diff.notify)
        assertTrue(diff.prune.isEmpty())
    }

    @Test
    fun diffCards_pruneRemovesVanishedIds() {
        val c2 = CardInfo(id = "c2", cell = "", cred = "", dest = "destB", tool = "", review = "")
        val pending = listOf(c2)
        val notified = setOf("c1", "c2", "c3")

        val diff = diffCards(pending, notified)
        assertTrue(diff.notify.isEmpty())
        assertEquals(setOf("c1", "c3"), diff.prune)
    }

    @Test
    fun diffCards_reParkedCardAlertsAgain() {
        val c1 = CardInfo(id = "c1", cell = "", cred = "", dest = "", tool = "toolA", review = "")
        var notified = setOf("c1")

        // Card c1 vanishes
        val vanishedDiff = diffCards(emptyList(), notified)
        assertEquals(setOf("c1"), vanishedDiff.prune)
        // Store updates notified set
        notified = (notified - vanishedDiff.prune) + emptySet()
        assertEquals(emptySet<String>(), notified)

        // Card c1 reappears later
        val reappearedDiff = diffCards(listOf(c1), notified)
        assertEquals(listOf(c1), reappearedDiff.notify)
        assertTrue(reappearedDiff.prune.isEmpty())
    }

    // -----------------------------------------------------------------------
    // scanEvents
    // -----------------------------------------------------------------------

    @Test
    fun scanEvents_cursorNull_baselinesSilently() {
        val json = JSONObject().apply {
            put(
                "events",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 1); put("text", "hi")
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "user"); put("seq", 2); put("text", "run")
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 5); put("text", "done")
                    })
                }
            )
            put("in_flight", false)
        }.toString()

        val result = scanEvents(json, cursor = null)
        assertFalse(result.seenBefore)
        assertEquals(5, result.newCursor)
        assertTrue(result.replies.isEmpty())
    }

    @Test
    fun scanEvents_cursorSet_filtersToNewAssistantMsgs() {
        val json = JSONObject().apply {
            put(
                "events",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 1); put("text", "old 1")
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 2); put("text", "old 2")
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 3); put("text", "new reply")
                    })
                    put(JSONObject().apply {
                        put("t", "tool_call"); put("role", "assistant"); put("seq", 4)
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "user"); put("seq", 5); put("text", "user msg")
                    })
                    put(JSONObject().apply {
                        put("t", "tool_result"); put("seq", 6)
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 7); put("text", "another reply")
                    })
                }
            )
            put("in_flight", false)
        }.toString()

        val result = scanEvents(json, cursor = 2)
        assertTrue(result.seenBefore)
        assertEquals(7, result.newCursor)
        assertEquals(2, result.replies.size)
        assertEquals(ReplyPreview(3, "new reply"), result.replies[0])
        assertEquals(ReplyPreview(7, "another reply"), result.replies[1])
    }

    @Test
    fun scanEvents_toolCallToolResultUserMsgsIgnored() {
        val json = JSONObject().apply {
            put(
                "events",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("t", "tool_call"); put("role", "assistant"); put("seq", 10)
                    })
                    put(JSONObject().apply {
                        put("t", "tool_result"); put("seq", 11)
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "user"); put("seq", 12); put("text", "hey")
                    })
                }
            )
        }.toString()

        val result = scanEvents(json, cursor = 9)
        assertTrue(result.seenBefore)
        assertEquals(12, result.newCursor)
        assertTrue(result.replies.isEmpty())
    }

    @Test
    fun scanEvents_missingSeqIgnored_cursorAdvancedByOthers() {
        val json = JSONObject().apply {
            put(
                "events",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("text", "no seq")
                    })
                    put(JSONObject().apply {
                        put("t", "msg"); put("role", "assistant"); put("seq", 4); put("text", "has seq")
                    })
                }
            )
        }.toString()

        val result = scanEvents(json, cursor = 1)
        assertTrue(result.seenBefore)
        assertEquals(4, result.newCursor)
        assertEquals(1, result.replies.size)
        assertEquals(ReplyPreview(4, "has seq"), result.replies[0])
    }

    @Test
    fun scanEvents_malformedBody_noThrow_returnsInputCursorAndEmptyReplies() {
        val result1 = scanEvents("malformed", cursor = 5)
        assertTrue(result1.seenBefore)
        assertEquals(5, result1.newCursor)
        assertTrue(result1.replies.isEmpty())

        val result2 = scanEvents("", cursor = null)
        assertFalse(result2.seenBefore)
        assertEquals(0, result2.newCursor)
        assertTrue(result2.replies.isEmpty())

        val result3 = scanEvents("{}", cursor = 8)
        assertTrue(result3.seenBefore)
        assertEquals(8, result3.newCursor)
        assertTrue(result3.replies.isEmpty())
    }

    // -----------------------------------------------------------------------
    // truncate
    // -----------------------------------------------------------------------

    @Test
    fun truncate_newlineCollapse() {
        assertEquals("hello world", truncate("hello\nworld"))
        assertEquals("hello world", truncate("hello\r\nworld"))
        assertEquals("hello world", truncate("hello\n\nworld"))
        assertEquals("hello world", truncate("hello\tworld"))
        assertEquals("hello world", truncate("hello\t\tworld"))
        assertEquals("hello world", truncate("  hello\nworld  "))
    }

    @Test
    fun truncate_boundaryExactlyAtMax_noEllipsis() {
        assertEquals("12345", truncate("12345", 5))
        assertEquals("hello", truncate("hello", 5))
        assertEquals("12 45", truncate("12\n45", 5))
    }

    @Test
    fun truncate_cutAppendsEllipsis() {
        assertEquals("12345…", truncate("123456", 5))
        assertEquals("12345…", truncate("1234567890", 5))
        assertEquals("hello…", truncate("hello world", 5))
    }
}
