package ph.capstone.archeryclub.data

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.capstone.archeryclub.domain.*

/** Debug-only sample data. These checks are workflow behavior, not a security boundary. */
class LocalPreviewRepository(private val store: SnapshotStore) : ClubRepository {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(store.load().withFixtures())
    override val snapshot = mutable.asStateFlow()

    override suspend fun refresh() = lock.withLock {
        mutable.value = store.load().withFixtures()
    }

    private suspend fun update(transform: (ClubSnapshot) -> ClubSnapshot) = lock.withLock {
        val next = transform(mutable.value)
        store.save(next)
        mutable.value = next
    }

    override suspend fun saveApplicationDraft(form: ApplicationForm) = update { current ->
        requireEditable(current.application)
        current.copy(application = current.application.copy(form = form.normalized()))
    }

    override suspend fun submitApplication(form: ApplicationForm) = update { current ->
        requireEditable(current.application)
        val clean = form.normalized()
        if (clean.errors().isNotEmpty()) throw WorkflowException("Review the highlighted application fields.")
        current.copy(application = current.application.copy(form = clean, status = ApplicationStatus.PENDING,
            submittedAt = Instant.now().toString(), correctionNote = null))
    }

    override suspend fun updateMemberContact(fullName: String, phone: String) = update { current ->
        val errors = ApplicationForm(fullName, current.member.email, phone, confirmed = true).errors()
        if (errors.isNotEmpty()) throw WorkflowException(errors.values.first())
        current.copy(member = current.member.copy(fullName = fullName.trim(), phone = phone.trim()))
    }

    override suspend fun requestRenewal() = update { current ->
        if (current.member.renewalPending) throw WorkflowException("A renewal request is already pending.")
        current.copy(member = current.member.copy(renewalPending = true))
    }

    override suspend fun setActivityRegistration(activityId: String, registered: Boolean) = update { current ->
        val activity = current.activities.firstOrNull { it.id == activityId }
            ?: throw WorkflowException("Activity not found.")
        if (!activity.eligible) throw WorkflowException("You are not eligible for this activity.")
        current.copy(
            activities = current.activities.map { if (it.id == activityId) it.copy(registered = registered) else it },
            registeredActivityIds = if (registered) current.registeredActivityIds + activityId else current.registeredActivityIds - activityId,
        )
    }

    private fun requireEditable(record: ApplicationRecord) {
        if (!record.status.editable) throw WorkflowException("This application is locked while awaiting a club decision.")
    }

    private fun ClubSnapshot.withFixtures(): ClubSnapshot {
        if (activities.isNotEmpty() || attendance.isNotEmpty() || training.isNotEmpty()) return this
        val seeded = copy(
            activities = listOf(
                ClubActivity("ACT-001", "Weekly Club Training", "Saturday, 3 Oct 2026", "8:00 AM – 11:00 AM",
                    "University Archery Range", "Technique, form checks and scoring practice for regular members.", "2 Oct 2026"),
                ClubActivity("ACT-002", "Inter-Club Friendly Match", "Saturday, 10 Oct 2026", "7:00 AM – 2:00 PM",
                    "National Archery Range", "Friendly competition and team preparation.", "7 Oct 2026"),
                ClubActivity("ACT-003", "Equipment Inspection", "Tuesday, 13 Oct 2026", "4:00 PM – 6:00 PM",
                    "Club Equipment Room", "Member equipment inspection and safety check.", "12 Oct 2026"),
            ),
            attendance = listOf(
                AttendanceRecord("ACT-000", "Monthly Club Assembly", "5 Sep 2026", AttendanceStatus.PRESENT),
                AttendanceRecord("ACT-004", "Strength & Conditioning", "12 Sep 2026", AttendanceStatus.PRESENT),
                AttendanceRecord("ACT-005", "Technical Training", "19 Sep 2026", AttendanceStatus.EXCUSED, "Academic requirement"),
                AttendanceRecord("ACT-006", "Weekly Club Training", "26 Sep 2026", AttendanceStatus.ABSENT),
            ),
            training = listOf(
                TrainingRecord("TR-001", "19 Sep 2026", "Technical", "Anchor point consistency", 82, "Coach Maria", "Keep the anchor position consistent across the full shot cycle."),
                TrainingRecord("TR-002", "12 Sep 2026", "Scoring", "30-arrow scoring round", 76, "Coach Maria", "Work on grouping before increasing draw weight."),
                TrainingRecord("TR-003", "5 Sep 2026", "Form", "Stance and release", 88, "Coach Daniel", "Good release control. Continue follow-through drills."),
            ),
        )
        return seeded.copy(activities = seeded.activities.map { it.copy(registered = it.id in registeredActivityIds) })
    }
}
