package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import java.time.Instant

/* Assigning several roles to one person at once (M14). Each ticked role is still saved as its own
 * assignment, so terms can end separately and the history stays one row per role. */

/** Each account's membership status, as the server sends it to administrators and the Secretary (migration 039).
 *  Null when this database doesn't send it, so the app can say nothing rather than something wrong. */
fun memberStandings(workspace: JsonObject): Map<String, String>? =
    (workspace["member_standing"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.associate { it.text("user_id") to it.text("status") }

fun standingLabel(status: String?) = when (status) { null -> "not a member"; "Active" -> "Active member"; else -> "membership ${status.lowercase()}" }

/** One role on the form: whether it can be ticked for this person, and why not. */
data class RoleChoice(val id: String, val name: String, val enabled: Boolean, val note: String)

fun roleChoices(workspace: JsonObject, userId: String, now: Instant): List<RoleChoice> {
    val standing = memberStandings(workspace)?.get(userId)
    val knowsStanding = memberStandings(workspace) != null
    val held = workspace.records("role_assignments").filter {
        it.text("user_id") == userId && it.text("retired_at").isBlank() && (instantOf(it.text("ends_at"))?.isAfter(now) ?: true)
    }.map { it.text("role_id") }.toSet()
    return workspace.records("roles").sortedBy { it.text("name") }.map { r ->
        val id = r.text("id")
        when {
            id in held -> RoleChoice(id, r.text("name"), false, "Already has this role")
            // Mirrors the server rule (migration 042): every role, Administrator included, needs an Active membership.
            knowsStanding && standing != "Active" -> RoleChoice(id, r.text("name"), false, "Members only")
            else -> RoleChoice(id, r.text("name"), true, "")
        }
    }
}

/** The text after "Saved": "Test Relation now has 2 roles: Activity officer, Interclub officer." */
fun assignedSummary(person: String, saved: List<String>, failed: List<Pair<String, String>>): String = buildString {
    if (saved.isNotEmpty()) append("$person now has ${saved.size} new role${if (saved.size == 1) "" else "s"}: ${saved.joinToString(", ")}.")
    if (failed.isNotEmpty()) {
        if (isNotEmpty()) append(' ')
        append(failed.joinToString(" ") { (role, why) -> "$role wasn't assigned: $why" })
    }
}
