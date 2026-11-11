package ph.capstone.archeryclub.connected

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import ph.capstone.archeryclub.ui.ClubIcon
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import ph.capstone.archeryclub.BuildConfig

@Composable
fun ConnectedEntry(activity:ComponentActivity) {
    if(!SupabaseGateway.configured){
        SetupScreen("Connect your club", "The development backend has not been configured in this build. Follow the setup guide and rebuild with the Supabase project URL and publishable key. No sample accounts are connected.")
        return
    }
    val vm=remember(activity){ViewModelProvider(activity,object:ViewModelProvider.Factory{
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=SystemViewModel(SupabaseGateway()) as T
    })[SystemViewModel::class.java]}
    ConnectedApp(vm)
}

@Composable
fun ConnectedApp(vm:SystemViewModel){
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val catalog=remember{Json.parseToJsonElement(context.assets.open("modules.json").bufferedReader().use{it.readText()}).jsonObject}
    val modules=catalog.records("modules");val forms=catalog.getValue("forms").jsonObject
    var moduleVar by rememberSaveable{mutableIntStateOf(0)}
    var landingVar by rememberSaveable{mutableStateOf("home")}
    var returnToLanding by rememberSaveable{mutableStateOf(false)}
    // Purpose-built workflow screens are addressed by a small back stack of routes,
    // e.g. "equipment|item:<id>|borrowing:<id>". Generic module screens remain available.
    var stackVar by rememberSaveable{mutableStateOf("")}
    val route=stackVar.substringAfterLast('|')
    var selectedVar by rememberSaveable(stateSaver=Saver<Pair<String,JsonObject>?,String>(
        save={it?.let{pair->JsonArray(listOf(JsonPrimitive(pair.first),pair.second)).toString()}.orEmpty()},
        restore={s->s.takeIf{it.isNotEmpty()}?.let{Json.parseToJsonElement(it).jsonArray.let{a->a[0].jsonPrimitive.content to a[1].jsonObject}}}
    )){mutableStateOf<Pair<String,JsonObject>?>(null)}
    var editingVar by rememberSaveable(stateSaver=Saver<Pair<JsonObject,JsonObject>?,String>(
        save={it?.let{pair->JsonArray(listOf(pair.first,pair.second)).toString()}.orEmpty()},
        restore={s->s.takeIf{it.isNotEmpty()}?.let{Json.parseToJsonElement(it).jsonArray.let{a->a[0].jsonObject to a[1].jsonObject}}}
    )){mutableStateOf<Pair<JsonObject,JsonObject>?>(null)}
    val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(state.error,state.message,state.signedIn){
        if(state.signedIn)(state.error?:state.message)?.let{snackbar.showSnackbar(it);vm.clearMessage()}
    }
    LaunchedEffect(state.signedIn){if(!state.signedIn){selectedVar=null;editingVar=null;moduleVar=0;landingVar="home";returnToLanding=false;stackVar=""}}
    LaunchedEffect(state.signedIn){while(state.signedIn){delay(30000);vm.refresh()}}
    if(!state.signedIn){ClubAuthScreen(state,vm,snackbar);return}
    val workspace=state.workspace
    if(workspace==null){ClubSplash(state.busy,state.refreshError,onRetry=vm::refresh,onSignOut={vm.auth("signout","","")});return}
    val permissions=workspace["permissions"]?.jsonArray?.map{it.jsonPrimitive.content}.orEmpty().toSet()
    val user=workspace.text("user_id")
    val isMember=workspace.records("memberships").any{it.text("user_id")==user&&it.text("status")=="Active"}
    LaunchedEffect(isMember){if(!isMember&&landingVar=="activities")landingVar="home"}
    val access=workspace.records("event_access").filter{it.text("user_id")==user&&it.text("revoked_at").isBlank()}
    fun allowed(permission:String)=when(permission){
        "account"->true;"member"->isMember;"funds"->"finance" in permissions||"president" in permissions
        "partner"->"interclub" in permissions||access.isNotEmpty()
        "coordinator"->"interclub" in permissions||access.any{it.text("role")=="coordinator"}
        else->permission in permissions
    }
    fun visible(n:Int)=when(n){1->workspace.records("memberships").any{it.text("user_id")==user};3,4,5,6->isMember;2->workspace.records("applications").any{it.text("user_id")==user}||(!isMember&&permissions.isEmpty());7->allowed("membership");8->allowed("activities");9->isMember||allowed("elections");10->allowed("equipment");11->allowed("training");12->true;13->allowed("reports");14->allowed("administration");15->allowed("partner");else->false}
    fun push(r:String){stackVar=if(stackVar.isEmpty())r else "$stackVar|$r"}
    fun pop(){stackVar=stackVar.substringBeforeLast('|',"")}
    /** Modules that have a purpose-built workflow screen open it first; its "records" link reaches the generic view. */
    fun dedicated(n:Int):String?=when(n){
        1->"profile";2->"application";7->"applications";3->if(isMember)"activities" else null;5->if(isMember)"equipment_member" else null
        8->"officer_activities";10->"equipment";4->if(isMember)"training" else null;11->"training_desk"
        12->if(allowed("finance")||allowed("president"))"finance_desk" else "payments"
        9->"elections";13->"reports";14->"admin";15->"interclub";else->null
    }
    /** "module:N" opens the module's purpose-built screen when it has one; "records:N" always opens its full record view. */
    fun go(r:String){
        fun records(n:Int){stackVar="";moduleVar=n;selectedVar=null;returnToLanding=false}
        when{
            r.startsWith("records:")->records(r.removePrefix("records:").toInt())
            r.startsWith("module:")->r.removePrefix("module:").toInt().let{n->dedicated(n)?.let(::push)?:records(n)}
            else->push(r)
        }
    }
    fun openModule(n:Int){val r=dedicated(n);if(r!=null){moduleVar=0;selectedVar=null;stackVar=r}else{moduleVar=n;selectedVar=null;returnToLanding=false}}
    fun start(action:String,row:JsonObject=buildJsonObject{}){
        val form=forms[action]?.jsonObject?:return
        val initial=buildJsonObject{
            row.forEach{(k,v)->put(k,v)}
            form["seed"]?.jsonObject?.forEach{(target,source)->row[source.jsonPrimitive.content]?.let{put(target,it)}}
            if(action=="define_role"&&row.containsKey("id"))put("role_id",row.getValue("id"))
        }
        editingVar=form to initial
    }
    fun openNotice(notice:JsonObject) {
        val destination=notice.text("module").toIntOrNull()?:return
        val record=notice.text("record_id")
        if(destination in setOf(3,8)&&workspace.records("events").any{it.text("id")==record}){stackVar="activity:$record";return}
        if(destination in setOf(5,10)&&workspace.records("borrowings").any{it.text("id")==record}){stackVar="borrowing:$record";return}
        workspace.records("training_records").find{it.text("id")==record}?.let{stackVar="scorecard:$record";return}
        workspace.records("financial_records").find{it.text("id")==record}?.let{stackVar="finance:$record";return}
        if(workspace.records("fee_assessments").any{it.text("id")==record}){stackVar="payments";return}
        if(workspace.records("elections").any{it.text("id")==record}){stackVar="election:$record";return}
        workspace.records("name_corrections").find{it.text("id")==record}?.let{stackVar=if(it.text("user_id")==user)"profile" else "name_corrections";return}
        workspace.records("applications").find{it.text("id")==record}?.let{
            val target=if(it.text("user_id")==user)"application" else if(allowed("membership"))"application_review:$record" else null
            if(target!=null){stackVar=target;return}}
        if(!visible(destination)){vm.error("This notice's module is not available under your current role.");return}
        val tables=modules.first{it.text("number")==destination.toString()}["tables"]!!.jsonArray.map{it.jsonPrimitive.content}
        returnToLanding=moduleVar==0
        moduleVar=destination
        selectedVar=tables.firstNotNullOfOrNull { table -> workspace.records(table).find {
            it.text("id")==notice.text("record_id") || (table=="interclub_events"&&it.text("event_id")==notice.text("record_id"))
        }?.let { table to it } }
    }
    val scan=rememberLauncherForActivityResult(ScanContract()){result->
        result.contents?.let{value->
            val uri=Uri.parse(value)
            val token=uri.getQueryParameter("token")
            if(token!=null)vm.command("check_in",buildJsonObject{put("token",token)})
            else if(uri.host=="event"){
                workspace.records("events").find{it.text("id")==uri.getQueryParameter("id")}?.let{stackVar="activity:${it.text("id")}"}
                    ?:vm.error("This event is not available to your account. Refresh and try again.")
            } else workspace.records("equipment").find{it.text("id")==value}?.let{stackVar="item:${it.text("id")}"}
                ?:vm.error("This is not an available club attendance, event, or equipment code.")
        }
    }
    fun leaveRecord(){selectedVar=null;if(returnToLanding){moduleVar=0;returnToLanding=false}}
    BackHandler(moduleVar!=0||selectedVar!=null||editingVar!=null||landingVar!="home"||stackVar.isNotEmpty()){
        if(editingVar!=null)editingVar=null else if(stackVar.isNotEmpty())pop() else if(selectedVar!=null)leaveRecord() else if(moduleVar!=0)moduleVar=0 else landingVar="home"
    }
    /* Each screen renders from a snapshot of the navigation state, so the outgoing screen keeps
     * showing its own content while it animates away. Writes still go to the live state above. */
    @Composable fun Screen(nav:Nav){
        val module=nav.module;val selected=nav.selected;val editing=nav.editing;val landingPage=nav.landing;val routeStack=nav.stack
        val route=routeStack.substringAfterLast('|')
        editing?.let{(form,initial)->
            WorkflowForm(form,initial,workspace,state.busy,onBack={editingVar=null},onSubmit={payload->
                vm.command(form.text("action"),payload){editingVar=null;leaveRecord()}
            },snackbar=snackbar)
            return
        }
        if(routeStack.isNotEmpty()){
            WorkflowRoute(route,workspace,state.busy,snackbar,isMember,::allowed,vm=vm,
                onBack=::pop,onPush=::push,onGo=::go,onStart=::start,
                onCommand={action,data,done->vm.command(action,data,done)},
                onUpload={uri,m,record,requirement->vm.upload(context,uri,m,record,requirement)},
                onOpenDocument={path->vm.openDocument(path){url->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}})
            return
        }
        if(module==0){
            ClubLanding(page=landingPage,onPage={landingVar=it},workspace=workspace,
                modules=modules.filter{visible(it.text("number").toInt())},state=state,snackbar=snackbar,
                onModule={openModule(it)},onRoute={go(it)},
                onRecord={target,table,row->if(visible(target)){moduleVar=target;selectedVar=table to row;returnToLanding=true}},
                onAnnouncement={if(allowed("activities"))start("publish_announcement")},
                onNotice=::openNotice,
                onAcknowledge={notice->vm.command("acknowledge_notification",buildJsonObject{put("id",notice.text("id"))})},
                onRefresh={vm.refresh()},onSignOut={vm.auth("signout","","")},
                onScan={scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE,ScanOptions.CODE_128).setPrompt("Scan a club code").setBeepEnabled(false))},
                recovery={PasswordChangeCard(vm,state.busy)},
                onCommand={action,data->vm.command(action,data)})
            return
        }
        if(!visible(module)){LaunchedEffect(module){moduleVar=0;selectedVar=null;editingVar=null};return}
        val current=modules.first{it.text("number").toInt()==module}
        val actionIds=current["actions"]!!.jsonArray.map{it.jsonPrimitive.content}
        val available=actionIds.mapNotNull{forms[it] as? JsonObject}.filter{allowed(it.text("permission"))&&(BuildConfig.EMAIL_ENABLED||it.text("action")!="invite_account")}
        val item=selected
        if(item!=null){
            val(table,original)=item
            val row=workspace.records(table).find{it.text("id")==original.text("id")&&it.text("user_id")==original.text("user_id")&&it.text("event_id")==original.text("event_id")}?:original
            ScreenShell(recordTitle(row,workspace),onBack=::leaveRecord,snackbar=snackbar){
                RecordDetails(row,workspace)
                if(module in integratedClubModules) {
                    workspace.records("financial_approvals").filter{table=="financial_records"&&it.text("record_id")==row.text("id")}.forEach{
                        InfoCard("${human(it.text("office"))} approval", "${it.text("remark")} · ${it.text("approved_at")}")
                    }
                    workspace.records("audit_log").filter{it.text("record_id")==row.text("id")}.sortedByDescending{it.text("created_at")}.take(10).forEach{
                        Text("${human(it.text("action"))} · ${it.text("created_at")}",style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(table=="documents")PrimaryAction("Open private document"){vm.openDocument(row.text("path")){url->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}
                if(table=="notifications")PrimaryAction("Mark as read"){vm.command("acknowledge_notification",buildJsonObject{put("id",row.text("id"))})}
                if(table=="notifications")SecondaryAction("Open related records"){openNotice(row)}
                if(table=="attendance_windows")QrImage("archeryclub://attendance?token=${row.text("token")}")
                if(table=="equipment"&&allowed("equipment"))QrImage(row.text("id"))
                if(table=="elections")ElectionPanel(row,workspace,vm,isMember)
                if(table in setOf("applications","renewals","candidates","training_records","financial_records","delegates","interclub_events")){
                    val docModule=mapOf("applications" to 2,"renewals" to 1,"candidates" to 9,"training_records" to 11,"financial_records" to 12,"delegates" to 15,"interclub_events" to 15).getValue(table)
                    if(table=="interclub_events")Text("Event documents are shared with this event's invited representatives. Attach only approved event materials.")
                    DocumentsPanel(row.text("id").ifBlank{row.text("event_id")},docModule,workspace,vm,state.busy)
                }
                recordWorkflowActions(available,table,row,user).forEach{form->
                    PrimaryAction(form.text("label"),!state.busy){start(form.text("action"),row)}
                }
            };return
        }
        var search by rememberSaveable(module){mutableStateOf("")}
        var table by rememberSaveable(module){mutableStateOf(current["tables"]!!.jsonArray.firstOrNull()?.jsonPrimitive?.content.orEmpty())}
        ScreenShell(current.text("name"),onBack={moduleVar=0},snackbar=snackbar){
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=vm::refresh,enabled=!state.busy){Text("Refresh records")}
            if(module in integratedClubModules)ClubModuleOverview(module,workspace)
            if(module==6)MemberOverview(workspace,onNavigate={moduleVar=it;selectedVar=null},onTask={task->moduleVar=task.module;selectedVar=task.table to task.row})
            if(module==13)ReportsPanel(workspace,vm)
            if(module==10||module==5)AvailabilityPanel(vm)
            if(module==11)TrainingImport(vm)
            if(module==3||module==8)RecordCalendar(workspace.records("events"),workspace){selectedVar="events" to it}
            if(module==10)RecordCalendar(workspace.records("borrowings").filter{it.text("status") in setOf("approved","released")},workspace){selectedVar="borrowings" to it}
            if(module==4||module==11)TrainingHistory(workspace.records("training_records").filter{module==11||it.text("user_id")==user},vm)
            val createActions=setOf("assess_fee_bulk","set_membership_fee","save_profile","request_renewal","save_application","check_in","save_event","publish_announcement","record_attendance","save_term","record_residency","create_election","record_leadership","save_equipment","request_borrowing","save_personal_training","save_training","save_financial_request","assess_fee","save_goal","configure_club","define_role","save_partner","share_interclub_event","save_delegate","raise_concern")
            available.filter{it.text("action") in createActions && it.text("action")!="request_renewal" || it.text("action") in setOf("save_renewal","invite_account")}.forEach{form->
                val application=workspace.records("applications").find{it.text("user_id")==user}
                val renewal=workspace.records("renewals").find{it.text("user_id")==user&&it.text("status") in setOf("draft","returned_for_correction","pending")}
                if(form.text("action")=="save_application"&&(workspace.records("memberships").any{it.text("user_id")==user}||(application!=null&&application.text("status") !in setOf("draft","incomplete","returned_for_correction"))))return@forEach
                if(form.text("action")=="save_renewal"&&renewal?.text("status")=="pending")return@forEach
                OutlinedButton(onClick={
                    val initial=when(form.text("action")){
                        "save_profile"->workspace.records("profiles").find{it.text("id")==user}
                        "save_application"->workspace.records("applications").find{it.text("user_id")==user}
                        "save_renewal"->renewal
                        "configure_club"->workspace.records("settings").firstOrNull()
                        else->null
                    }
                    start(form.text("action"),initial?:buildJsonObject{})
                },enabled=!state.busy,shape=CircleShape,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text(form.text("label"))}
            }
            if(module in integratedClubModules){
                ClubModuleRecords(module,current,workspace,available,state.busy,
                    onOpen={recordTable,row->selectedVar=recordTable to row},
                    onAction={action,row->if(available.any{it.text("action")==action})start(action,row)})
            } else {
            SelectField("Records",table,current["tables"]!!.jsonArray.map{it.jsonPrimitive.content to human(it.jsonPrimitive.content)}){table=it}
            OutlinedTextField(search,{search=it},placeholder={Text("Search records")},singleLine=true,shape=CircleShape,leadingIcon={ClubIcon("search",Modifier.size(18.dp))},modifier=Modifier.fillMaxWidth())
            val rows=workspace.records(table).filter{r->
                val own=when(module){1->table=="profiles"&&r.text("id")==user||table!="profiles"&&r.text("user_id")==user
                    2->table=="documents"&&r.text("owner_id")==user||table!="documents"&&r.text("user_id")==user
                    4->r.text("user_id")==user
                    5->table=="equipment"||r.text("user_id")==user
                    6->table=="documents"&&r.text("owner_id")==user||table=="notifications"&&r.text("user_id")==user
                    else->true}
                val correction=table!="corrections"||module==4||r.text("module")==if(module==8)"8" else "11"
                own&&correction&&r.toString().contains(search,true)
            }
            if(rows.isEmpty())Text("No matching records yet.")
            rows.forEach{r->
                val facts=listOf("status","validation","category","starts_at","training_date","amount","notified_at").map{r.text(it)}.filter{it.isNotBlank()}.take(3).map(::human)
                WorkCard(recordTitle(r,workspace),facts.drop(1),leading={IconChip(moduleIcon(module),Tone.POSITIVE)},
                    status=facts.firstOrNull()?.let{f-> @Composable {StatusPill(f,Tone.NEUTRAL)}},onClick={selectedVar=table to r})
            }
            }
        }
    }
    val nav=Nav(moduleVar,landingVar,stackVar,selectedVar,editingVar)
    AnimatedContent(targetState=nav,contentKey={it.key},label="screen",
        transitionSpec={navTransition(initialState.depth,targetState.depth)}){snapshot->Screen(snapshot)}
}

fun human(value:String)=value.replace('_',' ').replaceFirstChar{it.uppercase()}
fun recordTitle(row:JsonObject,workspace:JsonObject):String {
    for(key in listOf("title","shared_title","label","full_name","name","purpose","message","position","club_name","action","request","concern","summary"))if(row.text(key).isNotBlank())return row.text(key).take(120)
    val person=workspace.records("profiles").find{it.text("id")==row.text("user_id")}?.text("full_name")
        ?:workspace.records("member_directory").find{it.text("user_id")==row.text("user_id")}?.text("full_name")
    return person?.takeIf{it.isNotBlank()}?:human(row.text("status").ifBlank{row.text("training_date").ifBlank{"Club record"}})
}
@Composable fun SelectField(label:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text("$label: ${options.find{it.first==value}?.second?:"Select"}")}
        DropdownMenu(expanded,{expanded=false}){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onChange(id);expanded=false})}}}
}
@Composable private fun RecordDetails(row:JsonObject,workspace:JsonObject){
    val facts=row.entries.filter{(k,v)->k !in setOf("id","module","user_id","owner_id","event_id","encoded_by","reviewed_by","created_by","approved_by","token","path","snapshot","version")&&v!=JsonNull}
        .mapNotNull{(key,value)->when{
            value is JsonPrimitive->human(key) to (if(key.endsWith("_at")||key.endsWith("_on"))runCatching{fmtDateTime(java.time.Instant.parse(value.content))}.getOrElse{human(value.content)} else human(value.content))
            value is JsonArray&&value.all{it is JsonPrimitive}->human(key) to value.joinToString{it.jsonPrimitive.content}
            else->null}}
    RaisedCard{Column(Modifier.padding(16.dp)){facts.forEach{(k,v)->KeyValue(k,v)}}}
}
@Composable private fun QrImage(value:String){
    val bitmap=remember(value){val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,480,480);Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 480)for(x in 0 until 480)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}
    Image(bitmap.asImageBitmap(),contentDescription="QR code for this club record",modifier=Modifier.size(240.dp))
}
@Composable private fun DocumentsPanel(record:String,module:Int,workspace:JsonObject,vm:SystemViewModel,busy:Boolean){
    val context=LocalContext.current;var requirement by rememberSaveable(record){mutableStateOf("Supporting document")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{vm.upload(context,it,module,record,requirement)}}
    val canVerify=module==2&&(workspace["permissions"] as? JsonArray).orEmpty().any{it.jsonPrimitive.content=="membership"}
    workspace.records("documents").filter{it.text("record_id")==record}.forEach{d->
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            TextButton(onClick={vm.openDocument(d.text("path")){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it)))}}){Text("Open ${d.text("name")}")}
            if(d.text("verified_by").isNotBlank())StatusPill("Verified",Tone.POSITIVE)
            else if(canVerify&&d.text("owner_id")!=workspace.text("user_id"))TextButton(onClick={vm.command("verify_document",buildJsonObject{put("id",d.text("id"))})},enabled=!busy){Text("Mark verified")}
            else StatusPill("Not yet verified",Tone.WARNING)
        }
    }
    val names=if(module in setOf(1,2))(workspace.records("settings").firstOrNull()?.get(if(module==1)"renewal_requirements" else "application_requirements") as? JsonArray).orEmpty().map{it.jsonPrimitive.content} else emptyList()
    names.forEach{name->Text("$name: ${if(workspace.records("documents").any{it.text("record_id")==record&&it.text("requirement")==name})"Uploaded" else "Required"}")}
    val canAttach=canAttachDocument(module,record,workspace)
    if(canAttach){
        if(names.isNotEmpty())SelectField("Requirement",requirement,names.map{it to it}){requirement=it}
        else OutlinedTextField(requirement,{requirement=it},label={Text("Requirement or document label")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={picker.launch(if(module==11)arrayOf("application/pdf","image/jpeg","image/png","text/csv","video/mp4") else arrayOf("application/pdf","image/jpeg","image/png","text/csv"))},enabled=!busy&&requirement.isNotBlank()&&(names.isEmpty()||requirement in names)){Text("Upload supporting document")}
    }
    if(module==11){
        Text("Training videos: MP4, no longer than 10 seconds. The server validates the duration.")
        if(canAttach)TrainingVideoCapture(record,vm,busy)
    }
}
@Composable private fun MemberOverview(workspace:JsonObject,onNavigate:(Int)->Unit,onTask:(MemberTask)->Unit){
    val user=workspace.text("user_id")
    val m=workspace.records("memberships").find{it.text("user_id")==user}
    InfoCard("Membership",m?.text("status")?:"No official membership")
    val tasks=memberTasks(workspace)
    Text("Pending tasks and requests",style=MaterialTheme.typography.titleMedium)
    if(tasks.isEmpty())Text("No outstanding tasks in your currently loaded records.")
    tasks.forEach{task->
        InfoCard(task.title,task.status)
        TextButton(onClick={onTask(task)}){Text("Open ${task.title.lowercase()}")}
    }
    listOf("registrations" to 3,"attendance" to 4,"training_records" to 4,"borrowings" to 5,"financial_records" to 12).forEach{(key,target)->
        InfoCard(human(key),"${workspace.records(key).count{it.text("user_id")==user}} recorded entries")
        TextButton(onClick={onNavigate(target)}){Text("Open ${human(key).lowercase()}")}
    }
    val pending=workspace.records("notifications").filter{it.text("user_id")==user&&it.text("acknowledged_at").isBlank()}
    InfoCard("Notices to review","${pending.size} unacknowledged notices")
    val registered=workspace.records("registrations").filter{it.text("user_id")==user}.map{it.text("event_id")}.toSet()
    workspace.records("events").filter{it.text("id") in registered&&runCatching{Instant.parse(it.text("starts_at")).isAfter(Instant.now())}.getOrDefault(false)}.sortedBy{it.text("starts_at")}.forEach{
        InfoCard("Upcoming: ${it.text("title")}","${it.text("starts_at")} · ${it.text("venue")}")
    }
    Text("Summaries use your recorded information. Unvalidated training and unverified financial submissions remain labelled in their source modules.")
}


/** What is on screen. `key` decides when a transition plays; `depth` decides its direction. */
private data class Nav(val module:Int,val landing:String,val stack:String,val selected:Pair<String,JsonObject>?,val editing:Pair<JsonObject,JsonObject>?){
    val key get()=when{
        editing!=null->"form:"+editing.first.text("action")
        stack.isNotEmpty()->"route:$stack"
        selected!=null->"record:$module:${selected.first}:${selected.second.text("id")}"
        module!=0->"module:$module"
        else->"landing:$landing"
    }
    val depth get()=when{
        editing!=null->100
        stack.isNotEmpty()->10+stack.count{it=='|'}
        selected!=null->3
        module!=0->2
        else->0
    }
}

/** Forward: the new screen slides in from the right over the old one. Back: the reverse. Tabs: a soft fade-through. */
private fun AnimatedContentTransitionScope<Nav>.navTransition(from:Int,to:Int):ContentTransform{
    val slide=tween<IntOffset>(340,easing=FastOutSlowInEasing)
    return when{
        to>from->(slideInHorizontally(slide){it/3}+fadeIn(tween(220,60))) togetherWith (slideOutHorizontally(slide){-it/8}+fadeOut(tween(180)))
        to<from->((slideInHorizontally(slide){-it/8}+fadeIn(tween(220,40))) togetherWith (slideOutHorizontally(slide){it/3}+fadeOut(tween(200)))).apply{targetContentZIndex=-1f}
        else->(fadeIn(tween(240,80))+scaleIn(tween(240,80),initialScale=.97f)) togetherWith fadeOut(tween(100))
    }.using(SizeTransform(clip=false))
}
