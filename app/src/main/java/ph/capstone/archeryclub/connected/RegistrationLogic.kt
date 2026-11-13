package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*

/* Registration rules with no Android dependencies, so they are unit-tested on the JVM.
 * The server repeats every check; these only explain problems before a request is sent. */

// ------------------------------------------------------------------ account sign-up

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun signUpErrors(email: String, password: String, confirm: String): Map<String, String> = buildMap {
    val e = email.trim()
    if (e.isEmpty()) put("email", "Enter your email address.")
    else if (e.length > 254 || !EMAIL.matches(e)) put("email", "Enter a valid email address, like name@dlsu.edu.ph.")
    if (password.length < 8) put("password", "Use at least 8 characters.")
    else if (password.none { it.isLetter() } || password.none { it.isDigit() }) put("password", "Use letters and at least one number.")
    if (confirm != password) put("confirm", "The passwords don't match.")
}

// ------------------------------------------------------------------ membership application

/** Where an application stands, in the words an applicant would use. */
enum class ApplicationStage(val label: String, val tone: Tone) {
    NOT_STARTED("Not started", Tone.NEUTRAL),
    DRAFT("Draft", Tone.NEUTRAL),
    SUBMITTED("Submitted", Tone.INFO),
    UNDER_REVIEW("Under review", Tone.INFO),
    NEEDS_CHANGES("Changes requested", Tone.WARNING),
    APPROVED("Approved", Tone.POSITIVE),
    REJECTED("Not approved", Tone.DANGER);
    /** The applicant can change details and documents only in these stages (mirrors the server lock). */
    val editable get() = this in setOf(NOT_STARTED, DRAFT, NEEDS_CHANGES)
    /** A membership officer can decide only in these stages. */
    val decidable get() = this in setOf(SUBMITTED, UNDER_REVIEW)
}

fun applicationStage(status: String): ApplicationStage = when (status) {
    "" -> ApplicationStage.NOT_STARTED
    "draft" -> ApplicationStage.DRAFT
    "pending" -> ApplicationStage.SUBMITTED
    "under_review" -> ApplicationStage.UNDER_REVIEW
    "incomplete", "returned_for_correction" -> ApplicationStage.NEEDS_CHANGES
    "approved" -> ApplicationStage.APPROVED
    "rejected" -> ApplicationStage.REJECTED
    else -> ApplicationStage.DRAFT
}

enum class RequirementState(val label: String, val tone: Tone) {
    MISSING("Missing", Tone.DANGER), UPLOADED("Waiting for verification", Tone.WARNING), VERIFIED("Verified", Tone.POSITIVE)
}

data class RequirementView(val name: String, val state: RequirementState, val documents: List<JsonObject>) {
    val latest get() = documents.maxByOrNull { it.text("created_at") }
}

data class ApplicationView(
    val application: JsonObject?,
    val stage: ApplicationStage,
    val requirements: List<RequirementView>,
    /** Uploaded files whose label is no longer a configured requirement; officers still verify them. */
    val otherDocuments: List<JsonObject>,
) {
    val id get() = application?.text("id").orEmpty()
    val remark get() = application?.text("remark").orEmpty()
    val missing get() = requirements.filter { it.state == RequirementState.MISSING }.map { it.name }
    val documents get() = requirements.flatMap { it.documents } + otherDocuments
    val unverified get() = documents.count { it.text("verified_by").isBlank() }
    val uploadedCount get() = requirements.count { it.state != RequirementState.MISSING }
}

fun applicationRequirements(workspace: JsonObject): List<String> =
    (workspace.records("settings").firstOrNull()?.get("application_requirements") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

/** The applicant's own application, or the one an officer opened. */
fun applicationView(workspace: JsonObject, application: JsonObject? = workspace.records("applications").find { it.text("user_id") == workspace.text("user_id") }): ApplicationView {
    val id = application?.text("id").orEmpty()
    // A replaced file stays on record (superseded_by set) but no longer counts toward the requirement.
    val docs = if (id.isBlank()) emptyList() else workspace.records("documents")
        .filter { it.text("module") == "2" && it.text("record_id") == id && it.text("superseded_by").isBlank() }
    val names = applicationRequirements(workspace)
    val reqs = names.map { name ->
        val mine = docs.filter { it.text("requirement") == name }
        val state = when {
            mine.isEmpty() -> RequirementState.MISSING
            mine.all { it.text("verified_by").isNotBlank() } -> RequirementState.VERIFIED
            else -> RequirementState.UPLOADED
        }
        RequirementView(name, state, mine)
    }
    return ApplicationView(application, applicationStage(application?.text("status").orEmpty()), reqs, docs.filter { it.text("requirement") !in names })
}

const val APPLICATION_FIELDS = "full_name,phone,student_number,sex,college,program,year_level,emergency_contact_name,emergency_contact_phone,experience"
/** DLSU colleges, by the short names students use. */
val COLLEGES = listOf("CCS", "GCOE", "COS", "CLA", "RVR-COB", "SOE", "BAGCED", "TDCOL", "Other")
val YEAR_LEVELS = listOf("1st year", "2nd year", "3rd year", "4th year", "5th year", "Graduate", "Alumni")
val SEXES = listOf("Male", "Female")

/** The membership category that matches the year level the applicant gave, from the club's own list, or null when
 *  none clearly matches (the officer then picks). Alumni go to an Alumni category, else to a regular one. */
fun categoryFor(yearLevel: String, categories: List<String>): String? {
    fun find(test: (String) -> Boolean) = categories.firstOrNull { test(it.lowercase()) }
    val y = yearLevel.trim().lowercase()
    return when {
        y.matches(Regex("[1-5](st|nd|rd|th) year")) -> find { "undergrad" in it }
        y == "graduate" -> find { "graduate" in it && "undergrad" !in it }
        y == "alumni" -> find { "alumni" in it } ?: find { "regular" in it }
        else -> null
    }
}

/** Mirrors the server: name required (≤160); optional fields are length-limited and the phone must look like one. */
fun applicationErrors(v: Map<String, String>): Map<String, String> = buildMap {
    val name = v["full_name"].orEmpty().trim()
    if (name.isEmpty()) put("full_name", "Enter your full name as it appears on your school ID.")
    else if (name.length > 160) put("full_name", "Keep it under 160 characters.")
    val phone = v["phone"].orEmpty().trim()
    if (phone.isNotEmpty() && !validMobile(phone)) put("phone", MOBILE_HINT)
    if (v["student_number"].orEmpty().trim().length > 40) put("student_number", "Keep it under 40 characters.")
    if (v["experience"].orEmpty().length > 2000) put("experience", "Keep it under 2,000 characters.")
    if (v["program"].orEmpty().trim().length > 120) put("program", "Keep it under 120 characters.")
    val ecName = v["emergency_contact_name"].orEmpty().trim(); val ecPhone = v["emergency_contact_phone"].orEmpty().trim()
    if (ecName.isEmpty() != ecPhone.isEmpty()) put(if (ecName.isEmpty()) "emergency_contact_name" else "emergency_contact_phone", "Give both the name and the number, or leave both empty.")
    if (ecPhone.isNotEmpty() && !PHONE.matches(ecPhone)) put("emergency_contact_phone", "Use a number like 0917 123 4567 or (02) 8123 4567.")
}

/** Everything still standing between the applicant and a successful submission, in plain words. */
fun submitBlockers(view: ApplicationView, values: Map<String, String>, confirmed: Boolean): List<String> = buildList {
    if (!view.stage.editable) { add("This application is being checked."); return@buildList }
    applicationErrors(values).values.forEach { add(it) }
    if (values["sex"].orEmpty().isBlank()) add("Pick your sex.")
    if (values["college"].orEmpty().isBlank()) add("Pick your college.")
    if (values["program"].orEmpty().isBlank()) add("Enter your program.")
    if (values["year_level"].orEmpty().isBlank()) add("Pick your year level.")
    // Documents attach to a saved draft, so a brand-new application must be saved before uploading.
    if (view.requirements.isNotEmpty() && view.id.isBlank()) add("Save your details first, then upload your documents.")
    else view.missing.forEach { add("Upload your $it.") }
    if (!confirmed) add("Confirm that your information is correct.")
}

/** Reasons an officer cannot approve yet. The server enforces the same rules. */
fun approvalBlockers(view: ApplicationView, reviewer: String): List<String> = buildList {
    if (view.application?.text("user_id") == reviewer) add("Another membership officer must decide your own application.")
    if (!view.stage.decidable) add("Only submitted applications can be decided.")
    view.missing.forEach { add("Missing: $it") }
    if (view.unverified > 0) add("Verify ${view.unverified} uploaded document${if (view.unverified == 1) "" else "s"} first.")
}

/** Applications waiting for this officer, oldest submission first (first come, first served). */
fun applicationsToReview(workspace: JsonObject): List<ApplicationView> {
    val me = workspace.text("user_id")
    return workspace.records("applications")
        .filter { it.text("user_id") != me && applicationStage(it.text("status")).decidable }
        .sortedBy { it.text("submitted_at").ifBlank { it.text("updated_at") } }
        .map { applicationView(workspace, it) }
}

/** Applications that already have a decision (approved, sent back, not accepted), newest first, for the Decided tab. */
fun decidedApplications(workspace: JsonObject): List<ApplicationView> {
    val me = workspace.text("user_id")
    return workspace.records("applications")
        .filter { it.text("user_id") != me && applicationStage(it.text("status")) in setOf(ApplicationStage.APPROVED, ApplicationStage.REJECTED, ApplicationStage.NEEDS_CHANGES) }
        .sortedByDescending { it.text("updated_at").ifBlank { it.text("submitted_at") } }
        .map { applicationView(workspace, it) }
}

/** The decision as the Decided list shows it: "Returned" reads better there than "Changes requested". */
fun decisionLabel(stage: ApplicationStage): String = when (stage) {
    ApplicationStage.APPROVED -> "Approved"; ApplicationStage.REJECTED -> "Not accepted"; ApplicationStage.NEEDS_CHANGES -> "Returned"; else -> stage.label
}

/** Ordered milestones shown to the applicant. */
data class Milestone(val title: String, val done: Boolean, val current: Boolean)

fun applicationMilestones(view: ApplicationView, membershipStatus: String): List<Milestone> {
    val s = view.stage
    val submitted = s !in setOf(ApplicationStage.NOT_STARTED, ApplicationStage.DRAFT)
    val reviewed = s in setOf(ApplicationStage.UNDER_REVIEW, ApplicationStage.APPROVED, ApplicationStage.REJECTED, ApplicationStage.NEEDS_CHANGES)
    val decided = s in setOf(ApplicationStage.APPROVED, ApplicationStage.REJECTED)
    val active = membershipStatus == "Active"
    val steps = listOf(
        "Details and documents" to (view.id.isNotBlank() && view.missing.isEmpty() && submitted),
        "Submitted" to submitted,
        "Checked by the club" to reviewed,
        (if (s == ApplicationStage.REJECTED) "Decision: not approved" else "Approved") to decided,
        "Membership fee paid · Active" to active,
    )
    val firstOpen = steps.indexOfFirst { !it.second }
    return steps.mapIndexed { i, (t, d) -> Milestone(t, d, i == firstOpen) }
}
