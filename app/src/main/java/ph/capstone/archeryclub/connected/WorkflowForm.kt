package ph.capstone.archeryclub.connected

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.*

/** Translates user input only. The server independently validates every command. */
fun fieldValue(field: JsonObject, value: String): JsonElement {
    val label=field.text("label")
    val input=value.trim()
    val type=field.text("type")
    if(input.isEmpty() && field.text("optional")=="true") return JsonNull
    return when(type) {
        "boolean" -> JsonPrimitive(input=="true")
        "lines","permissions" -> JsonArray(input.lines().map{it.trim()}.filter{it.isNotEmpty()}.distinct().map(::JsonPrimitive))
        "number" -> JsonPrimitive(input.toBigDecimalOrNull() ?: error("$label must be a number."))
        "tallies" -> Json.parseToJsonElement(input).jsonArray
        "date" -> JsonPrimitive(runCatching{LocalDate.parse(input).toString()}.getOrElse{error("$label must use YYYY-MM-DD.")})
        "timestamp" -> JsonPrimitive(runCatching{OffsetDateTime.parse(input).toInstant().toString()}.recoverCatching{
            LocalDateTime.parse(input).atZone(ZoneId.systemDefault()).toInstant().toString()
        }.getOrElse{error("$label must use YYYY-MM-DDTHH:MM (local time) or include a UTC offset.")})
        else -> {require(input.isNotEmpty()){"$label is required."};JsonPrimitive(input)}
    }
}

@Composable
fun WorkflowForm(form:JsonObject, initial:JsonObject, workspace:JsonObject, busy:Boolean,
                 onBack:()->Unit,onSubmit:(JsonObject)->Unit,snackbar:SnackbarHostState) {
    val correctionRecord=workspace.records("training_records").find{it.text("id")==initial.text("record_id")}
    val correctionFields=form.records("correction_fields").map{JsonObject(it+ ("name" to JsonPrimitive("corrected_"+it.text("name"))))}
    val values=remember(form,initial){mutableStateMapOf<String,String>().apply {
        (form.records("fields")+correctionFields).forEach { field ->
            val name=field.text("name")
            val value=if(name.startsWith("corrected_"))correctionRecord?.get(name.removePrefix("corrected_")) else initial[name]
            put(name,when(value){is JsonArray->value.joinToString("\n"){it.jsonPrimitive.content};is JsonPrimitive->value.contentOrNull.orEmpty();else->""})
        }
    }}
    var error by remember{mutableStateOf<String?>(null)}
    ScreenShell(form.text("label"),onBack,snackbar) {
        if(form.text("help").isNotEmpty())Text(form.text("help"))
        (form.records("fields")+if(values["status"]=="approved")correctionFields else emptyList()).forEach { field ->
            val name=field.text("name");val value=values[name].orEmpty()
            val label=field.text("label")+if(field.text("optional")=="true")" (optional)" else ""
            when(field.text("type")) {
                "tallies" -> {
                    val candidates=workspace.records("candidates").filter{it.text("election_id")==initial.text("id")&&it.text("confirmed")=="true"}
                    val tallies=runCatching{Json.parseToJsonElement(value).jsonArray.map{it.jsonObject}}.getOrDefault(emptyList())
                    candidates.forEach{candidate->
                        val vote=tallies.find{it.text("id")==candidate.text("id")}?.text("votes").orEmpty()
                        OutlinedTextField(vote,{entered->
                            values[name]=JsonArray(candidates.map{c->buildJsonObject{
                                put("id",c.text("id"));put("votes",if(c.text("id")==candidate.text("id"))entered else tallies.find{it.text("id")==c.text("id")}?.text("votes").orEmpty())
                            }}).toString()
                        },label={Text("${candidate.text("position")}: ${recordTitle(candidate,workspace)} — votes")},modifier=Modifier.fillMaxWidth())
                    }
                }
                "boolean" -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Checkbox(value=="true",{values[name]=it.toString()});Text(label)
                }
                "choice" -> {
                    val options=if(name=="category" && form.text("action") in setOf("review_application","update_membership"))
                        workspace.records("settings").firstOrNull()?.get("membership_categories") as? JsonArray
                        else field["options"] as? JsonArray
                    SelectField(label,value,options.orEmpty().map{it.jsonPrimitive.content to human(it.jsonPrimitive.content)}){values[name]=it}
                }
                "record" -> {
                    val source=field.text("source")
                    val options=workspace.records(source).map{row ->
                        val id=if(source=="member_directory")row.text("user_id") else if(source=="interclub_events")row.text("event_id") else row.text("id")
                        id to recordTitle(row,workspace)
                    }
                    SelectField(label,value,if(field.text("optional")=="true")listOf("" to "None")+options else options){values[name]=it}
                    if(options.isEmpty())Text("No accessible ${human(source).lowercase()} are available yet.")
                }
                "permissions" -> {
                    Text(label)
                    listOf("membership","activities","elections","equipment","training","finance","president","reports","administration","interclub").forEach { permission ->
                        Row {Checkbox(permission in value.lines(),{ checked ->
                            values[name]=(if(checked)value.lines()+permission else value.lines()-permission).filter{it.isNotBlank()}.distinct().joinToString("\n")
                        });Text(human(permission))}
                    }
                }
                else -> OutlinedTextField(value,{values[name]=it},label={Text(label)},
                    supportingText={when(field.text("type")){"timestamp"->Text("YYYY-MM-DDTHH:MM · ${ZoneId.systemDefault()}");"date"->Text("YYYY-MM-DD");"lines"->Text("One entry per line");else->Unit}},
                    minLines=if(field.text("type") in setOf("long","lines"))3 else 1,modifier=Modifier.fillMaxWidth())
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy,onClick={
            runCatching {
                buildJsonObject {
                    listOf("id","version","user_id","event_id","election_id","record_id","delegate_id").forEach{key->initial[key]?.let{put(key,it)}}
                    form.records("fields").forEach {field->put(field.text("name"),fieldValue(field,values[field.text("name")].orEmpty()))}
                    if(form.text("action")=="review_training_correction" && values["status"]=="approved")put("corrected_record",buildJsonObject{
                        correctionRecord?.get("version")?.let{put("version",it)}
                        correctionFields.forEach{field->put(field.text("name").removePrefix("corrected_"),fieldValue(field,values[field.text("name")].orEmpty()))}
                    })
                }
            }.onSuccess{error=null;onSubmit(it)}.onFailure{error=it.message?:"Review the form fields."}
        },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Saving…" else form.text("label"))}
    }
}
