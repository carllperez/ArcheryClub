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
    @Test fun exportedScorecardsRoundTripWithoutGrantingValidationOrChangingImportKeys() {
        val row=buildJsonObject {
            put("id","saved-scorecard");put("user_id","member-id");put("training_date","2026-10-10")
            put("distance","18");put("target_face","40cm");put("category","recurve")
            put("scoring_format","30 arrows");put("score","240.25");put("maximum_score","300")
            put("observations","Wind, then rain\nCoach said \"steady\"");put("validation","validated")
            put("validated_by","officer-id");put("source","coach")
        }
        val imported=trainingCsv(trainingImportCsv(listOf(row))).single().jsonObject
        assertEquals("record:saved-scorecard",imported.text("import_key"))
        assertEquals(row.text("observations"),imported.text("observations"))
        assertEquals("240.25",imported.text("score"))
        assertFalse(imported.containsKey("validated_by"));assertFalse(imported.containsKey("validation"))
        assertEquals(imported,trainingCsv(trainingImportCsv(listOf(imported))).single())
    }
}
