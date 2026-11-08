package app.biblioscan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.biblioscan.app
import app.biblioscan.data.Books
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun CaptureScreen(onOpenLibrary: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    val pending by ctx.app.db.books().pendingCount().collectAsStateWithLifecycle(0)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) Camera(Modifier.fillMaxSize()) else Button(
            onClick = { askPermission.launch(Manifest.permission.CAMERA) },
            modifier = Modifier.align(Alignment.Center),
        ) { Text("Autoriser la caméra") }

        Button(onClick = onOpenLibrary, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Text(if (pending > 0) "Bibliothèque · $pending en cours" else "Bibliothèque")
        }
    }
}

@Composable
private fun Camera(modifier: Modifier) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var saving by remember { mutableIntStateOf(0) } // photos taken but not yet on disk
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    // ~1920px is plenty for cover OCR and keeps files small (SPEC §13).
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .build()
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PreviewView(c).also { view ->
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        future.get().run {
                            unbindAll()
                            bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }
                    }, ContextCompat.getMainExecutor(c))
                }
            },
        )

        // Framing guide, book-shaped (~2:3). No crop in V1: the user frames the cover.
        Box(
            Modifier.align(Alignment.Center).fillMaxHeight(0.75f).aspectRatio(2f / 3f)
                .border(2.dp, if (saving > 0) Color.Green else Color.White)
        )

        Box(
            Modifier.align(Alignment.BottomCenter).padding(32.dp).size(76.dp)
                .background(Color.White, CircleShape)
                .border(4.dp, Color.LightGray, CircleShape)
                .clip(CircleShape)
                .clickable(onClickLabel = "Prendre la photo", role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    saving++
                    val id = UUID.randomUUID().toString()
                    val out = ImageCapture.OutputFileOptions.Builder(Books.photoFile(ctx, id)).build()
                    imageCapture.takePicture(out, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                            saving--
                            ctx.app.scope.launch { Books.onPhotoSaved(ctx, id) }
                        }
                        override fun onError(e: ImageCaptureException) {
                            saving--
                            Toast.makeText(ctx, "Photo ratée : ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    })
                }
        )
    }
}
