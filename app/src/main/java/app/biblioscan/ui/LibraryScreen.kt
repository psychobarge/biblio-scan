package app.biblioscan.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.R
import app.biblioscan.app
import app.biblioscan.data.Backup
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Status
import app.biblioscan.match.Matching
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Filter(val label: String, val keep: (Status) -> Boolean) {
    ALL("Tous", { true }),
    REVIEW("À vérifier", { it.toReview }),
    PROGRESS("En cours", { it.inProgress }),
    ERRORS("Erreurs", { it == Status.ERROR }),
}

enum class Sort(val label: String) { ADDED("Date d'ajout"), TITLE("Titre"), AUTHOR("Auteur") }

/** Search (accent/case-insensitive, title + authors) then sort. Missing title/author sorts last. */
fun arrange(books: List<Book>, search: String, sort: Sort): List<Book> {
    val q = Matching.normalize(search)
    val found = if (q.isEmpty()) books else books.filter { Matching.normalize("${it.title} ${it.authors}").contains(q) }
    fun key(s: String?) = s?.let(Matching::normalize)?.ifBlank { null }
    return when (sort) {
        Sort.ADDED -> found.sortedByDescending { it.createdAt }
        Sort.TITLE -> found.sortedWith(compareBy(nullsLast()) { key(it.title) })
        Sort.AUTHOR -> found.sortedWith(compareBy(nullsLast()) { key(it.authors?.substringBefore(';')) })
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onOpen: (String) -> Unit,
    onReview: (firstId: String, total: Int) -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = ctx.app.db.books()
    val books by dao.all().collectAsStateWithLifecycle(emptyList())
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var sort by remember { mutableStateOf(runCatching { Sort.valueOf(prefs(ctx).getString("sort", "")!!) }.getOrDefault(Sort.ADDED)) }
    var search by rememberSaveable { mutableStateOf<String?>(null) } // null = search bar closed
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    fun io(label: String, block: suspend () -> String) = scope.launch {
        val msg = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            "$label : échec (${e.message})"
        }
        snackbar.showSnackbar(msg)
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { io("Export") { "${Backup.exportZip(ctx, it)} livres exportés" } }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { io("Export CSV") { "${Backup.exportCsv(ctx, it)} livres exportés" } }
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { io("Import") { "${Backup.importZip(ctx, it)} livres importés" } }
    }
    // System photo picker: no storage permission, only the chosen photos are readable (SPEC-3).
    val importPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // App scope: the copy must finish even if the screen goes away (picker URIs die with the session).
        ctx.app.scope.launch(Dispatchers.Main) {
            launch { snackbar.showSnackbar("Import de ${uris.size} photo${if (uris.size > 1) "s" else ""}…") }
            val r = Books.importPhotos(ctx, uris)
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                "${r.ok} photo${if (r.ok > 1) "s" else ""} importée${if (r.ok > 1) "s" else ""}" +
                    if (r.failed > 0) ", ${r.failed} illisible${if (r.failed > 1) "s" else ""}" else ""
            )
        }
    }
    val pickPhotos = { importPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Same edition scanned twice: flagged, not blocked (you may own two copies).
    val dupes = books.mapNotNull { it.sourceId ?: it.isbn }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val inProgress = books.count { it.status.inProgress }
    val toReview = books.count { it.status.toReview }
    val shown = arrange(books.filter { filter.keep(it.status) }, search.orEmpty(), sort)
    // A new capture or import lands at the top: show it instead of keeping the old scroll anchor.
    val newest = books.maxByOrNull { it.createdAt }?.id
    LaunchedEffect(newest) { if (sort == Sort.ADDED) listState.scrollToItem(0) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Bibliothèque") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { search = if (search == null) "" else null }) {
                        Icon(if (search == null) Icons.Default.Search else Icons.Default.Close, if (search == null) "Rechercher" else "Fermer la recherche")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(painterResource(R.drawable.ic_sort), "Trier") }
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
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Importer des photos") }, onClick = { menu = false; pickPhotos() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Exporter (ZIP)") }, onClick = { menu = false; exportZip.launch("biblioscan.zip") })
                            DropdownMenuItem(text = { Text("Importer (ZIP)") }, onClick = { menu = false; importZip.launch(arrayOf("application/zip")) })
                            DropdownMenuItem(text = { Text("Exporter (CSV)") }, onClick = { menu = false; exportCsv.launch("biblioscan.csv") })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Paramètres") }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                expanded = fabExpanded,
                icon = { Icon(painterResource(R.drawable.ic_camera), null) },
                text = { Text("Scanner") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            search?.let { q ->
                OutlinedTextField(
                    value = q, onValueChange = { search = it }, singleLine = true,
                    placeholder = { Text("Titre ou auteur") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (books.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Filter.entries) { f ->
                    val n = books.count { f.keep(it.status) }
                    FilterChip(
                        selected = filter == f, onClick = { filter = f }, enabled = n > 0 || filter == f,
                        label = { Text("${f.label} ($n)") },
                    )
                }
            }
            if (inProgress > 0) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("$inProgress livre${if (inProgress > 1) "s" else ""} en cours de traitement", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (toReview > 0) Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$toReview livre${if (toReview > 1) "s" else ""} à vérifier", Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { dao.nextToReview(-1)?.let { onReview(it.id, toReview) } }
                    }) { Text("Commencer") }
                }
            }

            when {
                books.isEmpty() -> EmptyState(
                    "Votre bibliothèque est vide",
                    "Photographiez la couverture de vos livres, ou importez des photos de votre galerie : " +
                        "ils seront reconnus automatiquement.\n\nAstuce : le livre doit remplir la photo, sans autre livre visible derrière.",
                    onScan, pickPhotos,
                )
                shown.isEmpty() -> EmptyState("Aucun livre ne correspond", null, null, null)
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { book ->
                        BookRow(book, isDupe = (book.sourceId ?: book.isbn) in dupes, onClick = { onOpen(book.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String?, onScan: (() -> Unit)?, onImport: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        onScan?.let { Button(onClick = it) { Text("Scanner un livre") } }
        onImport?.let { OutlinedButton(onClick = it) { Text("Importer des photos") } }
    }
}

@Composable
private fun BookRow(book: Book, isDupe: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Always the user's own photo (SPEC-4 §1): it is the book actually on the shelf.
        AsyncImage(
            model = Books.photoFile(ctx, book.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (book.status.inProgress && book.title == null) {
                Text("Lecture en cours…", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic)
            } else {
                Text(book.title ?: "Livre sans titre", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            book.authors?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(book.status)
                if (isDupe) Text("doublon possible", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
