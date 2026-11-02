package app.lararium.hearth.rails

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DeltaBuilder snapshot diffing and nested JSON equality (A5).
 */
class DeltaBuilderTest {

    @Test
    fun identicalSnapshots_producesEmptyDeltas() {
        val row1 = Row("id-1", JSONObject().apply { put("title", "Event 1"); put("time", 1000) })
        val row2 = Row("id-2", JSONObject().apply { put("title", "Event 2"); put("time", 2000) })

        val previous = listOf(row1, row2)
        val current = listOf(
            Row("id-1", JSONObject().apply { put("title", "Event 1"); put("time", 1000) }),
            Row("id-2", JSONObject().apply { put("title", "Event 2"); put("time", 2000) })
        )

        val deltas = DeltaBuilder.diff(previous, current)
        assertTrue(DeltaBuilder.isEmpty(deltas))
        assertEquals(0, deltas.length())
    }

    @Test
    fun addRow_producesAddOpWithData() {
        val previous = emptyList<Row>()
        val row = Row("contact-1", JSONObject().apply { put("name", "Alice"); put("phone", "555-0101") })
        val current = listOf(row)

        val deltas = DeltaBuilder.diff(previous, current)
        assertFalse(DeltaBuilder.isEmpty(deltas))
        assertEquals(1, deltas.length())

        val op = deltas.getJSONObject(0)
        assertEquals("add", op.getString("op"))
        assertEquals("contact-1", op.getString("id"))
        assertEquals("Alice", op.getJSONObject("data").getString("name"))
        assertEquals("555-0101", op.getJSONObject("data").getString("phone"))
    }

    @Test
    fun updateRow_producesUpdateOpWithData() {
        val previous = listOf(
            Row("row-1", JSONObject().apply { put("status", "pending") })
        )
        val current = listOf(
            Row("row-1", JSONObject().apply { put("status", "confirmed") })
        )

        val deltas = DeltaBuilder.diff(previous, current)
        assertEquals(1, deltas.length())

        val op = deltas.getJSONObject(0)
        assertEquals("update", op.getString("op"))
        assertEquals("row-1", op.getString("id"))
        assertEquals("confirmed", op.getJSONObject("data").getString("status"))
    }

    @Test
    fun deleteRow_producesDeleteOpWithIdOnly() {
        val previous = listOf(
            Row("del-1", JSONObject().apply { put("title", "Old Event") })
        )
        val current = emptyList<Row>()

        val deltas = DeltaBuilder.diff(previous, current)
        assertEquals(1, deltas.length())

        val op = deltas.getJSONObject(0)
        assertEquals("delete", op.getString("op"))
        assertEquals("del-1", op.getString("id"))
        assertFalse("delete op must carry id ONLY, never data", op.has("data"))
    }

    @Test
    fun nestedJsonEquality_handlesObjectsAndArraysWithoutSpuriousDeltas() {
        val nestedPrev = JSONObject().apply {
            put("meta", JSONObject().apply {
                put("tags", JSONArray().apply { put("alpha"); put("beta") })
                put("priority", 1)
            })
        }
        val nestedCurrIdentical = JSONObject().apply {
            put("meta", JSONObject().apply {
                // Same content in same structure
                put("priority", 1)
                put("tags", JSONArray().apply { put("alpha"); put("beta") })
            })
        }

        val previous = listOf(Row("nested-1", nestedPrev))
        val current = listOf(Row("nested-1", nestedCurrIdentical))

        val deltasIdentical = DeltaBuilder.diff(previous, current)
        assertTrue("Identical nested structures must not produce update deltas", DeltaBuilder.isEmpty(deltasIdentical))

        // Modify nested array item
        val nestedModified = JSONObject().apply {
            put("meta", JSONObject().apply {
                put("tags", JSONArray().apply { put("alpha"); put("gamma") })
                put("priority", 1)
            })
        }
        val deltasModified = DeltaBuilder.diff(previous, listOf(Row("nested-1", nestedModified)))
        assertEquals(1, deltasModified.length())
        assertEquals("update", deltasModified.getJSONObject(0).getString("op"))
    }

    @Test
    fun combinedDiff_addsUpdatesAndDeletesInSinglePass() {
        val previous = listOf(
            Row("row-unchanged", JSONObject().apply { put("v", 1) }),
            Row("row-to-update", JSONObject().apply { put("v", 1) }),
            Row("row-to-delete", JSONObject().apply { put("v", 1) })
        )
        val current = listOf(
            Row("row-unchanged", JSONObject().apply { put("v", 1) }),
            Row("row-to-update", JSONObject().apply { put("v", 2) }),
            Row("row-newly-added", JSONObject().apply { put("v", 3) })
        )

        val deltas = DeltaBuilder.diff(previous, current)
        assertEquals(3, deltas.length())

        val opsById = mutableMapOf<String, JSONObject>()
        for (i in 0 until deltas.length()) {
            val d = deltas.getJSONObject(i)
            opsById[d.getString("id")] = d
        }

        assertEquals("update", opsById["row-to-update"]!!.getString("op"))
        assertEquals("add", opsById["row-newly-added"]!!.getString("op"))
        assertEquals("delete", opsById["row-to-delete"]!!.getString("op"))
        assertFalse(opsById["row-to-delete"]!!.has("data"))
        assertFalse(opsById.containsKey("row-unchanged"))
    }
}
