package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogLogicTest {
    private fun entry(id: String, module: Int, action: String, actor: String, at: String, detail: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("id", id); put("module", module); put("action", action); put("actor", actor); put("created_at", at); put("detail", detail)
    }
    private val ws = buildJsonObject {
        put("audit_log", JsonArray(listOf(
            entry("1", 14, "assign_role", "carl", "2026-11-18T10:00:00Z", buildJsonObject { put("reason", "New term\nsecond line") }),
            entry("2", 12, "verify_financial_request", "tre", "2026-11-19T09:00:00Z", buildJsonObject { put("remark", "Matched GCash") }),
            entry("3", 14, "retire_role", "", "2026-11-19T12:00:00Z"),
        )))
    }
    private val names = mapOf("carl" to "Carll Perez", "tre" to "Test Tre")

    @Test fun newestFirstWithNamesAndNotes() {
        val all = activityLog(ws, null, "") { names[it] ?: it }
        assertEquals(listOf("3", "2", "1"), all.map { it.id })
        assertEquals("System", all[0].actor); assertEquals("Administration", all[0].moduleName)
        assertEquals("New term", all[2].note) // first line only
    }

    @Test fun filterAndSearch() {
        assertEquals(listOf("2"), activityLog(ws, 12, "") { names[it] ?: it }.map { it.id })
        assertEquals(listOf("2"), activityLog(ws, null, "gcash") { names[it] ?: it }.map { it.id })
        assertEquals(listOf("1"), activityLog(ws, null, "carll") { names[it] ?: it }.map { it.id })
        assertEquals(listOf(12, 14), logModules(ws))
    }
}
