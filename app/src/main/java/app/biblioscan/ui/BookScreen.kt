package app.biblioscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.BookRoute
import app.biblioscan.app
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Status
import app.biblioscan.match.Candidate
import app.biblioscan.match.Matching
import app.biblioscan.match.json
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(route: BookRoute, snackbar: SnackbarHostState, onBack: () -> Unit, onNext: (BookRoute) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = ctx.app.db.books()
    val book by dao.watch(route.id).collectAsStateWithLifecycle(null)
    val b = book ?: return
    val review = route.reviewPos > 0

    // Reset the form when the worker changes the book (e.g. a lookup finishes).
    var edited by remember(b.id, b.status) { mutableStateOf(b) }
    var menu by remember { mutableStateOf(false) }
    var relaunch by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    // Snackbars that must outlive this screen run in the app scope (the screen scope dies on navigation).
    fun toast(msg: String) = ctx.app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar(msg) }

    suspend fun next() {
        val n = dao.nextToReview(b.createdAt)
        if (n == null) {
            onBack()
            toast("Revue terminée")
        } else {
            onNext(BookRoute(n.id, route.reviewPos + 1, maxOf(route.reviewTotal, route.reviewPos + 1)))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(b.title ?: "Livre sans titre", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (review) Text("Revue ${route.reviewPos} / ${maxOf(route.reviewTotal, route.reviewPos)}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Relancer la recherche") }, onClick = { menu = false; relaunch = true })
                            DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (review) OutlinedButton(onClick = { scope.launch { next() } }) { Text("Passer") }
                    Button(
                        onClick = {
                            scope.launch {
                                dao.update(edited.copy(status = Status.CONFIRMED, lastError = null))
                                if (review) next() else { onBack(); toast("Livre validé") }
                            }
                        },
                        enabled = !edited.title.isNullOrBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (review) "Valider et suivant" else "Valider") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(b, onPhotoClick = { fullscreen = true })
            b.lastError?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(edited.title.orEmpty(), { edited = edited.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Titre") })
            OutlinedTextField(edited.authors.orEmpty(), { edited = edited.copy(authors = it) }, Modifier.fillMaxWidth(), label = { Text("Auteur(s), séparés par ;") })
            OutlinedTextField(edited.publisher.orEmpty(), { edited = edited.copy(publisher = it) }, Modifier.fillMaxWidth(), label = { Text("Éditeur") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(edited.year.orEmpty(), { edited = edited.copy(year = it) }, Modifier.weight(1f), label = { Text("Année") })
                OutlinedTextField(edited.isbn.orEmpty(), { edited = edited.copy(isbn = it) }, Modifier.weight(2f), label = { Text("ISBN") })
            }
            // Typed fields are saved first, so a search that finds nothing keeps them.
            OutlinedButton(
                onClick = {
                    val q = listOfNotNull(edited.title, edited.authors?.replace(';', ' ')).joinToString(" ")
                    scope.launch { Books.retry(ctx, edited, q); snackbar.showSnackbar("Recherche relancée") }
                },
                enabled = !edited.title.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Rechercher avec ce titre et cet auteur") }

            val candidates = remember(b.candidatesJson) {
                b.candidatesJson?.let { runCatching { json.decodeFromString<List<Candidate>>(it) }.getOrNull() }.orEmpty()
            }
            if (candidates.isNotEmpty()) {
                Text("Livres proposés", style = MaterialTheme.typography.titleSmall)
                candidates.forEach { c ->
                    CandidateCard(c, selected = edited.sourceId == c.sourceId) {
                        edited = edited.copy(
                            title = c.title, authors = c.authors.joinToString("; "), publisher = c.publisher,
                            year = c.year, isbn = c.isbn, sourceId = c.sourceId, coverUrl = c.coverUrl, score = c.score,
                        )
                    }
                }
            }

            b.ocrText?.let { OcrSection(it) }
        }
    }

    if (relaunch) RelaunchSheet(
        initial = b.query.orEmpty(),
        onDismiss = { relaunch = false },
        onRelaunch = { q ->
            relaunch = false
            scope.launch { Books.retry(ctx, b, q.trim().ifBlank { null }); snackbar.showSnackbar("Recherche relancée") }
        },
    )

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer ce livre ?") },
        text = { Text("La fiche et la photo seront effacées définitivement.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onBack(); ctx.app.scope.launch { Books.delete(ctx, b.id) } }) { Text("Supprimer") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )

    if (fullscreen) PhotoViewer(b) { fullscreen = false }
}

@Composable
private fun Header(b: Book, onPhotoClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = Books.photoFile(ctx, b.id),
                contentDescription = "Votre photo, toucher pour agrandir",
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPhotoClick),
            )
            Text("Votre photo", style = MaterialTheme.typography.labelSmall)
        }
        b.coverUrl?.let { url ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = url,
                    contentDescription = "Couverture trouvée",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                )
                Text("Couverture trouvée", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    StatusChip(b.status)
}

private fun strength(score: Double) = when {
    score >= Matching.AUTO -> "Correspondance forte"
    score >= Matching.REVIEW -> "Correspondance moyenne"
    else -> "Correspondance faible"
}

@Composable
private fun CandidateCard(c: Candidate, selected: Boolean, onPick: () -> Unit) {
    Card(
        onClick = onPick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onPick)
            AsyncImage(model = c.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(c.authors.joinToString(", ").ifBlank { null }, c.publisher, c.year).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text("${strength(c.score)} · ${c.sourceId.substringBefore(':')}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OcrSection(text: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Texte lu sur la couverture", Modifier.weight(1f, fill = false))
        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
    }
    if (open) Text(text, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaunchSheet(initial: String, onDismiss: () -> Unit, onRelaunch: (String) -> Unit) {
    var query by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Relancer la recherche", style = MaterialTheme.typography.titleMedium)
            Text("Corrigez le titre ou l'auteur si la couverture a été mal lue.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Texte recherché") })
            Button(onClick = { onRelaunch(query) }, modifier = Modifier.fillMaxWidth()) { Text("Relancer") }
        }
    }
}

@Composable
private fun PhotoViewer(b: Book, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = Books.photoFile(ctx, b.id),
                contentDescription = "Photo de la couverture",
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
            ) { Icon(Icons.Default.Close, "Fermer") }
        }
    }
}
