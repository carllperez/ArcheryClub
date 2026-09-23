package ph.capstone.archeryclub.data

import kotlinx.coroutines.flow.StateFlow
import ph.capstone.archeryclub.domain.*

/** The future authenticated Supabase repository implements this boundary. */
interface ClubRepository {
    val snapshot: StateFlow<ClubSnapshot>
    suspend fun saveApplicationDraft(form: ApplicationForm)
    suspend fun submitApplication(form: ApplicationForm)
    suspend fun updateMemberContact(fullName: String, phone: String)
    suspend fun requestRenewal()
}

interface SnapshotStore {
    fun load(): ClubSnapshot
    fun save(snapshot: ClubSnapshot)
}
