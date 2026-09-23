package ph.capstone.archeryclub.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ph.capstone.archeryclub.domain.*

class LocalPreviewRepositoryTest {
    private class MemoryStore(var value: ClubSnapshot = ClubSnapshot()) : SnapshotStore {
        var failWrites = false
        override fun load() = value
        override fun save(snapshot: ClubSnapshot) {
            if (failWrites) error("Storage unavailable")
            value = snapshot
        }
    }
    private fun validForm() = ApplicationForm("  Jamie Cruz  ", " jamie@example.com ", confirmed = true)

    @Test fun incompleteDraftSurvivesRepositoryRecreation() = runBlocking {
        val store = MemoryStore()
        LocalPreviewRepository(store).saveApplicationDraft(ApplicationForm(fullName = "Jamie"))
        val restored = LocalPreviewRepository(store).snapshot.value.application
        assertEquals("Jamie", restored.form.fullName)
        assertEquals(ApplicationStatus.DRAFT, restored.status)
    }

    @Test fun invalidSubmissionDoesNotChangeStoredStatus() = runBlocking {
        val store = MemoryStore()
        val repository = LocalPreviewRepository(store)
        try { repository.submitApplication(ApplicationForm()); fail("Invalid application accepted") }
        catch (_: WorkflowException) { }
        assertEquals(ApplicationStatus.DRAFT, store.value.application.status)
    }

    @Test fun submissionNormalizesValuesAndLocksTheRecord() = runBlocking {
        val repository = LocalPreviewRepository(MemoryStore())
        repository.submitApplication(validForm())
        val saved = repository.snapshot.value.application
        assertEquals("Jamie Cruz", saved.form.fullName)
        assertEquals("jamie@example.com", saved.form.email)
        assertEquals(ApplicationStatus.PENDING, saved.status)
        assertNotNull(saved.submittedAt)
        try { repository.saveApplicationDraft(validForm().copy(fullName = "Replacement")); fail("Locked record changed") }
        catch (_: WorkflowException) { }
        assertEquals(saved, repository.snapshot.value.application)
    }

    @Test fun repeatSubmissionIsRejected() = runBlocking {
        val repository = LocalPreviewRepository(MemoryStore())
        repository.submitApplication(validForm())
        try { repository.submitApplication(validForm()); fail("Duplicate submission accepted") }
        catch (_: WorkflowException) { }
    }

    @Test fun correctionsCanBeResubmittedButDoNotApproveMembership() = runBlocking {
        val store = MemoryStore(ClubSnapshot(application = ApplicationRecord(
            status = ApplicationStatus.RETURNED_FOR_CORRECTION, correctionNote = "Please correct your email.",
        )))
        val repository = LocalPreviewRepository(store)
        val memberBefore = repository.snapshot.value.member
        repository.submitApplication(validForm())
        assertEquals(ApplicationStatus.PENDING, repository.snapshot.value.application.status)
        assertNull(repository.snapshot.value.application.correctionNote)
        assertEquals(memberBefore, repository.snapshot.value.member)
    }

    @Test fun allOfficialDecisionStatesRejectApplicantEdits() = runBlocking {
        for (status in listOf(ApplicationStatus.PENDING, ApplicationStatus.UNDER_REVIEW,
            ApplicationStatus.APPROVED, ApplicationStatus.REJECTED)) {
            val repository = LocalPreviewRepository(MemoryStore(ClubSnapshot(
                application = ApplicationRecord(status = status))))
            try { repository.saveApplicationDraft(validForm()); fail("Edited $status") }
            catch (_: WorkflowException) { }
        }
    }

    @Test fun contactEditPreservesOfficialMemberFields() = runBlocking {
        val repository = LocalPreviewRepository(MemoryStore())
        val before = repository.snapshot.value.member
        repository.updateMemberContact("Jamie Cruz", "09171234567")
        assertEquals(before.copy(fullName = "Jamie Cruz", phone = "09171234567"), repository.snapshot.value.member)
    }

    @Test fun renewalCannotChangeStandingOrBeRequestedTwice() = runBlocking {
        val repository = LocalPreviewRepository(MemoryStore())
        val status = repository.snapshot.value.member.status
        repository.requestRenewal()
        assertTrue(repository.snapshot.value.member.renewalPending)
        assertEquals(status, repository.snapshot.value.member.status)
        try { repository.requestRenewal(); fail("Duplicate renewal accepted") }
        catch (_: WorkflowException) { }
    }

    @Test fun failedSaveDoesNotShowUnpersistedSuccess() = runBlocking {
        val store = MemoryStore()
        val repository = LocalPreviewRepository(store)
        store.failWrites = true
        try { repository.submitApplication(validForm()); fail("Storage error ignored") }
        catch (_: IllegalStateException) { }
        assertEquals(ApplicationStatus.DRAFT, repository.snapshot.value.application.status)
        assertEquals(store.value, repository.snapshot.value)
    }

    @Test fun invalidEmailAndMissingConfirmationHaveFieldErrors() {
        val errors = validForm().copy(email = "missing-at.example.com", confirmed = false).errors()
        assertTrue(errors.containsKey("email"))
        assertTrue(errors.containsKey("confirmed"))
    }
}
