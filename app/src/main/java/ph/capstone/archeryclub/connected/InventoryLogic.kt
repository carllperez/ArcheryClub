package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*

/* The club's own inventory set-up (migration 033): coloured categories with their own fields, and places.
 * Items keep the old equipment columns, so borrowing still works; the inventory columns sit beside them. */

data class InvField(val key: String, val label: String, val type: String, val options: List<String>, val onList: Boolean)

data class InvCategory(val id: String, val name: String, val color: String, val fields: List<InvField>, val borrowable: Boolean,
                       val labelled: Boolean, val matchArcher: Boolean, val version: Long)

data class InvPlace(val id: String, val name: String, val version: Long)

data class InvItem(val row: JsonObject, val id: String, val name: String, val category: InvCategory?, val categoryName: String,
                   val place: InvPlace?, val quantity: Int, val owner: String, val details: Map<String, String>,
                   val notes: String, val condition: String)

/** Category colours as (background, text), the same family as the club sheet's dropdown chips. */
val INV_COLORS: Map<String, Pair<Long, Long>> = linkedMapOf(
    "red" to (0xFFFBE1DF to 0xFF8C1D18), "orange" to (0xFFFBDCC4 to 0xFF8A3B12), "yellow" to (0xFFFCEFB4 to 0xFF6B5300),
    "green" to (0xFFDDEFD3 to 0xFF183F27), "blue" to (0xFFDCEAF7 to 0xFF1D4E7A), "teal" to (0xFFDCE9E6 to 0xFF2E5650),
    "purple" to (0xFFEDE0F7 to 0xFF5B2A86), "gray" to (0xFFEEF2EC to 0xFF3D4740))

val FIELD_TYPES = listOf("text" to "Text", "choice" to "Choice", "number" to "Number", "range" to "Range")

private fun JsonObject.bool(key: String, default: Boolean) = (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

fun invField(o: JsonObject) = InvField(o.text("key"), o.text("label"), o.text("type").ifBlank { "text" },
    (o["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(), o.bool("on_list", true))

fun invCategories(workspace: JsonObject): List<InvCategory> = workspace.records("inventory_categories").map { c ->
    InvCategory(c.text("id"), c.text("name"), c.text("color").ifBlank { "green" },
        (c["fields"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map(::invField).orEmpty(),
        c.bool("borrowable", true), c.bool("labelled", false), c.bool("match_archer", false), c.text("version").toLongOrNull() ?: 1)
}

fun invPlaces(workspace: JsonObject): List<InvPlace> = workspace.records("inventory_places").map {
    InvPlace(it.text("id"), it.text("name"), it.text("version").toLongOrNull() ?: 1)
}

fun invItems(workspace: JsonObject): List<InvItem> {
    val cats = invCategories(workspace).associateBy { it.id }; val places = invPlaces(workspace).associateBy { it.id }
    return workspace.records("equipment").map { e ->
        val details = (e["details"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val cat = cats[e.text("category_id")]
        InvItem(e, e.text("id"), e.text("name").ifBlank { e.text("label") }, cat, cat?.name ?: e.text("category").ifBlank { "Other" },
            places[e.text("place_id")], e.text("quantity").toIntOrNull() ?: 1, e.text("owner").ifBlank { "Club property" }, details,
            e.text("notes"), e.text("condition"))
    }
}

/** A field value as a short tag: lengths get ", weights get lb. */
fun fieldTag(f: InvField, value: String): String = when {
    value.isBlank() -> ""
    f.label.contains("(in", ignoreCase = true) -> "$value\""
    f.label.contains("lb", ignoreCase = true) -> "$value lb"
    else -> value
}

/** The tags shown on the inventory list: the category's "show on list" fields, in order. */
fun invTags(item: InvItem): List<String> = item.category?.fields.orEmpty().filter { it.onList }
    .map { fieldTag(it, item.details[it.key].orEmpty()) }.filter { it.isNotBlank() } +
    (if (item.quantity > 1) listOf("× ${item.quantity}") else emptyList())

/** Search over name, category, place, owner, note and every field value. */
fun invMatches(item: InvItem, query: String): Boolean {
    val q = query.trim().lowercase(); if (q.isEmpty()) return true
    return (listOf(item.name, item.categoryName, item.place?.name.orEmpty(), item.owner, item.notes, item.row.text("label")) + item.details.values)
        .any { it.lowercase().contains(q) }
}

fun conditionLabel(c: String) = when (c) { "serviceable" -> "Ready"; "maintenance" -> "Needs repair"; "damaged" -> "Damaged"; "retired" -> "Retired"; else -> human(c) }

/** What's wrong with one field value, or null; the server checks the same rules. */
fun fieldProblem(f: InvField, value: String): String? {
    val v = value.trim(); if (v.isEmpty()) return null
    return when (f.type) {
        "choice" -> if (v in f.options) null else "${f.label} must be one of ${f.options.joinToString(", ")}."
        "number" -> if (Regex("""\d+(\.\d+)?""").matches(v)) null else "${f.label} must be a number."
        "range" -> if (Regex("""\d+(\.\d+)?(\s*-\s*\d+(\.\d+)?)?""").matches(v)) null else "${f.label} must be a number or a range like 68-66."
        else -> if (v.length > 80) "${f.label} is too long." else null
    }
}

/** Fields as the server stores them. */
fun fieldsJson(fields: List<InvField>) = JsonArray(fields.map { f ->
    buildJsonObject {
        if (f.key.isNotBlank()) put("key", f.key); put("label", f.label.trim()); put("type", f.type)
        put("options", JsonArray(f.options.map { JsonPrimitive(it.trim()) }.filter { it.content.isNotBlank() })); put("on_list", f.onList)
    }
})

/* ---------- Importing one spreadsheet tab ---------- */

const val COL_SKIP = "skip"
/** Where a sheet column can go: the item's own columns, or a field (matched by its name inside each row's category). */
val ITEM_TARGETS = listOf("category" to "Category", "name" to "Name", "quantity" to "Quantity", "owner" to "Owner", "notes" to "Note", COL_SKIP to "Skip")

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

/** The likeliest target for a header: item columns by common names, then a field label (with a few sheet aliases). */
fun guessTarget(header: String, categories: List<InvCategory>): String {
    val h = norm(header)
    when {
        h.isEmpty() -> return COL_SKIP
        h in setOf("category", "type") -> return "category"
        h.startsWith("item") || h in setOf("name", "brand", "itemname", "itemnamebrand") -> return "name"
        h in setOf("qty", "quantity", "count", "pcs") -> return "quantity"
        h == "owner" -> return "owner"
        h in setOf("note", "notes", "remarks", "remark") -> return "notes"
    }
    val labels = categories.flatMap { c -> c.fields.map { it.label } }.distinct()
    labels.firstOrNull { norm(it) == h }?.let { return "field:$it" }
    // Sheet headings like "in./ size" and "lbs." mean the length and draw weight fields.
    val alias = when { h.startsWith("in") && h.length <= 7 -> "(in"; h.startsWith("lb") -> "lb"; h == "colour" -> "color"; else -> null }
    if (alias != null) labels.firstOrNull { it.lowercase().contains(alias) }?.let { return "field:$it" }
    labels.firstOrNull { norm(it).startsWith(h) || h.startsWith(norm(it)) }?.let { return "field:$it" }
    return COL_SKIP
}

data class SheetTable(val headers: List<String>, val rows: List<List<String>>)

/** The heading row is the first one with at least three filled cells (sheets often start with a title or blank rows). */
fun sheetTable(csv: String): SheetTable {
    val records = csvRecords(csv)
    val at = records.indexOfFirst { r -> r.count { it.isNotBlank() } >= 3 }
    if (at < 0) return SheetTable(emptyList(), emptyList())
    return SheetTable(records[at].map { it.trim() }, records.drop(at + 1))
}

data class ImportPlan(val rows: List<JsonObject>, val blank: Int, val problems: List<String>)

/** Turns sheet rows into items. Rows without a name are blank; a row whose category isn't set up is left out and listed.
 *  Values that don't fit their field (a choice that isn't on the list, text in a number) are left empty and listed. */
fun inventoryImport(table: SheetTable, targets: List<String>, categories: List<InvCategory>, defaultCategoryId: String?): ImportPlan {
    val byName = categories.associateBy { it.name.trim().lowercase() }
    val out = mutableListOf<JsonObject>(); val problems = mutableListOf<String>(); var blank = 0
    val fallback: InvCategory? = categories.find { it.id == defaultCategoryId }
    table.rows.forEachIndexed { i, cells ->
        fun at(target: String) = targets.indexOf(target).takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }.orEmpty()
        val n = i + 1
        val name = at("name")
        if (name.isBlank()) { blank++; return@forEachIndexed }
        val catText = at("category")
        val cat = if (catText.isBlank()) fallback else byName[catText.lowercase()]
        if (cat == null) { problems += "Row $n ($name): no category called \"${catText.ifBlank { "?" }}\". Add it in Set up or pick a category column."; return@forEachIndexed }
        val details = buildJsonObject {
            targets.forEachIndexed { col, t ->
                if (!t.startsWith("field:")) return@forEachIndexed
                val f = cat.fields.find { it.label.equals(t.removePrefix("field:"), ignoreCase = true) } ?: return@forEachIndexed
                var v = cells.getOrNull(col)?.trim().orEmpty(); if (v.isEmpty()) return@forEachIndexed
                if (f.type == "choice") v = f.options.find { it.equals(v, ignoreCase = true) } ?: v
                if (f.type == "range") v = v.replace(Regex("\\s*-\\s*"), "-")
                val bad = fieldProblem(f, v)
                if (bad != null) problems += "Row $n ($name): $bad Left empty." else put(f.key, v)
            }
        }
        var qty = at("quantity").filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 9999) ?: 1
        if (cat.labelled && qty > 1) { problems += "Row $n ($name): ${cat.name} are labelled one by one, so quantity is set to 1."; qty = 1 }
        val owner = at("owner").let { if (it.isBlank() || it.equals("club property", ignoreCase = true) || it.equals("club", ignoreCase = true)) "Club property" else it }
        out += buildJsonObject {
            put("name", name.take(120)); put("category_id", cat.id); put("quantity", qty); put("owner", owner.take(120))
            put("notes", at("notes").take(500)); put("condition", "serviceable"); put("details", details)
        }
    }
    return ImportPlan(out, blank, problems)
}
