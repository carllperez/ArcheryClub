package ph.capstone.archeryclub.connected

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ArcheryTheme
import java.time.Instant

// Design-time examples only. ConnectedEntry never uses these fixtures.
private fun previewWorkspace(officer:Boolean):JsonObject=buildJsonObject {
    put("user_id","preview-member")
    put("permissions",JsonArray((if(officer)listOf("membership","activities","equipment")else emptyList()).map(::JsonPrimitive)))
    put("profiles",buildJsonArray{add(buildJsonObject{put("id","preview-member");put("full_name",if(officer)"Jamie Cruz" else "Alex Lee")})})
    put("memberships",buildJsonArray{add(buildJsonObject{put("user_id","preview-member");put("status","Active")})})
    put("events",buildJsonArray{
        listOf("Saturday practice","Club orientation","Open range session").forEachIndexed{i,title->add(buildJsonObject {
            put("id","event-$i");put("title",title);put("venue","University range");put("published",true);put("archived",false)
            put("starts_at",Instant.now().plusSeconds((i*7+2)*86400L).toString());put("ends_at",Instant.now().plusSeconds((i*7+2)*86400L+7200).toString())
        })}
    })
    put("registrations",buildJsonArray{add(buildJsonObject{put("user_id","preview-member");put("event_id","event-0")})})
    put("announcements",buildJsonArray{add(buildJsonObject{put("id","announcement");put("title","Training schedule updated");put("created_at",Instant.now().toString())})})
    put("applications",buildJsonArray{if(officer)repeat(6){add(buildJsonObject{put("user_id","applicant-$it");put("status","pending")})}})
    put("borrowings",buildJsonArray{if(officer)repeat(3){add(buildJsonObject{put("status","pending")})}})
    put("settings",buildJsonArray{add(buildJsonObject{put("rules_validated",true)})})
}

@Composable private fun LandingPreview(page:String="home",officer:Boolean=false){
    val modules=listOf(1 to "Member Profile and Status",2 to "Applicant Submission and Screening Status",3 to "Activity Information and Registration")+
        if(officer)listOf(7 to "Membership and Applicant Processing",8 to "Announcement, Event, Registration, and Attendance Management",10 to "Equipment Inventory and Allocation Support")else emptyList()
    ArcheryTheme {
        ClubLanding(page,{},remember{previewWorkspace(officer)},modules.map{(n,name)->buildJsonObject{put("number",n);put("name",name)}},
            SystemState(signedIn=true),remember{SnackbarHostState()},onModule={},onRoute={},onRecord={_,_,_->},onAnnouncement={},onNotice={},onAcknowledge={},
            onRefresh={},onSignOut={},onScan={},recovery={})
    }
}

@Preview(name="Member home",widthDp=411,heightDp=891,showBackground=true)
@Composable private fun MemberHomePreview()=LandingPreview()
@Preview(name="Activities",widthDp=411,heightDp=891,showBackground=true)
@Composable private fun ActivitiesPreview()=LandingPreview("activities")
@Preview(name="Officer home",widthDp=411,heightDp=891,showBackground=true)
@Composable private fun OfficerHomePreview()=LandingPreview(officer=true)
@Preview(name="Large text",widthDp=360,heightDp=800,fontScale=1.5f,showBackground=true)
@Composable private fun LargeTextPreview()=LandingPreview(officer=true)
