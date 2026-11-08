package app.biblioscan.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.biblioscan.data.Status

private val Brand = Color(0xFF1F5F5B)

/** Material You colors on Android 12+, brand palette before; follows the system dark mode. */
@Composable
fun BiblioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8FD1CA))
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun statusLabel(s: Status) = when (s) {
    Status.CAPTURED -> "Lecture en attente"
    Status.OCR_DONE -> "Recherche en attente"
    Status.MATCHED -> "Trouvé"
    Status.NEEDS_REVIEW -> "À vérifier"
    Status.NOT_FOUND -> "Introuvable"
    Status.CONFIRMED -> "Validé"
    Status.ERROR -> "Erreur"
}

private fun statusIcon(s: Status): ImageVector = when (s) {
    Status.CAPTURED, Status.OCR_DONE -> Icons.Default.Refresh
    Status.MATCHED -> Icons.Default.Check
    Status.CONFIRMED -> Icons.Default.CheckCircle
    Status.NEEDS_REVIEW -> Icons.Default.Info
    Status.NOT_FOUND -> Icons.Default.Search
    Status.ERROR -> Icons.Default.Warning
}

val Status.inProgress get() = this == Status.CAPTURED || this == Status.OCR_DONE
val Status.toReview get() = this == Status.NEEDS_REVIEW || this == Status.NOT_FOUND

/** Icon + text, never color alone (SPEC-2 §5). */
@Composable
fun StatusChip(s: Status, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        s == Status.ERROR -> c.errorContainer to c.onErrorContainer
        s.toReview -> c.tertiaryContainer to c.onTertiaryContainer
        s.inProgress -> c.surfaceVariant to c.onSurfaceVariant
        else -> c.primaryContainer to c.onPrimaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon(s), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(statusLabel(s), style = MaterialTheme.typography.labelMedium)
        }
    }
}
