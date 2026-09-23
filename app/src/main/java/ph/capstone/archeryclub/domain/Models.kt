package ph.capstone.archeryclub.domain

enum class ApplicationStatus(val label: String) {
    DRAFT("Draft"), PENDING("Pending review"), UNDER_REVIEW("Under review"),
    INCOMPLETE("Incomplete"), RETURNED_FOR_CORRECTION("Returned for correction"),
    APPROVED("Approved"), REJECTED("Rejected");

    val editable: Boolean get() = this in setOf(DRAFT, INCOMPLETE, RETURNED_FOR_CORRECTION)
}

data class Attachment(val name: String, val uri: String, val sizeBytes: Long)

data class ApplicationForm(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val studentNumber: String = "",
    val experience: String = "",
    val attachment: Attachment? = null,
    val confirmed: Boolean = false,
) {
    fun normalized() = copy(
        fullName = fullName.trim(), email = email.trim(), phone = phone.trim(),
        studentNumber = studentNumber.trim(), experience = experience.trim(),
    )

    fun errors(): Map<String, String> = buildMap {
        if (fullName.trim().length < 2) put("name", "Enter your full name.")
        if (!EMAIL.matches(email.trim())) put("email", "Enter a valid email address.")
        if (phone.isNotBlank() && !PHONE.matches(phone.trim())) {
            put("phone", "Use 7–20 digits, spaces, +, parentheses or hyphens.")
        }
        if (!confirmed) put("confirmed", "Confirm your information before submitting.")
    }

    companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val PHONE = Regex("^[+()0-9 -]{7,20}$")
    }
}

data class ApplicationRecord(
    val form: ApplicationForm = ApplicationForm(),
    val status: ApplicationStatus = ApplicationStatus.DRAFT,
    val submittedAt: String? = null,
    val correctionNote: String? = null,
)

data class MemberProfile(
    val fullName: String = "Alex Reyes",
    val email: String = "alex@example.com",
    val phone: String = "",
    val studentNumber: String = "DEMO-001",
    val category: String = "Regular member",
    val status: String = "Active",
    val renewalPending: Boolean = false,
)

data class ClubSnapshot(
    val application: ApplicationRecord = ApplicationRecord(),
    val member: MemberProfile = MemberProfile(),
)

class WorkflowException(message: String) : IllegalStateException(message)
