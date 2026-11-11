package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/* Pure logic behind the training, payments, elections, reports, administration and interclub screens.
 * Everything is derived from the same workspace records the server enforces; nothing here decides permissions. */

fun memberName(workspace: JsonObject, userId: String): String =
    workspace.records("member_directory").find { it.text("user_id") == userId }?.text("full_name")?.takeIf { it.isNotBlank() }
        ?: workspace.records("profiles").find { it.text("id") == userId }?.text("full_name")?.takeIf { it.isNotBlank() }
        ?: "Club member"

fun plainNumber(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else ((v * 10).roundToInt() / 10.0).toString()

/** "₱1,200" or "₱450.50". */
fun peso(v: Double): String {
    val whole = v.toLong(); val cents = ((v - whole) * 100).roundToInt()
    val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    return if (cents == 0) "₱$grouped" else "₱$grouped.${cents.toString().padStart(2, '0')}"
}
fun JsonObject.amount(key: String = "amount"): Double = text(key).toDoubleOrNull() ?: 0.0
fun JsonObject.date(key: String): LocalDate? = runCatching { LocalDate.parse(text(key).take(10)) }.getOrNull()

// ---------------------------------------------------------------- Training (M4 + M11)

enum class ScoreState(val label: String, val tone: Tone) {
    PERSONAL("Personal", Tone.NEUTRAL), PENDING("With coach", Tone.WARNING), VALIDATED("Validated", Tone.POSITIVE), RETURNED("Returned", Tone.DANGER)
}
fun scoreState(validation: String) = when (validation) {
    "pending" -> ScoreState.PENDING; "validated" -> ScoreState.VALIDATED; "returned_for_correction" -> ScoreState.RETURNED; else -> ScoreState.PERSONAL
}

data class ScoreCard(val row: JsonObject, val date: LocalDate?, val score: Double, val max: Double) {
    val id get() = row.text("id")
    val userId get() = row.text("user_id")
    val state get() = scoreState(row.text("validation"))
    val percent: Int get() = if (max > 0) (score * 100 / max).roundToInt() else 0
    val scoreLabel: String get() = "${plainNumber(score)} / ${plainNumber(max)}"
    /** "18 m · 80 cm face · WA 360" */
    val conditions: String get() = listOfNotNull(row.text("distance").takeIf { it.isNotBlank() }?.let { "${plainNumber(it.toDoubleOrNull() ?: 0.0)} m" },
        row.text("target_face").takeIf { it.isNotBlank() }, row.text("scoring_format").takeIf { it.isNotBlank() }).joinToString(" · ")
}
fun scoreCard(row: JsonObject) = ScoreCard(row, row.date("training_date"), row.amount("score"), row.amount("maximum_score"))

fun scoreCards(workspace: JsonObject, userId: String? = null): List<ScoreCard> = workspace.records("training_records")
    .filter { userId == null || it.text("user_id") == userId }.map(::scoreCard)
    .sortedWith(compareByDescending<ScoreCard> { it.date }.thenByDescending { it.row.text("encoded_at") })

data class TrainingStats(val best: ScoreCard?, val roundsThisMonth: Int, val monthDelta: Int?)

/** Best round by percentage, rounds this month and the change in average percentage against last month. */
fun trainingStats(cards: List<ScoreCard>, today: LocalDate): TrainingStats {
    fun inMonth(c: ScoreCard, d: LocalDate) = c.date?.let { it.year == d.year && it.month == d.month } == true
    val thisMonth = cards.filter { inMonth(it, today) }
    val lastMonth = cards.filter { inMonth(it, today.minusMonths(1)) }
    val delta = if (thisMonth.isNotEmpty() && lastMonth.isNotEmpty())
        (thisMonth.map { it.percent }.average() - lastMonth.map { it.percent }.average()).roundToInt() else null
    return TrainingStats(cards.filter { it.max > 0 }.maxWithOrNull(compareBy<ScoreCard> { it.percent }.thenBy { it.score }), thisMonth.size, delta)
}

/** Oldest-to-newest percentages for the trend line: validated rounds when there are at least two, otherwise every round. */
fun trendPercents(cards: List<ScoreCard>, limit: Int = 8): List<Int> {
    val validated = cards.filter { it.state == ScoreState.VALIDATED }
    val source = if (validated.size >= 2) validated else cards
    return source.filter { it.date != null && it.max > 0 }.sortedBy { it.date }.takeLast(limit).map { it.percent }
}

fun filterCards(cards: List<ScoreCard>, filter: String) = when (filter) {
    "validated" -> cards.filter { it.state == ScoreState.VALIDATED }
    "personal" -> cards.filter { it.state == ScoreState.PERSONAL }
    "coach" -> cards.filter { it.state == ScoreState.PENDING }
    "fix" -> cards.filter { it.state == ScoreState.RETURNED }
    else -> cards
}

/** Scorecards waiting for a coach, oldest first so nobody waits longest. */
fun coachQueue(workspace: JsonObject) = scoreCards(workspace).filter { it.state == ScoreState.PENDING }.sortedBy { it.date }
fun trainingCorrections(workspace: JsonObject) = workspace.records("corrections").filter { it.text("module") == "11" && it.text("status") == "pending" }

// ---------------------------------------------------------------- Payments and finance (M12)

enum class FeeState(val label: String, val tone: Tone) {
    DUE("Due", Tone.WARNING), OVERDUE("Overdue", Tone.DANGER), DRAFT("Proof not sent", Tone.WARNING),
    CHECKING("Being checked", Tone.INFO), RETURNED("Needs fixing", Tone.DANGER), PAID("Paid", Tone.POSITIVE)
}
data class FeeView(val fee: JsonObject, val payment: JsonObject?, val state: FeeState) {
    val amount get() = fee.amount()
    val due get() = fee.date("due_on")
}

private fun sameAmount(a: JsonObject, b: JsonObject) = Math.abs(a.amount() - b.amount()) < 0.005

/** The payment a member made for a fee: the linked one, else their newest matching payment that wasn't rejected. */
fun paymentFor(fee: JsonObject, records: List<JsonObject>): JsonObject? {
    val linked = fee.text("payment_id")
    if (linked.isNotBlank()) return records.find { it.text("id") == linked }
    return records.filter { it.text("kind") == "payment" && it.text("user_id") == fee.text("user_id") && it.text("status") != "rejected" &&
        it.text("purpose").trim().equals(fee.text("purpose").trim(), true) && sameAmount(it, fee) }.maxByOrNull { it.text("created_at") }
}

fun feeState(fee: JsonObject, payment: JsonObject?, today: LocalDate): FeeState = when {
    fee.text("payment_id").isNotBlank() -> FeeState.PAID
    payment == null -> if (fee.date("due_on")?.isBefore(today) == true) FeeState.OVERDUE else FeeState.DUE
    payment.text("status") == "draft" -> FeeState.DRAFT
    payment.text("status") in setOf("incomplete", "returned_for_correction") -> FeeState.RETURNED
    else -> FeeState.CHECKING // pending verification, or verified and waiting to be matched to the fee
}

fun feeViews(workspace: JsonObject, userId: String, today: LocalDate): List<FeeView> {
    val records = workspace.records("financial_records")
    return workspace.records("fee_assessments").filter { it.text("user_id") == userId }.map { fee ->
        val payment = paymentFor(fee, records); FeeView(fee, payment, feeState(fee, payment, today))
    }.sortedWith(compareBy<FeeView> { it.state == FeeState.PAID }.thenBy { it.due })
}

/** What the member still has to act on (fees already sent for checking are not counted). */
fun amountOwed(fees: List<FeeView>) = fees.filter { it.state in setOf(FeeState.DUE, FeeState.OVERDUE, FeeState.DRAFT, FeeState.RETURNED) }.sumOf { it.amount }

fun financeStatus(status: String): Pair<String, Tone> = when (status) {
    "draft" -> "Draft" to Tone.NEUTRAL
    "pending_verification" -> "Being checked" to Tone.INFO
    "verified" -> "Verified" to Tone.POSITIVE
    "incomplete" -> "Incomplete" to Tone.DANGER
    "returned_for_correction" -> "Returned" to Tone.DANGER
    "rejected" -> "Rejected" to Tone.DANGER
    else -> human(status) to Tone.NEUTRAL
}

data class FundsSummary(val received: Double, val spent: Double) { val balance get() = received - spent }
/** Verified money only: payments in, expenses and reimbursements out. */
fun fundsSummary(records: List<JsonObject>): FundsSummary {
    val verified = records.filter { it.text("status") == "verified" }
    return FundsSummary(verified.filter { it.text("kind") == "payment" }.sumOf { it.amount() },
        verified.filter { it.text("kind") in setOf("expense", "reimbursement") }.sumOf { it.amount() })
}

/** Approval offices still missing before an expense or reimbursement can be verified (payments need none). */
fun missingApprovals(record: JsonObject, approvals: List<JsonObject>): List<String> =
    if (record.text("kind") == "payment") emptyList()
    else listOf("president", "finance").filter { office -> approvals.none { it.text("record_id") == record.text("id") && it.text("office") == office } }

fun financeToVerify(workspace: JsonObject) = workspace.records("financial_records")
    .filter { it.text("status") == "pending_verification" && it.text("user_id") != workspace.text("user_id") }.sortedBy { it.text("created_at") }

/** An unpaid fee assessment this verified payment settles (same person and amount; same purpose preferred). */
fun matchingFee(payment: JsonObject, fees: List<JsonObject>): JsonObject? {
    val open = fees.filter { it.text("payment_id").isBlank() && it.text("user_id") == payment.text("user_id") && sameAmount(it, payment) }
    return open.find { it.text("purpose").trim().equals(payment.text("purpose").trim(), true) } ?: open.minByOrNull { it.text("due_on") }
}

/** Verified payments that still need to be matched to the fee they pay (this is what activates a pending membership). */
fun paymentsToLink(workspace: JsonObject): List<Pair<JsonObject, JsonObject>> {
    val fees = workspace.records("fee_assessments"); val linked = fees.map { it.text("payment_id") }.toSet()
    return workspace.records("financial_records").filter { it.text("kind") == "payment" && it.text("status") == "verified" && it.text("id") !in linked }
        .mapNotNull { p -> matchingFee(p, fees)?.let { p to it } }
}

/** A receipt reference as the server stores it (spaces removed, upper case), or null when it can't be one. */
fun receiptReference(input: String): String? = input.replace(Regex("\\s"), "").uppercase().takeIf { Regex("^[A-Z0-9-]{6,30}$").matches(it) }

// ---------------------------------------------------------------- Applicant pay step

/** Where to pay, as the Treasurer recorded it in club settings. */
data class PaymentDetails(val gcashName: String, val gcashNumber: String, val bankName: String, val bankAccountName: String, val bankAccountNumber: String) {
    val hasGcash get() = gcashNumber.isNotBlank()
    val hasBank get() = bankAccountNumber.isNotBlank()
    val isEmpty get() = !hasGcash && !hasBank
}
fun paymentDetails(settings: JsonObject?): PaymentDetails {
    val d = settings?.get("payment_details") as? JsonObject
    fun f(k: String) = d?.text(k).orEmpty().trim()
    return PaymentDetails(f("gcash_name"), f("gcash_number"), f("bank_name"), f("bank_account_name"), f("bank_account_number"))
}

enum class PayStep { PAY, CHECKING, RETURNED }
fun payStep(fee: FeeView): PayStep = when (fee.state) {
    FeeState.CHECKING -> PayStep.CHECKING
    FeeState.RETURNED -> PayStep.RETURNED
    else -> PayStep.PAY
}

/** The unpaid membership fee an approved applicant must settle to become Active. */
fun membershipFeeDue(workspace: JsonObject, userId: String, today: LocalDate): FeeView? =
    feeViews(workspace, userId, today).firstOrNull { it.fee.text("purpose") == "Membership fee" && it.state != FeeState.PAID }

/** What the Send button should do: save a changed reference first, then send. Null = not ready yet. */
enum class PayAction { SAVE_REFERENCE, UPLOAD_PROOF, SEND }
fun payAction(typedReference: String, payment: JsonObject?, hasProof: Boolean): PayAction? {
    val clean = receiptReference(typedReference) ?: return null
    return when {
        payment == null || payment.text("reference") != clean -> PayAction.SAVE_REFERENCE
        !hasProof -> PayAction.UPLOAD_PROOF
        else -> PayAction.SEND
    }
}

/** What happens after submitting (option A: approve, then pay), so a waiting applicant sees the road ahead. */
data class NextStep(val title: String, val detail: String, val current: Boolean)
fun waitingSteps(view: ApplicationView, fee: Double): List<NextStep> {
    val total = view.documents.size; val verified = total - view.unverified
    val review = if (view.stage == ApplicationStage.UNDER_REVIEW && total > 0) "Checking your documents now: $verified of $total verified."
        else "Your details and documents are locked while it's reviewed. You'll get a notice when there's a decision."
    return listOf(
        NextStep("The Secretary reviews your application", review, true),
        if (fee > 0) NextStep("You pay the ${peso(fee)} membership fee", "Due within 2 weeks of approval (Bylaws 2.5.1). Where to pay appears here once you're approved.", false)
        else NextStep("No membership fee", "Approval makes you a member right away.", false),
        NextStep("You're an active member", "Practice sessions, club gear and the training log unlock.", false),
    )
}

// ---------------------------------------------------------------- Elections (M9)

fun electionStageLabel(status: String) = when (status) {
    "draft" -> "Draft"; "announced" -> "Candidacy open"; "voting" -> "Voting open"; "closed" -> "Counting"
    "confirmed" -> "Results out"; "requires_revote" -> "Revote needed"; "archived" -> "Archived"; else -> human(status)
}

/** Four-step timeline: Announced → Candidates → Voting → Results. */
fun electionTimeline(status: String): List<Milestone> {
    val reached = when (status) { "draft" -> 0; "announced" -> 1; "voting" -> 3; "closed", "requires_revote" -> 3; "confirmed", "archived" -> 4; else -> 0 }
    val now = when (status) { "announced" -> 1; "voting" -> 2; "closed", "requires_revote" -> 3; else -> -1 }
    return listOf("Announced", "Candidates", "Voting", "Results").mapIndexed { i, label -> Milestone(label, i < reached && i != now, i == now) }
}

/** The election a member should see first: voting, then candidacy, then the latest finished one. Drafts only for officers. */
fun featuredElection(elections: List<JsonObject>, officer: Boolean): JsonObject? {
    val visible = elections.filter { officer || it.text("status") != "draft" }
    val rank = mapOf("voting" to 0, "announced" to 1, "closed" to 2, "requires_revote" to 2, "draft" to 3, "confirmed" to 4, "archived" to 5)
    return visible.sortedWith(compareBy<JsonObject> { rank[it.text("status")] ?: 9 }.thenByDescending { it.text("voting_opens") }).firstOrNull()
}

// ---------------------------------------------------------------- Reports and goals (M13)

private val percentMetrics = setOf("applicant_conversion", "attendance_rate", "equipment_readiness", "training_average", "collection_completion")
data class GoalView(val title: String, val actual: Double?, val target: Double, val metric: String, val ends: LocalDate?, val basis: String) {
    val isPercent get() = metric in percentMetrics
    val progress: Float get() = if (actual == null || target <= 0) 0f else (actual / target).toFloat().coerceIn(0f, 1f)
    fun format(v: Double) = if (isPercent) "${plainNumber(v)}%" else plainNumber(v)
    val onTrack get() = actual != null && actual >= target
}
fun goalViews(report: JsonObject) = report.records("goals").map {
    GoalView(it.text("title"), it.text("actual").toDoubleOrNull(), it.amount("target"), it.text("metric"), it.date("ends_on"), it.text("basis"))
}

// ---------------------------------------------------------------- Administration (M14)

enum class TermState(val tone: Tone) { ACTIVE(Tone.NEUTRAL), ENDING(Tone.WARNING), EXPIRED(Tone.DANGER) }
data class TermView(val assignment: JsonObject, val name: String, val role: String, val ends: Instant?, val state: TermState)

/** Current officer terms (not retired), expired and ending-soon first. Terms expired more than 120 days ago are left out. */
fun termViews(workspace: JsonObject, now: Instant): List<TermView> {
    val roles = workspace.records("roles").associateBy { it.text("id") }
    return workspace.records("role_assignments").filter { it.text("retired_at").isBlank() }.mapNotNull { a ->
        val ends = a.instant("ends_at")
        val state = when { ends == null -> TermState.ACTIVE; ends.isBefore(now) -> TermState.EXPIRED
            ends.isBefore(now.plusSeconds(30 * 86400L)) -> TermState.ENDING; else -> TermState.ACTIVE }
        if (state == TermState.EXPIRED && ends!!.isBefore(now.minusSeconds(120 * 86400L))) null
        else TermView(a, memberName(workspace, a.text("user_id")), a.text("position").ifBlank { roles[a.text("role_id")]?.text("name").orEmpty() }, ends, state)
    }.sortedWith(compareBy<TermView> { it.state.ordinal * -1 }.thenBy { it.ends })
}

/** One role a person holds: the real role name, the title typed when it was assigned (if different), and its term. */
data class RoleLine(val assignment: JsonObject, val roleName: String, val title: String, val ends: Instant?, val state: TermState, val duplicate: Boolean)

/** Everyone with an officer role, once each, with the roles they hold. */
data class OfficerView(val userId: String, val name: String, val roles: List<RoleLine>) {
    val worst: TermState get() = TermState.values()[roles.maxOf { it.state.ordinal }]
    val nearestEnd: Instant? get() = roles.filter { it.state == worst }.mapNotNull { it.ends }.minOrNull()
    val roleNames: List<String> get() = roles.map { it.roleName }.distinct()
    val duplicates: Int get() = roles.count { it.duplicate }
}

fun officerViews(workspace: JsonObject, now: Instant): List<OfficerView> {
    val roles = workspace.records("roles").associateBy { it.text("id") }
    return termViews(workspace, now).groupBy { it.assignment.text("user_id") }.map { (userId, terms) ->
        // The same role held more than once: the one ending last is the real term, the rest are duplicates.
        val keep = terms.groupBy { it.assignment.text("role_id") }.mapValues { (_, same) -> same.maxByOrNull { it.ends ?: Instant.MAX }!!.assignment.text("id") }
        val lines = terms.map { t ->
            val roleName = roles[t.assignment.text("role_id")]?.text("name")?.ifBlank { null } ?: human(t.assignment.text("role_id"))
            val title = t.assignment.text("position").takeUnless { it.isBlank() || it.equals(roleName, true) }.orEmpty()
            RoleLine(t.assignment, roleName, title, t.ends, t.state, keep[t.assignment.text("role_id")] != t.assignment.text("id"))
        }.sortedWith(compareBy<RoleLine> { it.duplicate }.thenBy { it.roleName })
        OfficerView(userId, terms.first().name, lines)
    }.sortedWith(compareByDescending<OfficerView> { it.worst.ordinal }.thenBy { it.name })
}

data class SetupItem(val title: String, val detail: String, val done: Boolean)
fun clubSetup(settings: JsonObject?, applicationRequirements: List<String>, categories: List<String>): List<SetupItem> {
    val fee = settings?.amount("membership_fee") ?: 0.0
    return listOf(
        SetupItem("Application documents", if (applicationRequirements.isEmpty()) "None required yet" else applicationRequirements.joinToString(", "), applicationRequirements.isNotEmpty()),
        SetupItem("Membership fee", if (fee > 0) "${peso(fee)}, verified before membership starts" else "Free (no fee required)", true),
        SetupItem("Member categories", categories.joinToString(", ").ifBlank { "Not set" }, categories.isNotEmpty()),
        SetupItem("Club rules confirmed", if (settings?.text("rules_validated") == "true") "Confirmed" else "Elections stay locked until the rules are confirmed", settings?.text("rules_validated") == "true"),
    )
}

// ---------------------------------------------------------------- Interclub (M15)

/** The event to show first: preparation or active, soonest first; otherwise the most recent. */
fun featuredInterclub(events: List<JsonObject>, now: Instant): JsonObject? {
    val live = events.filter { it.text("status") in setOf("preparation", "active") }
    return live.minByOrNull { (it.instant("shared_start") ?: now).let { s -> Math.abs(s.epochSecond - now.epochSecond) } }
        ?: events.maxByOrNull { it.text("shared_start") }
}

data class PartnerView(val partner: JsonObject, val delegates: Int, val toReview: Int, val incomplete: Int) {
    val tone get() = when { incomplete > 0 -> Tone.DANGER; toReview > 0 -> Tone.WARNING; else -> Tone.POSITIVE }
    val label get() = when { incomplete > 0 -> "Missing info"; toReview > 0 -> "Review"; delegates == 0 -> "No delegates"; else -> "Ready" }
}
fun partnerViews(workspace: JsonObject, eventId: String): List<PartnerView> {
    val delegates = workspace.records("delegates").filter { it.text("event_id") == eventId }
    val invited = workspace.records("event_access").filter { it.text("event_id") == eventId && it.text("revoked_at").isBlank() }.map { it.text("partner_id") }.toSet()
    return workspace.records("partner_clubs").filter { p -> p.text("id") in invited || delegates.any { it.text("partner_id") == p.text("id") } }.map { p ->
        val mine = delegates.filter { it.text("partner_id") == p.text("id") }
        PartnerView(p, mine.count { it.text("status") != "substituted" }, mine.count { it.text("status") == "pending" }, mine.count { it.text("status") == "incomplete" })
    }.sortedBy { it.partner.text("name") }
}
fun delegateTone(status: String) = when (status) { "eligible", "checked_in" -> Tone.POSITIVE; "pending" -> Tone.WARNING; "incomplete", "rejected" -> Tone.DANGER; else -> Tone.NEUTRAL }

// ---------------------------------------------------------------- Roles & access (M14)

/** A desk a role can open: the server permission behind it and how officers know it. */
data class Desk(val permission: String, val title: String, val detail: String)
val DESKS = listOf(
    Desk("membership", "Applications", "Review applicants and renewals"),
    Desk("activities", "Events & attendance", "Post events, take attendance"),
    Desk("elections", "Elections", "Run elections and terms"),
    Desk("equipment", "Equipment", "Inventory and borrowing"),
    Desk("training", "Coach desk", "Check training rounds"),
    Desk("finance", "Finance desk", "Verify payments, set fees"),
    Desk("president", "Fund approval", "Approve fund releases"),
    Desk("reports", "Reports & goals", "Club goals and dashboards"),
    Desk("interclub", "Partner clubs", "Interclub events"),
    Desk("administration", "Administration", "Settings, roles, accounts"),
)
fun deskTitle(permission: String): String = DESKS.find { it.permission == permission }?.title ?: human(permission)
/** Desks in the fixed DESKS order, so chips and summaries always read the same way. */
fun deskOrder(permissions: Collection<String>): List<String> = DESKS.map { it.permission }.filter { it in permissions }

private fun JsonObject.stringList(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

data class RoleView(val id: String, val name: String, val permissions: List<String>, val defaults: List<String>?, val holders: List<String>) {
    val changedFromDefault get() = defaults != null && defaults.toSet() != permissions.toSet()
}

/** Every role with its desks and the people holding it right now (active, not retired), most-held first. */
fun roleViews(workspace: JsonObject, now: Instant): List<RoleView> {
    val live = workspace.records("role_assignments").filter { a ->
        a.text("retired_at").isBlank() && (a.instant("starts_at")?.let { !it.isAfter(now) } ?: true) && (a.instant("ends_at")?.isAfter(now) ?: true)
    }
    return workspace.records("roles").map { r ->
        val id = r.text("id")
        RoleView(id, r.text("name").ifBlank { human(id) }, deskOrder(r.stringList("permissions").orEmpty()), r.stringList("default_permissions")?.let(::deskOrder),
            live.filter { it.text("role_id") == id }.map { it.text("user_id") }.distinct().map { memberName(workspace, it) }.sorted())
    }.sortedWith(compareByDescending<RoleView> { it.holders.size }.thenBy { it.name.lowercase() })
}

/** "Range marshal" -> "range_marshal"; never clashes with an existing id. */
fun roleIdFrom(name: String, existing: Set<String>): String {
    var base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(60)
    if (base.isEmpty() || !base[0].isLetter()) base = "role_$base".trimEnd('_')
    if (base.length < 2) base = "role"
    var id = base; var n = 2
    while (id in existing) id = "${base}_${n++}"
    return id
}

data class RoleChange(val added: List<String>, val removed: List<String>) { val none get() = added.isEmpty() && removed.isEmpty() }
fun roleChange(before: Collection<String>, after: Collection<String>) = RoleChange(deskOrder(after - before.toSet()), deskOrder(before - after.toSet()))

/** What a save would break. BLOCK stops the save (the server refuses it too); WARN lets the club decide. */
enum class Severity { WARN, BLOCK }
data class RoleWarning(val text: String, val severity: Severity)

private val CRITICAL = linkedMapOf(
    "administration" to "Nobody would have Administration, so nobody could manage roles again. Give another role Administration first.",
    "president" to "No role will be able to approve fund releases. The bylaws need the President and Treasurer to approve reimbursements, so requests will wait until a role has Fund approval again.",
    "finance" to "No role will be able to verify payments, so applicants can't finish joining.",
    "membership" to "No role will be able to review applications.",
    "elections" to "No role will be able to run elections.",
)

/** Desks this change leaves with nobody to use them. Only desks someone currently holds count, so an unused desk never warns. */
fun roleWarnings(roles: List<RoleView>, roleId: String, after: Collection<String>): List<RoleWarning> {
    val role = roles.find { it.id == roleId } ?: return emptyList()
    val removed = role.permissions - after.toSet()
    return CRITICAL.filterKeys { it in removed }.mapNotNull { (p, text) ->
        val stillHeld = roles.any { it.id != roleId && p in it.permissions && it.holders.isNotEmpty() }
        when {
            stillHeld || role.holders.isEmpty() -> null
            p == "administration" -> RoleWarning(text, Severity.BLOCK)
            else -> RoleWarning(text, Severity.WARN)
        }
    }
}

// ---------------------------------------------------------------- Officer home

/** Where each desk opens. Fund approval lives on the finance desk. */
fun deskRoute(permission: String): String = when (permission) {
    "membership" -> "applications"; "activities" -> "officer_activities"; "elections" -> "elections"; "equipment" -> "equipment"
    "training" -> "training_desk"; "finance", "president" -> "finance_desk"; "reports" -> "reports"; "interclub" -> "interclub"
    "administration" -> "admin"; else -> "module:14"
}

data class DeskTile(val permission: String, val title: String, val route: String, val waiting: Int, val detail: String, val urgent: Boolean)

/** One tile per desk the person's roles open (Roles & access), always shown, with what is waiting on it. */
fun deskTiles(workspace: JsonObject, permissions: Set<String>, now: Instant): List<DeskTile> =
    deskOrder(permissions).filterNot { it == "president" && "finance" in permissions }.map { p ->
        val queues = officerQueues(workspace, setOf(p), now).filter { it.count > 0 }
        val desk = DESKS.first { it.permission == p }
        DeskTile(p, desk.title, deskRoute(p), queues.sumOf { it.count },
            if (queues.isEmpty()) desk.detail else queues.take(2).joinToString(" · ") { "${it.count} ${it.label.lowercase()}" },
            queues.any { it.tone == Tone.DANGER })
    }

data class OfficerFocus(val eyebrow: String, val title: String, val detail: String, val route: String, val action: String)

private val shortDay = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
private fun day(i: Instant?) = i?.atZone(java.time.ZoneId.systemDefault())?.format(shortDay).orEmpty()

/** The single most urgent item across all of a person's desks: overdue/danger first, then the biggest queue. */
fun officerFocus(workspace: JsonObject, permissions: Set<String>, now: Instant): OfficerFocus? {
    val q = officerQueues(workspace, permissions, now).filter { it.count > 0 }
        .sortedWith(compareByDescending<QueueItem> { it.tone == Tone.DANGER }.thenByDescending { it.count }).firstOrNull() ?: return null
    val user = workspace.text("user_id")
    val more = if (q.count > 1) " · ${q.count - 1} more after this" else ""
    return when (q.label) {
        "Applications to review", "Documents to verify" -> workspace.records("applications")
            .filter { it.text("user_id") != user && it.text("status") in setOf("pending", "under_review") }.minByOrNull { it.text("submitted_at") }
            ?.let { a -> OfficerFocus("WAITING LONGEST", a.text("full_name").ifBlank { memberName(workspace, a.text("user_id")) },
                "Submitted ${day(a.instant("submitted_at"))}$more", "applications", "Review application") }
        "Scorecards to validate" -> workspace.records("training_records").filter { it.text("validation") == "pending" }.minByOrNull { it.text("encoded_at") }
            ?.let { r -> OfficerFocus("CHECK FIRST", "${memberName(workspace, r.text("user_id"))} · ${plainNumber(r.amount("score"))}/${plainNumber(r.amount("maximum_score"))}",
                "${r.text("scoring_format")} · ${plainNumber(r.amount("distance"))} m · ${r.text("target_face")}$more", "training_desk", "Open coach desk") }
        "Financial submissions" -> workspace.records("financial_records").filter { it.text("user_id") != user && it.text("status") == "pending_verification" }
            .minByOrNull { it.text("created_at") }
            ?.let { r -> OfficerFocus("TO VERIFY", "${r.text("purpose")} · ${peso(r.amount("amount"))}",
                memberName(workspace, r.text("user_id")) + (r.text("reference").takeIf { it.isNotBlank() }?.let { " · Ref $it" } ?: "") + more, "finance_desk", "Check and verify") }
        "Borrowing requests" -> workspace.records("borrowings").filter { it.text("status") == "pending" }.minByOrNull { it.text("starts_at") }
            ?.let { b -> OfficerFocus("BORROWING REQUEST", memberName(workspace, b.text("user_id")),
                (workspace.records("equipment").find { it.text("id") == b.text("equipment_id") }?.text("label") ?: "Club equipment") +
                    " · from ${day(b.instant("starts_at"))}$more", "equipment", "Review request") }
        else -> null
    } ?: OfficerFocus("NEEDS YOU FIRST", "${q.count} ${q.label.lowercase()}", deskFor(q.route).takeIf { it.isNotEmpty() }?.let(::deskTitle) ?: "Your desk", q.route, "Open")
}

private fun deskFor(route: String): String = when (route) {
    "applications", "name_corrections", "records:7", "module:7" -> "membership"; "equipment" -> "equipment"
    "records:8", "officer_activities", "module:8" -> "activities"; "module:11" -> "training"; "module:12" -> "finance"
    "module:9" -> "elections"; "module:15" -> "interclub"; "module:14" -> "administration"; else -> ""
}

/** "Equipment officer / Armorer" style names for the person's current roles, for the hero pill and Profile. */
fun myRoleLines(workspace: JsonObject, now: Instant): List<RoleLine> =
    officerViews(workspace, now).find { it.userId == workspace.text("user_id") }?.roles.orEmpty().filter { !it.duplicate && it.state != TermState.EXPIRED }

// ---------------------------------------------------------------- Club setup (M14)

enum class SetupGroup(val label: String) { CLUB("The club"), MONEY("Money"), ELECTIONS("Before elections") }
/** One line of Club setup. [owners] are the desks allowed to change it; [route] opens its editor. */
data class SetupLine(val key: String, val group: SetupGroup, val title: String, val detail: String, val done: Boolean,
                     val owners: Set<String>, val route: String) {
    val ownerLabel get() = when (owners) { setOf("finance") -> "Treasurer"; setOf("finance", "administration") -> "Treasurer · Admin"; else -> "Admin" }
    fun canEdit(permissions: Set<String>) = owners.any { it in permissions }
}

const val DEFAULT_RETENTION = "Awaiting club validation"
fun settingList(settings: JsonObject?, key: String): List<String> =
    (settings?.get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

fun setupChecklist(settings: JsonObject?): List<SetupLine> {
    val fee = settings?.amount("membership_fee") ?: 0.0
    val pay = paymentDetails(settings)
    val apply = settingList(settings, "application_requirements"); val renew = settingList(settings, "renewal_requirements")
    val cats = settingList(settings, "membership_categories"); val retention = settings?.text("retention_policy").orEmpty()
    val admin = setOf("administration")
    return listOf(
        SetupLine("club_name", SetupGroup.CLUB, "Club name", settings?.text("club_name").orEmpty().ifBlank { "Not set" }, settings?.text("club_name").orEmpty().isNotBlank(), admin, "setup_text:club_name"),
        SetupLine("membership_categories", SetupGroup.CLUB, "Member categories", cats.joinToString(", ").ifBlank { "Not set" }, cats.isNotEmpty(), admin, "setup_list:membership_categories"),
        SetupLine("application_requirements", SetupGroup.CLUB, "Application requirements", apply.joinToString(", ").ifBlank { "None yet: applicants upload nothing" }, apply.isNotEmpty(), admin, "setup_list:application_requirements"),
        SetupLine("renewal_requirements", SetupGroup.CLUB, "Renewal requirements", renew.joinToString(", ").ifBlank { "None required" }, true, admin, "setup_list:renewal_requirements"),
        SetupLine("retention_policy", SetupGroup.CLUB, "Record keeping", if (retention.isBlank() || retention == DEFAULT_RETENTION) "Not set" else retention,
            retention.isNotBlank() && retention != DEFAULT_RETENTION, admin, "setup_text:retention_policy"),
        SetupLine("membership_fee", SetupGroup.MONEY, "Membership fee", if (fee > 0) "${peso(fee)} per year" else "Free (no fee)", true, setOf("finance", "administration"), "setup_fees"),
        SetupLine("payment_details", SetupGroup.MONEY, "Where members pay",
            when { fee <= 0 -> "Not needed while the club is free"; pay.isEmpty -> "Not set: applicants can't see where to send GCash"
                else -> listOfNotNull(pay.gcashNumber.takeIf { pay.hasGcash }?.let { "GCash $it" }, pay.bankName.ifBlank { "Bank" }.takeIf { pay.hasBank }).joinToString(" · ") },
            fee <= 0 || !pay.isEmpty, setOf("finance", "administration"), "setup_fees"),
        SetupLine("rules_validated", SetupGroup.ELECTIONS, "Confirm club rules",
            if (settings?.text("rules_validated") == "true") "Confirmed: elections are unlocked" else "Elections stay locked until confirmed",
            settings?.text("rules_validated") == "true", admin, "setup_confirm"),
    )
}

/** Suggestions from the club bylaws (revised August 2024) that the list doesn't have yet. */
fun setupSuggestions(key: String, current: List<String>): List<String> = when (key) {
    "application_requirements" -> listOf("Proof of DLSU affiliation (ID)", "Mentee training completion or evaluation result")
    "renewal_requirements" -> listOf("Proof of DLSU affiliation (ID)")
    "membership_categories" -> listOf("Undergraduate", "Graduate", "Alumni", "Faculty/Staff")
    else -> emptyList()
}.filter { s -> current.none { it.equals(s, ignoreCase = true) } }

fun setupListTitle(key: String) = when (key) {
    "application_requirements" -> "Application requirements"; "renewal_requirements" -> "Renewal requirements"; "membership_categories" -> "Member categories"; else -> human(key)
}

/** configure_club replaces every club setting at once, so an edit to one part must carry all the others unchanged. */
fun configureClubPayload(settings: JsonObject?, changes: Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
    fun list(k: String) = kotlinx.serialization.json.JsonArray(settingList(settings, k).map { kotlinx.serialization.json.JsonPrimitive(it) })
    val base = mapOf(
        "club_name" to kotlinx.serialization.json.JsonPrimitive(settings?.text("club_name").orEmpty()),
        "application_requirements" to list("application_requirements"),
        "renewal_requirements" to list("renewal_requirements"),
        "membership_categories" to list("membership_categories"),
        "retention_policy" to kotlinx.serialization.json.JsonPrimitive(settings?.text("retention_policy").orEmpty().ifBlank { DEFAULT_RETENTION }),
        "rules_validated" to kotlinx.serialization.json.JsonPrimitive(settings?.text("rules_validated") == "true"),
    )
    return JsonObject(base + changes)
}
