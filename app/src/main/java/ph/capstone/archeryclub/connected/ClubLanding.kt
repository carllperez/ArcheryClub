package ph.capstone.archeryclub.connected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.InfoCard
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Only published, unarchived activities enter the discovery list. Registration is personal. */
internal fun landingEvents(workspace:JsonObject, registeredOnly:Boolean=false, now:Instant=Instant.now()):List<JsonObject> {
    val registered=workspace.records("registrations").filter{it.text("user_id")==workspace.text("user_id")}.map{it.text("event_id")}.toSet()
    return workspace.records("events").filter{row->
        row.text("published")=="true" && row.text("archived")!="true" &&
            (!registeredOnly || row.text("id") in registered) &&
            runCatching{Instant.parse(row.text("starts_at"))}.isSuccess &&
            runCatching{Instant.parse(row.text("ends_at").ifBlank{row.text("starts_at")}).isAfter(now)}.getOrDefault(false)
    }.sortedBy{Instant.parse(it.text("starts_at"))}
}

private fun dateOf(row:JsonObject)=runCatching{Instant.parse(row.text("starts_at")).atZone(ZoneId.systemDefault())}.getOrNull()
private fun eventWhen(row:JsonObject)=dateOf(row)?.format(DateTimeFormatter.ofPattern("MMM d · h:mm a",Locale.getDefault()))?:"Date to be confirmed"

@Composable private fun LineIcon(kind:String) {
    val color=LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u=size.width/24f; val stroke=Stroke(1.7f*u)
        fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,Offset(x*u,y*u),Offset(x2*u,y2*u),strokeWidth=1.7f*u)
        when(kind){
            "home"->{val path=Path().apply{moveTo(3*u,10*u);lineTo(12*u,3*u);lineTo(21*u,10*u);lineTo(21*u,21*u);lineTo(15*u,21*u);lineTo(15*u,14*u);lineTo(9*u,14*u);lineTo(9*u,21*u);lineTo(3*u,21*u);close()};drawPath(path,color,style=stroke)}
            "activities"->{drawRoundRect(color,Offset(3*u,5*u),Size(18*u,16*u),CornerRadius(2*u),style=stroke);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f)}
            "modules"->for(x in listOf(3f,14f))for(y in listOf(3f,14f))drawRoundRect(color,Offset(x*u,y*u),Size(7*u,7*u),CornerRadius(1.5f*u),style=stroke)
            "account"->{drawCircle(color,4*u,Offset(12*u,7*u),style=stroke);val path=Path().apply{moveTo(4*u,22*u);lineTo(4*u,19*u);cubicTo(4*u,12*u,20*u,12*u,20*u,19*u);lineTo(20*u,22*u);close()};drawPath(path,color,style=stroke)}
            else->{line(9f,5f,16f,12f);line(16f,12f,9f,19f)}
        }
    }
}

@Composable private fun SectionLabel(text:String) {
    Text(text.uppercase(Locale.getDefault()),style=MaterialTheme.typography.labelMedium,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun QuietRow(title:String,subtitle:String="",leading:(@Composable ()->Unit)?=null,onClick:()->Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=20.dp).heightIn(min=48.dp),
            horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LineIcon("next")
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Presentation only; destinations still use the original module catalogue and server permissions. */
@Composable
fun ClubLanding(
    page:String,onPage:(String)->Unit,workspace:JsonObject,modules:List<JsonObject>,state:SystemState,
    snackbar:SnackbarHostState,onModule:(Int)->Unit,onRoute:(String)->Unit,onRecord:(Int,String,JsonObject)->Unit,
    onAnnouncement:()->Unit,onNotice:(JsonObject)->Unit,onAcknowledge:(JsonObject)->Unit,
    onRefresh:()->Unit,onSignOut:()->Unit,onScan:()->Unit,recovery:@Composable ()->Unit,
) {
    val user=workspace.text("user_id")
    val profile=workspace.records("profiles").find{it.text("id")==user}
    val fullName=profile?.text("full_name").orEmpty().ifBlank{"Club account"}
    val initials=fullName.split(Regex("\\s+")).filter{it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase()
    val permissions=(workspace["permissions"] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}.toSet()
    val membership=workspace.records("memberships").find{it.text("user_id")==user}
    val isMember=membership?.text("status")=="Active"
    val staff=permissions.isNotEmpty()
    val memberHome=isMember&&!staff
    val hasModule:(Int)->Boolean={n->modules.any{it.text("number")==n.toString()}}
    val notices=workspace.records("notifications").filter{it.text("user_id")==user&&it.text("acknowledged_at").isBlank()}.sortedByDescending{it.text("notified_at")}
    val title=when(page){
        "activities"->"Activities";"modules"->"All modules";"account"->"Your account";"notices"->"Notices"
        else->if(staff)"Club workspace" else if(memberHome){
            val greeting=when(LocalTime.now().hour){in 0..11->"Good morning";in 12..17->"Good afternoon";else->"Good evening"}
            "$greeting,\n${fullName.substringBefore(' ')}."
        }else if(hasModule(15))"Partner workspace" else if(membership!=null)"Your membership" else "Your application"
    }
    val tabs=if(memberHome)listOf("home" to "Home","activities" to "Activities","account" to "Account")
        else listOf("home" to "Home","modules" to "Modules","account" to "Account")
    ScreenShell(title,snackbar=snackbar,onBack=if(page=="notices" || (page=="modules"&&memberHome)){{onPage("home")}}else null,
        actions={
            TextButton(onClick={onPage("account")},modifier=Modifier.padding(end=8.dp).semantics{contentDescription="Open account"}) {
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.size(40.dp),contentAlignment=Alignment.Center){Text(initials,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.labelLarge)}
                }
            }
        },bottomBar={
            Surface(color=MaterialTheme.colorScheme.surface){Column {
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                    tabs.forEach{(key,label)->NavigationBarItem(selected=page==key,onClick={onPage(key)},icon={LineIcon(key)},label={Text(label)},
                        colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.surface,
                            selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary))}
                }
            }}
        }) {
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.refreshError?.let{InfoCard("Refresh needed",it);TextButton(onClick=onRefresh,enabled=!state.busy){Text("Try again")}}
        when(page){
            "activities"->{
                MemberActivitiesContent(workspace){onRoute("activity:$it")}
                var calendar by rememberSaveable{mutableStateOf(false)}
                TextButton(onClick={calendar=!calendar}){Text(if(calendar)"Hide calendar" else "View calendar")}
                if(calendar)RecordCalendar(memberActivities(workspace).map{it.event},workspace){onRoute("activity:${it.text("id")}")}
                QuietRow("Announcements and attendance records","Club notices, registration history and corrections"){onRoute("module:3")}
            }
            "modules"->{
                var search by rememberSaveable{mutableStateOf("")}
                Text("Your available club services",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(search,{search=it},label={Text("Find a module")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val matches=modules.filter{it.text("name").contains(search,true)||("M"+it.text("number")).contains(search,true)}
                Column{matches.forEach{m->QuietRow(m.text("name")){onModule(m.text("number").toInt())}}}
                if(matches.isEmpty())Text("No matching modules. Try a different name or number.")
            }
            "notices"->{
                Text("Acknowledging a notice does not complete its related task.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(notices.isEmpty())InfoCard("You're all caught up","New notices will appear here when a club record needs your attention.")
                notices.forEach{notice->Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(notice.text("message"),style=MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onNotice(notice)}){Text("View details")}
                        TextButton(onClick={onAcknowledge(notice)},enabled=!state.busy){Text("Acknowledge")}
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                }}
            }
            "account"->{
                Text(fullName,style=MaterialTheme.typography.titleLarge)
                Text(membership?.text("status")?.let{"$it membership"}?:"Club account",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.recovery)recovery()
                Column {
                    if(hasModule(1))QuietRow("My profile","Contact, emergency contact, archery details and membership"){onRoute("profile")}
                    if(isMember)QuietRow("Equipment","Browse the catalog and track your borrowings"){onRoute("equipment_member")}
                    if(hasModule(2))QuietRow("My application","Submission and screening status"){onModule(2)}
                    QuietRow("Notices","${notices.size} to review"){onPage("notices")}
                    QuietRow("All modules","Browse your available services"){onPage("modules")}
                    if(isMember||"equipment" in permissions)QuietRow("Scan club code","Attendance, activities and equipment"){onScan()}
                }
                val settings=workspace.records("settings").firstOrNull()
                if(settings?.text("rules_validated")!="true")InfoCard("Club configuration pending","Club-specific requirements and rules are awaiting validation.")
                OutlinedButton(onClick=onRefresh,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Refresh records")}
                TextButton(onClick=onSignOut,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}
            }
            else->{
                Text(when{staff&&"administration" in permissions->"Administration";staff->"Officer";memberHome->"Active member";hasModule(15)->"Partner club";else->"Application and screening"},color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.recovery)recovery()
                if(staff){
                    OfficerDashboardContent(workspace,permissions,onRoute)
                    if("activities" in permissions)Button(onClick=onAnnouncement,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.small){Text("Create announcement")}
                }else if(memberHome){
                    Spacer(Modifier.height(8.dp));SectionLabel("Your next session")
                    val next=landingEvents(workspace,registeredOnly=true).firstOrNull()
                    if(next!=null){
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(next.text("title"),style=MaterialTheme.typography.headlineMedium)
                            Text(eventWhen(next),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(next.text("venue").isNotBlank())Text(next.text("venue"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val nextView=activityView(next,workspace)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StatusPill(nextView.status.label,nextView.status.tone());StatusPill(relative(nextView.start),Tone.NEUTRAL)}
                        Button(onClick={onRoute("activity:${next.text("id")}")},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.small){Text("View session")}
                    }else{
                        Text("Your next session starts here.",style=MaterialTheme.typography.titleLarge)
                        Text("Explore upcoming activities and register when you're ready.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick={onPage("activities")},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.small){Text("Browse activities")}
                    }
                    Spacer(Modifier.height(4.dp));SectionLabel("Quick actions")
                    val open=memberActivities(workspace).count{!it.status.isMine}
                    TileRow {
                        StatTile("$open","Open activities",Modifier.weight(1f).fillMaxHeight()){onPage("activities")}
                        val borrowed=workspace.records("borrowings").count{it.text("user_id")==user&&it.text("status") in setOf("pending","approved","released")}
                        StatTile("$borrowed","Equipment requests",Modifier.weight(1f).fillMaxHeight()){onRoute("equipment_member")}
                    }
                    Column {
                        QuietRow("Borrow equipment","Bows, arrows and accessories from the club"){onRoute("equipment_member")}
                        QuietRow("My profile","${profileCompleteness(profile)}% complete"){onRoute("profile")}
                    }
                    val tasks=memberTasks(workspace)
                    if(tasks.isNotEmpty())QuietRow("Your tasks","${tasks.size} requests and tasks to follow up"){onModule(6)}
                }else if(hasModule(15)){
                    QuietRow("Shared interclub events","Delegates, schedules and coordination"){onModule(15)}
                }else if(membership!=null){
                    SectionLabel("Membership status")
                    Text(membership.text("status"),style=MaterialTheme.typography.headlineMedium)
                    Text("Review your membership details and renewal status.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick={onModule(1)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.small){Text("View membership")}
                }else{
                    val application=workspace.records("applications").find{it.text("user_id")==user}
                    SectionLabel("Application status")
                    Text(application?.text("status")?.let(::human)?:"Ready when you are",style=MaterialTheme.typography.headlineMedium)
                    Text("View the club requirements, upload your documents and follow your screening status.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick={onModule(2)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.small){Text(if(application==null)"Start application" else "View application")}
                }
                Spacer(Modifier.height(12.dp))
                Column {
                    val announcement=workspace.records("announcements").maxByOrNull{it.text("created_at")}
                    if(announcement!=null&&(hasModule(3)||hasModule(8)))QuietRow(announcement.text("title"),"Latest announcement"){
                        onRecord(if(hasModule(8))8 else 3,"announcements",announcement)
                    }
                    if(notices.isNotEmpty())QuietRow("Notices to review","${notices.size} updates"){onPage("notices")}
                    QuietRow("All modules",leading={LineIcon("modules")}){onPage("modules")}
                }
            }
        }
    }
}
