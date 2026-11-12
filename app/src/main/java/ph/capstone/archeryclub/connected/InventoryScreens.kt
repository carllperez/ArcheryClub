package ph.capstone.archeryclub.connected

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.LocalDate
import java.time.ZoneId

/* Inventory set up by the club (migration 033): list by place and category, add/edit an item from its
 * category's fields, set up categories and places, and import a spreadsheet tab into a place. */

private fun colors(c: String) = (INV_COLORS[c] ?: INV_COLORS.getValue("gray")).let { Color(it.first) to Color(it.second) }

@Composable
fun CategoryChip(name: String, color: String, small: Boolean = true, selected: Boolean = true, onClick: (() -> Unit)? = null) {
    val (bg, fg) = colors(color)
    Text(name, Modifier.clip(RoundedCornerShape(999.dp))
        .then(if (selected) Modifier.background(bg) else Modifier.background(Color.White).border(1.5.dp, Color(0xFFD5DED2), RoundedCornerShape(999.dp)))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = if (small) 9.dp else 12.dp, vertical = if (small) 3.dp else 6.dp),
        style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.ExtraBold, color = if (selected) fg else Color(0xFF131715))
}

@Composable
private fun Tag(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF2F6EF)).padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF3D4740))
}

private fun conditionTone(c: String) = when (c) { "serviceable" -> Tone.POSITIVE; "maintenance" -> Tone.WARNING; "damaged" -> Tone.DANGER; else -> Tone.NEUTRAL }

/** The Inventory tab of the Equipment desk: places along the top, items grouped under their coloured category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryContent(workspace: JsonObject, onItem: (String) -> Unit, onAdd: () -> Unit, onSetup: () -> Unit) {
    val items = invItems(workspace)
    val places = invPlaces(workspace)
    val cats = invCategories(workspace)
    var place by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it.take(60) }, placeholder = { Text("Search name, label, colour…") }, singleLine = true,
            shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onSetup, shape = RoundedCornerShape(999.dp)) { Text("Set up", fontWeight = FontWeight.ExtraBold) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("" to "All places · ${items.size}") + places.map { p -> p.id to "${p.name} · ${items.count { it.place?.id == p.id }}" } +
            (if (items.any { it.place == null }) listOf("none" to "No place · ${items.count { it.place == null }}") else emptyList())).forEach { (id, label) ->
            FilterChip(place == id, { place = id }, { Text(label, fontWeight = if (place == id) FontWeight.ExtraBold else FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Club.forestDeep, selectedLabelColor = Color.White))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cats.forEach { c -> CategoryChip(c.name, c.color, selected = cat.isBlank() || cat == c.id) { cat = if (cat == c.id) "" else c.id } }
    }
    val shown = items.filter { i ->
        (place.isBlank() || (place == "none" && i.place == null) || i.place?.id == place) && (cat.isBlank() || i.category?.id == cat) && invMatches(i, query)
    }
    if (items.isEmpty()) EmptyState("No equipment yet", "Add the club's gear, or import your inventory spreadsheet from Set up.", "Add item", onAdd)
    else if (shown.isEmpty()) EmptyState("Nothing matches", "Try another place, category or search.")
    // Category order from Set up; items without a set-up category come last under their old category name.
    val order = cats.map { it.name }
    shown.groupBy { it.categoryName }.toList().sortedBy { (name, _) -> order.indexOf(name).let { if (it < 0) 999 else it } }.forEach { (name, group) ->
        val c = group.first().category
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryChip(name, c?.color ?: "gray")
            Text("${group.sumOf { it.quantity }} item${if (group.sumOf { it.quantity } == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val (_, edge) = colors(c?.color ?: "gray")
        RaisedCard(radius = 18.dp) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(edge))
                Column(Modifier.weight(1f)) {
                    group.sortedWith(compareBy({ it.name.lowercase() }, { it.details["label"].orEmpty() })).forEachIndexed { i, item ->
                        Column(Modifier.fillMaxWidth().clickable { onItem(item.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                if (item.condition != "serviceable") StatusPill(conditionLabel(item.condition), conditionTone(item.condition))
                                else StatusPill(if (item.owner == "Club property") "Club" else item.owner, if (item.owner == "Club property") Tone.POSITIVE else Tone.INFO)
                            }
                            val tags = invTags(item) + listOfNotNull(item.place?.name?.takeIf { place.isBlank() })
                            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { Tag(it) } }
                            else if (item.category == null) Text(item.row.text("specifications"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.notes.isNotBlank()) Text("✎ ${item.notes}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Club.amberInk)
                        }
                        if (i < group.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    }
                }
            }
        }
    }
}

/** Details an inventory item adds to its page: place, owner, quantity and its category's fields. */
@Composable
fun InventoryDetails(workspace: JsonObject, itemId: String) {
    val item = invItems(workspace).find { it.id == itemId } ?: return
    if (item.category == null && item.place == null) return
    RaisedCard { Column(Modifier.padding(16.dp)) {
        Row(Modifier.padding(bottom = 6.dp)) { CategoryChip(item.categoryName, item.category?.color ?: "gray") }
        item.category?.fields?.forEach { f -> item.details[f.key]?.takeIf { it.isNotBlank() }?.let { KeyValue(f.label, it) } }
        KeyValue("Where", item.place?.name ?: "Not set")
        KeyValue("Owner", item.owner)
        if (item.quantity > 1) KeyValue("Quantity", item.quantity.toString())
        item.row.text("acquired_on").takeIf { it.isNotBlank() }?.let { KeyValue("Acquired", it.take(10)) }
    } }
    if (item.notes.isNotBlank()) Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFF7E3), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("NOTE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = Club.amberInk)
            Text(item.notes, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Whether members may request this item: its category must be lent out (items without one keep the old rule). */
fun lentOut(workspace: JsonObject, item: JsonObject): Boolean =
    invCategories(workspace).find { it.id == item.text("category_id") }?.borrowable ?: true

@Composable
private fun FieldInput(f: InvField, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    when (f.type) {
        "choice" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.label, style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("" to "—") + f.options.map { it to it }, value) { onChange(it) }
        }
        else -> {
            val problem = fieldProblem(f, value)
            OutlinedTextField(value, { onChange(it.take(80)) }, label = { Text(f.label) }, singleLine = true, enabled = enabled,
                placeholder = { Text(when (f.type) { "range" -> "e.g. 68-66"; "number" -> "e.g. 23"; else -> "" }) },
                isError = problem != null, supportingText = problem?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Add or edit one item. Route ids: "new", "new:<category id>" or the item id. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryItemEditScreen(workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, onSetup: () -> Unit) {
    val cats = invCategories(workspace); val places = invPlaces(workspace)
    val existing = invItems(workspace).find { it.id == id }
    if (vm == null || (existing == null && !id.startsWith("new"))) { ScreenShell("Item", onBack, snackbar) { EmptyState("This item is unavailable", "Go back and refresh.") }; return }
    var catId by rememberSaveable(id) { mutableStateOf(existing?.category?.id ?: id.removePrefix("new").removePrefix(":").ifBlank { null } ?: cats.firstOrNull()?.id.orEmpty()) }
    var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
    // Field values kept as a JSON string so they survive rotation.
    var detailsText by rememberSaveable(id) { mutableStateOf(JsonObject(existing?.details.orEmpty().mapValues { JsonPrimitive(it.value) }).toString()) }
    val details = runCatching { Json.parseToJsonElement(detailsText) as JsonObject }.getOrDefault(JsonObject(emptyMap())).mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    var placeId by rememberSaveable(id) { mutableStateOf(existing?.place?.id ?: places.firstOrNull()?.id.orEmpty()) }
    var quantity by rememberSaveable(id) { mutableIntStateOf(existing?.quantity ?: 1) }
    var clubOwned by rememberSaveable(id) { mutableStateOf((existing?.owner ?: "Club property") == "Club property") }
    var owner by rememberSaveable(id) { mutableStateOf(existing?.owner?.takeIf { it != "Club property" }.orEmpty()) }
    var condition by rememberSaveable(id) { mutableStateOf(existing?.condition?.ifBlank { null } ?: "serviceable") }
    var acquired by rememberSaveable(id) { mutableStateOf(existing?.row?.text("acquired_on")?.take(10).orEmpty()) }
    var notes by rememberSaveable(id) { mutableStateOf(existing?.notes.orEmpty()) }
    val cat = cats.find { it.id == catId }
    val problems = cat?.fields.orEmpty().mapNotNull { fieldProblem(it, details[it.key].orEmpty()) }
    val ready = !busy && cat != null && name.isNotBlank() && problems.isEmpty() && (clubOwned || owner.isNotBlank())
    fun save(again: Boolean) {
        vm.save("save_inventory_item", buildJsonObject {
            put("p_data", buildJsonObject {
                existing?.let { put("id", it.id); it.row.text("version").toLongOrNull()?.let { v -> put("version", v) } }
                put("name", name.trim()); put("category_id", catId); if (placeId.isNotBlank()) put("place_id", placeId)
                put("quantity", if (cat?.labelled == true) 1 else quantity); put("owner", if (clubOwned) "Club property" else owner.trim())
                put("condition", condition); if (acquired.isNotBlank()) put("acquired_on", acquired); put("notes", notes.trim())
                put("details", JsonObject(cat?.fields.orEmpty().mapNotNull { f -> details[f.key]?.trim()?.takeIf { it.isNotBlank() }?.let { f.key to JsonPrimitive(it) } }.toMap()))
            })
        }) {
            // "Add another" keeps the category, place and owner and clears the rest, like filling the next sheet row.
            if (again) { name = ""; detailsText = "{}"; notes = ""; quantity = 1 } else onBack()
        }
    }
    ScreenShell(if (existing == null) "Add item" else "Edit item", onBack, snackbar, stickyAction = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (existing == null) OutlinedButton(onClick = { save(true) }, enabled = ready, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp)) {
                Text("Add another", fontWeight = FontWeight.ExtraBold) }
            Button(onClick = { save(false) }, enabled = ready, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Save item", fontWeight = FontWeight.ExtraBold) }
        }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (cats.isEmpty()) { EmptyState("No categories yet", "Set up the club's categories first.", "Set up", onSetup); return@ScreenShell }
        DeskLabel("Category")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cats.forEach { c -> CategoryChip(c.name, c.color, small = false, selected = c.id == catId) { catId = c.id } }
        }
        if (existing != null && existing.category == null) Text("This item came from before categories. Pick one to fill in its details.",
            style = MaterialTheme.typography.bodySmall, color = Club.amberInk)
        DeskLabel("${cat?.name ?: "Item"} details")
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Item name / brand") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        cat?.fields?.forEach { f ->
            FieldInput(f, details[f.key].orEmpty(), !busy) { v ->
                detailsText = JsonObject(details.mapValues { JsonPrimitive(it.value) } + (f.key to JsonPrimitive(v))).toString()
            }
        }
        if (cat?.labelled == true) Text("${cat.name} are labelled one by one: add each piece as its own item.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { quantity = (quantity - 1).coerceAtLeast(1) }, shape = CircleShape) { Text("−") }
            Text("$quantity", fontWeight = FontWeight.ExtraBold)
            OutlinedButton(onClick = { quantity = (quantity + 1).coerceAtMost(9999) }, shape = CircleShape) { Text("+") }
        }
        SelectField("Where it's kept", placeId, listOf("" to "Not set") + places.map { it.id to it.name }) { placeId = it }
        Text("Owner", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("club" to "Club property", "other" to "Someone else"), if (clubOwned) "club" else "other") { clubOwned = it == "club" }
        if (!clubOwned) OutlinedTextField(owner, { owner = it.take(120) }, label = { Text("Owner's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Condition", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("serviceable", "maintenance", "damaged", "retired").map { it to conditionLabel(it) }, condition) { condition = it }
        DateTimeField("Acquired on (optional)", runCatching { LocalDate.parse(acquired).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull(),
            { acquired = it.atZone(ZoneId.systemDefault()).toLocalDate().toString() }, dateOnly = true)
        OutlinedTextField(notes, { notes = it.take(500) }, label = { Text("Note (optional)") }, placeholder = { Text("e.g. confirm if label is right") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

/** Set up inventory: categories and places, in the club's order, and the spreadsheet import. */
@Composable
fun InventorySetupScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, onRoute: (String) -> Unit) {
    val cats = invCategories(workspace); val places = invPlaces(workspace); val items = invItems(workspace)
    var editingPlace by remember { mutableStateOf<InvPlace?>(null) }
    var addingPlace by remember { mutableStateOf(false) }
    fun move(kind: String, ids: List<String>, i: Int, by: Int) {
        val j = i + by; if (vm == null || j !in ids.indices) return
        val list = ids.toMutableList(); val t = list[i]; list[i] = list[j]; list[j] = t
        vm.save("order_inventory", buildJsonObject { put("p_kind", kind); put("p_ids", JsonArray(list.map(::JsonPrimitive))) })
    }
    ScreenShell("Set up inventory", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Your club decides what it tracks. Changes to a category apply to every item in it.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DeskLabel("Categories", "+ New") { onRoute("inv_category:new") }
        RowGroup {
            cats.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().clickable { onRoute("inv_category:${c.id}") }.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column {
                        TextButton(onClick = { move("category", cats.map { it.id }, i, -1) }, enabled = !busy && i > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(22.dp)) { Text("▲") }
                        TextButton(onClick = { move("category", cats.map { it.id }, i, 1) }, enabled = !busy && i < cats.lastIndex, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(22.dp)) { Text("▼") }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row { CategoryChip(c.name, c.color) }
                        Text(c.fields.joinToString(" · ") { it.label }.ifBlank { "Count only" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${items.filter { it.category?.id == c.id }.sumOf { it.quantity }}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i < cats.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        DeskLabel("Places", "+ New") { addingPlace = true }
        RowGroup {
            places.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().clickable { editingPlace = p }.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column {
                        TextButton(onClick = { move("place", places.map { it.id }, i, -1) }, enabled = !busy && i > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(22.dp)) { Text("▲") }
                        TextButton(onClick = { move("place", places.map { it.id }, i, 1) }, enabled = !busy && i < places.lastIndex, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(22.dp)) { Text("▼") }
                    }
                    Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("${items.count { it.place?.id == p.id }} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i < places.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        RaisedCard(onClick = { onRoute("inv_import") }) {
            Column(Modifier.padding(14.dp)) {
                Text("Import from a spreadsheet", fontWeight = FontWeight.ExtraBold)
                Text("Download one sheet tab as CSV (File → Download → .csv) and import it into a place.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val placeDialog = editingPlace
    if ((addingPlace || placeDialog != null) && vm != null) {
        var name by remember(placeDialog?.id) { mutableStateOf(placeDialog?.name.orEmpty()) }
        val count = placeDialog?.let { p -> items.count { it.place?.id == p.id } } ?: 0
        AlertDialog(onDismissRequest = { addingPlace = false; editingPlace = null },
            title = { Text(if (placeDialog == null) "New place" else "Edit place") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, placeholder = { Text("e.g. Tall White Cabinet") }, singleLine = true)
                if (placeDialog != null) {
                    if (count == 0) TextButton(onClick = {
                        vm.save("delete_inventory_group", buildJsonObject { put("p_kind", "place"); put("p_id", placeDialog.id) }) { editingPlace = null }
                    }, enabled = !busy) { Text("Delete this place", color = Club.roseInk, fontWeight = FontWeight.ExtraBold) }
                    else Text("$count item${if (count == 1) " is" else "s are"} kept here, so it can't be deleted. Move them first.", style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton(onClick = {
                vm.save("save_inventory_place", buildJsonObject { put("p_data", buildJsonObject {
                    placeDialog?.let { put("id", it.id); put("version", it.version) }; put("name", name.trim()) }) }) { addingPlace = false; editingPlace = null }
            }, enabled = !busy && name.isNotBlank()) { Text("Save", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { addingPlace = false; editingPlace = null }) { Text("Cancel") } })
    }
}

/** A category's name, colour, fields and rules. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditScreen(workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit) {
    val existing = invCategories(workspace).find { it.id == id }
    if (vm == null || (existing == null && id != "new")) { ScreenShell("Category", onBack, snackbar) { EmptyState("This category is unavailable", "Go back and refresh.") }; return }
    var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable(id) { mutableStateOf(existing?.color ?: "green") }
    var fieldsText by rememberSaveable(id) { mutableStateOf(fieldsJson(existing?.fields.orEmpty()).toString()) }
    val fields = runCatching { (Json.parseToJsonElement(fieldsText) as JsonArray).map { invField(it as JsonObject) } }.getOrDefault(emptyList())
    fun setFields(list: List<InvField>) { fieldsText = fieldsJson(list).toString() }
    var borrowable by rememberSaveable(id) { mutableStateOf(existing?.borrowable ?: true) }
    var labelled by rememberSaveable(id) { mutableStateOf(existing?.labelled ?: false) }
    var match by rememberSaveable(id) { mutableStateOf(existing?.matchArcher ?: false) }
    val count = invItems(workspace).count { it.category?.id == id }
    val labelsOk = fields.all { it.label.isNotBlank() } && fields.map { it.label.trim().lowercase() }.toSet().size == fields.size &&
        fields.none { it.type == "choice" && it.options.none { o -> o.isNotBlank() } }
    ScreenShell(existing?.name ?: "New category", onBack, snackbar, stickyAction = {
        Button(onClick = {
            vm.save("save_inventory_category", buildJsonObject { put("p_data", buildJsonObject {
                existing?.let { put("id", it.id); put("version", it.version) }
                put("name", name.trim()); put("color", color); put("fields", fieldsJson(fields))
                put("borrowable", borrowable); put("labelled", labelled); put("match_archer", match)
            }) }) { onBack() }
        }, enabled = !busy && name.isNotBlank() && labelsOk && fields.size <= 12, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text(if (existing == null) "Create category" else "Save changes", fontWeight = FontWeight.ExtraBold) }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, placeholder = { Text("e.g. Limbs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INV_COLORS.forEach { (key, pair) ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(pair.first))
                    .border(if (key == color) 3.dp else 1.5.dp, if (key == color) Color(pair.second) else Color(0xFFD5DED2), CircleShape)
                    .clickable { color = key })
            }
        }
        Row { CategoryChip(name.ifBlank { "Preview" }, color, small = false) }
        DeskLabel("Fields", if (fields.size < 12) "+ Add a field" else null) { setFields(fields + InvField("", "", "text", emptyList(), true)) }
        if (fields.isEmpty()) Text("No fields: items in this category are just counted, like Stringers.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        fields.forEachIndexed { i, f ->
            fun update(n: InvField) = setFields(fields.mapIndexed { j, x -> if (j == i) n else x })
            RaisedCard(radius = 16.dp) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(f.label, { update(f.copy(label = it.take(40))) }, label = { Text("Field name") }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = f.label.isBlank() || fields.count { it.label.trim().equals(f.label.trim(), ignoreCase = true) } > 1)
                    TextButton(onClick = { if (i > 0) setFields(fields.toMutableList().also { val t = it[i]; it[i] = it[i - 1]; it[i - 1] = t }) }, enabled = i > 0, contentPadding = PaddingValues(4.dp)) { Text("▲") }
                    TextButton(onClick = { if (i < fields.lastIndex) setFields(fields.toMutableList().also { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t }) }, enabled = i < fields.lastIndex, contentPadding = PaddingValues(4.dp)) { Text("▼") }
                    TextButton(onClick = { setFields(fields.filterIndexed { j, _ -> j != i }) }, contentPadding = PaddingValues(4.dp)) { Text("✕", color = Club.roseInk) }
                }
                ChoiceChips(FIELD_TYPES, f.type) { update(f.copy(type = it)) }
                if (f.type == "choice") OutlinedTextField(f.options.joinToString(", "), { update(f.copy(options = it.split(',').map { o -> o.trimStart() })) },
                    label = { Text("Choices, separated by commas") }, placeholder = { Text("S, M, L") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(when (f.type) { "range" -> "A number or a range, e.g. 68-66 or 20-22."; "number" -> "Digits only, e.g. 23."; "choice" -> "Pick one from the list."; else -> "Any short text." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show on the inventory list", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(f.onList, { update(f.copy(onList = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Club.forest))
                }
            } }
        }
        if (existing != null) Text("Renaming a field keeps its values. Deleting a field hides its values; add it back to see them again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DeskLabel("How it's tracked")
        RowGroup {
            listOf(Triple("Members can borrow it", "Shows in Gear for requests", borrowable) to { v: Boolean -> borrowable = v },
                Triple("Each piece has its own label", "Off: just a quantity, like Stringers", labelled) to { v: Boolean -> labelled = v },
                Triple("Match to the archer", "Uses hand and draw weight from their profile", match) to { v: Boolean -> match = v }).forEachIndexed { i, (t, set) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.first, fontWeight = FontWeight.ExtraBold); Text(t.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(t.third, set, colors = SwitchDefaults.colors(checkedTrackColor = Club.forest))
                }
                if (i < 2) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        Text("Every item also has a name, quantity, place, owner, condition, photo and note.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existing != null) {
            if (count == 0) DeleteRecordButton("Delete this category", "Delete ${existing.name}?", "No items are in it.", busy) {
                vm.save("delete_inventory_group", buildJsonObject { put("p_kind", "category"); put("p_id", existing.id) }) { onBack() }
            } else Text("$count item${if (count == 1) " is" else "s are"} in this category, so it can't be deleted.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One spreadsheet tab into one place: pick the CSV, the place, match the columns, import. */
@Composable
fun InventoryImportScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit) {
    val context = LocalContext.current
    val cats = invCategories(workspace); val places = invPlaces(workspace)
    var fileName by remember { mutableStateOf("") }
    var table by remember { mutableStateOf<SheetTable?>(null) }
    var targets by remember { mutableStateOf<List<String>>(emptyList()) }
    var placeId by rememberSaveable { mutableStateOf("") }
    var fallback by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) fileName = it.getString(0) }
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val buffer = CharArray(1_000_001); var count = 0
                while (count < buffer.size) { val n = r.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                require(count <= 1_000_000) { "Choose a CSV smaller than 1 MB." }
                String(buffer, 0, count)
            } ?: error("This file could not be read.")
        }.onSuccess { text ->
            val t = sheetTable(text)
            if (t.headers.isEmpty()) vm?.error("No heading row found. Download the sheet tab as CSV and try again.")
            else {
                table = t; targets = t.headers.map { guessTarget(it, cats) }
                // A file named after a place goes there, e.g. "Inventory (26-27) - Tall White Cabinet.csv".
                places.firstOrNull { fileName.contains(it.name, ignoreCase = true) }?.let { placeId = it.id }
            }
        }.onFailure { vm?.error(it.message ?: "Check the CSV and try again.") }
    }
    val t = table
    val plan = t?.let { inventoryImport(it, targets, cats, fallback.ifBlank { null }) }
    val importButton: (@Composable ColumnScope.() -> Unit)? = if (plan != null && vm != null) ({
        Button(onClick = {
            vm.save("import_inventory", buildJsonObject { if (placeId.isNotBlank()) put("p_place_id", placeId) else put("p_place_id", JsonNull); put("p_rows", JsonArray(plan.rows)) }) { onBack() }
        }, enabled = !busy && plan.rows.isNotEmpty() && plan.rows.size <= 500, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Import ${plan.rows.size} item${if (plan.rows.size == 1) "" else "s"}", fontWeight = FontWeight.ExtraBold) }
    }) else null
    ScreenShell("Import inventory", onBack, snackbar, stickyAction = importButton) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedCard { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(fileName.ifBlank { "No file yet" }, fontWeight = FontWeight.ExtraBold)
                Text(t?.let { "${it.rows.size} rows · ${it.headers.size} columns" } ?: "In Google Sheets: open the tab, File → Download → Comma-separated values (.csv).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel")) }, enabled = !busy,
                shape = RoundedCornerShape(999.dp)) { Text(if (t == null) "Choose CSV" else "Change") }
        } }
        if (t == null || plan == null) return@ScreenShell
        SelectField("Put these items in", placeId, listOf("" to "No place yet") + places.map { it.id to it.name }) { placeId = it }
        if (targets.none { it == "category" }) SelectField("Category for every row", fallback, listOf("" to "Choose a category") + cats.map { it.id to it.name }) { fallback = it }
        DeskLabel("Match the columns")
        val options = ITEM_TARGETS + cats.flatMap { c -> c.fields.map { it.label } }.distinct().map { "field:$it" to it }
        RowGroup {
            t.headers.forEachIndexed { i, h ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.ifBlank { "(blank)" }, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1.2f)) { SelectField("", targets.getOrElse(i) { COL_SKIP }, options) { v -> targets = targets.toMutableList().also { it[i] = v } } }
                }
            }
        }
        Text("A field column fills that field for rows whose category has it, e.g. Label fills Limbs but not Riser.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (plan.blank > 0 || plan.problems.isNotEmpty()) NoticeCard(
            "${plan.rows.size} ready" + if (plan.blank > 0) " · ${plan.blank} blank rows skipped" else "",
            plan.problems.take(8).joinToString("\n") + if (plan.problems.size > 8) "\n…and ${plan.problems.size - 8} more" else "", Tone.WARNING)
        if (plan.rows.size > 500) NoticeCard("Too many rows", "Import up to 500 items at a time. Split the sheet and import it in parts.", Tone.DANGER)
    }
}
