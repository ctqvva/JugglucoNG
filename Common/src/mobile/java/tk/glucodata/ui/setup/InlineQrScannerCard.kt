package tk.glucodata.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import tk.glucodata.R

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

/** How long the same rejected payload is ignored before it is offered to the caller again. */
private const val REJECTED_SCAN_COOLDOWN_MS = 4_000L

/** Last payload the caller turned down, so a code held in frame is not re-reported every frame. */
private class RejectedScan {
    @Volatile var value: String? = null
    @Volatile var atMs: Long = 0L
}

/**
 * @param onScanResult invoked with a decoded payload; return true if the caller accepted it, which
 *   stops the camera. Returning false leaves the scanner running — a stray code in frame (a box's
 *   "app download" QR, a code for a different sensor type) must not be able to end the scan.
 */
@Composable
@SuppressLint("ClickableViewAccessibility")
fun InlineQrScannerCard(
    modifier: Modifier = Modifier,
    scannerEnabled: Boolean = true,
    onScanResult: (String) -> Boolean,
    onManualFallback: (() -> Unit)? = null,
    manualFallbackLabel: String? = null,
    onTouchInteractionChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val bindingGeneration = remember { AtomicInteger(0) }

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var scannerError by remember { mutableStateOf<String?>(null) }
    var consumed by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var touchActive by remember { mutableStateOf(false) }
    var lifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var previewInstanceNonce by remember { mutableIntStateOf(0) }
    val rejectedScan = remember { RejectedScan() }
    // The analyzer closure outlives the recompositions that change what a scan should do (the
    // selected sensor type, the selected Bluetooth device), so it must not capture the lambda.
    val onScanResultState = rememberUpdatedState(onScanResult)
    val cameraState = rememberUpdatedState(camera)
    val onTouchInteractionChangedState = rememberUpdatedState(onTouchInteractionChanged)
    val scaleDetector = remember(context) {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = cameraState.value ?: return false
                    val zoomState = activeCamera.cameraInfo.zoomState.value ?: return false
                    val currentZoom = zoomState.zoomRatio
                    val nextZoom = (currentZoom * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    activeCamera.cameraControl.setZoomRatio(nextZoom)
                    return true
                }
            }
        )
    }

    val scannerOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
    }
    val barcodeScannerResult = remember {
        runCatching { BarcodeScanning.getClient(scannerOptions) }
    }
    val barcodeScanner = barcodeScannerResult.getOrNull()
    LaunchedEffect(barcodeScannerResult) {
        barcodeScannerResult.exceptionOrNull()?.let { throwable ->
            scannerError = throwable.message ?: throwable.javaClass.simpleName
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (touchActive) {
                onTouchInteractionChangedState.value?.invoke(false)
                touchActive = false
            }
            camera = null
            torchEnabled = false
            analyzerExecutor.shutdown()
            barcodeScanner?.close()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleResumed = false
                    if (touchActive) {
                        touchActive = false
                        onTouchInteractionChangedState.value?.invoke(false)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                }
                Lifecycle.Event.ON_DESTROY -> lifecycleResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(
        previewView,
        hasPermission,
        lifecycleOwner,
        consumed,
        lifecycleResumed,
        scannerEnabled,
        barcodeScanner
    ) {
        val generation = bindingGeneration.incrementAndGet()
        val targetView = previewView
        val activeBarcodeScanner = barcodeScanner
        if (
            !hasPermission ||
            targetView == null ||
            consumed ||
            !lifecycleResumed ||
            !scannerEnabled ||
            activeBarcodeScanner == null
        ) {
            camera = null
            torchEnabled = false
            onDispose { }
        } else {
            val processing = AtomicBoolean(false)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            var analysis: ImageAnalysis? = null
            var disposed = false
            scannerError = null

            cameraProviderFuture.addListener(
                {
                    if (disposed) return@addListener
                    try {
                        provider = cameraProviderFuture.get()
                        val preview = Preview.Builder()
                            .build()
                            .also { it.setSurfaceProvider(targetView.surfaceProvider) }

                        analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage == null || consumed) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    if (!processing.compareAndSet(false, true)) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )

                                    activeBarcodeScanner.process(inputImage)
                                        .addOnSuccessListener(mainExecutor) { barcodes ->
                                            val rawValue = barcodes
                                                .firstNotNullOfOrNull { barcode ->
                                                    tk.glucodata.PhotoScan
                                                        .trimOuterScannerWhitespace(barcode.rawValue)
                                                        ?.takeIf { value -> value.isNotEmpty() }
                                                }
                                            if (rawValue != null && !consumed) {
                                                val nowMs = android.os.SystemClock.elapsedRealtime()
                                                val isRepeatOfRejected = rawValue == rejectedScan.value &&
                                                    nowMs - rejectedScan.atMs < REJECTED_SCAN_COOLDOWN_MS
                                                if (!isRepeatOfRejected) {
                                                    if (onScanResultState.value(rawValue)) {
                                                        consumed = true
                                                    } else {
                                                        rejectedScan.value = rawValue
                                                        rejectedScan.atMs = nowMs
                                                    }
                                                }
                                            }
                                        }
                                        .addOnFailureListener(mainExecutor) { throwable ->
                                            if (!consumed) {
                                                scannerError = throwable.message
                                            }
                                        }
                                        .addOnCompleteListener {
                                            processing.set(false)
                                            imageProxy.close()
                                        }
                                }
                            }

                        provider?.unbindAll()
                        val boundCamera = provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                        camera = boundCamera
                        torchEnabled = boundCamera?.cameraInfo?.torchState?.value == TorchState.ON
                    } catch (throwable: Throwable) {
                        scannerError = throwable.message ?: throwable.javaClass.simpleName
                    }
                },
                mainExecutor
            )

            onDispose {
                disposed = true
                analysis?.clearAnalyzer()
                if (touchActive) {
                    onTouchInteractionChangedState.value?.invoke(false)
                    touchActive = false
                }
                camera = null
                torchEnabled = false
                if (bindingGeneration.get() == generation) {
                    try {
                        provider?.unbindAll()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasPermission && scannerEnabled) {
                key(previewInstanceNonce) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                setOnTouchListener { _, event ->
                                    scaleDetector.onTouchEvent(event)
                                    val action = event.actionMasked
                                    val activeNow = action != MotionEvent.ACTION_UP &&
                                        action != MotionEvent.ACTION_CANCEL &&
                                        action != MotionEvent.ACTION_OUTSIDE
                                    if (touchActive != activeNow) {
                                        touchActive = activeNow
                                        onTouchInteractionChangedState.value?.invoke(activeNow)
                                    }

                                    val activeCamera = cameraState.value
                                    val activePreview = previewView ?: this
                                    if (
                                        action == MotionEvent.ACTION_UP &&
                                        !scaleDetector.isInProgress &&
                                        activeCamera != null
                                    ) {
                                        val focusPoint = activePreview.meteringPointFactory.createPoint(event.x, event.y)
                                        val focusAction = FocusMeteringAction.Builder(
                                            focusPoint,
                                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                        activeCamera.cameraControl.startFocusAndMetering(focusAction)
                                    }
                                    true
                                }
                            }.also { previewView = it }
                        },
                        update = { preview -> previewView = preview }
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.32f)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(20.dp)
                    )
            )

            if (hasPermission && camera?.cameraInfo?.hasFlashUnit() == true) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    IconButton(
                        onClick = {
                            val nextTorch = !torchEnabled
                            try {
                                camera?.cameraControl?.enableTorch(nextTorch)
                                torchEnabled = nextTorch
                            } catch (throwable: Throwable) {
                                scannerError = throwable.message ?: throwable.javaClass.simpleName
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = stringResource(R.string.flash),
                            tint = Color.White
                        )
                    }
                }
            }

            if (hasPermission && onManualFallback != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    IconButton(onClick = {
                        if (touchActive) {
                            touchActive = false
                            onTouchInteractionChangedState.value?.invoke(false)
                        }
                        camera = null
                        torchEnabled = false
                        previewView = null
                        previewInstanceNonce++
                        onManualFallback()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.OpenInFull,
                            contentDescription = stringResource(R.string.scan_qr_button),
                            tint = Color.White
                        )
                    }
                }
            }

            if (!hasPermission || scannerError != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (!hasPermission) {
                            stringResource(R.string.scan_permission)
                        } else {
                            stringResource(R.string.scanner_label)
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (scannerError != null) {
                        Text(
                            text = scannerError ?: "",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.permission))
                    }

                    if (onManualFallback != null) {
                        OutlinedButton(onClick = onManualFallback) {
                            Text(manualFallbackLabel ?: stringResource(R.string.scanname))
                        }
                    }
                }
            }
        }
    }
}
