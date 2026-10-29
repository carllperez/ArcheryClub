package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DocumentAccessTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    @Test fun privateUploadsFollowOwnershipAndReviewLocks(){
        val w=obj("""{"user_id":"me","permissions":["finance"],"financial_records":[
            {"id":"a","user_id":"other","status":"draft"},
            {"id":"b","user_id":"me","status":"pending_verification"},
            {"id":"c","user_id":"me","status":"returned_for_correction"}]}""")
        assertFalse(canAttachDocument(12,"a",w));assertFalse(canAttachDocument(12,"b",w));assertTrue(canAttachDocument(12,"c",w))
    }
    @Test fun partnerMayUploadOnlyOwnOpenDelegation(){
        val w=obj("""{"user_id":"me","permissions":[],"interclub_events":[{"event_id":"e","status":"preparation","registration_closes":"2030-01-01T00:00:00Z"}],
            "event_access":[{"event_id":"e","user_id":"me","role":"partner","partner_id":"p"}],
            "delegates":[{"id":"d","event_id":"e","partner_id":"p","status":"pending"},{"id":"other","event_id":"e","partner_id":"q","status":"pending"}]}""")
        assertTrue(canAttachDocument(15,"d",w,Instant.parse("2026-01-01T00:00:00Z")))
        assertFalse(canAttachDocument(15,"d",w,Instant.parse("2031-01-01T00:00:00Z")))
        assertFalse(canAttachDocument(15,"other",w));assertFalse(canAttachDocument(15,"e",w))
    }
    @Test fun archivedEventCannotReceiveOfficerUploads(){
        val w=obj("""{"user_id":"me","permissions":["interclub"],"interclub_events":[{"event_id":"e","status":"archived"}]}""")
        assertFalse(canAttachDocument(15,"e",w))
    }
}
