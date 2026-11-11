package app.biblioscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Place
import app.biblioscan.data.Places
import kotlinx.coroutines.launch
import java.util.UUID

/** Which place dialog is open; shared by the « Rangements » tab and the place screen. */
private class PlaceEdit {
    var adding by mutableStateOf<String?>(null) // "" = new room, else the room id of a new spot
    var renaming by mutableStateOf<Place?>(null)
    var deleting by mutableStateOf<Place?>(null)
}

/** Tab « Rangements » (SPEC-8 §2.3): one card per room with its spots; tap one to see its books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(onOpenPlace: (String) -> Unit) {
    val ctx = LocalContext.current
    val places by ctx.app.db.places().all().collectAsStateWithLifecycle(emptyList())
    val books by ctx.app.db.books().all().collectAsStateWithLifecycle(emptyList())
    val counts = Places.counts(books, places)
    val edit = remember { PlaceEdit() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rangements") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { edit.adding = "" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Nouvelle pièce") })
        },
    ) { padding ->
        if (places.isEmpty()) EmptyState("Aucune pièce", PLACES_HINT, Modifier.padding(padding))
        else LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Places.tree(places), key = { it.first.id }) { (room, spots) ->
                PlaceCard {
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Voir les livres") { onOpenPlace(room.id) }.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(room.name, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                        Text(plural(counts[room.id] ?: 0, "livre"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options de ${room.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; edit.renaming = room })
                                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; edit.deleting = room })
                            }
                        }
                    }
                    spots.forEach { s ->
                        HorizontalDivider(Modifier.padding(start = 32.dp))
                        PlaceLine(s.name, counts[s.id] ?: 0, indent = true) { onOpenPlace(s.id) }
                    }
                    TextButton(onClick = { edit.adding = room.id }, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)) {
                        Icon(Icons.Default.Add, null)
                        Text("Emplacement", Modifier.padding(start = 8.dp))
                    }
                }
            }
            item { PlaceCard { PlaceLine("Non rangé", counts[Places.NONE] ?: 0) { onOpenPlace(Places.NONE) } } }
        }
    }

    PlaceDialogs(edit, places, counts)
}

/** Books of one room, spot, or « Non rangé » (SPEC-8 §2.3); rename and delete from ⋮. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceScreen(scope: String, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val loadedPlaces by ctx.app.db.places().all().collectAsStateWithLifecycle(null)
    val places = loadedPlaces.orEmpty()
    val books by ctx.app.db.books().all().collectAsStateWithLifecycle(emptyList())
    val byId = places.associateBy { it.id }
    val place = byId[scope]
    val edit = remember { PlaceEdit() }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    // Deleted (here or elsewhere): nothing left to show.
    LaunchedEffect(loadedPlaces, place) { if (loadedPlaces != null && scope != Places.NONE && place == null) onBack() }

    val shown = arrange(books, "", loadSort(ctx), scope, places)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) SelectionBar(selected, books, snackbar, onSelect = { selected = it }, all = { shown.map { it.id }.toSet() })
            else TopAppBar(
                title = { Text(if (scope == Places.NONE) "Non rangé" else Places.path(scope, byId).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    if (place != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (place.parentId == null) DropdownMenuItem(text = { Text("Ajouter un emplacement") }, onClick = { menu = false; edit.adding = place.id })
                            DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; edit.renaming = place })
                            DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; edit.deleting = place })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                shown.isNotEmpty() -> BookList(
                    books = shown, dupes = dupesOf(books), selected = selected, onSelect = { selected = it }, onOpen = onOpen, state = listState,
                    place = { rowPlace(it, scope, byId, searching = false) },
                )
                scope == Places.NONE -> EmptyState("Tous vos livres sont rangés", null)
                else -> EmptyState("Aucun livre ici", "Validez des livres dans cet emplacement, ou déplacez-en avec un appui long sur un livre.")
            }
        }
    }

    PlaceDialogs(edit, places, Places.counts(books, places))
}

@Composable
private fun PlaceCard(content: @Composable () -> Unit) {
    // surfaceContainerHigh, not tertiaryContainer: a tinted card read as disabled (SPEC-8 §1.3).
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun PlaceLine(name: String, count: Int, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Voir les livres", onClick = onClick)
            .padding(start = if (indent) 32.dp else 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), style = if (indent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium)
        Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Create, rename and delete dialogs (SPEC-7 §6.3 rules: deleting never deletes a book). */
@Composable
private fun PlaceDialogs(edit: PlaceEdit, places: List<Place>, counts: Map<String, Int>) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.places()
    fun io(block: suspend () -> Unit) = ctx.app.scope.launch { block() }
    fun name(id: String?) = places.find { it.id == id }?.name

    edit.adding?.let { a ->
        val parent = a.ifEmpty { null }
        NameDialog(
            title = parent?.let { "Nouvel emplacement dans ${name(it)}" } ?: "Nouvelle pièce",
            label = if (parent == null) "Nom de la pièce" else "Nom de l'emplacement",
            confirm = "Créer",
            error = { Places.nameError(it, parent, places) },
            onDismiss = { edit.adding = null },
        ) { n ->
            edit.adding = null
            io { dao.insert(listOf(Place(UUID.randomUUID().toString(), parent, n.trim()))) }
        }
    }

    edit.renaming?.let { p ->
        NameDialog(
            title = "Renommer",
            label = if (p.parentId == null) "Nom de la pièce" else "Nom de l'emplacement",
            confirm = "Renommer",
            initial = p.name,
            error = { Places.nameError(it, p.parentId, places, selfId = p.id) },
            onDismiss = { edit.renaming = null },
        ) { n ->
            edit.renaming = null
            io { dao.rename(p.id, n.trim()) }
        }
    }

    edit.deleting?.let { p ->
        val n = counts[p.id] ?: 0
        val livres = plural(n, "livre")
        val spots = places.count { it.parentId == p.id }
        val title = if (p.parentId == null && spots > 0) "Supprimer « ${p.name} » et ${if (spots > 1) "ses $spots emplacements" else "son emplacement"} ?" else "Supprimer « ${p.name} » ?"
        val text = when {
            n == 0 -> null
            p.parentId != null -> "${if (n > 1) "Ses" else "Son"} $livres ${if (n > 1) "resteront" else "restera"} dans ${name(p.parentId)}."
            else -> "${if (n > 1) "Ses" else "Son"} $livres ${if (n > 1) "passeront" else "passera"} dans Non rangé."
        }
        AlertDialog(
            onDismissRequest = { edit.deleting = null },
            title = { Text(title) },
            text = text?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = {
                    edit.deleting = null
                    if (loadScope(ctx) == p.id) saveScope(ctx, p.parentId ?: Places.ALL)
                    io { dao.delete(p) }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { edit.deleting = null }) { Text("Annuler") } },
        )
    }
}
