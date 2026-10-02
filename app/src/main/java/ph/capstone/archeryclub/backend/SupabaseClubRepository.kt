package ph.capstone.archeryclub.backend

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ph.capstone.archeryclub.data.ClubRepository
import ph.capstone.archeryclub.domain.*

private const val ACTIVE = "Active"

@Serializable
private data class ProfileRow(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val email: String,
    val phone: String = "",
    @SerialName("student_number") val studentNumber: String = "",
    val category: String = "Regular member",
    @SerialName("membership_status") val membershipStatus: String = ACTIVE,
    @SerialName("renewal_pending") val renewalPending: Boolean = false,
)

@Serializable
private data class ActivityRow(
    val id: String,
    val title: String,
    val description: String,
    @SerialName("activity_date") val activityDate: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val location: String,
    @SerialName("registration_deadline") val registrationDeadline: String,
    val published: Boolean = true,
)

@Serializable
private data class RegistrationRow(
    @SerialName("activity_id") val activityId: String,
    @SerialName("member_id") val memberId: String,
    val status: String = "registered",
)

@Serializable
private data class AttendanceRow(
    @SerialName("activity_id") val activityId: String,
    @SerialName("member_id") val memberId: String,
    val status: String,
    val note: String? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
private data class UpdateContactParams(
    val full_name: String,
    val phone: String,
)

@Serializable
private data class AnnouncementRow(
    val id: String,
    val title: String,
    val message: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class TrainingRow(
    val id: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("training_date") val trainingDate: String,
    @SerialName("training_type") val trainingType: String,
    val focus: String,
    val score: Int? = null,
    val coach: String = "",
    val feedback: String = "",
)

/**
 * Real backend repository for M3 Activities, M4 Attendance/Training and M6 Dashboard data.
 * The publishable Supabase key is safe for client apps only when RLS policies are enabled.
 */
class SupabaseClubRepository : ClubRepository {
    private val client = createSupabaseClient(
        supabaseUrl = SupabaseConfig.url,
        supabaseKey = SupabaseConfig.publishableKey,
    ) {
        install(io.github.jan.supabase.auth.Auth)
        install(io.github.jan.supabase.postgrest.Postgrest)
    }

    private val mutable = MutableStateFlow(ClubSnapshot())
    override val snapshot = mutable.asStateFlow()

    val isSignedIn: Boolean
        get() = client.auth.currentUserOrNull() != null

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        refresh()
    }

    suspend fun signUp(email: String, password: String, fullName: String, studentNumber: String, phone: String) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName.trim())
                put("student_number", studentNumber.trim())
                put("phone", phone.trim())
            }
        }
        if (client.auth.currentUserOrNull() != null) refresh()
    }

    suspend fun signOut() {
        client.auth.signOut()
        mutable.value = ClubSnapshot()
    }

    override suspend fun refresh() {
        val user = client.auth.currentUserOrNull() ?: throw WorkflowException("Please sign in first.")
        val profile = client.from("profiles").select {
            filter { eq("id", user.id) }
        }.decodeSingle<ProfileRow>()

        val activities = client.from("activities").select {
            filter { eq("published", true) }
        }.decodeList<ActivityRow>()

        val registrations = client.from("activity_registrations").select {
            filter { eq("member_id", user.id) }
        }.decodeList<RegistrationRow>()
        val registeredIds = registrations.filter { it.status == "registered" }.map { it.activityId }.toSet()

        val attendance = client.from("attendance_records").select {
            filter { eq("member_id", user.id) }
        }.decodeList<AttendanceRow>()

        val activityById = activities.associateBy { it.id }
        val attendanceRecords = attendance.map { row ->
            val activity = activityById[row.activityId]
            AttendanceRecord(
                activityId = row.activityId,
                activityTitle = activity?.title ?: "Club activity",
                date = activity?.activityDate ?: row.recordedAt.take(10),
                status = row.status.toAttendanceStatus(),
                note = row.note,
            )
        }

        val training = client.from("training_records").select {
            filter { eq("member_id", user.id) }
        }.decodeList<TrainingRow>().map {
            TrainingRecord(it.id, it.trainingDate, it.trainingType, it.focus, it.score, it.coach, it.feedback)
        }

        val announcements = client.from("announcements").select {
            filter { eq("published", true) }
        }.decodeList<AnnouncementRow>().map {
            Announcement(
                id = it.id,
                title = it.title,
                message = it.message,
                createdAt = it.createdAt,
            )
        }

        mutable.value = ClubSnapshot(
            member = MemberProfile(
                fullName = profile.fullName,
                email = profile.email,
                phone = profile.phone,
                studentNumber = profile.studentNumber,
                category = profile.category,
                status = profile.membershipStatus,
                renewalPending = profile.renewalPending,
            ),
            activities = activities.map {
                ClubActivity(
                    id = it.id,
                    title = it.title,
                    date = it.activityDate,
                    time = listOf(it.startTime, it.endTime)
                        .filter(String::isNotBlank)
                        .joinToString(" – "),
                    location = it.location,
                    description = it.description,
                    registrationDeadline = it.registrationDeadline,
                    eligible = profile.membershipStatus == ACTIVE,
                    registered = it.id in registeredIds,
                )
            },
            attendance = attendanceRecords,
            training = training,
            announcements = announcements,
            registeredActivityIds = registeredIds,
        )
    }

    override suspend fun saveApplicationDraft(form: ApplicationForm) =
        throw WorkflowException("Applicant backend is not part of this M3–M6 backend package.")

    override suspend fun submitApplication(form: ApplicationForm) =
        throw WorkflowException("Applicant backend is not part of this M3–M6 backend package.")

    override suspend fun updateMemberContact(fullName: String, phone: String) {
        val user = client.auth.currentUserOrNull() ?: throw WorkflowException("Please sign in first.")
        val cleanName = fullName.trim()
        val cleanPhone = phone.trim()
        if (cleanName.length < 2) throw WorkflowException("Enter your full name.")
        client.postgrest.rpc("update_member_contact", UpdateContactParams(cleanName, cleanPhone))
        refresh()
    }

    override suspend fun requestRenewal() {
        val user = client.auth.currentUserOrNull() ?: throw WorkflowException("Please sign in first.")
        client.postgrest.rpc("request_membership_renewal")
        refresh()
    }

    override suspend fun setActivityRegistration(activityId: String, registered: Boolean) {
        val user = client.auth.currentUserOrNull() ?: throw WorkflowException("Please sign in first.")
        if (registered) {
            client.from("activity_registrations").insert(
                RegistrationRow(activityId = activityId, memberId = user.id, status = "registered")
            )
        } else {
            client.from("activity_registrations").delete {
                filter {
                    eq("activity_id", activityId)
                    eq("member_id", user.id)
                }
            }
        }
        refresh()
    }

    private fun String.toAttendanceStatus(): AttendanceStatus = when (uppercase()) {
        "PRESENT" -> AttendanceStatus.PRESENT
        "EXCUSED" -> AttendanceStatus.EXCUSED
        else -> AttendanceStatus.ABSENT
    }
}
