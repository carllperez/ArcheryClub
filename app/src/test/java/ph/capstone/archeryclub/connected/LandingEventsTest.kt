package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LandingEventsTest {
    private val now=Instant.parse("2026-11-05T00:00:00Z")
    private fun event(id:String,start:String="2026-11-07T00:00:00Z",end:String="2026-11-07T02:00:00Z",published:Boolean=true,archived:Boolean=false)=buildJsonObject {
        put("id",id);put("starts_at",start);put("ends_at",end);put("published",published);put("archived",archived)
    }
    private fun workspace(events:List<JsonObject>,registrations:List<JsonObject> = emptyList())=buildJsonObject {
        put("user_id","member-a");put("events",JsonArray(events));put("registrations",JsonArray(registrations))
    }
    @Test fun discoveryExcludesDraftsArchivesPastAndMalformedDates() {
        val w=workspace(listOf(event("live"),event("draft",published=false),event("archive",archived=true),
            event("past","2026-11-01T00:00:00Z","2026-11-01T02:00:00Z"),event("bad","unknown")))
        assertEquals(listOf("live"),landingEvents(w,now=now).map{it.text("id")})
    }
    @Test fun registeredTabUsesOnlyCurrentMembersRegistrations() {
        val w=workspace(listOf(event("own"),event("someone-else")),listOf(
            buildJsonObject{put("user_id","member-a");put("event_id","own")},
            buildJsonObject{put("user_id","member-b");put("event_id","someone-else")}
        ))
        assertEquals(listOf("own"),landingEvents(w,registeredOnly=true,now=now).map{it.text("id")})
    }
    @Test fun nextSessionUsesChronologicalInstantsAndIncludesOngoingEvents() {
        val w=workspace(listOf(event("later"),event("ongoing","2026-11-04T23:00:00Z","2026-11-05T01:00:00Z")))
        assertEquals(listOf("ongoing","later"),landingEvents(w,now=now).map{it.text("id")})
    }
}
