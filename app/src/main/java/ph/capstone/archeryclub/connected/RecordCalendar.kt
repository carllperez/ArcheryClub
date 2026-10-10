package ph.capstone.archeryclub.connected

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun RecordCalendar(records:List<JsonObject>,workspace:JsonObject,onSelect:(JsonObject)->Unit){
    var month by remember{mutableStateOf(YearMonth.now())}
    var date by remember{mutableStateOf(LocalDate.now())}
    val zone=ZoneId.systemDefault()
    val ranges=records.mapNotNull{row->runCatching{
        Triple(row,Instant.parse(row.text("starts_at")).atZone(zone).toLocalDate(),Instant.parse(row.text("ends_at")).atZone(zone).toLocalDate())
    }.getOrNull()}
    Text("Calendar · $zone",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        TextButton(onClick={month=month.minusMonths(1)}){Text("Previous")}
        Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")))
        TextButton(onClick={month=month.plusMonths(1)}){Text("Next")}
    }
    Row(Modifier.fillMaxWidth()){listOf("M","T","W","T","F","S","S").forEach{Text(it,Modifier.weight(1f))}}
    val offset=month.atDay(1).dayOfWeek.value-1
    repeat((offset+month.lengthOfMonth()+6)/7){week->Row(Modifier.fillMaxWidth()){
        repeat(7){weekday->val day=week*7+weekday-offset+1
            if(day !in 1..month.lengthOfMonth())Spacer(Modifier.weight(1f))
            else {val current=month.atDay(day);val count=ranges.count{!current.isBefore(it.second)&&!current.isAfter(it.third)}
                TextButton(onClick={date=current},modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp)){
                    Text("$day"+if(count>0)"•" else "",color=if(date==current)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }}
    Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")))
    val selected=ranges.filter{!date.isBefore(it.second)&&!date.isAfter(it.third)}
    if(selected.isEmpty())Text("No recorded activities or bookings on this date.")
    selected.forEach{(row,_,_)->OutlinedButton(onClick={onSelect(row)},modifier=Modifier.fillMaxWidth()){
        Text(recordTitle(row,workspace)+row.text("status").takeIf{it.isNotBlank()}?.let{" · ${human(it)}"}.orEmpty())
    }}
}
