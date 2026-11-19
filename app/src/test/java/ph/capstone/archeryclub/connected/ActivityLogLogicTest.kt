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
            entry("3", 14, "retire_role", "", "2026-11-19T12:00:00Z", buildJsonObject { put("id", "ra1"); put("reason", "Mistake") }),
            entry("4", 14, "session_access", "tre", "2026-11-19T13:00:00Z"),
            entry("5", 14, "set_account_status", "carl", "2026-11-19T14:00:00Z", buildJsonObject { put("user_id", "og"); put("enabled", false); put("reason", "Testing") }),
        )))
        put("role_assignments", JsonArray(listOf(buildJsonObject { put("id", "ra1"); put("user_id", "og"); put("role_id", "administrator") })))
        put("roles", JsonArray(listOf(buildJsonObject { put("id", "administrator"); put("name", "Administrator") })))
    }
    private val names = mapOf("carl" to "Carll Perez", "tre" to "Test Tre", "og" to "OG Archer")

    @Test fun newestFirstWithNamesAndNotes() {
        val all = activityLog(ws, null, "") { names[it] ?: it }
        assertEquals(listOf("5", "3", "2", "1"), all.map { it.id }) // sign-ins hidden by default
        assertEquals("System", all[1].actor); assertEquals("Administration", all[1].moduleName)
        assertEquals("New term", all[3].note) // first line only
        assertEquals("Disabled OG Archer's account", all[0].title)
        assertEquals("Retired OG Archer's Administrator role", all[1].title)
        assertEquals("Signed in", activityLog(ws, null, "", signIns = true) { names[it] ?: it }.first { it.id == "4" }.title)
    }

    @Test fun filterAndSearch() {
        assertEquals(listOf("2"), activityLog(ws, 12, "") { names[it] ?: it }.map { it.id })
        assertEquals(listOf("2"), activityLog(ws, null, "gcash") { names[it] ?: it }.map { it.id })
        assertEquals(listOf("5", "1"), activityLog(ws, null, "carll") { names[it] ?: it }.map { it.id })
        assertEquals(listOf("5", "3"), activityLog(ws, null, "og archer") { names[it] ?: it }.map { it.id })
        assertEquals(listOf(12, 14), logModules(ws))
    }
}
