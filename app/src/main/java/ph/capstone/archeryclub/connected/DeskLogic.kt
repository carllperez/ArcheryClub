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
