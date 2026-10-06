package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.iol_explorer_copied
import weil.app.sharedui.generated.resources.iol_explorer_copy
import weil.app.sharedui.generated.resources.iol_explorer_data
import weil.app.sharedui.generated.resources.iol_explorer_each
import weil.app.sharedui.generated.resources.iol_explorer_empty
import weil.app.sharedui.generated.resources.iol_explorer_err_auth
import weil.app.sharedui.generated.resources.iol_explorer_err_missing
import weil.app.sharedui.generated.resources.iol_explorer_err_other
import weil.app.sharedui.generated.resources.iol_explorer_err_server
import weil.app.sharedui.generated.resources.iol_explorer_fields
import weil.app.sharedui.generated.resources.iol_explorer_filter
import weil.app.sharedui.generated.resources.iol_explorer_items
import org.jetbrains.compose.resources.pluralStringResource
import weil.app.sharedui.generated.resources.iol_explorer_items_filtered
import weil.app.sharedui.generated.resources.iol_explorer_loading
import weil.app.sharedui.generated.resources.iol_explorer_meta
import weil.app.sharedui.generated.resources.iol_explorer_missing
import weil.app.sharedui.generated.resources.iol_explorer_not_json
import weil.app.sharedui.generated.resources.iol_explorer_ok
import weil.app.sharedui.generated.resources.iol_explorer_operation
import weil.app.sharedui.generated.resources.iol_explorer_raw
import weil.app.sharedui.generated.resources.iol_explorer_retry
import weil.app.sharedui.generated.resources.iol_explorer_root
import weil.app.sharedui.generated.resources.iol_explorer_seen
import weil.app.sharedui.generated.resources.iol_explorer_status
import weil.app.sharedui.generated.resources.iol_type_array
import weil.app.sharedui.generated.resources.iol_type_boolean
import weil.app.sharedui.generated.resources.iol_type_decimal
import weil.app.sharedui.generated.resources.iol_type_empty
import weil.app.sharedui.generated.resources.iol_type_integer
import weil.app.sharedui.generated.resources.iol_type_null
import weil.app.sharedui.generated.resources.iol_type_object
import weil.app.sharedui.generated.resources.iol_type_string
import weil.app.sharedui.generated.resources.iol_value_no
import weil.app.sharedui.generated.resources.iol_value_yes

/**
 * One IOL answer, or one node inside it ([IolResponseRoute.pointer]): a list
 * is a list of rows, each opening its item on a screen of its own; an item
 * is a card of label/value pairs, its inner lists again rows that open.
 * The answer itself (pointer "") also carries what was asked, how IOL
 * answered, and the two other readings: **Campos** (every field, its type,
 * an example, whether it is optional) and the **JSON**.
 */
@Composable
fun IolResponseScreen(
    state: IolExplorerState,
    route: IolResponseRoute,
    onNavigateBack: () -> Unit,
    onOpen: (IolResponseRoute) -> Unit,
    onSymbol: (IolSymbolRoute) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(Res.string.iol_explorer_copied)
    LaunchedEffect(route.path) { state.run(route.path) }
    val result = state.result(route.path)
    val failure = state.failure(route.path)
    val isRoot = route.pointer.isEmpty()
    var view by rememberSaveable(route) {
        mutableStateOf(ResponseView.entries.firstOrNull { it.name.equals(route.view, ignoreCase = true) } ?: ResponseView.Data)
    }
    val node = result?.json?.at(route.pointer)
    var filter by rememberSaveable(route) { mutableStateOf("") }

    val operationTitle = stringResource(Res.string.iol_explorer_operation, "%s")
    val actions = ValueActions(
        // The list of operations links its numbers; an operation's own page doesn't link to itself.
        inOperations = route.path.contains("/operaciones", ignoreCase = true) &&
            !Regex("""/operaciones/\d+""", RegexOption.IGNORE_CASE).containsMatchIn(route.path),
        openOperation = { n -> onOpen(IolResponseRoute("/api/v2/operaciones/$n", operationTitle.replace("%s", n))) },
        openSymbol = onSymbol,
        copy = { clipboard.setText(AnnotatedString(it)); Feedback.show(copied) },
        openChild = { pointer, title -> onOpen(IolResponseRoute(route.path, title, pointer)) },
    )

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            title = route.title,
            onNavigateBack = onNavigateBack,
            actions = {
                if (result != null) {
                    IconButton(onClick = {
                        val text = when {
                            isRoot && view == ResponseView.Fields -> result.fieldsTable()
                            node != null -> prettyJson(node)
                            else -> result.response.body
                        }
                        clipboard.setText(AnnotatedString(text))
                        Feedback.show(copied)
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(Res.string.iol_explorer_copy))
                    }
                }
                if (isRoot && result != null && !state.isRunning(route.path)) {
                    IconButton(onClick = { state.run(route.path, force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.iol_explorer_retry))
                    }
                }
            },
        )

        when {
            result == null && failure != null -> Centered {
                Text(failure, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { state.run(route.path, force = true) }) {
                    Text(stringResource(Res.string.iol_explorer_retry))
                }
            }
            result == null -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.iol_explorer_loading), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                if (isRoot) {
                    item(key = "summary") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            SummaryCard(result)
                            SegmentedSwitch(
                                options = ResponseView.entries,
                                selected = view,
                                label = { stringResource(it.label) },
                                onSelect = { view = it },
                            )
                        }
                    }
                }
                when {
                    isRoot && view == ResponseView.Raw -> items(result.lines.size, key = { "line-$it" }) { i ->
                        Text(result.lines[i], style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    result.json == null -> item(key = "not-json") { Note(stringResource(Res.string.iol_explorer_not_json)) }
                    isRoot && view == ResponseView.Fields -> fieldsSection(result)
                    node == null -> item(key = "missing") { Note(stringResource(Res.string.iol_explorer_missing)) }
                    else -> nodeSection(node, route.pointer, isRoot, actions, ListFilter(filter) { filter = it })
                }
            }
        }
    }
}

private enum class ResponseView(val label: StringResource) {
    Data(Res.string.iol_explorer_data),
    Fields(Res.string.iol_explorer_fields),
    Raw(Res.string.iol_explorer_raw),
}

/** What tapping a value does: order numbers and symbols open, children open, the rest is copied. */
private class ValueActions(
    val inOperations: Boolean,
    val openOperation: (String) -> Unit,
    val openSymbol: (IolSymbolRoute) -> Unit,
    val copy: (String) -> Unit,
    val openChild: (pointer: String, title: String) -> Unit,
)

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What was asked, whether IOL liked it, and in plain words why not when it didn't. */
@Composable
private fun SummaryCard(result: ExplorerResult) {
    val response = result.response
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "GET ${response.path}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Pill(
                    if (result.ok) stringResource(Res.string.iol_explorer_ok) else stringResource(Res.string.iol_explorer_status, response.status),
                    container = if (result.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    content = if (result.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.iol_explorer_meta, response.elapsedMs.toInt(), sizeLabel(response.body.length), result.fields.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!result.ok) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        when (response.status) {
                            401, 403 -> Res.string.iol_explorer_err_auth
                            404 -> Res.string.iol_explorer_err_missing
                            in 500..599 -> Res.string.iol_explorer_err_server
                            else -> Res.string.iol_explorer_err_other
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                result.errorMessage?.let {
                    Text("«$it»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * Datos for one node. A list is rows; an object is a card — except the
 * answer's root when it only wraps a list (`{"pais", "activos": […]}`),
 * whose few fields go in a card above the list's rows, so the portfolio
 * opens on the positions and not on a «activos» row to tap first.
 */
private fun LazyListScope.nodeSection(node: JsonElement, pointer: String, isRoot: Boolean, actions: ValueActions, filter: ListFilter) {
    when (node) {
        is JsonArray -> listSection(node, pointer, actions, filter)
        is JsonObject -> {
            val wrapped = if (isRoot) wrappedListKey(node) else null
            if (wrapped == null) {
                item(key = "record") { RecordCard(node, pointer, actions) }
            } else {
                val rest = JsonObject(node.filterKeys { it != wrapped })
                if (rest.isNotEmpty()) item(key = "record") { RecordCard(rest, pointer, actions) }
                listSection(node[wrapped] as JsonArray, childPointer(pointer, wrapped), actions, filter, caption = wrapped)
            }
        }
        else -> item(key = "value") {
            ValueCard { ValueRow(stringResource(Res.string.iol_explorer_root), node, null, actions) }
        }
    }
}

/** The array a root object exists to carry: a list of objects next to at most two other fields. */
private fun wrappedListKey(obj: JsonObject): String? =
    obj.takeIf { it.size <= 3 }?.entries
        ?.firstOrNull { (_, v) -> v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject } }?.key

private fun LazyListScope.listSection(
    list: JsonArray,
    pointer: String,
    actions: ValueActions,
    filter: ListFilter,
    caption: String? = null,
) {
    if (list.isEmpty()) {
        item(key = "empty") { Note(stringResource(Res.string.iol_explorer_empty)) }
        return
    }
    // A list of plain values (dates, symbols): one card, nothing to open.
    if (list.none { it is JsonObject || it is JsonArray }) {
        item(key = "values") {
            ValueCard { list.forEachIndexed { i, v -> ValueRow("${i + 1}", v, null, actions) } }
        }
        return
    }
    val needle = filter.text.trim().lowercase()
    val visible = list.indices.filter { needle.isEmpty() || needle in list[it].toString().lowercase() }
    item(key = "list-header") {
        Column {
            Text(
                (caption?.let { "$it · " } ?: "") + if (needle.isEmpty()) {
                    pluralStringResource(Res.plurals.iol_explorer_items, list.size, list.size)
                } else {
                    stringResource(Res.string.iol_explorer_items_filtered, visible.size, list.size)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (list.size > 8) {
                OutlinedTextField(
                    value = filter.text,
                    onValueChange = filter.onChange,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(Res.string.iol_explorer_filter)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
        }
    }
    items(visible, key = { "item-$it" }) { i ->
        val item = list[i]
        val (title, subtitle) = itemTitle(item, i)
        ExplorerRow(
            icon = null,
            title = title,
            subtitle = subtitle,
            trailing = headline(item),
            onClick = { actions.openChild(childPointer(pointer, "$i"), title) },
        )
    }
}

/** The list's filter, held by the screen (a lazy list's builder can't remember anything). */
private class ListFilter(val text: String, val onChange: (String) -> Unit)

@Composable
private fun ValueCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        // The bottom gap matches the list rows' own, so cards and rows stack evenly.
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

/**
 * An object as label/value rows. Nested objects are titled blocks in the
 * same card (a position's `titulo`); nested lists of objects are rows that
 * open on their own screen (an order's `aranceles`).
 */
@Composable
private fun RecordCard(obj: JsonObject, pointer: String, actions: ValueActions) {
    ValueCard { RecordRows(obj, pointer, actions) }
}

@Composable
private fun RecordRows(obj: JsonObject, pointer: String, actions: ValueActions) {
    obj.forEach { (key, value) ->
        val child = childPointer(pointer, key)
        when {
            value is JsonObject && value.isNotEmpty() -> {
                Text(
                    key,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) { RecordRows(value, child, actions) }
            }
            value is JsonArray && value.any { it is JsonObject || it is JsonArray } -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { actions.openChild(child, key) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pluralStringResource(Res.plurals.iol_explorer_items, value.size, value.size),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            else -> ValueRow(key, value, obj, actions)
        }
    }
}

/** One field: its name as IOL spells it, and its value as a person reads it. */
@Composable
private fun ValueRow(key: String, value: JsonElement, parent: JsonObject?, actions: ValueActions) {
    val raw = when (value) {
        is JsonPrimitive -> value.takeIf { it !is JsonNull }?.content
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }.ifEmpty { null }
        else -> null
    }
    val shown = when {
        raw.isNullOrEmpty() -> "—"
        value is JsonPrimitive && value.isString -> friendlyTimestamp(raw) ?: raw
        value is JsonPrimitive && value.booleanOrNull != null ->
            stringResource(if (value.booleanOrNull == true) Res.string.iol_value_yes else Res.string.iol_value_no)
        // Identifiers keep their digits as IOL prints them: «185.135.140» is not an order number.
        value is JsonPrimitive && isIdentifier(key) -> raw
        value is JsonPrimitive -> friendlyNumber(raw)
        else -> raw
    }
    val operation = raw != null && key == "numero" && actions.inOperations && raw.all { it.isDigit() }
    val symbol = raw != null && key == "simbolo"
    val link = operation || symbol
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = raw != null) {
                when {
                    operation -> actions.openOperation(raw!!)
                    symbol -> {
                        val market = (parent?.get("mercado") as? JsonPrimitive)?.content?.lowercase()?.takeIf { it.isNotBlank() } ?: "bcba"
                        val name = (parent?.get("descripcion") as? JsonPrimitive)?.content
                        actions.openSymbol(IolSymbolRoute(raw!!.uppercase(), market, name = name))
                    }
                    else -> actions.copy(raw!!)
                }
            }
            .padding(vertical = 8.dp),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (link) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                link -> MaterialTheme.colorScheme.primary
                shown == "—" -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f),
        )
        if (link) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun isIdentifier(key: String): Boolean =
    key == "numero" || key == "tipo" || key.endsWith("id") || key.endsWith("Id") || key.startsWith("codigo") || key.startsWith("nro")

/** A row's heading: what the item is (symbol, order number, name), and what kind. */
private fun itemTitle(item: JsonElement, index: Int): Pair<String, String?> {
    val obj = item as? JsonObject ?: return "#${index + 1}" to null
    fun text(vararg path: String): String? {
        var node: JsonElement? = obj
        for (p in path) node = (node as? JsonObject)?.get(p)
        return (node as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
    }
    val date = (text("fechaHora") ?: text("fecha") ?: text("fechaOrden"))?.let { friendlyTimestamp(it) ?: it }
    val title = text("simbolo") ?: text("titulo", "simbolo") ?: text("numero")?.let { "#$it" }
        ?: text("nombre") ?: text("descripcion") ?: text("detalle") ?: text("tipo") ?: date ?: "#${index + 1}"
    val subtitle = listOfNotNull(
        text("descripcion")?.takeIf { it != title } ?: text("titulo", "descripcion"),
        text("tipo")?.takeIf { it != title },
        text("estado"),
        date?.takeIf { it != title },
    ).distinct().take(2).joinToString(" · ").ifEmpty { null }
    return title to subtitle
}

/** The one figure worth showing at the end of a row: what it is worth, or its price. */
private fun headline(item: JsonElement): String? {
    val obj = item as? JsonObject ?: return null
    val keys = listOf("valorizado", "montoOperado", "monto", "saldo", "total", "ultimoPrecio", "ultimoOperado", "ultimoCierre", "precio", "cantidad")
    for (key in keys) {
        val v = obj[key] as? JsonPrimitive ?: continue
        if (v is JsonNull || v.isString) continue
        return friendlyNumber(v.content)
    }
    return null
}

/**
 * Campos: every field, grouped under the object it belongs to, with its
 * type in words, a real example and whether some items leave it out.
 */
private fun LazyListScope.fieldsSection(result: ExplorerResult) {
    val groups = result.fields
        .filterNot { "object" in it.types && it.types.all { t -> t == "object" || t == "null" } }
        .groupBy { parentOf(it.path) }
    groups.forEach { (parent, fields) ->
        item(key = "group-$parent") {
            ValueCard {
                Text(
                    when {
                        parent.isEmpty() -> stringResource(Res.string.iol_explorer_root)
                        parent.endsWith("[]") -> stringResource(Res.string.iol_explorer_each, parent.removeSuffix("[]"))
                        else -> parent
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (parent.isNotEmpty()) {
                    Text(
                        parent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                fields.forEach { FieldRow(it, result.parentCount(it)) }
            }
        }
    }
}

/** `activos[].titulo.simbolo` → `activos[].titulo`. */
private fun parentOf(path: String): String {
    val cut = path.lastIndexOf('.')
    return if (cut < 0) "" else path.substring(0, cut)
}

@Composable
private fun FieldRow(field: JsonField, parentCount: Int?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                field.path.substringAfterLast('.'),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            field.types.forEach { type ->
                Spacer(Modifier.width(4.dp))
                Pill(
                    stringResource(typeLabel(type)),
                    container = if (type == "null") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (type == "null") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        field.example?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (parentCount != null && field.count < parentCount) {
            Text(
                stringResource(Res.string.iol_explorer_seen, field.count, parentCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun typeLabel(type: String): StringResource = when (type) {
    "string" -> Res.string.iol_type_string
    "integer" -> Res.string.iol_type_integer
    "decimal" -> Res.string.iol_type_decimal
    "boolean" -> Res.string.iol_type_boolean
    "object" -> Res.string.iol_type_object
    "array" -> Res.string.iol_type_array
    "array (vacío)" -> Res.string.iol_type_empty
    else -> Res.string.iol_type_null
}

private fun sizeLabel(chars: Int): String = when {
    chars < 1024 -> "$chars B"
    chars < 1024 * 1024 -> "${chars / 1024} KB"
    else -> "${chars / (1024 * 1024)} MB"
}
