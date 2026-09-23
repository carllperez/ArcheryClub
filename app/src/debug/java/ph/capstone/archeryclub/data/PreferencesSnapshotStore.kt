package ph.capstone.archeryclub.data

import android.content.Context
import org.json.JSONObject
import ph.capstone.archeryclub.domain.*

/** Device-local preview storage. No passwords, authentication tokens or real club records. */
class PreferencesSnapshotStore(context: Context) : SnapshotStore {
    private val preferences = context.getSharedPreferences("local_preview_v1", Context.MODE_PRIVATE)

    override fun load(): ClubSnapshot {
        val raw = preferences.getString("snapshot", null) ?: return ClubSnapshot()
        val root = JSONObject(raw)
        val a = root.getJSONObject("application")
        val f = a.getJSONObject("form")
        val attachment = f.optJSONObject("attachment")?.let {
            Attachment(it.getString("name"), it.getString("uri"), it.getLong("size"))
        }
        val m = root.getJSONObject("member")
        return ClubSnapshot(
            ApplicationRecord(
                ApplicationForm(f.getString("name"), f.getString("email"), f.getString("phone"),
                    f.getString("studentNumber"), f.getString("experience"), attachment, f.getBoolean("confirmed")),
                ApplicationStatus.valueOf(a.getString("status")),
                a.optString("submittedAt").takeIf { it.isNotBlank() },
                a.optString("correctionNote").takeIf { it.isNotBlank() },
            ),
            MemberProfile(m.getString("name"), m.getString("email"), m.getString("phone"),
                m.getString("studentNumber"), m.getString("category"), m.getString("status"),
                m.getBoolean("renewalPending")),
        )
    }

    override fun save(snapshot: ClubSnapshot) {
        val a = snapshot.application
        val f = a.form
        val m = snapshot.member
        val form = JSONObject().put("name", f.fullName).put("email", f.email).put("phone", f.phone)
            .put("studentNumber", f.studentNumber).put("experience", f.experience).put("confirmed", f.confirmed)
        f.attachment?.let {
            form.put("attachment", JSONObject().put("name", it.name).put("uri", it.uri).put("size", it.sizeBytes))
        }
        val root = JSONObject()
            .put("application", JSONObject().put("form", form).put("status", a.status.name)
                .put("submittedAt", a.submittedAt ?: "").put("correctionNote", a.correctionNote ?: ""))
            .put("member", JSONObject().put("name", m.fullName).put("email", m.email).put("phone", m.phone)
                .put("studentNumber", m.studentNumber).put("category", m.category).put("status", m.status)
                .put("renewalPending", m.renewalPending))
        check(preferences.edit().putString("snapshot", root.toString()).commit()) {
            "The preview could not save your changes. Please try again."
        }
    }
}
