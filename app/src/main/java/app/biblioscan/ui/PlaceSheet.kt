package app.biblioscan.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Place
import app.biblioscan.data.Places
import kotlinx.coroutines.launch
import java.util.UUID

const val PLACES_HINT = "Créez des pièces (Salon, Bureau…) puis des emplacements (Étagère noire 1…) pour savoir où est chaque livre."

/**
 * The one place picker (SPEC-7 §5). [current] and [onPick] use scopes: [Places.ALL], [Places.NONE] or a place id.
 * [scopeMode] adds « Toute la bibliothèque ». Managing places is the « Rangements » tab (SPEC-8 §2.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSheet(title: String, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit, scopeMode: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val places by ctx.app.db.places().all().collectAsStateWithLifecycle(emptyList())
    val books by ctx.app.db.books().all().collectAsStateWithLifecycle(emptyList())
    val counts = Places.counts(books, places)
    var adding by remember { mutableStateOf<Place?>(null) } // a blank Place: its parentId says room or spot

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            if (scopeMode) item { PlaceRow("Toute la bibliothèque", counts[Places.ALL], current == Places.ALL) { onPick(Places.ALL) } }
            item { PlaceRow("Non rangé", counts[Places.NONE], current == Places.NONE) { onPick(Places.NONE) } }
            if (places.isEmpty()) item {
                Text(PLACES_HINT, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            Places.tree(places).forEach { (room, spots) ->
                item(key = room.id) { PlaceRow(room.name, counts[room.id], current == room.id) { onPick(room.id) } }
                spots.forEach { s ->
                    item(key = s.id) { PlaceRow(s.name, counts[s.id], current == s.id, room = room.name) { onPick(s.id) } }
                }
                item { AddButton("Emplacement", indent = true) { adding = Place("", room.id, "") } }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { AddButton("Nouvelle pièce") { adding = Place("", null, "") } }
        }
    }

    adding?.let { a ->
        val roomName = a.parentId?.let { id -> places.find { it.id == id }?.name }
        NameDialog(
            title = if (roomName == null) "Nouvelle pièce" else "Nouvel emplacement dans $roomName",
            label = if (roomName == null) "Nom de la pièce" else "Nom de l'emplacement",
            confirm = "Créer",
            error = { Places.nameError(it, a.parentId, places) },
            onDismiss = { adding = null },
        ) { name ->
            adding = null
            val p = Place(UUID.randomUUID().toString(), a.parentId, name.trim())
            // Picked at once (SPEC-7 §5), once it is in the db so the caller can name it.
            scope.launch { ctx.app.db.places().insert(listOf(p)); onPick(p.id) }
        }
    }
}

@Composable
private fun PlaceRow(name: String, count: Int?, selected: Boolean, room: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (room != null) 48.dp else 12.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(
            name,
            Modifier.weight(1f).then(if (room != null) Modifier.semantics { contentDescription = "$name, dans $room" } else Modifier),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("${count ?: 0}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddButton(label: String, indent: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = if (indent) 48.dp else 12.dp)) {
        Icon(Icons.Default.Add, null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** One autofocused field; [error] follows [Places.nameError] (null = ok, "" = blank). */
@Composable
fun NameDialog(title: String, label: String, confirm: String, error: (String) -> String?, onDismiss: () -> Unit, initial: String = "", onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val err = error(name)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(label) },
                isError = !err.isNullOrEmpty(),
                supportingText = err?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (err == null) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = err == null) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Scope label for titles: (room overline or null, name). */
fun scopeTitle(scope: String, places: List<Place>): Pair<String?, String> {
    val p = places.find { it.id == scope }
    return when {
        scope == Places.NONE -> null to "Non rangé"
        p == null -> null to "Bibliothèque"
        else -> p.parentId?.let { id -> places.find { it.id == id }?.name } to p.name
    }
}

