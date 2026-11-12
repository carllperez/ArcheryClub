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
        NextStep("The club checks your application", review, true),
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

// ---------------------------------------------------------------- Coach CSV import (M11)

/** RFC 4180 rows: quoted fields may hold commas, line breaks and doubled quotes. */
fun csvRecords(csv: String, delimiter: Char = csvDelimiter(csv)): List<List<String>> {
    val records = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder()
    var quoted = false; var closed = false; var i = 0
    fun cell() { row.add(field.toString()); field.clear(); closed = false }
    while (i < csv.length) {
        val c = csv[i]
        if (quoted) { if (c == '"') { if (i + 1 < csv.length && csv[i + 1] == '"') { field.append('"'); i++ } else { quoted = false; closed = true } } else field.append(c) }
        else when (c) {
            '"' -> { require(field.isEmpty() && !closed) { "Invalid CSV quoting." }; quoted = true }
            delimiter -> cell()
            '\r', '\n' -> { cell(); records.add(row); row = mutableListOf(); if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++ }
            else -> { require(!closed) { "Unexpected text after a quoted CSV field." }; field.append(c) }
        }
        i++
    }
    require(!quoted) { "A quoted field in the CSV is never closed." }
    if (field.isNotEmpty() || row.isNotEmpty() || closed) { cell(); records.add(row) }
    return records.filter { r -> r.any { it.isNotBlank() } }
}

/** Scoring apps export with semicolons; spreadsheets with commas. Decided by the header line. */
fun csvDelimiter(csv: String): Char { val head = csv.lineSequence().firstOrNull().orEmpty(); return if (head.count { it == ';' } > head.count { it == ',' }) ';' else ',' }

/** The columns a coach fills in, in plain words. Older technical names are still accepted. */
private val COACH_COLUMNS = linkedMapOf(
    "member" to listOf("member", "name", "archer", "user_id"),
    "date" to listOf("date", "training_date"),
    "distance" to listOf("distance", "distance_m"),
    "target_face" to listOf("target_face", "target", "face"),
    "bow" to listOf("bow", "category", "bow_type"),
    "round" to listOf("round", "scoring_format", "format"),
    "score" to listOf("score"),
    "max_score" to listOf("max_score", "maximum_score", "max"),
    "notes" to listOf("notes", "observations"),
    "feedback" to listOf("feedback"),
    "grouping" to listOf("grouping"),
    "level" to listOf("level"),
    "import_key" to listOf("import_key"),
)
private val COACH_REQUIRED = listOf("member", "date", "distance", "target_face", "bow", "round", "score", "max_score")

fun coachCsvTemplate(exampleName: String, today: LocalDate): String =
    "member,date,distance,target_face,bow,round,score,max_score,notes\n" +
        "${exampleName.replace(",", " ")},$today,18,80 cm,Recurve,WA 360,286,360,Windy on the right side\n"

/** One parsed CSV: the rows ready to send to import_training, one line per row for the preview, and anything to fix first. */
data class CoachImport(val rows: List<JsonObject>, val preview: List<String>, val problems: List<String>)

fun coachImport(csv: String, workspace: JsonObject): CoachImport {
    val records = csvRecords(csv)
    if (records.size < 2) return CoachImport(emptyList(), emptyList(), listOf("The file has no rounds under the header row."))
    if (records.size > 501) return CoachImport(emptyList(), emptyList(), listOf("Import up to 500 rounds at a time."))
    val headers = records.first().map { it.trim().removePrefix("﻿").lowercase().replace(Regex("[\\s-]+"), "_") }
    val index = COACH_COLUMNS.mapValues { (_, aliases) -> headers.indexOfFirst { it in aliases } }
    val missing = COACH_REQUIRED.filter { index.getValue(it) < 0 }
    if (missing.isNotEmpty()) return CoachImport(emptyList(), emptyList(), listOf("Add the missing column${if (missing.size == 1) "" else "s"}: ${missing.joinToString(", ")}."))
    val members = workspace.records("member_directory").filter { it.text("status") == "Active" }
    val rows = mutableListOf<JsonObject>(); val preview = mutableListOf<String>(); val problems = mutableListOf<String>()
    records.drop(1).forEachIndexed { n, values ->
        val line = n + 2
        fun v(key: String) = index.getValue(key).let { if (it in values.indices) values[it].trim() else "" }
        val who = v("member")
        val matches = members.filter { it.text("user_id") == who || it.text("full_name").equals(who, ignoreCase = true) }
        val score = v("score").toDoubleOrNull(); val max = v("max_score").toDoubleOrNull(); val distance = v("distance").removeSuffix("m").trim().toDoubleOrNull()
        val date = runCatching { LocalDate.parse(v("date")) }.getOrNull()
        val issue = when {
            who.isBlank() -> "no member"
            matches.isEmpty() -> "\"$who\" is not an active member"
            matches.size > 1 -> "more than one member is called \"$who\""
            date == null -> "date must look like 2026-11-12"
            distance == null || distance <= 0 -> "distance must be a number of metres"
            score == null || max == null || max <= 0 -> "score and max score must be numbers"
            score < 0 || score > max -> "score must be between 0 and the max score"
            COACH_REQUIRED.any { v(it).isBlank() } -> "fill in " + COACH_REQUIRED.filter { v(it).isBlank() }.joinToString(", ")
            else -> null
        }
        if (issue != null) { problems.add("Row $line: $issue."); return@forEachIndexed }
        val userId = matches.single().text("user_id")
        val key = v("import_key").ifBlank { "csv:$userId:$date:${v("round")}:${plainNumber(score!!)}" }
        rows.add(JsonObject(buildMap {
            put("import_key", kotlinx.serialization.json.JsonPrimitive(key)); put("user_id", kotlinx.serialization.json.JsonPrimitive(userId))
            put("training_date", kotlinx.serialization.json.JsonPrimitive(date.toString())); put("distance", kotlinx.serialization.json.JsonPrimitive(plainNumber(distance!!)))
            put("target_face", kotlinx.serialization.json.JsonPrimitive(v("target_face"))); put("category", kotlinx.serialization.json.JsonPrimitive(v("bow")))
            put("scoring_format", kotlinx.serialization.json.JsonPrimitive(v("round"))); put("score", kotlinx.serialization.json.JsonPrimitive(plainNumber(score!!)))
            put("maximum_score", kotlinx.serialization.json.JsonPrimitive(plainNumber(max!!)))
            listOf("notes" to "observations", "feedback" to "feedback", "grouping" to "grouping", "level" to "level").forEach { (from, to) ->
                v(from).takeIf { it.isNotBlank() }?.let { put(to, kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }))
        preview.add("${matches.single().text("full_name")} · ${plainNumber(score!!)}/${plainNumber(max!!)} · ${v("round")} · $date")
    }
    val dupes = rows.groupBy { it.text("import_key") }.filter { it.value.size > 1 }.keys
    if (dupes.isNotEmpty()) problems.add("${dupes.size} round${if (dupes.size == 1) " appears" else "s appear"} twice in the file.")
    return CoachImport(rows, preview, problems)
}


// ---------------------------------------------------------------- Arrow-by-arrow exports (scoring apps)

/** A scoring-app export: one row per arrow with Title, Date, Distance, Target, End and Points, and no member column. */
fun isArrowExport(csv: String): Boolean = runCatching {
    val head = csvRecords(csv).firstOrNull().orEmpty().map { it.trim().removePrefix("\uFEFF").lowercase() }
    "points" in head && "end" in head && "date" in head
}.getOrDefault(false)

/** X counts 10, M (a miss) 0; anything else is its number. */
fun arrowPoints(value: String): Int? = when (val v = value.trim().uppercase()) { "X" -> 10; "M", "" -> 0; else -> v.toIntOrNull() }

fun bowCategory(bow: String): String = listOf("Compound", "Recurve", "Barebow", "Longbow", "Traditional").firstOrNull { bow.contains(it, ignoreCase = true) } ?: bow.ifBlank { "Recurve" }

/** Turns an arrow export into one training round per session and round, for one archer the coach picks. */
fun arrowImport(csv: String, userId: String, memberName: String): CoachImport {
    val records = csvRecords(csv)
    val head = records.first().map { it.trim().removePrefix("\uFEFF").lowercase() }
    fun col(name: String) = head.indexOf(name)
    val iTitle = col("title"); val iDate = col("date"); val iStd = col("standard round"); val iIndoor = col("indoor"); val iBow = col("bow")
    val iRound = col("round"); val iDist = col("distance"); val iTarget = col("target"); val iEnd = col("end"); val iPts = col("points")
    fun at(r: List<String>, i: Int) = if (i in r.indices) r[i].trim() else ""
    val problems = mutableListOf<String>()
    val arrows = records.drop(1).mapIndexedNotNull { n, r ->
        val p = arrowPoints(at(r, iPts))
        if (p == null || p !in 0..10) { problems.add("Row ${n + 2}: \"${at(r, iPts)}\" is not an arrow score."); null } else r to p
    }
    val sessions = arrows.groupBy { (r, _) -> listOf(at(r, iTitle), at(r, iDate), at(r, iRound)) }
    val rows = mutableListOf<JsonObject>(); val preview = mutableListOf<String>()
    sessions.forEach { (key, list) ->
        val (title, dateText, round) = key
        val first = list.first().first
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        val distance = Regex("[0-9]+(\\.[0-9]+)?").find(at(first, iDist))?.value?.toDoubleOrNull()
        if (date == null || distance == null) { problems.add("\"$title\": the date or distance can't be read."); return@forEach }
        val ends = list.groupBy { (r, _) -> at(r, iEnd) }.values.map { e -> e.sumOf { it.second } }
        val score = list.sumOf { it.second }; val max = list.size * 10
        val standard = at(first, iStd).ifBlank { "Practice" }
        val format = "$standard · ${list.size} arrows"
        val notes = listOfNotNull(title.ifBlank { null }, at(first, iIndoor).ifBlank { null }, "round $round".takeIf { sessions.keys.count { it[0] == title && it[1] == dateText } > 1 },
            "ends ${ends.joinToString(", ")}").joinToString(" · ")
        rows.add(JsonObject(mapOf(
            "import_key" to kotlinx.serialization.json.JsonPrimitive("arrows:$userId:$date:$title:$round"),
            "user_id" to kotlinx.serialization.json.JsonPrimitive(userId),
            "training_date" to kotlinx.serialization.json.JsonPrimitive(date.toString()),
            "distance" to kotlinx.serialization.json.JsonPrimitive(plainNumber(distance)),
            "target_face" to kotlinx.serialization.json.JsonPrimitive(at(first, iTarget).ifBlank { "Unknown face" }),
            "category" to kotlinx.serialization.json.JsonPrimitive(bowCategory(at(first, iBow))),
            "scoring_format" to kotlinx.serialization.json.JsonPrimitive(format.take(100)),
            "score" to kotlinx.serialization.json.JsonPrimitive(score.toString()),
            "maximum_score" to kotlinx.serialization.json.JsonPrimitive(max.toString()),
            "observations" to kotlinx.serialization.json.JsonPrimitive(notes),
            "arrows" to arrowsJson(list.groupBy { (r, _) -> at(r, iEnd) }.values.map { e ->
                e.map { (r, _) -> Arrow(at(r, iPts).uppercase().ifBlank { "M" }, at(r, col("x")).toDoubleOrNull(), at(r, col("y")).toDoubleOrNull()) }
            }, RoundInfo(title, standard, at(first, iIndoor), at(first, iBow), at(first, col("arrow")))),
        )))
        preview.add("$memberName · $score/$max · ${list.size} arrows, ${ends.size} ends · $date")
    }
    if (rows.isEmpty() && problems.isEmpty()) problems.add("The file has no arrows in it.")
    return CoachImport(rows, preview, problems)
}

// ---------------------------------------------------------------- Arrow-by-arrow rounds

/** One arrow: its score as written (X, 10..1, M) and, from scoring apps, where it hit (1 = the target's outer edge, +y is down). */
data class Arrow(val p: String, val x: Double? = null, val y: Double? = null) { val points get() = arrowPoints(p) ?: 0 }

val ARROW_KEYS = listOf("X", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "M")

/** The round's ends, or empty when the round was saved as a total only. */
fun roundArrows(row: JsonObject): List<List<Arrow>> {
    val ends = (row["arrows"] as? JsonObject)?.get("ends") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return ends.mapNotNull { e ->
        (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { a ->
            (a as? JsonObject)?.let { o -> Arrow(o.text("p").uppercase(), o.text("x").toDoubleOrNull(), o.text("y").toDoubleOrNull()) }
        }?.takeIf { it.isNotEmpty() }
    }
}

fun arrowsJson(ends: List<List<Arrow>>, info: RoundInfo? = null): JsonObject = JsonObject(buildMap {
    put("ends", kotlinx.serialization.json.JsonArray(ends.map { e ->
        kotlinx.serialization.json.JsonArray(e.map { a -> JsonObject(buildMap {
            put("p", kotlinx.serialization.json.JsonPrimitive(a.p))
            a.x?.let { put("x", kotlinx.serialization.json.JsonPrimitive(it)) }; a.y?.let { put("y", kotlinx.serialization.json.JsonPrimitive(it)) }
        }) })
    }))
    info?.let { put("session", JsonObject(it.asMap().filterValues { v -> v.isNotBlank() }.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) })) }
})

/** The session details a scoring app exports (Title, Standard round, Indoor/Outdoor, Bow, Arrow), kept with the arrows. */
data class RoundInfo(val title: String = "", val standardRound: String = "", val environment: String = "", val bow: String = "", val arrow: String = "",
                     val sessionId: String = "", val plannedEnds: Int? = null) {
    fun asMap() = mapOf("title" to title, "standard_round" to standardRound, "environment" to environment, "bow" to bow, "arrow" to arrow,
        "session_id" to sessionId, "planned_ends" to (plannedEnds?.toString() ?: ""))
}

/** A round's session details: from its arrows when saved arrow by arrow, otherwise from its ordinary fields. */
fun roundInfo(row: JsonObject): RoundInfo {
    val s = (row["arrows"] as? JsonObject)?.get("session") as? JsonObject
    return RoundInfo(s?.text("title").orEmpty(), s?.text("standard_round")?.ifBlank { null } ?: row.text("scoring_format"),
        s?.text("environment").orEmpty(), s?.text("bow")?.ifBlank { null } ?: row.text("category"), s?.text("arrow").orEmpty(),
        s?.text("session_id").orEmpty(), s?.text("planned_ends")?.toIntOrNull())
}

data class RoundStats(val total: Int, val max: Int, val endTotals: List<Int>, val endAverages: List<Double>,
                      val counts: List<Pair<String, Int>>, val hits: Int, val misses: Int, val centre: Pair<Double, Double>?, val plotted: Int) {
    val average get() = if (max == 0) 0.0 else total * 10.0 / max
}

fun roundStats(ends: List<List<Arrow>>): RoundStats {
    val all = ends.flatten()
    val placed = all.filter { it.x != null && it.y != null && it.p != "M" }
    return RoundStats(all.sumOf { it.points }, all.size * 10, ends.map { e -> e.sumOf { it.points } },
        ends.map { e -> e.sumOf { it.points }.toDouble() / e.size }, ARROW_KEYS.map { k -> k to all.count { it.p == k } }.filter { it.second > 0 },
        all.count { it.p != "M" }, all.count { it.p == "M" },
        placed.takeIf { it.isNotEmpty() }?.let { p -> p.sumOf { it.x!! } / p.size to p.sumOf { it.y!! } / p.size }, placed.size)
}

private fun oneDecimal(v: Double) = (Math.round(v * 10) / 10.0).toString()

/** Where the group sits, in words a coach would use: a tenth of the radius off centre is "low", less than that "slightly low". */
fun groupDescription(centre: Pair<Double, Double>?): String? {
    val (x, y) = centre ?: return null
    fun side(d: Double, minus: String, plus: String) = when {
        kotlin.math.abs(d) >= 0.1 -> if (d > 0) plus else minus
        kotlin.math.abs(d) >= 0.03 -> "slightly " + if (d > 0) plus else minus
        else -> ""
    }
    val v = side(y, "high", "low"); val h = side(x, "left", "right")
    return when { v.isEmpty() && h.isEmpty() -> "centred"; v.isEmpty() -> h; h.isEmpty() -> v; else -> "$v and $h" }
}

/** The one thing worth saying about the ends: the biggest drop or rise from one end to the next. */
fun endTrend(stats: RoundStats): String? {
    val a = stats.endAverages
    if (a.size < 2) return null
    val i = (1 until a.size).maxByOrNull { kotlin.math.abs(a[it] - a[it - 1]) } ?: return null
    val d = a[i] - a[i - 1]
    if (kotlin.math.abs(d) < 0.5) return "Steady across ${a.size} ends."
    return "${if (d < 0) "Dropped" else "Rose"} ${oneDecimal(kotlin.math.abs(d))} points per arrow from end $i to end ${i + 1}."
}

/** The score where an arrow hit, on a WA face where 1 is the outer edge: X inside half the 10 ring, a miss outside the 1 ring.
 *  Line cutters count up (WA rule): the shaft's own width, about 0.012 of the radius, is taken off before reading the ring. */
fun scoreAt(x: Double, y: Double): String {
    val r = kotlin.math.sqrt(x * x + y * y) - 0.012
    return when { r <= 0.05 -> "X"; r >= 1.0 -> "M"; else -> (10 - kotlin.math.floor(r * 10).toInt()).toString() }
}

/** How spread the group is across and up-down (one standard deviation each way), for the shaded ellipse. */
fun groupSpread(arrows: List<Arrow>): Pair<Double, Double>? {
    val p = arrows.filter { it.x != null && it.y != null && it.p != "M" }
    if (p.size < 2) return null
    val mx = p.sumOf { it.x!! } / p.size; val my = p.sumOf { it.y!! } / p.size
    return kotlin.math.sqrt(p.sumOf { (it.x!! - mx) * (it.x!! - mx) } / p.size) to kotlin.math.sqrt(p.sumOf { (it.y!! - my) * (it.y!! - my) } / p.size)
}

/** Ends kept in a saved-state string: "9@0.1/0.2,M;8" (position optional). */
fun encodeEnds(ends: List<List<Arrow>>): String = ends.joinToString(";") { e ->
    e.joinToString(",") { a -> a.p + if (a.x != null && a.y != null) "@${a.x}/${a.y}" else "" }
}
fun decodeEnds(text: String): List<List<Arrow>> = text.split(';').filter { it.isNotBlank() }.map { e ->
    e.split(',').filter { it.isNotBlank() }.map { s ->
        val p = s.substringBefore('@'); val pos = s.substringAfter('@', "")
        Arrow(p, pos.substringBefore('/').toDoubleOrNull(), pos.substringAfter('/', "").toDoubleOrNull())
    }
}


// ---------------------------------------------------------------- Training sessions (several rounds under one title)

/** A WA round's fixed set-up, filled in when "Standard round" is chosen. */
data class StandardRound(val name: String, val distance: Int, val face: String, val perEnd: Int, val ends: Int) {
    val arrows get() = perEnd * ends
}
val STANDARD_ROUNDS = listOf(
    StandardRound("WA 18m", 18, "WA Full (40cm)", 3, 20), StandardRound("WA 25m", 25, "WA Full (60cm)", 3, 20),
    StandardRound("WA 50m", 50, "WA Full (80cm)", 6, 12), StandardRound("WA 70m", 70, "WA Full (122cm)", 6, 12),
)
val DISTANCES_M = listOf(5, 10, 15, 18, 20, 25, 30, 40, 50, 60, 70, 90)
val TARGET_FACES = listOf("WA Full" to "Recurve and barebow, X to 1", "WA 6 Ring" to "Compound and outdoor", "WA 5 Ring" to "Indoor compound",
    "WA Vertical 3 Spot" to "Indoor, three faces", "Vegas 3 Spot" to "Indoor, three faces")
val FACE_SIZES = listOf("40cm", "60cm", "80cm", "122cm")
fun faceName(face: String) = face.substringBefore(" (").trim()
fun faceSize(face: String) = face.substringAfter("(", "").substringBefore(")").trim()

/** One training: its rounds in the order they were shot, with the combined score and where it stands with the coach. */
data class TrainingView(val sessionId: String, val rounds: List<ScoreCard>) {
    val first get() = rounds.first()
    val info get() = roundInfo(first.row)
    val title get() = info.title.ifBlank { first.row.text("scoring_format").ifBlank { "Training" } }
    val date get() = first.date
    val total get() = rounds.sumOf { it.score }
    val max get() = rounds.sumOf { it.max }
    /** Needs fixing beats with-coach beats personal; validated only when every round is. */
    val state get() = when {
        rounds.any { it.state == ScoreState.RETURNED } -> ScoreState.RETURNED
        rounds.any { it.state == ScoreState.PENDING } -> ScoreState.PENDING
        rounds.all { it.state == ScoreState.VALIDATED } -> ScoreState.VALIDATED
        else -> ScoreState.PERSONAL
    }
    val toSend get() = rounds.filter { it.state in setOf(ScoreState.PERSONAL, ScoreState.RETURNED) }
}

/** A person's trainings, newest first. Rounds saved together share a session id; older rounds stand alone. */
fun trainings(workspace: JsonObject, userId: String): List<TrainingView> =
    scoreCards(workspace, userId).groupBy { roundInfo(it.row).sessionId.ifBlank { it.id } }
        .map { (sid, rounds) -> TrainingView(sid, rounds.sortedBy { it.row.text("encoded_at") }) }
        .sortedWith(compareByDescending<TrainingView> { it.date }.thenByDescending { it.rounds.last().row.text("encoded_at") })

fun trainingById(workspace: JsonObject, sessionId: String): TrainingView? =
    scoreCards(workspace).filter { roundInfo(it.row).sessionId.ifBlank { it.id } == sessionId }.takeIf { it.isNotEmpty() }
        ?.let { TrainingView(sessionId, it.sortedBy { r -> r.row.text("encoded_at") }) }

/** "NOVEMBER 2026" headings for the list. */
fun monthHeading(d: LocalDate?): String = d?.let { "${it.month.name} ${it.year}" } ?: "UNDATED"

/** One line of a printed scoreboard: the end's arrows best first, its total and the running total. */
data class BoardLine(val end: Int, val arrows: List<String>, val total: Int, val running: Int)

fun boardLines(ends: List<List<Arrow>>): List<BoardLine> {
    var run = 0
    return ends.mapIndexed { i, e ->
        val t = e.sumOf { it.points }; run += t
        BoardLine(i + 1, e.sortedWith(compareByDescending<Arrow> { it.points }.thenBy { if (it.p == "X") 0 else 1 }).map { it.p }, t, run)
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** The training as a printable page: details, each round's ends with running totals, counts, and signature lines. */
fun scoreboardHtml(t: TrainingView, archer: String, coach: String = ""): String = buildString {
    val info = t.info; val all = t.rounds.flatMap { roundArrows(it.row) }; val st = roundStats(all)
    append("<!doctype html><html><head><meta charset='utf-8'><style>")
    append("body{font-family:sans-serif;font-size:11pt;color:#16201b;margin:24px}h1{font-size:18pt;margin:0 0 4px}h2{font-size:12pt;margin:18px 0 6px}")
    append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #9aa39d;padding:4px 6px;text-align:center}th{background:#e7f1ea}")
    append(".d td{text-align:left}.sig{display:flex;gap:40px;margin-top:40px}.sig div{flex:1;border-top:1px solid #16201b;padding-top:4px;font-size:9pt}")
    append("</style></head><body>")
    append("<h1>${esc(t.title)}</h1><div>DLSU Archery Club · Training scoreboard</div>")
    append("<h2>Details</h2><table class='d'>")
    listOf("Archer" to archer, "Date" to (t.date?.toString() ?: "—"), "Round" to info.standardRound.ifBlank { "Free training" },
        "Environment" to info.environment, "Bow" to info.bow, "Arrow" to info.arrow,
        "Total" to "${plainNumber(t.total)} / ${plainNumber(t.max)}").filter { it.second.isNotBlank() }
        .forEach { (k, v) -> append("<tr><th style='width:30%'>${esc(k)}</th><td>${esc(v)}</td></tr>") }
    append("</table>")
    t.rounds.forEachIndexed { i, r ->
        val ends = roundArrows(r.row); val per = ends.maxOfOrNull { it.size } ?: 0
        append("<h2>Round ${i + 1} · ${esc(r.conditions)}</h2>")
        if (ends.isEmpty()) { append("<p>Total only: ${esc(r.scoreLabel)}</p>"); return@forEachIndexed }
        append("<table><tr><th>End</th>"); (1..per).forEach { append("<th>$it</th>") }; append("<th>End</th><th>Total</th></tr>")
        boardLines(ends).forEach { l ->
            append("<tr><td>${l.end}</td>"); (0 until per).forEach { append("<td>${l.arrows.getOrNull(it) ?: ""}</td>") }
            append("<td><b>${l.total}</b></td><td>${l.running}</td></tr>")
        }
        append("</table>")
    }
    if (all.isNotEmpty()) {
        append("<h2>Summary</h2><table><tr>"); st.counts.forEach { append("<th>${it.first}</th>") }; append("<th>Hits</th><th>Average</th></tr><tr>")
        st.counts.forEach { append("<td>${it.second}</td>") }
        append("<td>${st.hits}</td><td>${Math.round(st.average * 100) / 100.0}</td></tr></table>")
    }
    append("<div class='sig'><div>Archer: ${esc(archer)}</div><div>Coach${if (coach.isBlank()) "" else ": " + esc(coach)}</div></div>")
    append("</body></html>")
}

/* Scoring moves end by end only when the scorer says so. The cursor is the end being filled or fixed;
 * a cursor equal to ends.size is a fresh end with no arrows yet. Empty ends are never kept. */
data class EndCursor(val ends: List<List<Arrow>>, val cursor: Int) {
    val current: List<Arrow> get() = ends.getOrNull(cursor).orEmpty()
    fun full(perEnd: Int) = current.size >= perEnd
}

/** Adds to the end under the cursor; null when that end already has its arrows. */
fun EndCursor.add(a: Arrow, perEnd: Int): EndCursor? = when {
    cursor >= ends.size -> EndCursor(ends + listOf(listOf(a)), ends.size)
    ends[cursor].size >= perEnd -> null
    else -> EndCursor(ends.mapIndexed { i, e -> if (i == cursor) e + a else e }, cursor)
}

/** Takes one arrow out of the end under the cursor. An end left empty disappears and the cursor stays on that place. */
fun EndCursor.remove(index: Int): EndCursor {
    val e = current; if (index !in e.indices) return this
    val left = e.filterIndexed { i, _ -> i != index }
    return if (left.isEmpty()) EndCursor(ends.filterIndexed { i, _ -> i != cursor }, cursor.coerceAtMost(ends.size - 1))
    else EndCursor(ends.mapIndexed { i, x -> if (i == cursor) left else x }, cursor)
}

/** Undo: the last arrow of this end, or of the end before when this one is still blank. */
fun EndCursor.undo(): EndCursor = when {
    current.isNotEmpty() -> remove(current.lastIndex)
    ends.isNotEmpty() -> EndCursor(ends, ends.lastIndex).let { it.remove(it.current.lastIndex) }
    else -> this
}

fun EndCursor.clearEnd(): EndCursor = if (cursor < ends.size) EndCursor(ends.filterIndexed { i, _ -> i != cursor }, cursor.coerceAtMost(ends.size - 1)) else this

/** Next end is allowed from any end with arrows, up to the round's planned number of ends. */
fun EndCursor.canNext(planned: Int?): Boolean = cursor < ends.size && (cursor < ends.lastIndex || planned == null || ends.size < planned)
fun EndCursor.next(planned: Int?): EndCursor = if (canNext(planned)) copy(cursor = cursor + 1) else this
fun EndCursor.previous(): EndCursor = if (cursor > 0) copy(cursor = cursor - 1) else this
