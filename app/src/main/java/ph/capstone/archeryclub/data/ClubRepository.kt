package ph.capstone.archeryclub.data

import kotlinx.coroutines.flow.StateFlow
import ph.capstone.archeryclub.domain.*

/** Future authenticated Supabase implementation replaces this boundary. */
interface ClubRepository {
    val snapshot: StateFlow<ClubSnapshot>

    suspend fun refresh()

    suspend fun saveApplicationDraft(form: ApplicationForm)
    suspend fun submitApplication(form: ApplicationForm)
    suspend fun updateMemberContact(fullName: String, phone: String)
    suspend fun requestRenewal()
    suspend fun setActivityRegistration(activityId: String, registered: Boolean)
}

interface SnapshotStore {
    fun load(): ClubSnapshot
    fun save(snapshot: ClubSnapshot)
}
