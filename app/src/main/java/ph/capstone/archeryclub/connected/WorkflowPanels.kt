package ph.capstone.archeryclub.connected

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.*
import java.time.LocalDate

@Composable fun ElectionPanel(election:JsonObject,workspace:JsonObject,vm:SystemViewModel,isMember:Boolean){
    val id=election.text("id");val user=workspace.text("user_id")
    val voted=workspace.records("voter_participation").any{it.text("election_id")==id&&it.text("user_id")==user}
    val eligible=workspace.records("eligible_voters").any{it.text("election_id")==id&&it.text("user_id")==user}
    val choices=remember(id){mutableStateMapOf<String,String>()}
    val positions=(election["positions"] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}
    var confirming by remember{mutableStateOf(false)}
    var summary by remember(id){mutableStateOf<JsonElement?>(null)}
    if(voted)Text("Your participation has been recorded. Your ballot choices are private.")
    if(isMember&&eligible&&!voted&&election.text("status")=="voting"&&election.text("method")=="digital"){
        positions.forEach{position->SelectField(position,choices[position].orEmpty(),workspace.records("candidates")
            .filter{it.text("election_id")==id&&it.text("confirmed")=="true"&&it.text("position")==position}
            .map{it.text("id") to recordTitle(it,workspace)}){choices[position]=it}}
        Button(onClick={confirming=true},enabled=positions.isNotEmpty()&&positions.all{!choices[it].isNullOrBlank()}){Text("Review ballot")}
    }
    if(confirming)AlertDialog(onDismissRequest={confirming=false},title={Text("Submit this ballot?")},
        text={Text("You can vote once. Your choices cannot be changed after submission.")},
        confirmButton={TextButton(onClick={confirming=false;vm.command("cast_ballot",buildJsonObject{
            put("election_id",id);put("choices",buildJsonObject{choices.forEach{(k,v)->put(k,v)}})
        }){choices.clear()}}){Text("Submit ballot")}},dismissButton={TextButton(onClick={confirming=false}){Text("Go back")}})
    OutlinedButton(onClick={vm.rpc("election_summary",buildJsonObject{put("p_id",id)}){summary=it}}){Text("View turnout and official results")}
    summary?.let{JsonSummary(it)}
}

@Composable fun ReportsPanel(workspace:JsonObject,vm:SystemViewModel){
    var from by remember{mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())}
    var to by remember{mutableStateOf(LocalDate.now().toString())}
    var report by remember{mutableStateOf<JsonElement?>(null)}
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null&&report!=null)runCatching{context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(report.toString())}}
            .onFailure{vm.error("The report could not be saved.")}
    }
    OutlinedTextField(from,{from=it},label={Text("Report from (YYYY-MM-DD)")})
    OutlinedTextField(to,{to=it},label={Text("Report to (YYYY-MM-DD)")})
    Button(onClick={if(runCatching{!LocalDate.parse(to).isBefore(LocalDate.parse(from))}.getOrDefault(false))
        vm.rpc("club_report",buildJsonObject{put("p_from",from);put("p_to",to)}){report=it}
        else vm.error("Choose a valid reporting period.")}){Text("Generate report")}
    report?.let{value->
        val objectReport=value.jsonObject
        objectReport.records("goals").forEach{goal->
            Text(goal.text("title"),style=MaterialTheme.typography.titleMedium)
            val actual=goal.text("actual").toFloatOrNull();val target=goal.text("target").toFloatOrNull()
            if(actual!=null&&target!=null&&target>0){LinearProgressIndicator(progress={(actual/target).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("Recorded: $actual · Target: $target")}
            else Text("A comparison is unavailable because its source data or permission is missing.")
        }
        objectReport.records("activities").forEach{event->
            Text(event.text("title"));val rate=event.text("attendance_rate").toFloatOrNull()
            if(rate!=null){LinearProgressIndicator(progress={(rate/100).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("Attendance: $rate%")}
            else Text("Attendance rate unavailable: no registrations.")
        }
        JsonSummary(value);OutlinedButton(onClick={export.launch("archery-report-$from-$to.json")}){Text("Export report")}
    }
}

@Composable fun AvailabilityPanel(vm:SystemViewModel){
    var start by remember{mutableStateOf("")};var end by remember{mutableStateOf("")}
    var hand by remember{mutableStateOf("")};var weight by remember{mutableStateOf("")};var setup by remember{mutableStateOf("")}
    var result by remember{mutableStateOf<JsonElement?>(null)}
    Text("Check equipment availability and compatible alternatives")
    OutlinedTextField(start,{start=it},label={Text("Start (YYYY-MM-DDTHH:MM)")})
    OutlinedTextField(end,{end=it},label={Text("End (YYYY-MM-DDTHH:MM)")})
    SelectField("Handedness",hand,listOf("" to "Any","left" to "Left","right" to "Right")){hand=it}
    OutlinedTextField(weight,{weight=it},label={Text("Maximum draw weight (optional)")})
    OutlinedTextField(setup,{setup=it},label={Text("Bow setup (optional)")})
    Button(onClick={runCatching{buildJsonObject {
        val dateField=buildJsonObject{put("type","timestamp");put("label","Date")}
        put("p_start",fieldValue(dateField,start));put("p_end",fieldValue(dateField,end));put("p_handedness",hand);put("p_setup",setup)
        put("p_max_weight",if(weight.isBlank())JsonNull else JsonPrimitive(weight.toBigDecimal()))
    }}.onSuccess{vm.rpc("equipment_availability",it){result=it}}.onFailure{vm.error(it.message?:"Review the search fields.")}}){Text("Check availability")}
    (result as? JsonArray)?.forEach{value->val item=value.jsonObject
        Text("${item.text("label")}: ${if(item.text("reserved")=="true")"Reserved" else "Available"} · ${if(item.text("compatible")=="true")"Compatible" else "Not compatible"}")
    }
    Text("An officer must still confirm safe compatibility and approve release.")
}

@Composable fun TrainingImport(vm:SystemViewModel){
    val context=LocalContext.current
    var rows by remember{mutableStateOf<JsonArray?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runCatching{
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{reader->
                val buffer=CharArray(1_000_001);var count=0
                while(count<buffer.size){val n=reader.read(buffer,count,buffer.size-count);if(n<0)break;count+=n}
                require(count<=1_000_000){"Choose a CSV smaller than 1 MB."}
                trainingCsv(String(buffer,0,count))
            }?:error("Cannot read this CSV.")
        }.onSuccess{rows=it}.onFailure{vm.error(it.message?:"Check the CSV format.")}
    }
    Text("Import coach scorecards: CSV with import_key, user_id, training_date, distance, target_face, category, scoring_format, score, maximum_score. Optional columns: event_id, grouping, level, observations, feedback. Each import key must be unique.")
    OutlinedButton(onClick={picker.launch(arrayOf("text/csv","text/comma-separated-values"))}){Text("Choose training CSV")}
    rows?.let{data->Text("${data.size} scorecards ready for server validation. All rows must pass for the import to save.")
        Button(onClick={vm.command("import_training",buildJsonObject{put("rows",data)}){rows=null}}){Text("Import ${data.size} scorecards")}}
}

/** RFC 4180 quoting, including embedded commas/newlines and escaped quotes. */
fun trainingCsv(csv:String):JsonArray {
    val records=mutableListOf<List<String>>();var row=mutableListOf<String>();val field=StringBuilder()
    var quoted=false;var closed=false;var i=0
    fun cell(){row.add(field.toString());field.clear();closed=false}
    while(i<csv.length){val c=csv[i]
        if(quoted){if(c=='"'){if(i+1<csv.length&&csv[i+1]=='"'){field.append('"');i++}else{quoted=false;closed=true}}else field.append(c)}
        else when(c){
            '"'->{require(field.isEmpty()&&!closed){"Invalid CSV quoting."};quoted=true}
            ','->cell()
            '\r','\n'->{cell();records.add(row);row=mutableListOf();if(c=='\r'&&i+1<csv.length&&csv[i+1]=='\n')i++}
            else->{require(!closed){"Unexpected text after a quoted CSV field."};field.append(c)}
        };i++
    }
    require(!quoted){"Unclosed CSV quoted field."}
    if(field.isNotEmpty()||row.isNotEmpty()||closed){cell();records.add(row)}
    require(records.size in 2..501){"Import between 1 and 500 scorecards."}
    val headers=records.first().map{it.trim().removePrefix("\uFEFF")}
    val required=setOf("import_key","user_id","training_date","distance","target_face","category","scoring_format","score","maximum_score")
    require(headers.toSet().size==headers.size&&headers.containsAll(required)){"CSV has missing or duplicate columns."}
    val allowed=required+setOf("event_id","grouping","level","observations","feedback")
    require(headers.all{it in allowed}){"CSV contains unsupported columns."}
    return JsonArray(records.drop(1).mapIndexed {index,values->
        require(values.size==headers.size){"Row ${index+2} has the wrong column count."}
        buildJsonObject{headers.forEachIndexed{n,key->
            require(key !in required||values[n].isNotBlank()){"Row ${index+2}: $key is required."}
            put(key,values[n].trim())
        }}
    })
}

@Composable fun TrainingHistory(records:List<JsonObject>,vm:SystemViewModel){
    val context=LocalContext.current
    val columns=listOf("user_id","training_date","distance","target_face","category","scoring_format","score","maximum_score","grouping","level","observations","feedback","source","validation","validated_at")
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)runCatching{
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{out->
            fun cell(value:String)="\""+value.replace("\"","\"\"")+"\""
            out.write(columns.joinToString(",",transform=::cell)+"\r\n")
            records.forEach{row->out.write(columns.joinToString(","){key->cell(row.text(key))}+"\r\n")}
        }
    }.onFailure{vm.error("The scorecard export could not be saved.")}}
    OutlinedButton(onClick={export.launch("archery-scorecards.csv")}){Text("Export scorecards")}
    val groups=records.filter{it.text("validation")=="validated"}.groupBy{row->listOf("user_id","distance","target_face","category","scoring_format","maximum_score").map(row::text)}
    groups.forEach{(_,rows)->
        val sorted=rows.sortedBy{it.text("training_date")};val first=sorted.first()
        Text("${first.text("distance")} m · ${first.text("target_face")} · ${first.text("category")} · ${first.text("scoring_format")}")
        sorted.forEach{Text("${it.text("training_date")}: ${it.text("score")} / ${it.text("maximum_score")}")}
        if(sorted.size>=2){val delta=sorted.last().text("score").toBigDecimal()-sorted.first().text("score").toBigDecimal();Text("Change across these comparable records: $delta points")}
        else Text("More validated sessions are needed for a progress comparison.")
    }
    if(groups.isEmpty())Text("No validated training is available for comparison yet.")
}

@Composable private fun JsonSummary(value:JsonElement,label:String=""){
    when(value){
        is JsonObject -> value.forEach{(key,entry)->JsonSummary(entry,if(label.isBlank())human(key) else "$label · ${human(key)}")}
        is JsonArray -> {if(value.isEmpty())Text("$label: No recorded data");value.forEachIndexed{index,entry->JsonSummary(entry,"$label ${index+1}")}}
        JsonNull -> Text("$label: Not available")
        else -> Text("$label: ${value.jsonPrimitive.content}")
    }
}
