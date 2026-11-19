package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import java.time.Instant

/* Administration → Accounts: every sign-in account with what an administrator needs to decide on it —
 * name or email, membership standing, current roles and whether it can sign in. Roles are given only
 * through Assign roles, which applies the members-only rule; this screen never offers a role directly. */

data class AccountView(val id: String, val name: String, val email: String, val enabled: Boolean, val standing: String?,
                       val roles: List<String>, val created: String) {
    val isMember get() = standing == "Active"
}

enum class AccountFilter(val label: String) { ALL("All"), MEMBERS("Active members"), OFFICERS("With roles"), NON_MEMBERS("Not members"), DISABLED("Disabled") }

fun accountViews(workspace: JsonObject, now: Instant): List<AccountView> {
    val standing = memberStandings(workspace)
    val roleNames = workspace.records("roles").associate { it.text("id") to it.text("name").ifBlank { human(it.text("id")) } }
    val held = workspace.records("role_assignments").filter {
        it.text("retired_at").isBlank() && (instantOf(it.text("ends_at"))?.isAfter(now) ?: true)
    }.groupBy { it.text("user_id") }
    return workspace.records("profiles").map { p ->
        val id = p.text("id")
        AccountView(id, p.text("full_name").trim(), p.text("email"), p.text("enabled") != "false", standing?.get(id),
            held[id].orEmpty().map { roleNames[it.text("role_id")] ?: human(it.text("role_id")) }.distinct().sorted(), p.text("created_at"))
    }.sortedWith(compareBy<AccountView> { !it.enabled }.thenBy { (it.name.ifBlank { it.email }).lowercase() })
}

fun filterAccounts(accounts: List<AccountView>, filter: AccountFilter, query: String): List<AccountView> {
    val q = query.trim().lowercase()
    return accounts.filter { a ->
        when (filter) {
            AccountFilter.ALL -> true
            AccountFilter.MEMBERS -> a.isMember
            AccountFilter.OFFICERS -> a.roles.isNotEmpty()
            AccountFilter.NON_MEMBERS -> !a.isMember
            AccountFilter.DISABLED -> !a.enabled
        }
    }.filter { a -> q.isEmpty() || listOf(a.name, a.email, a.roles.joinToString(" ")).any { it.lowercase().contains(q) } }
}

/** "Active member", "Pending payment", "Not a member" — the line under each name. */
fun accountStanding(a: AccountView): String = when (a.standing) { null -> "Not a member"; "Active" -> "Active member"; else -> a.standing }
