package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryLogicTest {
    private fun JsonElement?.str() = (this as JsonPrimitive).content
    private fun JsonElement?.obj() = this as JsonObject
    private val riser = InvCategory("c1", "Riser", "red", listOf(
        InvField("hand", "Hand", "choice", listOf("RH", "LH"), true), InvField("size", "Size", "choice", listOf("S", "M", "L"), true),
        InvField("sight", "Sight", "text", emptyList(), true), InvField("color", "Color", "text", emptyList(), true),
        InvField("length", "Length (in.)", "number", emptyList(), true)), true, false, true, 1)
    private val limbs = InvCategory("c2", "Limbs", "orange", listOf(
        InvField("label", "Label", "text", emptyList(), true), InvField("size", "Size", "choice", listOf("S", "M", "L"), true),
        InvField("length", "Length (in.)", "range", emptyList(), true), InvField("lbs", "Draw weight (lbs.)", "range", emptyList(), true),
        InvField("color", "Color", "text", emptyList(), false)), true, true, true, 1)
    private val cats = listOf(riser, limbs)

    @Test fun sheetHeadingsFindTheirFields() {
        val headers = listOf("Category", "Item Name/ Brand", "QTY", "Label", "Size", "Sight", "Color", "in./ size", "lbs.", "Owner", "Note")
        assertEquals(listOf("category", "name", "quantity", "field:Label", "field:Size", "field:Sight", "field:Color", "field:Length (in.)",
            "field:Draw weight (lbs.)", "owner", "notes"), headers.map { guessTarget(it, cats) })
    }

    @Test fun sheetTabBecomesItems() {
        val csv = ",,,,\nCategory,Item Name/ Brand,QTY,Label,Size,Sight,Color,in./ size,lbs.,Owner,Note\n" +
            "Riser,Xelium (RH),1,RH,S,WNS,Black,23,,CLUB PROPERTY,replace with same length\n" +
            ",,,,,,,,,,\n" +
            "Limbs,Akusta Expedition,1,M3,m,,White,68 - 66,20-22,CLUB PROPERTY,confirm if label is right\n" +
            "Limbs,WNS Explore W1,2,M2,XL,,Black,68-66,20-22,Juan,broken\n" +
            "Bow,Old bow,1,,,,,,,,\n"
        val table = sheetTable(csv)
        assertEquals("Category", table.headers.first())
        val plan = inventoryImport(table, table.headers.map { guessTarget(it, cats) }, cats, null)
        assertEquals(3, plan.rows.size)
        val xel = plan.rows[0]
        assertEquals("Club property", xel["owner"].str())
        assertEquals("WNS", xel["details"].obj()["sight"].str())
        assertTrue("label column only fills categories that have a Label field", xel["details"].obj()["label"] == null)
        val exp = plan.rows[1]["details"].obj()
        assertEquals("M", exp["size"].str()); assertEquals("68-66", exp["length"].str())
        val broken = plan.rows[2]
        assertEquals("1", broken["quantity"].str()); assertEquals("Juan", broken["owner"].str())
        assertTrue(broken["details"].obj()["size"] == null)
        assertEquals(3, plan.problems.size)
        assertTrue(plan.problems.any { it.contains("Size must be one of") })
        assertTrue(plan.problems.any { it.contains("quantity is set to 1") })
        assertTrue(plan.problems.any { it.contains("no category called \"Bow\"") })
    }

    @Test fun listTagsFollowTheCategory() {
        val ws = JsonObject(mapOf(
            "inventory_categories" to JsonArray(listOf(buildJsonObject {
                put("id", "c2"); put("name", "Limbs"); put("color", "orange"); put("labelled", true); put("version", 3)
                put("fields", fieldsJson(limbs.fields))
            })),
            "inventory_places" to JsonArray(listOf(buildJsonObject { put("id", "p1"); put("name", "Tall White Cabinet") })),
            "equipment" to JsonArray(listOf(buildJsonObject {
                put("id", "e1"); put("label", "WNS Explore W1 S1"); put("name", "WNS Explore W1"); put("category_id", "c2"); put("place_id", "p1")
                put("quantity", 1); put("condition", "serviceable"); put("notes", "")
                put("details", buildJsonObject { put("label", "S1"); put("size", "S"); put("length", "66-64"); put("lbs", "20-22"); put("color", "Black") })
            }, buildJsonObject { put("id", "e2"); put("label", "Recurve 01"); put("category", "Bow"); put("condition", "damaged") }))))
        val items = invItems(ws)
        assertEquals("S1,S,66-64\",20-22 lb", invTags(items[0]).joinToString(","))
        assertEquals("Tall White Cabinet", items[0].place?.name)
        assertEquals("Recurve 01", items[1].name); assertEquals("Bow", items[1].categoryName)
        assertTrue(invMatches(items[0], "black")); assertTrue(invMatches(items[0], "tall white"))
        assertEquals(null, fieldProblem(limbs.fields[2], "68-66")); assertTrue(fieldProblem(limbs.fields[2], "heavy") != null)
    }
}
