package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import java.time.Instant

/* Partner representatives join with an invite code (migration 044) instead of the DLSU membership application.
 * The Interclub officer creates a code per partner club and event; the rep signs up and enters it. */

/** What the code box keeps while typing: letters and digits in upper case, a dash after the fourth, 8 at most ("7KQ2-9F01"). */
fun inviteCodeInput(typed: String): String {
    val raw = typed.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)
    return if (raw.length > 4) raw.substring(0, 4) + "-" + raw.substring(4) else raw
}

fun validInviteCode(code: String) = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(code)

/** Field problems on the "I have an invite code" form, keyed by field. */
fun partnerClaimErrors(code: String, fullName: String, university: String, phone: String): Map<String, String> = buildMap {
    if (!validInviteCode(code)) put("code", "Enter the 8-character code from the host club, e.g. 7KQ2-9F01.")
    if (fullName.trim().length < 2) put("full_name", "Enter your full name.")
    if (university.trim().length < 2) put("university", "Enter your university or school.")
    if (phone.isBlank()) put("phone", "Enter your mobile number.") else if (!validMobile(phone)) put("phone", MOBILE_HINT)
}

enum class InviteState(val label: String, val tone: Tone) {
    WAITING("Waiting", Tone.WARNING), USED("Joined", Tone.POSITIVE), CANCELLED("Cancelled", Tone.NEUTRAL), EXPIRED("Expired", Tone.NEUTRAL)
}

data class InviteView(val id: String, val code: String, val partnerId: String, val partnerName: String, val state: InviteState,
                      val claimedBy: String, val expires: Instant?)

fun inviteState(invite: JsonObject, now: Instant): InviteState = when {
    invite.text("claimed_by").isNotBlank() -> InviteState.USED
    invite.text("revoked_at").isNotBlank() -> InviteState.CANCELLED
    instantOf(invite.text("expires_at"))?.isBefore(now) == true -> InviteState.EXPIRED
    else -> InviteState.WAITING
}

/** An event's invite codes, newest first, with the club and who used each. */
fun inviteViews(workspace: JsonObject, eventId: String, now: Instant, nameOf: (String) -> String): List<InviteView> {
    val clubs = workspace.records("partner_clubs").associate { it.text("id") to it.text("name") }
    return workspace.records("partner_invites").filter { it.text("event_id") == eventId }.sortedByDescending { it.text("created_at") }.map { v ->
        val by = v.text("claimed_by")
        InviteView(v.text("id"), v.text("code"), v.text("partner_id"), clubs[v.text("partner_id")] ?: "Partner club", inviteState(v, now),
            if (by.isBlank()) "" else nameOf(by), instantOf(v.text("expires_at")))
    }
}

/** The message a rep's club sends: what the code is for and how to use it. */
fun inviteMessage(eventTitle: String, clubName: String, code: String, closes: String): String =
    "You're invited to represent $clubName at \"$eventTitle\" (DLSU Archery Club).\n" +
        "1. Install the DLSU Archery Club app and create an account.\n" +
        "2. Choose \"I'm a partner club representative\" and enter this code: $code\n" +
        "The code works once and expires $closes."

/** Events a partner representative was invited to, soonest first (they never see other club records). */
fun partnerEvents(workspace: JsonObject): List<JsonObject> {
    val me = workspace.text("user_id")
    val mine = workspace.records("event_access").filter { it.text("user_id") == me && it.text("revoked_at").isBlank() }.map { it.text("event_id") }.toSet()
    return workspace.records("interclub_events").filter { it.text("event_id") in mine }.sortedBy { it.text("shared_start") }
}
