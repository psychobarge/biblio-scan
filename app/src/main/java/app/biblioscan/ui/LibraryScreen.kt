package app.biblioscan.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.R
import app.biblioscan.app
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Place
import app.biblioscan.data.Places
import app.biblioscan.data.Status
import app.biblioscan.match.Matching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class Sort(val label: String) { ADDED("Date d'ajout"), TITLE("Titre"), AUTHOR("Auteur") }

/**
 * Without search: validated books of the scope. With a search: every book, validated or not, whatever
 * the scope (SPEC-7 §3.3), accent/case-insensitive on title + authors. Missing title/author sorts last.
 */
fun arrange(books: List<Book>, search: String, sort: Sort, scope: String = Places.ALL, places: List<Place> = emptyList()): List<Book> {
    val q = Matching.normalize(search)
    val byId = places.associateBy { it.id }
    val found = if (q.isEmpty()) books.filter { it.status == Status.CONFIRMED && Places.inScope(it.placeId, scope, byId) }
    else books.filter { Matching.normalize("${it.title} ${it.authors}").contains(q) }
    fun key(s: String?) = s?.let(Matching::normalize)?.ifBlank { null }
    return when (sort) {
        Sort.ADDED -> found.sortedByDescending { it.createdAt }
        Sort.TITLE -> found.sortedWith(compareBy(nullsLast()) { key(it.title) })
        Sort.AUTHOR -> found.sortedWith(compareBy(nullsLast()) { key(it.authors?.substringBefore(';')) })
    }
}

/** The location line of a row: only what the scope does not already say (SPEC-7 §3.4). */
fun rowPlace(b: Book, scope: String, byId: Map<String, Place>, searching: Boolean): String? {
    if (b.status != Status.CONFIRMED) return null
    val p = b.placeId?.let(byId::get) ?: return null
    return when {
        searching || scope == Places.ALL -> Places.path(p.id, byId)
        scope == p.parentId -> p.name
        else -> null
    }
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
fun loadScope(ctx: Context): String = prefs(ctx).getString("scope", null) ?: Places.ALL
fun saveScope(ctx: Context, scope: String) = prefs(ctx).edit().putString("scope", scope).apply()
fun loadSort(ctx: Context) = runCatching { Sort.valueOf(prefs(ctx).getString("sort", "")!!) }.getOrDefault(Sort.ADDED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onOpen: (String) -> Unit,
    onInbox: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val books by ctx.app.db.books().all().collectAsStateWithLifecycle(emptyList())
    val loadedPlaces by ctx.app.db.places().all().collectAsStateWithLifecycle(null)
    val places = loadedPlaces.orEmpty()
    var sort by remember { mutableStateOf(loadSort(ctx)) }
    var savedScope by remember { mutableStateOf(loadScope(ctx)) }
    // A place deleted elsewhere: show everything (only once places are loaded, or the scope would flicker).
    val where = savedScope.takeUnless { s -> loadedPlaces != null && Places.placeIdOf(s)?.let { id -> places.none { it.id == id } } == true } ?: Places.ALL
    fun setScope(s: String) { savedScope = s; saveScope(ctx, s) }
    var search by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(where) { listState.scrollToItem(0) }

    // System photo picker: no storage permission, only the chosen photos are readable (SPEC-3).
    val importPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // App scope: the copy must finish even if the screen goes away (picker URIs die with the session).
        ctx.app.scope.launch(Dispatchers.Main) {
            launch { snackbar.showSnackbar("Import de ${uris.size} photo${if (uris.size > 1) "s" else ""}…") }
            val r = Books.importPhotos(ctx, uris)
            snackbar.currentSnackbarData?.dismiss()
            val msg = "${r.ok} photo${if (r.ok > 1) "s" else ""} importée${if (r.ok > 1) "s" else ""}" +
                if (r.failed > 0) ", ${r.failed} illisible${if (r.failed > 1) "s" else ""}" else ""
            // The new books wait in « À valider » (SPEC-7 §6.1).
            if (snackbar.showSnackbar(msg, if (r.ok > 0) "Voir" else null) == SnackbarResult.ActionPerformed) onInbox()
        }
    }
    val pickPhotos = { importPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val pending = books.count { it.status != Status.CONFIRMED }
    val searching = search.isNotBlank()
    val shown = arrange(books, search, sort, where, places)
    val byId = places.associateBy { it.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) SelectionBar(
                selected, books, snackbar, onSelect = { selected = it },
                all = { shown.filter { it.status == Status.CONFIRMED }.map { it.id }.toSet() },
            ) else Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 4.dp)) {
                // Search is the main way to answer « où est ce livre ? »: always visible (SPEC-8 §2.1).
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        placeholder = { Text("Rechercher un livre") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Effacer la recherche") } },
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Importer des photos") }, onClick = { menu = false; pickPhotos() })
                            DropdownMenuItem(text = { Text("Paramètres") }, onClick = { menu = false; onSettings() })
                        }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The place filter is a chip, not a dropdown hidden in the title (SPEC-8 §2.1).
                    FilterChip(
                        selected = where != Places.ALL,
                        onClick = { sheet = true },
                        label = {
                            Text(
                                if (where == Places.NONE) "Non rangé" else Places.path(where, byId) ?: "Tous les rangements",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (where == Places.ALL) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            else Icon(
                                Icons.Default.Close, "Afficher tous les rangements",
                                Modifier.size(18.dp).clickable(role = Role.Button) { setScope(Places.ALL) },
                            )
                        },
                    )
                    Box {
                        FilterChip(
                            selected = false, onClick = { sortMenu = true },
                            label = { Text(sort.label) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Default.Check, "Tri actuel") },
                                    onClick = {
                                        sort = s
                                        prefs(ctx).edit().putString("sort", s.name).apply()
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (searching) Text(
                    "Recherche dans toute la bibliothèque",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = onScan,
                expanded = fabExpanded,
                icon = { Icon(painterResource(R.drawable.ic_camera), null) },
                text = { Text("Scanner") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                books.isEmpty() -> EmptyState(
                    "Votre bibliothèque est vide",
                    "Photographiez la couverture de vos livres, ou importez des photos de votre galerie : " +
                        "ils seront reconnus automatiquement.\n\nAstuce : le livre doit remplir la photo, sans autre livre visible derrière.",
                    icon = painterResource(R.drawable.ic_camera),
                ) {
                    Button(onClick = onScan) { Text("Scanner un livre") }
                    OutlinedButton(onClick = pickPhotos) { Text("Importer des photos") }
                }
                shown.isEmpty() && searching -> EmptyState("Aucun livre ne correspond", null)
                pending == books.size && !searching -> EmptyState(
                    "Aucun livre validé pour l'instant",
                    "Les livres scannés attendent dans « À valider » : validez-les pour les ranger dans la bibliothèque.",
                ) { Button(onClick = onInbox) { Text(if (pending > 1) "Voir les $pending livres à valider" else "Voir le livre à valider") } }
                shown.isEmpty() && where == Places.NONE -> EmptyState("Tous vos livres sont rangés", null)
                shown.isEmpty() -> EmptyState("Aucun livre ici", "Validez des livres dans cet emplacement, ou déplacez-en avec un appui long sur un livre.")
                else -> BookList(
                    books = shown, dupes = dupesOf(books), selected = selected, onSelect = { selected = it }, onOpen = onOpen, state = listState,
                    place = { rowPlace(it, where, byId, searching) },
                    selectable = { it.status == Status.CONFIRMED }, // moving is for library books only
                )
            }
        }
    }

    if (sheet) PlaceSheet(
        title = "Afficher", current = where, scopeMode = true,
        onPick = { sheet = false; setScope(it) },
        onDismiss = { sheet = false },
    )
}
