package app.biblioscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Status
import kotlinx.coroutines.launch

enum class Filter(val label: String, val keep: (Status) -> Boolean) {
    ALL("Tous", { true }),
    FOUND("Trouvés", { it == Status.MATCHED }),
    REVIEW("À vérifier", { it.toReview }),
    PROGRESS("En cours", { it.inProgress }),
    ERRORS("Erreurs", { it == Status.ERROR }),
}

/** Tab « À valider » (SPEC-7 §4, SPEC-8 §2.2): every book not validated yet, newest first. Validating files it in the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onReview: (firstId: String, total: Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = ctx.app.db.books()
    val books by dao.all().collectAsStateWithLifecycle(emptyList()) // newest first
    val pending = books.filter { it.status != Status.CONFIRMED }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    val shown = pending.filter { filter.keep(it.status) }
    val inProgress = pending.count { it.status.inProgress }
    val toValidate = pending.count { it.status == Status.MATCHED || it.status.toReview }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) SelectionBar(
                selected, books, snackbar, onSelect = { selected = it },
                all = { shown.map { it.id }.toSet() }, validate = true,
            ) else TopAppBar(title = { Text("À valider") })
        },
        floatingActionButton = {
            // The review is this tab's main action (SPEC-8 §2.2).
            if (toValidate > 0 && selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = { scope.launch { dao.nextToReview(-1)?.let { onReview(it.id, toValidate) } } },
                icon = { Icon(Icons.Default.Check, null) },
                text = { Text("Valider un par un") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pending.isNotEmpty()) LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Empty filters are hidden, not disabled: they pushed the useful ones off screen.
                items(Filter.entries.filter { f -> f == Filter.ALL || f == filter || pending.any { f.keep(it.status) } }) { f ->
                    FilterChip(
                        selected = filter == f, onClick = { filter = f },
                        label = { Text("${f.label} (${pending.count { f.keep(it.status) }})") },
                    )
                }
            }
            if (inProgress > 0) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${plural(inProgress, "livre")} en cours de traitement", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }

            when {
                pending.isEmpty() -> EmptyState("Rien à valider", "Les livres scannés ou importés attendent ici jusqu'à ce que vous les validiez.")
                shown.isEmpty() -> EmptyState("Aucun livre ici", null)
                else -> BookList(books = shown, dupes = dupesOf(books), selected = selected, onSelect = { selected = it }, onOpen = onOpen, state = listState)
            }
        }
    }
}
