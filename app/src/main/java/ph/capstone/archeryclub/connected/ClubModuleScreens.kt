package ph.capstone.archeryclub.connected

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

val integratedClubModules = setOf(5, 7, 9, 10, 12, 13)

fun workflowChoiceOptions(form: JsonObject, field: JsonObject, initial: JsonObject, workspace: JsonObject): List<String> {
    if(field.text("name")=="status") when(form.text("action")) {
        "set_election_status" -> return when(initial.text("status")) {
            "draft"->listOf("announced");"announced"->listOf("voting");"voting"->listOf("closed")
            "confirmed","requires_revote"->listOf("archived");else->emptyList()
        }
        "review_delegate" -> return when(initial.text("status")) {
            "pending","incomplete"->listOf("eligible","incomplete","rejected")
            "eligible"->listOf("checked_in");else->emptyList()
        }
        "update_shared_event" -> return when(initial.text("status")) {
            "preparation"->listOf("preparation","active");"active"->listOf("active","completed")
            "completed"->listOf("completed");else->emptyList()
        }
    }
    if (form.text("action") == "review_borrowing" && field.text("name") == "status") return when (initial.text("status")) {
        "pending" -> listOf("approved", "rejected", "returned_for_correction")
        "approved" -> listOf("released", "rejected")
        "released" -> listOf("returned")
        else -> emptyList()
    }
    if (form.text("action") == "approve_funds" && field.text("name") == "office") {
        val permissions = (workspace["permissions"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val approvals = workspace.records("financial_approvals").filter { it.text("record_id") == initial.text("id") }
        if (approvals.any { it.text("approved_by") == workspace.text("user_id") }) return emptyList()
        return listOf("president", "finance").filter { office -> office in permissions && approvals.none { it.text("office") == office } }
    }
    return (field["options"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
}

/** The server filters the workspace; member screens additionally select their own records. */
fun clubModuleRows(module: Int, table: String, workspace: JsonObject): List<JsonObject> =
    workspace.records(table).filter { row ->
        module != 5 || table == "equipment" || row.text("user_id") == workspace.text("user_id")
    }

/** UI affordances only: every command is still independently authorized by Supabase. */
fun recordWorkflowActions(forms: List<JsonObject>, table: String, row: JsonObject, user: String): List<JsonObject> =
    forms.filter { form ->
        val states = (form["states"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val action = form.text("action")
        form.text("table") == table &&
            (form.text("permission") !in setOf("account", "member") || row.text("user_id").isBlank() || row.text("user_id") == user) &&
            (states.isEmpty() || row.text("status") in states) &&
            (action != "save_financial_request" || row.text("status") in setOf("draft", "incomplete", "returned_for_correction")) &&
            (action != "request_borrowing" || row.text("status") == "returned_for_correction") &&
            (action !in setOf("approve_funds", "verify_financial_request") || row.text("user_id") != user) &&
            (action != "approve_funds" || row.text("kind") != "payment") &&
            (action != "review_borrowing" || row.text("status") in setOf("pending", "approved", "released")) &&
            (action != "save_application" || row.text("status") in setOf("draft","incomplete","returned_for_correction")) &&
            (action !in setOf("review_application","review_renewal","update_membership") || row.text("user_id")!=user) &&
            (action !in setOf("save_event","publish_event","archive_event","open_attendance","register_event") || row.text("archived")!="true") &&
            (action !in setOf("register_event","open_attendance","archive_event") || row.text("published")=="true") &&
            (action != "publish_event" || row.text("published")!="true") &&
            (action !in setOf("save_personal_training","submit_training") || row.text("validation") in setOf("personal","returned_for_correction")) &&
            (action != "validate_training" || row.text("validation")=="pending") &&
            (action != "request_training_correction" || row.text("validation")=="validated") &&
            (action != "record_external_results" || row.text("method") in setOf("paper","external")) &&
            (action != "set_election_status" || row.text("status") in setOf("draft","announced","voting","confirmed","requires_revote")) &&
            (action != "save_delegate" || row.text("status") in setOf("pending","incomplete")) &&
            (action != "review_delegate" || row.text("status") in setOf("pending","incomplete","eligible")) &&
            (action !in setOf("assign_delegate","substitute_delegate") || row.text("status") in setOf("eligible","checked_in")) &&
            (action != "record_result" || row.text("status")=="checked_in") &&
            (action != "verify_result" || row.text("verified_by").isBlank()) &&
            (action != "update_shared_event" || row.text("status")!="archived") &&
            (action != "retire_role" || row.text("retired_at").isBlank())
    }

/** Adapts the group's cards, filter chips and review queues to the authenticated workspace. */
@Composable
fun ClubModuleOverview(module: Int, workspace: JsonObject) {
    val metrics: List<Pair<String, Int>> = when (module) {
        5 -> listOf("My requests" to clubModuleRows(5, "borrowings", workspace).size,
            "Awaiting review" to clubModuleRows(5, "borrowings", workspace).count { it.text("status") == "pending" })
        7 -> listOf("Applications to review" to workspace.records("applications").count { it.text("status") in setOf("pending", "under_review") },
            "Renewals to review" to workspace.records("renewals").count { it.text("status") == "pending" },
            "Active members" to workspace.records("memberships").count { it.text("status") == "Active" })
        9 -> listOf("Elections" to workspace.records("elections").size,
            "Open for voting" to workspace.records("elections").count { it.text("status") == "voting" })
        10 -> listOf("Equipment items" to workspace.records("equipment").size,
            "Serviceable items" to workspace.records("equipment").count { it.text("condition") == "serviceable" },
            "Requests to review" to workspace.records("borrowings").count { it.text("status") == "pending" })
        12 -> listOf("Awaiting verification" to workspace.records("financial_records").count { it.text("status") == "pending_verification" },
            "Verified records" to workspace.records("financial_records").count { it.text("status") == "verified" })
        else -> emptyList()
    }
    metrics.forEach { (title, value) ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
    Text(when (module) {
        5 -> "Request equipment for your dates, then track review, release and return. Save reimbursements as drafts and attach receipts before submitting."
        7 -> "Open an application to inspect requirements before recording a screening decision. Approved applicants keep their existing account."
        9 -> "Open an election for candidacy, eligibility, a private ballot or verified results. President and Vice President are elected; other positions use appointment records."
        10 -> "Serviceable does not mean available for every date. Check availability and compatibility before approving an allocation."
        12 -> "Open a record to inspect receipts and approval history. President approval and finance verification follow your assigned permissions. No money is transferred."
        else -> "Generate reports from authorized club records. Goal progress is calculated by the server from operational data."
    })
    Text("Counts reflect records available to your account.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ClubChoices(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { value -> FilterChip(selected == value, { onSelected(value) }, label = { Text(human(value)) }) }
    }
}

@Composable
fun ClubModuleRecords(
    module: Int, current: JsonObject, workspace: JsonObject, available: List<JsonObject>, busy: Boolean,
    onOpen: (String, JsonObject) -> Unit, onAction: (String, JsonObject) -> Unit,
) {
    val tables = current["tables"]!!.jsonArray.map { it.jsonPrimitive.content }
    var table by rememberSaveable(module) { mutableStateOf(tables.first()) }
    var search by rememberSaveable(module) { mutableStateOf("") }
    var status by rememberSaveable(module, table) { mutableStateOf("All") }
    var hand by rememberSaveable(module, table) { mutableStateOf("All") }
    val rows = clubModuleRows(module, table, workspace)
    SelectField("Records", table, tables.map { it to human(it) }) { table = it }
    OutlinedTextField(search, { search = it }, label = { Text("Search names and records") }, modifier = Modifier.fillMaxWidth())
    val stateKey = if (table == "equipment") "condition" else "status"
    val states = rows.map { it.text(stateKey) }.filter { it.isNotBlank() }.distinct().sorted()
    if (states.isNotEmpty()) ClubChoices(human(stateKey), listOf("All") + states, status) { status = it }
    if (table == "equipment") ClubChoices("Handedness", listOf("All", "left", "right", "ambidextrous", "not_applicable"), hand) { hand = it }
    val filtered = rows.filter { row ->
        (status == "All" || row.text(stateKey) == status) &&
            (table != "equipment" || hand == "All" || row.text("handedness") == hand) &&
            (row.toString() + recordTitle(row, workspace)).contains(search, ignoreCase = true)
    }
    Text("${filtered.size} matching records", style = MaterialTheme.typography.labelLarge)
    if (filtered.isEmpty()) Text("No matching records. Refresh or adjust the filters; no sample records are substituted.")
    filtered.forEach { row ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recordTitle(row, workspace), style = MaterialTheme.typography.titleMedium)
                listOf("status", "condition", "category", "handedness", "draw_weight", "starts_at", "ends_at", "amount", "position", "metric", "target")
                    .filter { row.text(it).isNotBlank() }.forEach { key -> Text("${human(key)}: ${human(row.text(key))}") }
                if (table == "borrowings") workspace.records("equipment").find { it.text("id") == row.text("equipment_id") }
                    ?.let { Text("Equipment: ${it.text("label")}") }
                Button(onClick = { onOpen(table, row) }, enabled = !busy) {
                    Text(when (table) {
                        "applications", "renewals", "financial_records" -> "Open record and documents"
                        "elections" -> "Open election and ballot"
                        else -> "Open record"
                    })
                }
                if (module == 5 && table == "equipment" && row.text("condition") == "serviceable" && available.any { it.text("action") == "request_borrowing" }) {
                    OutlinedButton(onClick = { onAction("request_borrowing", buildJsonObject {
                        put("equipment_id", row.text("id"))
                        if (row.text("handedness") in setOf("left", "right", "not_applicable")) put("handedness", row.text("handedness"))
                        put("bow_setup", row.text("bow_setup"))
                    }) }, enabled = !busy) { Text("Request this equipment") }
                }
                // Decisions stay in the detail screen alongside documents and audit information.
                if (table == "equipment" && module == 10) recordWorkflowActions(available, table, row, workspace.text("user_id")).forEach { form ->
                    OutlinedButton(onClick = { onAction(form.text("action"), row) }, enabled = !busy) { Text("Edit equipment and condition") }
                }
            }
        }
    }
}

/** CSV represents the exact permission-filtered server report, including periods and nulls. */
fun clubReportCsv(report: JsonObject): String {
    val rows = mutableListOf(listOf("Field", "Value"))
    fun visit(path: String, value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> visit(if (path.isEmpty()) key else "$path.$key", child) }
            is JsonArray -> if (value.isEmpty()) rows.add(listOf(path, "No recorded data")) else value.forEachIndexed { i, child -> visit("$path[${i + 1}]", child) }
            JsonNull -> rows.add(listOf(path, "Not available"))
            else -> rows.add(listOf(path, value.jsonPrimitive.content))
        }
    }
    visit("", report)
    return rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } }
}
