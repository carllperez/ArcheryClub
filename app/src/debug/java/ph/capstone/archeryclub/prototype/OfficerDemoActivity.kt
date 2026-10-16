package ph.capstone.archeryclub.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.capstone.archeryclub.ui.ArcheryTheme
import java.time.LocalDate
import java.util.Locale

/** Separate debug-only launcher. The regular member app and production entry point are untouched. */
class OfficerDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArcheryTheme { OfficerModuleDemo() } }
    }
}

private val moduleNames = linkedMapOf(
    10 to "Equipment Inventory and Allocation Support",
    5 to "Member Operations and Equipment Borrowing",
    7 to "Membership and Applicant Processing",
    9 to "Election Management",
    12 to "Financial Record Verification and Tracking",
    13 to "Reports, Dashboard Analytics, and Goal Monitoring",
)

@Composable
private fun OfficerModuleDemo() {
    val store = remember { OfficerDemoStore() }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<DemoResult?>(null) }
    fun act(operation: () -> DemoResult) { notice = operation(); if (notice?.accepted == true) revision++ }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current != 0) TextButton(onClick = { current = 0; notice = null }) { Text("← Modules") }
                    Text("OFFICER MODULES · DEBUG DEMO", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                }
                Text("Fictional local data • No Supabase calls • Changes reset on restart", style = MaterialTheme.typography.bodySmall)
            }
        }
        notice?.let { outcome ->
            Surface(color = if (outcome.accepted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(outcome.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { notice = null }) { Text("Dismiss") }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (current == 0) {
                Text("Choose a module to test", style = MaterialTheme.typography.headlineSmall)
                Text("These six functional UI prototypes are isolated from your group's live application. Test the controls, validations and edge cases before connecting the production workflows.")
                moduleNames.forEach { (number, name) ->
                    ElevatedCard(onClick = { current = number; notice = null }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("M$number", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text("Open interactive demo →", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Demo records are kept in memory for this session only. Restart the demo to restore its sample records.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("M$current · ${moduleNames[current]}", style = MaterialTheme.typography.headlineSmall)
                // Including revision in the content identity refreshes all display values after in-memory mutations.
                key(current, revision) {
                    when (current) {
                        10 -> EquipmentDemo(store, ::act)
                        5 -> BorrowingDemo(store, ::act)
                        7 -> MembershipDemo(store, ::act)
                        9 -> ElectionDemo(store, ::act)
                        12 -> FinanceDemo(store, ::act)
                        13 -> ReportsDemo(store, ::act)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ItemCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun Choices(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelected(option) }, label = { Text(option.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
}

@Composable
private fun EquipmentDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    Section("Equipment catalog", "Filter by handedness or equipment condition. Each item represents one shared bow setup, not unlimited inventory.")
    var hand by remember { mutableStateOf("ALL") }
    var status by remember { mutableStateOf("ALL") }
    Choices("Shooting hand", listOf("ALL", "LEFT", "RIGHT", "AMBIDEXTROUS"), hand) { hand = it }
    Choices("Condition", listOf("ALL", "READY", "MAINTENANCE", "RETIRED"), status) { status = it }
    store.gear.filter { (hand == "ALL" || it.hand.name == hand) && (status == "ALL" || it.condition.name == status) }.forEach { item ->
        val allocated = store.borrowings.count { it.gearId == item.id && it.status == BorrowStatus.APPROVED }
        ItemCard("${item.id} · ${item.description}", "${item.hand} · ${item.drawWeight} lb · ${item.condition} · $allocated approved booking(s)") {
            if (item.condition == GearCondition.READY) {
                OutlinedButton(onClick = { act { store.changeCondition(item.id, GearCondition.MAINTENANCE) } }) { Text("Flag for maintenance") }
                OutlinedButton(onClick = { act { store.changeCondition(item.id, GearCondition.RETIRED) } }) { Text("Retire item") }
            } else OutlinedButton(onClick = { act { store.changeCondition(item.id, GearCondition.READY) } }) { Text("Mark ready") }
        }
    }
    HorizontalDivider()
    Section("Add equipment", "Adds a fictional record to this running demo only.")
    var description by remember { mutableStateOf("") }
    var newHand by remember { mutableStateOf("LEFT") }
    var weight by remember { mutableStateOf("24") }
    OutlinedTextField(description, { description = it }, label = { Text("Bow setup description") }, modifier = Modifier.fillMaxWidth())
    Choices("Hand compatibility", listOf("LEFT", "RIGHT", "AMBIDEXTROUS"), newHand) { newHand = it }
    OutlinedTextField(weight, { weight = it }, label = { Text("Draw weight (lb)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = { act { store.addGear(description, Hand.valueOf(newHand), weight.toIntOrNull() ?: 0) } }) { Text("Add equipment") }
    Section("Allocation check", "Approval validates item condition, handedness and overlapping dates. Try M5 and return to M10 to inspect changed allocations.")
    store.borrowings.filter { it.status == BorrowStatus.APPROVED }.forEach { row ->
        Text("${row.gearId} reserved by ${row.member} · ${row.start} to ${row.end}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BorrowingDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    Section("Request club-owned equipment", "Bow compatibility and approved booking conflicts are checked before a request can be submitted or approved.")
    var member by remember { mutableStateOf("Demo Member") }
    var hand by remember { mutableStateOf("LEFT") }
    var itemId by remember { mutableStateOf("EQ-002") }
    var from by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var to by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    OutlinedTextField(member, { member = it }, label = { Text("Requesting member") }, modifier = Modifier.fillMaxWidth())
    Choices("Shooting hand", listOf("LEFT", "RIGHT"), hand) { hand = it }
    Section("Select compatible item", "Only ready equipment matching the selected hand is listed.")
    val compatible = store.gear.filter { it.condition == GearCondition.READY && (it.hand.name == hand || it.hand == Hand.AMBIDEXTROUS) }
    Choices("Equipment", compatible.map { it.id }, itemId) { itemId = it }
    compatible.find { it.id == itemId }?.let { Text("${it.description} · ${it.drawWeight} lb", style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(from, { from = it }, label = { Text("Start date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(to, { to = it }, label = { Text("End date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        act {
            val dates = runCatching { LocalDate.parse(from) to LocalDate.parse(to) }.getOrNull()
                ?: return@act DemoResult(false, "Enter valid dates using YYYY-MM-DD.")
            store.requestBorrow(member, Hand.valueOf(hand), itemId, dates.first, dates.second)
        }
    }, enabled = compatible.isNotEmpty()) { Text("Submit borrowing request") }
    HorizontalDivider()
    Section("Borrowing and review queue", "Local approval requires a compatible item without an overlapping approved reservation.")
    store.borrowings.reversed().forEach { row ->
        ItemCard("${row.id} · ${row.member}", "${row.gearId} · ${row.hand} · ${row.start} to ${row.end} · ${row.status}") {
            when (row.status) {
                BorrowStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act { store.reviewBorrow(row.id, true) } }) { Text("Approve") }
                    OutlinedButton(onClick = { act { store.reviewBorrow(row.id, false) } }) { Text("Decline") }
                }
                BorrowStatus.APPROVED -> OutlinedButton(onClick = { act { store.returnBorrow(row.id) } }) { Text("Record return") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun MembershipDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    Section("Applicant and renewal reviews", "Approval creates or updates the fictional membership record; rejection does not grant membership.")
    store.applications.forEach { row ->
        ItemCard("${row.id} · ${row.person}", "${row.kind} · Requested category: ${row.requestedCategory} · ${row.status}") {
            if (row.status == ReviewStatus.SUBMITTED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act { store.reviewApplication(row.id, true) } }) { Text("Approve") }
                OutlinedButton(onClick = { act { store.reviewApplication(row.id, false) } }) { Text("Reject") }
            }
        }
    }
    HorizontalDivider()
    Section("Simulate incoming application", "Adds a new applicant or renewal to the same local review queue.")
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("New applicant") }
    var category by remember { mutableStateOf("Trainee") }
    OutlinedTextField(name, { name = it }, label = { Text("Applicant / member name") }, modifier = Modifier.fillMaxWidth())
    Choices("Request type", listOf("New applicant", "Renewal"), kind) { kind = it }
    Choices("Membership category", listOf("Trainee", "Regular", "Associate"), category) { category = it }
    Button(onClick = { act { store.submitApplication(name, kind, category) } }) { Text("Submit sample application") }
    HorizontalDivider()
    Section("Membership directory (sample)")
    store.memberships.forEach { row -> ItemCard(row.person, "Category: ${row.category} · Status: Active") }
    Text("Real membership validation, notifications, officer permissions and profile linking remain Supabase responsibilities.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ElectionDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    Section("Election control", "One simulated election, one eligible demo member and one ballot. This does not create a real election.")
    ItemCard("2026 Club Officer Election", if (store.electionOpen) "Status: Voting open" else "Status: Closed") {
        if (store.electionOpen) OutlinedButton(onClick = { act { store.closeElection() } }) { Text("Close election & show tallies") }
    }
    Section("Nominations")
    var nominee by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("President") }
    OutlinedTextField(nominee, { nominee = it }, label = { Text("Candidate name") }, modifier = Modifier.fillMaxWidth())
    Choices("Position", store.positions, position) { position = it }
    Button(onClick = { act { store.nominate(nominee, position) } }, enabled = store.electionOpen) { Text("Add candidate") }
    store.positions.forEach { pos ->
        Text(pos, style = MaterialTheme.typography.titleSmall)
        store.candidates.filter { it.position == pos }.forEach { candidate ->
            Text("• ${candidate.person}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    HorizontalDivider()
    Section("Member ballot", "Use this to test required positions and prevention of duplicate votes.")
    var president by remember { mutableStateOf("") }
    var vicePresident by remember { mutableStateOf("") }
    if (store.electionOpen && store.ballots.isEmpty()) {
        Choices("President", store.candidates.filter { it.position == "President" }.map { it.id }, president) { president = it }
        Text(store.candidates.find { it.id == president }?.person.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Choices("Vice President", store.candidates.filter { it.position == "Vice President" }.map { it.id }, vicePresident) { vicePresident = it }
        Text(store.candidates.find { it.id == vicePresident }?.person.orEmpty(), style = MaterialTheme.typography.bodySmall)
        var confirm by remember { mutableStateOf(false) }
        Button(onClick = { confirm = true }) { Text("Review and cast sample ballot") }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Submit this demo ballot?") },
            text = { Text("Only one ballot can be cast in this session. You cannot change the choices afterwards.") },
            confirmButton = { TextButton(onClick = { confirm = false; act { store.castBallot(mapOf("President" to president, "Vice President" to vicePresident)) } }) { Text("Cast ballot") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    } else if (store.ballots.isNotEmpty()) {
        Text("Ballot recorded for the simulated member. Selections are hidden while voting is open.")
    } else Text("Voting is closed.")
    if (!store.electionOpen) {
        Section("Demo results", "Results are illustrative and based on the single locally simulated ballot.")
        store.candidates.forEach { candidate ->
            val votes = store.ballots.values.count { it == candidate.id }
            Text("${candidate.position}: ${candidate.person} — $votes vote(s)")
        }
    }
}

@Composable
private fun FinanceDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    Section("Submit financial record", "Verification only: no accounting integration, payment collection, or fund disbursement.")
    var submitter by remember { mutableStateOf("Demo Member") }
    var purpose by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    OutlinedTextField(submitter, { submitter = it }, label = { Text("Submitter") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose / description") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(amount, { amount = it }, label = { Text("Amount (PHP)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(reference, { reference = it }, label = { Text("Supporting document reference (fictional)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { act { store.submitFinance(submitter, purpose, amount.toDoubleOrNull() ?: Double.NaN, reference) } }) { Text("Submit for review") }
    HorizontalDivider()
    Section("Approval and verification queue", "A record must be approved before it can be verified. Real distinct-role authorization is NOT simulated here.")
    store.finances.reversed().forEach { record ->
        ItemCard("${record.id} · ${record.purpose}", "${record.submitter} · ${String.format(Locale.US, "%,.2f", record.amount)} PHP · ${record.status} · Proof: ${record.proofReference}") {
            when (record.status) {
                FinanceStatus.SUBMITTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act { store.reviewFinance(record.id, "approve") } }) { Text("Approve") }
                    OutlinedButton(onClick = { act { store.reviewFinance(record.id, "reject") } }) { Text("Reject") }
                }
                FinanceStatus.APPROVED -> Button(onClick = { act { store.reviewFinance(record.id, "verify") } }) { Text("Verify record") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ReportsDemo(store: OfficerDemoStore, act: (() -> DemoResult) -> Unit) {
    val context = LocalContext.current
    val csv = store.summaryCsv()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                    ?: error("File could not be opened")
            }
            act { if (result.isSuccess) DemoResult(true, "Fictional CSV report exported.") else DemoResult(false, "Could not export CSV: ${result.exceptionOrNull()?.message ?: "Unknown error"}") }
        }
    }
    Section("Club operations overview", "All figures below are calculated from this demo's fictional data, never from Supabase.")
    val metrics = listOf(
        "Equipment items" to store.gear.size,
        "Ready items" to store.gear.count { it.condition == GearCondition.READY },
        "Pending borrowing" to store.borrowings.count { it.status == BorrowStatus.PENDING },
        "Approved borrowing" to store.borrowings.count { it.status == BorrowStatus.APPROVED },
        "Pending member reviews" to store.applications.count { it.status == ReviewStatus.SUBMITTED },
        "Verified financial records" to store.finances.count { it.status == FinanceStatus.VERIFIED },
    )
    metrics.forEach { (label, value) -> ItemCard(label, value.toString()) }
    HorizontalDivider()
    Section("Goal tracking", "Change a sample goal and observe the updated progress on this screen.")
    store.goals.forEach { goal ->
        ItemCard(goal.label, "${goal.achieved} / ${goal.target} recorded") {
            LinearProgressIndicator(progress = { (goal.achieved.toFloat() / goal.target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { act { store.incrementGoal(goal.label) } }) { Text("Add one demo record") }
        }
    }
    HorizontalDivider()
    Section("Export demo report", "Exports the displayed session counts as a CSV file for review. It is not an official club report.")
    Button(onClick = { export.launch("archery-officer-demo-report.csv") }) { Text("Export fictional CSV") }
}
