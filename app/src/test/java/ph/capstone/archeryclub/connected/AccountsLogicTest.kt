package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AccountsLogicTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private fun profile(id: String, name: String, email: String, enabled: Boolean = true) = buildJsonObject {
        put("id", id); put("full_name", name); put("email", email); put("enabled", enabled); put("created_at", "2026-11-01T00:00:00Z")
    }
    private val ws = buildJsonObject {
        put("profiles", JsonArray(listOf(profile("og", "", "og_archer@gmail.com", enabled = false), profile("riane", "Riane Caimoy", "riane@dlsu.edu.ph"),
            profile("jeo", "Jeo Ong", "jeo@dlsu.edu.ph"))))
        put("member_standing", JsonArray(listOf(buildJsonObject { put("user_id", "riane"); put("status", "Active") },
            buildJsonObject { put("user_id", "jeo"); put("status", "Pending payment") })))
        put("roles", JsonArray(listOf(buildJsonObject { put("id", "interclub_officer"); put("name", "Interclub officer") },
            buildJsonObject { put("id", "administrator"); put("name", "Administrator") })))
        put("role_assignments", JsonArray(listOf(
            buildJsonObject { put("user_id", "riane"); put("role_id", "interclub_officer"); put("ends_at", "2027-01-01T00:00:00Z"); put("retired_at", "") },
            buildJsonObject { put("user_id", "og"); put("role_id", "administrator"); put("ends_at", "2027-01-01T00:00:00Z"); put("retired_at", "2026-11-19T10:00:00Z") },
        )))
    }

    @Test fun standingRolesAndOrder() {
        val a = accountViews(ws, now)
        assertEquals(listOf("jeo", "riane", "og"), a.map { it.id }) // enabled first, then by name; disabled last
        assertEquals(listOf("Interclub officer"), a[1].roles)
        assertEquals(emptyList<String>(), a[2].roles) // a retired role doesn't count
        assertEquals("Pending payment", accountStanding(a[0])); assertEquals("Not a member", accountStanding(a[2]))
    }

    @Test fun filtersAndSearch() {
        val a = accountViews(ws, now)
        assertEquals(listOf("riane"), filterAccounts(a, AccountFilter.MEMBERS, "").map { it.id })
        assertEquals(listOf("jeo", "og"), filterAccounts(a, AccountFilter.NON_MEMBERS, "").map { it.id })
        assertEquals(listOf("og"), filterAccounts(a, AccountFilter.DISABLED, "").map { it.id })
        assertEquals(listOf("riane"), filterAccounts(a, AccountFilter.OFFICERS, "").map { it.id })
        assertEquals(listOf("og"), filterAccounts(a, AccountFilter.ALL, "og_arch").map { it.id })
        assertEquals(listOf("riane"), filterAccounts(a, AccountFilter.ALL, "interclub").map { it.id })
    }
}
