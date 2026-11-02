package app.lararium.hearth.rails

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JVM incremental delta diff builder per rail (A5).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

data class Row(val id: String, val data: JSONObject)

object DeltaBuilder {
    fun isEmpty(deltas: JSONArray): Boolean = deltas.length() == 0

    private fun jsonKeys(obj: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            keys.add(it.next())
        }
        return keys
    }

    /**
     * Deep equality comparison for JSON structures (JSONObject, JSONArray, primitives).
     */
    fun jsonEquals(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false

        if (a is JSONObject && b is JSONObject) {
            val aKeys = jsonKeys(a)
            val bKeys = jsonKeys(b)
            if (aKeys != bKeys) return false
            for (key in aKeys) {
                if (!jsonEquals(a.opt(key), b.opt(key))) return false
            }
            return true
        }

        if (a is JSONArray && b is JSONArray) {
            if (a.length() != b.length()) return false
            for (i in 0 until a.length()) {
                if (!jsonEquals(a.opt(i), b.opt(i))) return false
            }
            return true
        }

        if (a is Number && b is Number) {
            return a.toDouble() == b.toDouble()
        }

        return a == b
    }

    /**
     * Pure diff between two snapshots of rows.
     * Produces `[{"op":"add"|"update"|"delete","id":..., "data": ... }]`
     * (add and update carry "data"; delete carries "id" only).
     */
    fun diff(previous: List<Row>, current: List<Row>): JSONArray {
        val deltas = JSONArray()
        val prevMap = LinkedHashMap<String, Row>()
        for (row in previous) {
            prevMap[row.id] = row
        }
        val currMap = LinkedHashMap<String, Row>()
        for (row in current) {
            currMap[row.id] = row
        }

        // Add and Update operations
        for (currRow in current) {
            val prevRow = prevMap[currRow.id]
            if (prevRow == null) {
                val addOp = JSONObject().apply {
                    put("op", "add")
                    put("id", currRow.id)
                    put("data", currRow.data)
                }
                deltas.put(addOp)
            } else if (!jsonEquals(prevRow.data, currRow.data)) {
                val updateOp = JSONObject().apply {
                    put("op", "update")
                    put("id", currRow.id)
                    put("data", currRow.data)
                }
                deltas.put(updateOp)
            }
        }

        // Delete operations (carries id only)
        for (prevRow in previous) {
            if (!currMap.containsKey(prevRow.id)) {
                val deleteOp = JSONObject().apply {
                    put("op", "delete")
                    put("id", prevRow.id)
                }
                deltas.put(deleteOp)
            }
        }

        return deltas
    }
}

fun diff(previous: List<Row>, current: List<Row>): JSONArray = DeltaBuilder.diff(previous, current)
fun isEmpty(deltas: JSONArray): Boolean = DeltaBuilder.isEmpty(deltas)
