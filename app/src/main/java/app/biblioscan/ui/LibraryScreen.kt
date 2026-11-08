package app.biblioscan.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Backup
import app.biblioscan.data.Book
import app.biblioscan.data.Books
import app.biblioscan.data.Status
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Filter(val label: String, val keep: (Status) -> Boolean) {
    ALL("Tous", { true }),
    REVIEW("À vérifier", { it == Status.NEEDS_REVIEW || it == Status.NOT_FOUND }),
    ERRORS("Erreurs", { it == Status.ERROR }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by ctx.app.db.books().all().collectAsStateWithLifecycle(emptyList())
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var menu by remember { mutableStateOf(false) }

    fun io(label: String, block: suspend () -> String) = scope.launch {
        val msg = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            "$label : échec (${e.message})"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
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

    // Same edition scanned twice: flagged, not blocked (you may own two copies).
    val dupes = books.mapNotNull { it.sourceId ?: it.isbn }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Bibliothèque (${books.size})") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour à la capture") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Exporter (ZIP)") }, onClick = { menu = false; exportZip.launch("biblioscan.zip") })
                    DropdownMenuItem(text = { Text("Importer (ZIP)") }, onClick = { menu = false; importZip.launch(arrayOf("application/zip")) })
                    DropdownMenuItem(text = { Text("Exporter (CSV)") }, onClick = { menu = false; exportCsv.launch("biblioscan.csv") })
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    val n = books.count { f.keep(it.status) }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($n)") })
                }
            }
            LazyColumn {
                items(books.filter { filter.keep(it.status) }, key = { it.id }) { book ->
                    BookRow(book, isDupe = (book.sourceId ?: book.isbn) in dupes, onClick = { onOpen(book.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, isDupe: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = Books.photoFile(ctx, book.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 48.dp, height = 72.dp),
        )
        Column {
            Text(book.title ?: "(sans titre)", style = MaterialTheme.typography.titleMedium)
            book.authors?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLabel(book.status), color = statusColor(book.status), style = MaterialTheme.typography.labelMedium)
                if (isDupe) Text("doublon possible", color = Color(0xFFB26A00), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

fun statusLabel(s: Status) = when (s) {
    Status.CAPTURED -> "lecture en attente"
    Status.OCR_DONE -> "recherche en attente"
    Status.MATCHED -> "trouvé (auto)"
    Status.NEEDS_REVIEW -> "à vérifier"
    Status.NOT_FOUND -> "introuvable"
    Status.CONFIRMED -> "validé"
    Status.ERROR -> "erreur"
}

fun statusColor(s: Status) = when (s) {
    Status.CONFIRMED, Status.MATCHED -> Color(0xFF2E7D32)
    Status.NEEDS_REVIEW, Status.NOT_FOUND -> Color(0xFFB26A00)
    Status.ERROR -> Color(0xFFC62828)
    else -> Color.Gray
}
