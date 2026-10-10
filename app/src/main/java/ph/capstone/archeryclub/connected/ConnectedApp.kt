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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
    var module by rememberSaveable{mutableIntStateOf(0)}
    var selected by remember{mutableStateOf<Pair<String,JsonObject>?>(null)}
    var editing by remember{mutableStateOf<Pair<JsonObject,JsonObject>?>(null)}
    val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(state.error,state.message,state.signedIn){
        if(state.signedIn)(state.error?:state.message)?.let{snackbar.showSnackbar(it);vm.clearMessage()}
    }
    LaunchedEffect(state.signedIn){if(!state.signedIn){selected=null;editing=null;module=0}}
    LaunchedEffect(state.signedIn){while(state.signedIn){delay(30000);vm.refresh()}}
    if(!state.signedIn){AuthScreen(state,vm,snackbar);return}
    val workspace=state.workspace
    if(workspace==null){ScreenShell("Loading your club",snackbar=snackbar){
        if(state.busy) CircularProgressIndicator()
        Text("Your account must be verified and enabled to access club records.")
        Button(onClick=vm::refresh,enabled=!state.busy){Text("Retry connection")}
        TextButton(onClick={vm.auth("signout","","")}){Text("Sign out")}
    };return}
    val permissions=workspace["permissions"]?.jsonArray?.map{it.jsonPrimitive.content}.orEmpty().toSet()
    val user=workspace.text("user_id")
    val isMember=workspace.records("memberships").any{it.text("user_id")==user&&it.text("status")=="Active"}
    val access=workspace.records("event_access").filter{it.text("user_id")==user&&it.text("revoked_at").isBlank()}
    fun allowed(permission:String)=when(permission){
        "account"->true;"member"->isMember;"funds"->"finance" in permissions||"president" in permissions
        "partner"->"interclub" in permissions||access.isNotEmpty()
        "coordinator"->"interclub" in permissions||access.any{it.text("role")=="coordinator"}
        else->permission in permissions
    }
    fun visible(n:Int)=when(n){1->workspace.records("memberships").any{it.text("user_id")==user};3,4,5,6->isMember;2->true;7->allowed("membership");8->allowed("activities");9->isMember||allowed("elections");10->allowed("equipment");11->allowed("training");12->true;13->allowed("reports");14->allowed("administration");15->allowed("partner");else->false}
    fun start(action:String,row:JsonObject=buildJsonObject{}){
        val form=forms[action]?.jsonObject?:return
        val initial=buildJsonObject{
            row.forEach{(k,v)->put(k,v)}
            form["seed"]?.jsonObject?.forEach{(target,source)->row[source.jsonPrimitive.content]?.let{put(target,it)}}
            if(action=="define_role"&&row.containsKey("id"))put("role_id",row.getValue("id"))
        }
        editing=form to initial
    }
    val scan=rememberLauncherForActivityResult(ScanContract()){result->
        result.contents?.let{value->
            val uri=Uri.parse(value)
            val token=uri.getQueryParameter("token")
            if(token!=null)vm.command("check_in",buildJsonObject{put("token",token)})
            else if(uri.host=="event"){
                workspace.records("events").find{it.text("id")==uri.getQueryParameter("id")}?.let{module=3;selected="events" to it}
                    ?:vm.error("This event is not available to your account. Refresh and try again.")
            } else workspace.records("equipment").find{it.text("id")==value}?.let{module=if(allowed("equipment"))10 else 5;selected="equipment" to it}
                ?:vm.error("This is not an available club attendance, event, or equipment code.")
        }
    }
    BackHandler(module!=0||selected!=null||editing!=null){if(editing!=null)editing=null else if(selected!=null)selected=null else module=0}
    editing?.let{(form,initial)->
        WorkflowForm(form,initial,workspace,state.busy,onBack={editing=null},onSubmit={payload->
            vm.command(form.text("action"),payload){editing=null;selected=null}
        },snackbar=snackbar)
        return
    }
    if(module==0){ScreenShell("Your club",snackbar=snackbar){
        val settings=workspace.records("settings").firstOrNull()
        Text(settings?.text("club_name")?:"Archery Club",style=MaterialTheme.typography.titleLarge)
        if(settings?.text("rules_validated")!="true")InfoCard("Configuration awaiting club validation","Club-specific requirements and rules must be reviewed before operational use.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick=vm::refresh,enabled=!state.busy){Text("Refresh")}
            TextButton(onClick={vm.auth("signout","","")},enabled=!state.busy){Text("Sign out")}
        }
        if(state.recovery)PasswordChange(vm,state.busy)
        if(isMember||allowed("equipment"))OutlinedButton(onClick={scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE,ScanOptions.CODE_128).setPrompt("Scan a club code").setBeepEnabled(false))}){Text("Scan club code")}
        modules.filter{visible(it.text("number").toInt())}.forEach{m->
            Card(Modifier.fillMaxWidth().clickable{module=m.text("number").toInt()}){Column(Modifier.padding(18.dp)){
                Text("M${m.text("number")}",style=MaterialTheme.typography.labelMedium)
                Text(m.text("name"),style=MaterialTheme.typography.titleMedium)
            }}
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    };return}
    if(!visible(module)){LaunchedEffect(module){module=0;selected=null;editing=null};return}
    val current=modules.first{it.text("number").toInt()==module}
    val actionIds=current["actions"]!!.jsonArray.map{it.jsonPrimitive.content}
    val available=actionIds.mapNotNull{forms[it] as? JsonObject}.filter{allowed(it.text("permission"))}
    val item=selected
    if(item!=null){
        val(table,original)=item
        val row=workspace.records(table).find{it.text("id")==original.text("id")&&it.text("user_id")==original.text("user_id")&&it.text("event_id")==original.text("event_id")}?:original
        ScreenShell(recordTitle(row,workspace),onBack={selected=null},snackbar=snackbar){
            RecordDetails(row,workspace)
            if(table=="documents")Button(onClick={vm.openDocument(row.text("path")){url->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}){Text("Open private document")}
            if(table=="notifications")Button(onClick={vm.command("acknowledge_notification",buildJsonObject{put("id",row.text("id"))})}){Text("Acknowledge notice")}
            if(table=="attendance_windows")QrImage("archeryclub://attendance?token=${row.text("token")}")
            if(table=="equipment"&&allowed("equipment"))QrImage(row.text("id"))
            if(table=="events")QrImage("archeryclub://event?id=${row.text("id")}")
            if(table=="elections")ElectionPanel(row,workspace,vm,isMember)
            if(table in setOf("applications","renewals","candidates","training_records","financial_records","delegates","interclub_events")){
                val docModule=mapOf("applications" to 2,"renewals" to 1,"candidates" to 9,"training_records" to 11,"financial_records" to 12,"delegates" to 15,"interclub_events" to 15).getValue(table)
                if(table=="interclub_events")Text("Event documents are shared with this event's invited representatives. Attach only approved event materials.")
                DocumentsPanel(row.text("id").ifBlank{row.text("event_id")},docModule,workspace,vm,state.busy)
            }
            available.filter{it.text("table")==table}.filter{it.text("permission")!="account"||row.text("user_id").isBlank()||row.text("user_id")==user}.filter{f->f["states"]!!.jsonArray.isEmpty()||row.text("status") in f["states"]!!.jsonArray.map{it.jsonPrimitive.content}}.forEach{form->
                Button(onClick={start(form.text("action"),row)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(form.text("label"))}
            }
        };return
    }
    var search by rememberSaveable(module){mutableStateOf("")}
    var table by rememberSaveable(module){mutableStateOf(current["tables"]!!.jsonArray.firstOrNull()?.jsonPrimitive?.content.orEmpty())}
    ScreenShell("M$module: ${current.text("name")}",onBack={module=0},snackbar=snackbar){
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick=vm::refresh,enabled=!state.busy){Text("Refresh records")}
        if(module==6)MemberOverview(workspace){module=it;selected=null}
        if(module==13)ReportsPanel(workspace,vm)
        if(module==10||module==5)AvailabilityPanel(vm)
        if(module==11)TrainingImport(vm)
        if(module==3||module==8)RecordCalendar(workspace.records("events"),workspace){selected="events" to it}
        if(module==10)RecordCalendar(workspace.records("borrowings").filter{it.text("status") in setOf("approved","released")},workspace){selected="borrowings" to it}
        if(module==4||module==11)TrainingHistory(workspace.records("training_records").filter{module==11||it.text("user_id")==user},vm)
        val createActions=setOf("save_profile","request_renewal","save_application","check_in","save_event","publish_announcement","record_attendance","save_term","record_residency","create_election","record_leadership","save_equipment","request_borrowing","save_personal_training","save_training","save_financial_request","assess_fee","save_goal","configure_club","define_role","save_partner","share_interclub_event","save_delegate","raise_concern")
        available.filter{it.text("action") in createActions && it.text("action")!="request_renewal" || it.text("action") in setOf("save_renewal","invite_account")}.forEach{form->
            OutlinedButton(onClick={
                val initial=when(form.text("action")){
                    "save_profile"->workspace.records("profiles").find{it.text("id")==user}
                    "save_application"->workspace.records("applications").find{it.text("user_id")==user}
                    "configure_club"->workspace.records("settings").firstOrNull()
                    else->null
                }
                start(form.text("action"),initial?:buildJsonObject{})
            },enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(form.text("label"))}
        }
        SelectField("Records",table,current["tables"]!!.jsonArray.map{it.jsonPrimitive.content to human(it.jsonPrimitive.content)}){table=it}
        OutlinedTextField(search,{search=it},label={Text("Search records")},modifier=Modifier.fillMaxWidth())
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
        rows.forEach{r->Card(Modifier.fillMaxWidth().clickable{selected=table to r}){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(recordTitle(r,workspace),style=MaterialTheme.typography.titleMedium)
            listOf("status","validation","category","starts_at","training_date","amount","notified_at").map{r.text(it)}.filter{it.isNotBlank()}.take(3).forEach{Text(human(it))}
        }}}
    }
}

@Composable private fun AuthScreen(state:SystemState,vm:SystemViewModel,snackbar:SnackbarHostState){
    var email by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
    ScreenShell("Welcome to Archery Club",snackbar=snackbar){
        TargetMark();Text("Sign in to access your applications, membership, or assigned club responsibilities.")
        OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(onClick={vm.auth("signin",email,password)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sign in")}
        state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        state.message?.let{Text(it)}
        OutlinedButton(onClick={vm.auth("signup",email,password)},enabled=!state.busy){Text("Create account")}
        TextButton(onClick={vm.auth("recover",email,"")},enabled=!state.busy){Text("Forgot password")}
        OutlinedTextField(code,{code=it},label={Text("Email verification or recovery code")},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={vm.auth("verify",email,"",code)},enabled=!state.busy){Text("Verify email code")}
        TextButton(onClick={vm.auth("verify_invite",email,"",code)},enabled=!state.busy){Text("Accept officer invitation code")}
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable private fun PasswordChange(vm:SystemViewModel,busy:Boolean){var password by remember{mutableStateOf("")};OutlinedTextField(password,{password=it},label={Text("New password")},visualTransformation=PasswordVisualTransformation());Button(onClick={vm.auth("password","",password)},enabled=!busy){Text("Save new password")}}

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
    row.entries.filter{(k,v)->k !in setOf("id","user_id","owner_id","event_id","encoded_by","reviewed_by","created_by","approved_by","token","path","snapshot","version")&&v!=JsonNull}.forEach{(key,value)->
        if(value is JsonPrimitive)Text("${human(key)}: ${human(value.content)}",style=MaterialTheme.typography.bodyMedium)
        else if(value is JsonArray&&value.all{it is JsonPrimitive})Text("${human(key)}: ${value.joinToString{it.jsonPrimitive.content}}")
    }
}
@Composable private fun QrImage(value:String){
    val bitmap=remember(value){val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,480,480);Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888).apply{for(y in 0 until 480)for(x in 0 until 480)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}
    Image(bitmap.asImageBitmap(),contentDescription="QR code for this club record",modifier=Modifier.size(240.dp))
}
@Composable private fun DocumentsPanel(record:String,module:Int,workspace:JsonObject,vm:SystemViewModel,busy:Boolean){
    val context=LocalContext.current;var requirement by rememberSaveable(record){mutableStateOf("Supporting document")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{vm.upload(context,it,module,record,requirement)}}
    workspace.records("documents").filter{it.text("record_id")==record}.forEach{d->TextButton(onClick={vm.openDocument(d.text("path")){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it)))}}){Text("Open ${d.text("name")}")}}
    val names=if(module in setOf(1,2))(workspace.records("settings").firstOrNull()?.get(if(module==1)"renewal_requirements" else "application_requirements") as? JsonArray).orEmpty().map{it.jsonPrimitive.content} else emptyList()
    names.forEach{name->Text("$name: ${if(workspace.records("documents").any{it.text("record_id")==record&&it.text("requirement")==name})"Uploaded" else "Required"}")}
    val table=mapOf(1 to "renewals",2 to "applications",9 to "candidates",11 to "training_records",12 to "financial_records",15 to "delegates")[module].orEmpty()
    val row=workspace.records(table).find{it.text("id")==record}
    val canAttach=when(module){1,2,12->row?.text("user_id")==workspace.text("user_id")&&row.text("status") in setOf("draft","incomplete","returned_for_correction");else->true}
    if(canAttach){
        if(names.isNotEmpty())SelectField("Requirement",requirement,names.map{it to it}){requirement=it}
        else OutlinedTextField(requirement,{requirement=it},label={Text("Requirement or document label")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={picker.launch(if(module==11)arrayOf("application/pdf","image/jpeg","image/png","text/csv","video/mp4") else arrayOf("application/pdf","image/jpeg","image/png","text/csv"))},enabled=!busy&&requirement.isNotBlank()&&(names.isEmpty()||requirement in names)){Text("Upload supporting document")}
    }
    if(module==11)Text("Training videos: MP4, no longer than 10 seconds. The server validates the duration.")
}
@Composable private fun MemberOverview(workspace:JsonObject,onNavigate:(Int)->Unit){
    val user=workspace.text("user_id")
    val m=workspace.records("memberships").find{it.text("user_id")==user}
    InfoCard("Membership",m?.text("status")?:"No official membership")
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
