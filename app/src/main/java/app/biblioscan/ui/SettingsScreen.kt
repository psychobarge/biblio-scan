package app.biblioscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.biblioscan.data.Books
import app.biblioscan.data.Settings
import app.biblioscan.match.Matching
import app.biblioscan.match.NoiseEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var noise by remember { mutableStateOf(Settings.noise(ctx)) }
    var input by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    fun save(list: List<NoiseEntry>) {
        noise = list
        Settings.setNoise(ctx, list)
    }
    fun add() {
        val next = Matching.withEntry(noise, input)
        if (next == null) {
            if (input.isNotBlank()) scope.launch { snackbar.showSnackbar("Déjà dans la liste") }
        } else {
            save(next)
            input = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clés API", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Stockées uniquement sur ce téléphone. Sans clé Google Books, Google limite vite les recherches. " +
                            "Avec une clé DeepSeek, la photo est envoyée à DeepSeek pour lire titre et auteur ; sinon seul l'OCR est utilisé.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    KeyField(Settings.BOOKS_KEY, "Clé Google Books")
                    KeyField(Settings.DEEPSEEK_KEY, "Clé DeepSeek")
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mots ignorés sur les couvertures", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ces mots sont retirés de la recherche (éditeur, collection, genre…). " +
                            "« Toute la ligne » écarte la ligne entière qui contient le mot (« Prix Goncourt », « Traduit de… »). " +
                            "Les mots de ${Matching.INSIDE_MIN} lettres ou plus sont aussi retirés quand l'OCR les colle à d'autres.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, singleLine = true,
                            label = { Text("Ajouter un mot ou une expression") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { add() }, enabled = input.isNotBlank()) { Text("Ajouter") }
                    }
                    OutlinedButton(
                        onClick = { scope.launch { snackbar.showSnackbar("${Books.reapplyNoise(ctx)} livre(s) relancé(s)") } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Appliquer aux livres non validés") }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            items(noise.sortedBy { it.text }, key = { it.text }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Row(
                        Modifier.toggleable(
                            value = e.wholeLine, role = Role.Switch,
                            onValueChange = { on -> save(noise.map { if (it.text == e.text) it.copy(wholeLine = on) else it }) },
                        ).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Toute la ligne", style = MaterialTheme.typography.labelMedium)
                        Switch(checked = e.wholeLine, onCheckedChange = null)
                    }
                    IconButton(onClick = { save(noise.filter { it.text != e.text }) }) { Icon(Icons.Default.Delete, "Supprimer « ${e.text} »") }
                }
            }
            item {
                TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Rétablir la liste par défaut") }
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Rétablir la liste par défaut ?") },
        text = { Text("Vos ajouts et suppressions seront perdus.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                Settings.resetNoise(ctx)
                noise = Settings.noise(ctx)
            }) { Text("Rétablir") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } },
    )
}

/** Saved on every keystroke: the next lookup pass reads it. */
@Composable
private fun KeyField(name: String, label: String) {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf(Settings.key(ctx, name)) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; Settings.setKey(ctx, name, it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
