package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowInputTest {
    private val headers="import_key,user_id,training_date,distance,target_face,category,scoring_format,score,maximum_score,observations\r\n"
    @Test fun quotedCsvPreservesCommasNewlinesAndQuotes() {
        val rows=trainingCsv(headers+"one,uuid,2026-10-02,18,40cm,recurve,30 arrows,240,300,\"Wind, then rain\nCoach said \"\"steady\"\"\"\r\n")
        assertEquals("Wind, then rain\nCoach said \"steady\"",rows[0].jsonObject.text("observations"))
    }
    @Test fun csvRejectsMalformedAndPrivilegedFields() {
        assertThrows(IllegalArgumentException::class.java){trainingCsv(headers+"one,uuid,2026-10-02,18,40cm,recurve,30 arrows,240,300,\"unclosed")}
        assertThrows(IllegalArgumentException::class.java){trainingCsv(headers.replace("observations","id")+"one,uuid,2026-10-02,18,40cm,recurve,30 arrows,240,300,record")}
    }
    @Test fun datesAreValidatedAndOffsetsPreserved() {
        val field=buildJsonObject{put("type","timestamp");put("label","Start")}
        assertEquals("2026-10-02T04:30:00Z",fieldValue(field,"2026-10-02T12:30:00+08:00").jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java){fieldValue(field,"yesterday")}
    }
    @Test fun decimalAmountsAreNotRoundedByInputConversion() {
        assertEquals("120.25",fieldValue(buildJsonObject{put("type","number")},"120.25").jsonPrimitive.content)
    }
}
