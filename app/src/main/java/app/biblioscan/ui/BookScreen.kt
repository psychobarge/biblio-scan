package app.biblioscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Status
import app.biblioscan.match.Candidate
import app.biblioscan.match.json
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val book by ctx.app.db.books().watch(id).collectAsStateWithLifecycle(null)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(book?.let { statusLabel(it.status) } ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
    }) { padding ->
        book?.let { b ->
            // Re-key on status: when a lookup finishes, the form shows the new result.
            androidx.compose.runtime.key(b.id, b.status) {
                BookForm(b, Modifier.padding(padding), onDeleted = onBack)
            }
        }
    }
}

@Composable
private fun BookForm(book: Book, modifier: Modifier, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = ctx.app.db.books()
    var edited by remember { mutableStateOf(book) }
    var query by remember { mutableStateOf(book.query.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val candidates = remember(book.candidatesJson) {
        book.candidatesJson?.let { runCatching { json.decodeFromString<List<Candidate>>(it) }.getOrNull() }.orEmpty()
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = Books.photoFile(ctx, book.id),
            contentDescription = "Photo de la couverture",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        book.lastError?.let { Text("Erreur : $it", color = statusColor(Status.ERROR)) }

        OutlinedTextField(edited.title.orEmpty(), { edited = edited.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Titre") })
        OutlinedTextField(edited.authors.orEmpty(), { edited = edited.copy(authors = it) }, Modifier.fillMaxWidth(), label = { Text("Auteur(s), séparés par ;") })
        OutlinedTextField(edited.publisher.orEmpty(), { edited = edited.copy(publisher = it) }, Modifier.fillMaxWidth(), label = { Text("Éditeur") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(edited.year.orEmpty(), { edited = edited.copy(year = it) }, Modifier.weight(1f), label = { Text("Année") })
            OutlinedTextField(edited.isbn.orEmpty(), { edited = edited.copy(isbn = it) }, Modifier.weight(2f), label = { Text("ISBN") })
        }
        Button(
            onClick = { scope.launch { dao.update(edited.copy(status = Status.CONFIRMED, lastError = null)) } },
            enabled = !edited.title.isNullOrBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Valider") }

        if (candidates.isNotEmpty()) {
            Text("Candidats (toucher pour remplir le formulaire)", style = MaterialTheme.typography.titleSmall)
            candidates.forEach { c ->
                CandidateRow(c) {
                    edited = edited.copy(
                        title = c.title, authors = c.authors.joinToString("; "), publisher = c.publisher,
                        year = c.year, isbn = c.isbn, sourceId = c.sourceId, coverUrl = c.coverUrl, score = c.score,
                    )
                }
                HorizontalDivider()
            }
        }

        Text("Relancer la recherche", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Texte recherché") })
        OutlinedButton(onClick = { scope.launch { Books.retry(ctx, book, query.trim().ifBlank { null }) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Relancer")
        }

        book.ocrText?.let {
            Text("Texte lu sur la couverture", style = MaterialTheme.typography.titleSmall)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer ce livre", color = statusColor(Status.ERROR)) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer ce livre ?") },
        text = { Text("La fiche et la photo seront effacées définitivement.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDeleted(); ctx.app.scope.launch { Books.delete(ctx, book.id) } }) { Text("Supprimer") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

@Composable
private fun CandidateRow(c: Candidate, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = c.coverUrl, contentDescription = null, modifier = Modifier.size(width = 40.dp, height = 60.dp))
        Column {
            Text(c.title, style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(c.authors.joinToString(", ").ifBlank { null }, c.publisher, c.year).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text("score ${(c.score * 100).toInt()} % · ${c.sourceId.substringBefore(':')}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
