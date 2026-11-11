package app.biblioscan.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import android.util.Rational
import android.view.Surface
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.biblioscan.app
import app.biblioscan.data.Books
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
/** [onFinish]: « Terminé » after at least one photo (SPEC-7 §6.1). */
fun CaptureScreen(onDone: () -> Unit, onFinish: () -> Unit, onOpenBook: (String) -> Unit) {
    val ctx = LocalContext.current
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // No rationale after a refusal = "don't ask again": only the system settings can grant it now.
        permanentlyDenied = !ok && !ActivityCompat.shouldShowRequestPermissionRationale(ctx as Activity, Manifest.permission.CAMERA)
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    // Re-check on every resume (e.g. back from system settings), but auto-ask only once:
    // the permission dialog itself pauses/resumes the activity.
    LifecycleResumeEffect(Unit) {
        granted = hasPermission()
        if (!granted && !askedOnce) {
            askedOnce = true
            askPermission.launch(Manifest.permission.CAMERA)
        }
        onPauseOrDispose { }
    }

    // Light status bar icons over the dark camera preview; restored when leaving.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val bars = WindowCompat.getInsetsController((ctx as Activity).window, view)
        val previous = bars.isAppearanceLightStatusBars
        bars.isAppearanceLightStatusBars = false
        onDispose { bars.isAppearanceLightStatusBars = previous }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            Camera(onDone, onFinish, onOpenBook)
        } else {
            PermissionExplanation(
                permanentlyDenied = permanentlyDenied,
                onAsk = { askPermission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                },
            )
            BackButton(onDone, Modifier.align(Alignment.TopStart))
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour à la bibliothèque") }
}

@Composable
private fun PermissionExplanation(permanentlyDenied: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("La caméra sert à photographier vos couvertures", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            if (permanentlyDenied) "L'accès a été refusé. Autorisez la caméra dans les réglages de l'application."
            else "Les photos restent sur votre téléphone.",
            color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
        )
        if (permanentlyDenied) Button(onClick = onSettings) { Text("Ouvrir les réglages") }
        else Button(onClick = onAsk) { Text("Autoriser la caméra") }
    }
}

@Composable
private fun Camera(onDone: () -> Unit, onFinish: () -> Unit, onOpenBook: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val flash = remember { Animatable(0f) }
    var saving by remember { mutableIntStateOf(0) } // photos taken but not yet on disk
    var sessionCount by rememberSaveable { mutableIntStateOf(0) }
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
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

    fun shoot() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { flash.snapTo(0.6f); flash.animateTo(0f, tween(150)) }
        saving++
        val id = UUID.randomUUID().toString()
        val out = ImageCapture.OutputFileOptions.Builder(Books.photoFile(ctx, id)).build()
        imageCapture.takePicture(out, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                saving--
                sessionCount++
                lastId = id
                ctx.app.scope.launch { Books.onPhotoSaved(ctx, id) }
            }
            override fun onError(e: ImageCaptureException) {
                saving--
                scope.launch { snackbar.showSnackbar("Photo ratée : ${e.message}") }
            }
        })
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) { BackButton(onDone, Modifier.align(Alignment.TopStart)) }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                // What you see is the photo: the preview window is 2:3 and a 2:3 ViewPort makes CameraX
                // crop both the preview and the saved JPEG to that same region.
                val shape = RoundedCornerShape(8.dp)
                Box(Modifier.aspectRatio(2f / 3f).clip(shape).border(2.dp, if (saving > 0) Color.Green else Color.White, shape)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            PreviewView(c).also { view ->
                                val future = ProcessCameraProvider.getInstance(c)
                                future.addListener({
                                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                    // Portrait-locked activity, so the display rotation is always 0.
                                    val viewPort = ViewPort.Builder(Rational(2, 3), Surface.ROTATION_0).build()
                                    val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(imageCapture).build()
                                    future.get().run {
                                        unbindAll()
                                        bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                                    }
                                }, ContextCompat.getMainExecutor(c))
                            }
                        },
                    )
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                }
            }
            if (sessionCount == 0) Text("Cadrez la couverture", color = Color.White, modifier = Modifier.padding(8.dp))
            SnackbarHost(snackbar)

            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    AnimatedContent(lastId, transitionSpec = { scaleIn() togetherWith fadeOut() }, label = "thumb") { id ->
                        if (id == null) Spacer(Modifier.size(48.dp, 72.dp)) else AsyncImage(
                            model = Books.photoFile(ctx, id),
                            contentDescription = "Dernière photo, ouvrir la fiche",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp, 72.dp).clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color.White, RoundedCornerShape(4.dp))
                                .clickable { onOpenBook(id) },
                        )
                    }
                }
                Box(
                    Modifier.size(76.dp).background(Color.White, CircleShape).border(4.dp, Color.LightGray, CircleShape)
                        .clip(CircleShape).clickable(onClickLabel = "Prendre la photo", role = Role.Button) { shoot() }
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = if (sessionCount > 0) onFinish else onDone, modifier = Modifier.width(112.dp)) {
                        Text(if (sessionCount > 0) "Terminé ($sessionCount)" else "Terminé", color = Color.White)
                    }
                }
            }
        }
    }
}
