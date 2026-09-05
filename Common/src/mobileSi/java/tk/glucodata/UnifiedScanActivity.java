package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.annotation.SuppressLint;
import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.graphics.Insets;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UnifiedScanActivity extends AppCompatActivity {
    public static final String EXTRA_SCAN_TEXT = "tk.glucodata.extra.scan_text";
    public static final String EXTRA_SENSOR_PTR = "tk.glucodata.extra.sensor_ptr";
    public static final String EXTRA_SCAN_REQUEST = "tk.glucodata.extra.scan_request";
    public static final String EXTRA_SCAN_CONTEXT = "tk.glucodata.extra.scan_context";
    public static final String EXTRA_SCAN_TITLE = "tk.glucodata.extra.scan_title";
    public static final int SCAN_CONTEXT_SENSOR = 0;
    public static final int SCAN_CONTEXT_MIRROR = 1;

    private static final String LOG_ID = "UnifiedScanActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 0x541;

    private PreviewView previewView;
    private View topBar;
    private View scanFrame;
    private View scanLine;
    private TextView titleView;
    private ImageButton flashButton;
    private ObjectAnimator scanAnimator;
    private ScaleGestureDetector scaleDetector;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private BarcodeScanner barcodeScanner;
    private ExecutorService analyzerExecutor;

    private int scanRequest;
    private int scanContext;
    private long sensorPtr;
    private String scanTitle;

    private final AtomicBoolean analyzerBusy = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean cameraStartInFlight = new AtomicBoolean(false);
    private boolean torchEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unified_scan);

        scanRequest = getIntent().getIntExtra(EXTRA_SCAN_REQUEST, MainActivity.REQUEST_BARCODE);
        scanContext = getIntent().getIntExtra(EXTRA_SCAN_CONTEXT, SCAN_CONTEXT_SENSOR);
        sensorPtr = getIntent().getLongExtra(EXTRA_SENSOR_PTR, 0L);
        scanTitle = getIntent().getStringExtra(EXTRA_SCAN_TITLE);

        previewView = findViewById(R.id.scanPreview);
        topBar = findViewById(R.id.scanTopBar);
        scanFrame = findViewById(R.id.scanFrame);
        scanLine = findViewById(R.id.scanLine);
        titleView = findViewById(R.id.scanTitle);
        flashButton = findViewById(R.id.scanFlashButton);
        final ImageButton closeButton = findViewById(R.id.scanCancelButton);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        applySystemInsets();
        applyScanTexts();
        configureFrameAndAnimation();
        installCameraGestures();

        closeButton.setOnClickListener(v -> cancelAndFinish());
        flashButton.setOnClickListener(v -> toggleTorch());

        analyzerExecutor = Executors.newSingleThreadExecutor();
        try {
            barcodeScanner = BarcodeScanning.getClient(
                    new com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
                            .build()
            );
        } catch (Throwable t) {
            Log.stack(LOG_ID, "createBarcodeScanner", t);
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            cancelAndFinish();
            return;
        }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION
            );
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void applySystemInsets() {
        final View root = findViewById(R.id.scanRoot);
        final int topLeft = topBar.getPaddingLeft();
        final int topTop = topBar.getPaddingTop();
        final int topRight = topBar.getPaddingRight();
        final int topBottom = topBar.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(topLeft, topTop + systemBars.top, topRight, topBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void applyScanTexts() {
        if (scanTitle != null && !scanTitle.isEmpty()) {
            titleView.setText(scanTitle);
            return;
        }
        if (scanContext == SCAN_CONTEXT_MIRROR) {
            titleView.setText(R.string.mirror);
            return;
        }
        if (scanRequest == MainActivity.REQUEST_BARCODE_SIB2) {
            titleView.setText(R.string.scan_transmitter_button);
            return;
        }
        titleView.setText(R.string.scan_qr_button);
    }

    private void configureFrameAndAnimation() {
        scanFrame.post(() -> {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int target = (int) (screenWidth * 0.84f);
            int side = Math.max(dp(270), Math.min(dp(460), target));

            ViewGroup.LayoutParams params = scanFrame.getLayoutParams();
            if (params.width != side || params.height != side) {
                params.width = side;
                params.height = side;
                scanFrame.setLayoutParams(params);
            }
            startScanLineAnimation();
        });
    }

    private void installCameraGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null || camera.getCameraInfo() == null) {
                    return false;
                }
                ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                if (zoomState == null) {
                    return false;
                }
                float currentZoom = zoomState.getZoomRatio();
                float nextZoom = currentZoom * detector.getScaleFactor();
                if (nextZoom < zoomState.getMinZoomRatio()) {
                    nextZoom = zoomState.getMinZoomRatio();
                } else if (nextZoom > zoomState.getMaxZoomRatio()) {
                    nextZoom = zoomState.getMaxZoomRatio();
                }
                camera.getCameraControl().setZoomRatio(nextZoom);
                return true;
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            if (camera == null) {
                return false;
            }
            scaleDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !scaleDetector.isInProgress()) {
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                camera.getCameraControl().startFocusAndMetering(action);
            }
            return true;
        });
    }

    private void startScanLineAnimation() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
        }
        float inset = 22f * getResources().getDisplayMetrics().density;
        float travel = Math.max(0f, scanFrame.getHeight() - scanLine.getHeight() - inset - inset);
        float fromY = inset;
        float toY = inset + travel;
        scanLine.setTranslationY(fromY);
        scanAnimator = ObjectAnimator.ofFloat(scanLine, "translationY", fromY, toY);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.setDuration(1800L);
        scanAnimator.setRepeatMode(ObjectAnimator.RESTART);
        scanAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        scanAnimator.start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startCamera() {
        if (finished.get() || !cameraStartInFlight.compareAndSet(false, true)) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                if (finished.get()) {
                    cameraStartInFlight.set(false);
                    return;
                }
                cameraProvider = providerFuture.get();
                if (finished.get() || isFinishing() || isDestroyed()) {
                    releaseCameraSession();
                    return;
                }
                bindCameraUseCases();
            } catch (Throwable t) {
                Log.stack(LOG_ID, "startCamera", t);
                Toast.makeText(this, t.getMessage(), Toast.LENGTH_SHORT).show();
                cancelAndFinish();
            } finally {
                cameraStartInFlight.set(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || finished.get() || isFinishing() || isDestroyed()) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analyzerExecutor, this::analyzeFrame);

        camera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
        );

        updateFlashButtonState();
    }

    private void releaseCameraSession() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (Throwable ignored) {
        }
        camera = null;
        torchEnabled = false;
        updateFlashButtonState();
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (finished.get()) {
            imageProxy.close();
            return;
        }
        if (analyzerBusy.getAndSet(true)) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            analyzerBusy.set(false);
            imageProxy.close();
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && !raw.isEmpty()) {
                            deliverResult(PhotoScan.trimOuterScannerWhitespace(raw));
                            break;
                        }
                    }
                })
                .addOnFailureListener(t -> {
                    if (doLog) {
                        Log.i(LOG_ID, "barcodeScanner failure: " + t.getMessage());
                    }
                })
                .addOnCompleteListener(task -> {
                    analyzerBusy.set(false);
                    imageProxy.close();
                });
    }

    private void deliverResult(String scanText) {
        if (!finished.compareAndSet(false, true)) return;
        releaseCameraSession();

        Intent data = new Intent();
        data.putExtra(EXTRA_SCAN_TEXT, scanText);
        data.putExtra(EXTRA_SCAN_REQUEST, scanRequest);
        data.putExtra(EXTRA_SENSOR_PTR, sensorPtr);
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private void cancelAndFinish() {
        if (!finished.compareAndSet(false, true)) return;
        releaseCameraSession();

        Intent data = new Intent();
        data.putExtra(EXTRA_SCAN_REQUEST, scanRequest);
        data.putExtra(EXTRA_SENSOR_PTR, sensorPtr);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
    }

    private void toggleTorch() {
        if (camera == null || camera.getCameraInfo() == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, R.string.flashpermission, Toast.LENGTH_SHORT).show();
            return;
        }
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        updateFlashButtonState();
    }

    private void updateFlashButtonState() {
        flashButton.setImageResource(torchEnabled ? R.drawable.ic_scan_flash_on_24 : R.drawable.ic_scan_flash_off_24);
        if (camera == null || camera.getCameraInfo() == null || !camera.getCameraInfo().hasFlashUnit()) {
            flashButton.setEnabled(false);
            flashButton.setAlpha(0.4f);
            return;
        }
        flashButton.setEnabled(true);
        flashButton.setAlpha(torchEnabled ? 1f : 0.8f);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        cancelAndFinish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!finished.get() && hasCameraPermission() && camera == null) {
            startCamera();
        }
    }

    @Override
    protected void onStop() {
        releaseCameraSession();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.scan_permission, Toast.LENGTH_SHORT).show();
                cancelAndFinish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
            scanAnimator = null;
        }
        releaseCameraSession();
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
        if (analyzerExecutor != null) {
            analyzerExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
