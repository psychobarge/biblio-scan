package app.biblioscan.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.biblioscan.data.Status

// Brand teal everywhere (SPEC-8 §2.6): Material You turns everything grey with a monochrome system palette.
// Tertiary is amber: it marks what needs the user (« À vérifier », « Introuvable »).
private val Light = lightColorScheme(
    primary = Color(0xFF1F5F5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFA8EFE8), onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4), onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF6E5D0E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9E287), onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFF4FBF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLow = Color(0xFFEEF5F3), surfaceContainer = Color(0xFFE9EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8), surfaceContainerHighest = Color(0xFFDDE4E2),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FD1CA), onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B), onPrimaryContainer = Color(0xFFA8EFE8),
    secondary = Color(0xFFB0CCC8), onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF324B48), onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFDCC66E), onTertiary = Color(0xFF3A3000),
    tertiaryContainer = Color(0xFF534600), onTertiaryContainer = Color(0xFFF9E287),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainerLow = Color(0xFF161D1C), surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF2F3635),
    outline = Color(0xFF889391), outlineVariant = Color(0xFF3F4947),
)

/** Brand palette; follows the system dark mode. */
@Composable
fun BiblioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
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

/** Icon + text, never color alone (SPEC-2 §5). Light chip; color only for what needs the user (SPEC-8 §2.6). */
@Composable
fun StatusChip(s: Status, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        s == Status.ERROR -> c.errorContainer to c.onErrorContainer
        s.toReview -> c.tertiaryContainer to c.onTertiaryContainer
        else -> Color.Transparent to c.onSurfaceVariant
    }
    val border = if (bg == Color.Transparent) BorderStroke(1.dp, c.outlineVariant) else null
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, border = border, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon(s), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(statusLabel(s), style = MaterialTheme.typography.labelMedium)
        }
    }
}
