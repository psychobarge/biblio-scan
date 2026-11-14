package app.biblioscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.biblioscan.R
import app.biblioscan.app
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Places
import app.biblioscan.data.Status
import app.biblioscan.match.Matching
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Same edition scanned twice, over the whole library: flagged, not blocked (you may own two copies). */
fun dupesOf(books: List<Book>) = books.mapNotNull { it.sourceId ?: it.isbn }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

fun plural(n: Int, word: String) = "$n $word${if (n > 1) "s" else ""}"

/** Snackbar with « Annuler »; runs [undo] if tapped. */
suspend fun SnackbarHostState.undoable(msg: String, undo: suspend () -> Unit) {
    if (showSnackbar(msg, "Annuler", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) undo()
}

/** " dans Étagère noire 1" for a snackbar; reads the db so a place created a moment ago is found. */
suspend fun whereLabel(ctx: android.content.Context, scope: String): String =
    if (scope == Places.NONE) " dans Non rangé"
    else ctx.app.db.places().snapshot().find { it.id == scope }?.let { " dans ${it.name}" }.orEmpty()

/**
 * Book list shared by the library and « À valider »: swipe to delete (SPEC-6 §3) and long press to select (SPEC-7 §3.5).
 * [selectable] says which rows may join a selection; [place] is the location line of a row;
 * [group] puts a header above each run of rows with the same label.
 */
@Composable
fun BookList(
    books: List<Book>,
    dupes: Set<String>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    onOpen: (String) -> Unit,
    state: LazyListState,
    place: (Book) -> String? = { null },
    selectable: (Book) -> Boolean = { true },
    group: ((Book) -> String)? = null,
) {
    val ctx = LocalContext.current
    var openId by remember { mutableStateOf<String?>(null) } // row swiped open on its trash icon
    var toDelete by remember { mutableStateOf<Book?>(null) }
    val selecting = selected.isNotEmpty()
    fun toggle(b: Book) { if (selectable(b)) onSelect(if (b.id in selected) selected - b.id else selected + b.id) }

    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
        books.forEachIndexed { i, book ->
            val header = group?.invoke(book)
            if (header != null && (i == 0 || group(books[i - 1]) != header)) item(key = "header:$header") {
                Text(
                    header, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = book.id) {
            val open = openId == book.id
            SwipeToRevealDelete(
                open = open,
                enabled = !selecting,
                onOpenChange = { openId = if (it) book.id else if (open) null else openId },
                onDelete = { toDelete = book },
            ) {
                BookRow(
                    book,
                    isDupe = (book.sourceId ?: book.isbn) in dupes,
                    place = place(book),
                    selected = if (selecting) book.id in selected else null,
                    // An open row closes on tap instead of opening the book.
                    onClick = { if (open) openId = null else if (selecting) toggle(book) else onOpen(book.id) },
                    onLongClick = if (selectable(book)) ({ openId = null; toggle(book) }) else null,
                )
            }
            HorizontalDivider()
            }
        }
    }

    // Same dialog as the book screen; cancelling also slides the row back.
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null; openId = null },
            title = { Text("Supprimer ce livre ?") },
            text = { Text("La fiche et la photo seront effacées définitivement.") },
            confirmButton = {
                TextButton(onClick = { toDelete = null; openId = null; ctx.app.scope.launch { Books.delete(ctx, b.id) } }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null; openId = null }) { Text("Annuler") } },
        )
    }
}

/**
 * Contextual top bar of a selection (SPEC-8 §2.5): select all, then move and set the series — or validate in « À valider » —, then delete.
 * Owns its place sheet, its dialog and the back handler that leaves the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(selected: Set<String>, books: List<Book>, snackbar: SnackbarHostState, onSelect: (Set<String>) -> Unit, all: () -> Set<String>, validate: Boolean = false) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.books()
    var sheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var seriesDialog by remember { mutableStateOf(false) }
    BackHandler { onSelect(emptySet()) }
    TopAppBar(
        title = { Text("${selected.size} sélectionné${if (selected.size > 1) "s" else ""}") },
        navigationIcon = { IconButton(onClick = { onSelect(emptySet()) }) { Icon(Icons.Default.Close, "Quitter la sélection") } },
        actions = {
            IconButton(onClick = { onSelect(all()) }) { Icon(painterResource(R.drawable.ic_select_all), "Tout sélectionner") }
            if (validate) IconButton(onClick = { sheet = true }) { Icon(Icons.Default.Check, "Valider et ranger") }
            else {
                IconButton(onClick = { sheet = true }) { Icon(Icons.Default.Place, "Déplacer") }
                IconButton(onClick = { seriesDialog = true }) { Icon(Icons.AutoMirrored.Filled.List, "Mettre dans une collection") }
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
        },
    )

    if (sheet) PlaceSheet(
        title = if (validate) "Valider et ranger dans" else "Déplacer vers", current = "",
        onPick = { s ->
            sheet = false
            val chosen = books.filter { it.id in selected }
            onSelect(emptySet())
            ctx.app.scope.launch(Dispatchers.Main) {
                val where = whereLabel(ctx, s)
                if (validate) {
                    // Only sure matches are validated in bulk (SPEC-7 §4.3); the rest stays for the one-by-one review.
                    val n = dao.validate(chosen.map { it.id }, Places.placeIdOf(s))
                    val rest = (chosen.size - n).let { if (it > 0) " · $it à vérifier un par un" else "" }
                    if (n == 0) snackbar.showSnackbar("Aucun livre trouvé avec certitude : validez-les un par un")
                    else snackbar.undoable("${plural(n, "livre")} validé${if (n > 1) "s" else ""}$where$rest") {
                        dao.update(chosen.filter { it.status == Status.MATCHED })
                    }
                } else {
                    dao.moveTo(chosen.map { it.id }, Places.placeIdOf(s))
                    val n = chosen.size
                    snackbar.undoable("${plural(n, "livre")} déplacé${if (n > 1) "s" else ""}$where") { dao.update(chosen) }
                }
            }
        },
        onDismiss = { sheet = false },
    )

    if (seriesDialog) {
        val chosen = books.filter { it.id in selected }
        SeriesDialog(
            initial = chosen.map { it.series }.distinct().singleOrNull().orEmpty(),
            existing = books.mapNotNull { it.series?.trim()?.ifBlank { null } }.distinctBy(Matching::normalize).sortedBy(Matching::normalize),
            onDismiss = { seriesDialog = false },
            onSave = { name ->
                seriesDialog = false
                onSelect(emptySet())
                ctx.app.scope.launch(Dispatchers.Main) {
                    dao.setSeries(chosen.map { it.id }, name)
                    val n = chosen.size
                    val msg = if (name == null) "${plural(n, "livre")} retiré${if (n > 1) "s" else ""} de leur collection" else "${plural(n, "livre")} dans « $name »"
                    snackbar.undoable(msg) { dao.update(chosen) }
                }
            },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (selected.size > 1) "Supprimer ${selected.size} livres ?" else "Supprimer ce livre ?") },
        text = { Text(if (selected.size > 1) "Les fiches et les photos seront effacées définitivement." else "La fiche et la photo seront effacées définitivement.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                val ids = selected.toList()
                onSelect(emptySet())
                ctx.app.scope.launch { ids.forEach { Books.delete(ctx, it) } }
            }) { Text("Supprimer") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

private enum class Reveal { Closed, Open }

/**
 * Swipe right-to-left reveals a red trash button; the row stays open until closed (SPEC-6 §3).
 * Not SwipeToDismissBox: it dismisses the row, while the user must tap the trash and confirm.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeToRevealDelete(open: Boolean, enabled: Boolean, onOpenChange: (Boolean) -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val width = 80.dp
    val state = remember {
        AnchoredDraggableState(
            initialValue = Reveal.Closed,
            positionalThreshold = { it / 2 },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = tween(),
            decayAnimationSpec = exponentialDecay(),
        ).apply { updateAnchors(DraggableAnchors { Reveal.Closed at 0f; Reveal.Open at -with(density) { width.toPx() } }) }
    }
    LaunchedEffect(state.currentValue) { onOpenChange(state.currentValue == Reveal.Open) }
    LaunchedEffect(open) { if (!open && state.currentValue == Reveal.Open) state.animateTo(Reveal.Closed) }

    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.error).clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.onError) }
        }
        Box(
            Modifier
                .offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
                .anchoredDraggable(state, Orientation.Horizontal, enabled = enabled)
                .background(MaterialTheme.colorScheme.background)
                .semantics { customActions = listOf(CustomAccessibilityAction("Supprimer") { onDelete(); true }) },
        ) { content() }
    }
}

@Composable
fun EmptyState(title: String, text: String?, modifier: Modifier = Modifier, icon: Painter? = null, buttons: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Icon(it, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        buttons()
    }
}

/** [selected] null = not in selection mode. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: Book, isDupe: Boolean, place: String?, selected: Boolean?, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "Sélectionner" })
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            // Always the user's own photo (SPEC-4 §1): it is the book actually on the shelf.
            AsyncImage(
                model = Books.photoFile(ctx, book.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(4.dp)),
            )
            if (selected == true) Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (book.status.inProgress && book.title == null) {
                Text("Lecture en cours…", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic)
            } else {
                Text(book.title ?: "Livre sans titre", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            book.authors?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            book.series?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.trim() + (book.volume?.let { v -> " · tome $v" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            place?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // A « Validé » chip on every library row would say nothing (SPEC-7 §3.1).
            if (book.status != Status.CONFIRMED || isDupe) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (book.status != Status.CONFIRMED) StatusChip(book.status)
                if (isDupe) Text("doublon possible", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Series for a selection: free text, with the library's existing series as suggestions so spellings stay consistent. Empty = no series. */
@Composable
private fun SeriesDialog(initial: String, existing: List<String>, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val q = Matching.normalize(name)
    val suggestions = existing.filter { Matching.normalize(it).contains(q) && Matching.normalize(it) != q }.take(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collection") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom de la collection") }, singleLine = true)
                suggestions.forEach { TextButton(onClick = { name = it }) { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                Text("Laisser vide pour retirer les livres de leur collection.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { null }) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
