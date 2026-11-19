package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*

/* The administrator's full activity log (M14): every recorded change, newest first, filterable by module
 * and searchable by action, person or reason. "Accounts" lists people; this lists what happened. */

/** Short names for the module chips and rows (the module numbers the server writes into audit_log). */
val LOG_MODULES = mapOf(
    1 to "Profile", 2 to "Applications", 3 to "Activities", 4 to "Training log", 5 to "Borrowing", 6 to "Dashboard",
    7 to "Membership", 8 to "Events", 9 to "Elections", 10 to "Equipment", 11 to "Training", 12 to "Finance",
    13 to "Reports", 14 to "Administration", 15 to "Interclub",
)

data class LogLine(val id: String, val action: String, val module: Int, val moduleName: String, val actorId: String, val actor: String,
                   val at: String, val note: String, val title: String = human(action))

const val SIGN_IN = "session_access"

/** A readable line for administration changes: who was affected and how, not just the action code.
 *  Role actions find the person through the assignment; account actions carry the user and the new state. */
fun logTitle(action: String, detail: JsonObject?, recordId: String, workspace: JsonObject, nameOf: (String) -> String): String {
    fun d(k: String) = (detail?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val assignment = workspace.records("role_assignments").firstOrNull { it.text("id") == (d("id").ifBlank { recordId }) }
    fun roleName(id: String) = workspace.records("roles").firstOrNull { it.text("id") == id }?.text("name")?.ifBlank { null } ?: human(id)
    val person = (d("user_id").ifBlank { assignment?.text("user_id").orEmpty() }).let { if (it.isBlank()) "" else nameOf(it) }
    val role = roleName(d("role_id").ifBlank { assignment?.text("role_id").orEmpty() })
    return when (action) {
        SIGN_IN -> "Signed in"
        "set_account_status" -> (if (d("enabled") == "true") "Enabled" else "Disabled") + (if (person.isNotBlank()) " $person's account" else " an account")
        "assign_role" -> "Gave $role to ${person.ifBlank { "someone" }}"
        "retire_role" -> "Retired ${person.ifBlank { "a" }}${if (person.isNotBlank()) "'s" else ""} $role role".replace("  ", " ")
        "update_role_term" -> "Changed ${person.ifBlank { "a" }}${if (person.isNotBlank()) "'s" else ""} $role term".replace("  ", " ")
        else -> human(action)
    }
}

/** The one line of context worth showing: the reason, remark or note the person typed, if any. */
fun logNote(detail: JsonObject?): String {
    if (detail == null) return ""
    for (k in listOf("reason", "remark", "note", "message", "title", "purpose")) {
        val v = (detail[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (v.isNotBlank()) return v.lineSequence().first().take(140)
    }
    return ""
}

/** Log lines, newest first. module = null shows every module; the query matches action, person, module or note. */
fun activityLog(workspace: JsonObject, module: Int?, query: String, signIns: Boolean = false, nameOf: (String) -> String): List<LogLine> {
    val q = query.trim().lowercase()
    return workspace.records("audit_log").map { r ->
        val m = r.text("module").toIntOrNull() ?: 0
        val actorId = r.text("actor")
        val detail = r["detail"] as? JsonObject
        LogLine(r.text("id"), r.text("action"), m, LOG_MODULES[m] ?: "Module $m", actorId,
            if (actorId.isBlank()) "System" else nameOf(actorId), r.text("created_at"), logNote(detail),
            logTitle(r.text("action"), detail, r.text("record_id"), workspace, nameOf))
    }.filter { module == null || it.module == module }
        // Sign-ins are recorded every time the app opens; they bury real changes unless asked for.
        .filter { signIns || it.action != SIGN_IN }
        .filter { l -> q.isEmpty() || listOf(l.title, l.action, l.actor, l.moduleName, l.note).any { it.lowercase().contains(q) } }
        .sortedByDescending { it.at }
}

/** Modules that actually have entries, for the filter chips (no empty filters). */
fun logModules(workspace: JsonObject): List<Int> =
    workspace.records("audit_log").mapNotNull { it.text("module").toIntOrNull() }.distinct().sorted()
