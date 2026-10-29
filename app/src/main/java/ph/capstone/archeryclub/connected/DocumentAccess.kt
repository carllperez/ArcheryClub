package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import java.time.Instant

/** Display only eligible upload actions; Storage and RPC permissions remain authoritative. */
fun canAttachDocument(module:Int,record:String,workspace:JsonObject,now:Instant=Instant.now()):Boolean {
    val user=workspace.text("user_id")
    val permissions=(workspace["permissions"] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}.toSet()
    val table=mapOf(1 to "renewals",2 to "applications",9 to "candidates",11 to "training_records",12 to "financial_records",15 to "delegates")[module]?:return false
    val row=workspace.records(table).find{it.text("id")==record}
    fun notClosed(value:String)=runCatching{!now.isAfter(Instant.parse(value))}.getOrDefault(false)
    return when(module){
        1,2,12->row!=null&&row.text("user_id")==user&&row.text("status") in setOf("draft","incomplete","returned_for_correction")
        9->{val election=workspace.records("elections").find{it.text("id")==row?.text("election_id")}
            row!=null&&("elections" in permissions||(row.text("user_id")==user&&election?.text("status")=="announced"&&notClosed(election.text("candidacy_closes"))))}
        11->row!=null&&("training" in permissions||(row.text("user_id")==user&&row.text("validation") in setOf("personal","returned_for_correction")))
        15->{val eventId=row?.text("event_id")?:record
            val event=workspace.records("interclub_events").find{it.text("event_id")==eventId}?:return false
            val grants=workspace.records("event_access").filter{it.text("event_id")==eventId&&it.text("user_id")==user&&it.text("revoked_at").isBlank()}
            val staff="interclub" in permissions||grants.any{it.text("role")=="coordinator"}
            if(staff)event.text("status")!="archived"
            else row!=null&&event.text("status")=="preparation"&&notClosed(event.text("registration_closes"))&&row.text("status") in setOf("pending","incomplete")&&grants.any{it.text("partner_id")==row.text("partner_id")}}
        else->false
    }
}
