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
    private val mutable = MutableStateFlow(store.load())
    override val snapshot = mutable.asStateFlow()

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
        current.copy(application = current.application.copy(
            form = clean, status = ApplicationStatus.PENDING,
            submittedAt = Instant.now().toString(), correctionNote = null,
        ))
    }

    override suspend fun updateMemberContact(fullName: String, phone: String) = update { current ->
        val errors = ApplicationForm(fullName, current.member.email, phone, confirmed = true).errors()
        if (errors.isNotEmpty()) throw WorkflowException(errors.values.first())
        // Status, category, email, student number and role are deliberately not writable here.
        current.copy(member = current.member.copy(fullName = fullName.trim(), phone = phone.trim()))
    }

    override suspend fun requestRenewal() = update { current ->
        if (current.member.renewalPending) throw WorkflowException("A renewal request is already pending.")
        current.copy(member = current.member.copy(renewalPending = true))
    }

    private fun requireEditable(record: ApplicationRecord) {
        if (!record.status.editable) throw WorkflowException("This application is locked while awaiting a club decision.")
    }
}
