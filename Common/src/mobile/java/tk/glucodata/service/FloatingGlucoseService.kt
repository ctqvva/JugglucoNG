package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tk.glucodata.ui.optOutOfForceDark
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
import tk.glucodata.ui.overlay.OverlayPosition
import tk.glucodata.ui.overlay.OverlaySafeArea
import tk.glucodata.ui.overlay.clampOverlayPixels
import tk.glucodata.ui.overlay.overlayPixels
import tk.glucodata.ui.overlay.overlayPosition
import tk.glucodata.Natives

class FloatingGlucoseService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private companion object {
        private const val FLOATING_HISTORY_WINDOW_MS = 6L * 60L * 60L * 1000L
    }

    enum class CutoutEdge {
        NONE,
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    // The view actually attached to the WindowManager; see CutoutAwareContainer.
    private var overlayRoot: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepository: FloatingSettingsRepository
    private val glucoseRepository = GlucoseRepository()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = FloatingSettingsRepository(this)
        glucoseRepository.refreshSensorSerial()

        setupOverlay()
        observeSettings()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        // Satisfy Foreground Service requirement immediately
        startForeground(81431, createForegroundNotification())
    }
    
    private fun createForegroundNotification(): android.app.Notification {
        val channelId = "glucoseNotification"
        val channelName = "JugglucoNG Service"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = android.app.NotificationChannel(channelId, channelName, android.app.NotificationManager.IMPORTANCE_HIGH)
            chan.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            chan.setShowBadge(true) // Standard behavior
            chan.description = "Glucose Levels"
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(chan)
        }
        
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            android.app.Notification.Builder(this)
        }
        
        val prefs = getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val hideIcon = prefs.getBoolean("notification_hide_status_icon", false)
        val icon = if (hideIcon) tk.glucodata.R.drawable.transparent_icon else tk.glucodata.R.drawable.novalue
        
        return builder.setOngoing(true)
            .setSmallIcon(icon)
            .setContentTitle("JugglucoNG Overlay")
            .setContentText("Service is running")
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // If the system kills the service, recreate it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // State for Dynamic Island
    data class CutoutData(
        val size: androidx.compose.ui.unit.Dp,
        val edge: CutoutEdge
    )
    private val cutoutData = kotlinx.coroutines.flow.MutableStateFlow(CutoutData(0.dp, CutoutEdge.NONE))
    private var dynamicIslandEnabled = false
    // Freeform placement is kept screen-relative and resolved against the current
    // safe area on every placement; see OverlayPosition.
    private var freeformPosition = OverlayPosition.DEFAULT
    // Pixel position from before #685, converted once the overlay has a size.
    private var legacyPixelPosition: Pair<Int, Int>? = null
    private var safeArea: OverlaySafeArea? = null
    private var dragging = false

    private fun setupOverlay() {
        if (composeView != null) return

        val root = CutoutAwareContainer(this) { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                cutoutData.value = resolveCutoutData(v, insets.displayCutout)
                if (dynamicIslandEnabled) {
                    applyOverlayPlacement()
                }
            }
        }.apply {
            // A new value width, font size or secondary line resizes the pill; re-place
            // it so it grows away from the edge it is docked to instead of over it.
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
                if (resized && !dynamicIslandEnabled && !dragging) {
                    applyOverlayPlacement()
                }
            }
            // Compose resolves the window recomposer's lifecycle from the window's
            // rootView, which is this container now — the owners have to live here.
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        overlayRoot = root
        root.optOutOfForceDark()

        composeView = ComposeView(this).apply {
            setContent {
                FloatingGlucoseOverlay(
                    repository = settingsRepository,
                    historyFlow = glucoseRepository.getHistoryFlow(
                        System.currentTimeMillis() - FLOATING_HISTORY_WINDOW_MS,
                        Natives.getunit() == 1
                    ),
                    onUpdatePosition = { x, y -> updateViewPosition(x, y) },
                    onDragFinished = { persistViewPosition() },
                    cutoutDataFlow = cutoutData
                )
            }
        }
        
        // ... (LayoutParams init)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        // Initial position; corrected once the overlay has been measured.
        val savedPosition = settingsRepository.getPosition()
        if (savedPosition != null) {
            freeformPosition = savedPosition
        } else {
            legacyPixelPosition = settingsRepository.getLegacyPixelPosition()
        }
        safeArea = currentSafeArea()
        layoutParams.gravity = Gravity.TOP or Gravity.START
        placeFreeform()

        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        try {
            windowManager?.addView(root, layoutParams)
            root.requestApplyInsets()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateViewPosition(xDelta: Int, yDelta: Int) {
        val root = overlayRoot ?: return
        if (windowManager == null) return
        if (!dragging) {
            // Re-read once per drag: a 180° turn changes the navigation bar side
            // without a configuration change.
            dragging = true
            safeArea = currentSafeArea()
        }

        val area = safeArea
        val x = layoutParams.x + xDelta
        val y = layoutParams.y + yDelta
        if (area != null) {
            val (clampedX, clampedY) = clampOverlayPixels(x, y, area, root.width, root.height)
            layoutParams.x = clampedX
            layoutParams.y = clampedY
            freeformPosition = overlayPosition(clampedX, clampedY, area, root.width, root.height, freeformPosition)
        } else {
            layoutParams.x = x
            layoutParams.y = y
        }

        try {
            windowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistViewPosition() {
        dragging = false
        if (dynamicIslandEnabled) return
        settingsRepository.savePosition(freeformPosition)
    }

    private fun observeSettings() {
        serviceScope.launch {
            UiRefreshBus.events.collectLatest {
                glucoseRepository.refreshSensorSerial()
            }
        }

        serviceScope.launch {
            settingsRepository.isEnabled.collectLatest { enabled ->
                if (!enabled) {
                    stopSelf()
                }
            }
        }
        
        // Picks up "Reset position" from settings. Our own saves echo back unchanged.
        serviceScope.launch {
            settingsRepository.position.collectLatest { saved ->
                if (saved != null && saved != freeformPosition) {
                    freeformPosition = saved
                    legacyPixelPosition = null
                    applyOverlayPlacement()
                }
            }
        }

        serviceScope.launch {
            settingsRepository.isDynamicIslandEnabled.collectLatest { isIsland ->
                dynamicIslandEnabled = isIsland
                applyOverlayPlacement()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCutoutData(
        anchorView: View?,
        cutout: android.view.DisplayCutout?
    ): CutoutData {
        val density = resources.displayMetrics.density
        val edge = resolveIslandEdge(anchorView)
        val cutoutRect = when (edge) {
            CutoutEdge.LEFT -> cutout?.boundingRects?.minByOrNull { it.left }
            CutoutEdge.RIGHT -> cutout?.boundingRects?.maxByOrNull { it.right }
            CutoutEdge.BOTTOM -> cutout?.boundingRects?.maxByOrNull { it.bottom }
            else -> cutout?.boundingRects?.minByOrNull { it.top }
        }
        val gapPx = cutoutRect?.width()?.takeIf { it > 0 }
            ?: cutoutRect?.height()?.takeIf { it > 0 }
            ?: 0
        return CutoutData(
            size = if (gapPx > 0) (gapPx / density).dp else 0.dp,
            edge = edge
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveIslandEdge(anchorView: View? = composeView): CutoutEdge {
        val rotation = anchorView?.display?.rotation
            ?: windowManager?.defaultDisplay?.rotation
            ?: Surface.ROTATION_0
        return when (rotation) {
            // Android ROTATION_90/270 are mirrored relative to the user's
            // landscape left/right expectation on the Pixel punch-hole case.
            Surface.ROTATION_90 -> CutoutEdge.LEFT
            Surface.ROTATION_180 -> CutoutEdge.BOTTOM
            Surface.ROTATION_270 -> CutoutEdge.RIGHT
            else -> CutoutEdge.TOP
        }
    }

    private fun applyOverlayPlacement() {
        if (overlayRoot == null || windowManager == null) return

        if (dynamicIslandEnabled) {
            val islandEdge = cutoutData.value.edge.takeIf { it != CutoutEdge.NONE }
                ?: resolveIslandEdge()
            when (islandEdge) {
                CutoutEdge.LEFT -> {
                    layoutParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                CutoutEdge.RIGHT -> {
                    layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                CutoutEdge.BOTTOM -> {
                    layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                else -> {
                    layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
            }
        } else {
            layoutParams.gravity = Gravity.TOP or Gravity.START
            safeArea = currentSafeArea()
            placeFreeform()
        }

        try {
            windowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Resolves [freeformPosition] into layoutParams.x/y for the current safe area. */
    private fun placeFreeform() {
        val area = safeArea
        val root = overlayRoot
        if (area == null || root == null) {
            legacyPixelPosition?.let { (x, y) ->
                layoutParams.x = x
                layoutParams.y = y
            }
            return
        }
        val width = root.width
        val height = root.height
        val legacy = legacyPixelPosition
        if (legacy != null) {
            if (width == 0 || height == 0) {
                // Not measured yet: keep it near the old spot until the first layout.
                val (x, y) = clampOverlayPixels(legacy.first, legacy.second, area, 0, 0)
                layoutParams.x = x
                layoutParams.y = y
                return
            }
            freeformPosition = overlayPosition(legacy.first, legacy.second, area, width, height, OverlayPosition.DEFAULT)
            legacyPixelPosition = null
            settingsRepository.savePosition(freeformPosition)
        }
        val (x, y) = overlayPixels(freeformPosition, area, width, height)
        layoutParams.x = x
        layoutParams.y = y
    }

    /**
     * The display minus status bar, navigation bar and cutout, in the coordinates the
     * TOP|START overlay window is laid out in. Bars are reserved even while hidden: a
     * full-screen app must not let the overlay be dropped where they come back over it.
     */
    @Suppress("DEPRECATION")
    private fun currentSafeArea(): OverlaySafeArea? {
        val wm = windowManager ?: return null
        val modern = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val metrics = wm.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                OverlaySafeArea(
                    left = bounds.left + insets.left,
                    top = bounds.top + insets.top,
                    right = bounds.right - insets.right,
                    bottom = bounds.bottom - insets.bottom
                )
            }.getOrNull()?.takeIf { it.right > it.left && it.bottom > it.top }
        } else {
            null
        }
        return modern ?: runCatching {
            val display = wm.defaultDisplay
            val real = Point().also { display.getRealSize(it) }
            // getSize() leaves out the navigation bar, wherever it is docked.
            val usable = Point().also { display.getSize(it) }
            val navigationWidth = (real.x - usable.x).coerceAtLeast(0)
            val navigationHeight = (real.y - usable.y).coerceAtLeast(0)
            val navigationOnLeft = display.rotation == Surface.ROTATION_270
            val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
            OverlaySafeArea(
                left = if (navigationOnLeft) navigationWidth else 0,
                top = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0,
                right = real.x - if (navigationOnLeft) 0 else navigationWidth,
                bottom = real.y - navigationHeight
            )
        }.getOrNull()?.takeIf { it.right > it.left && it.bottom > it.top }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayRoot?.post {
            overlayRoot?.requestApplyInsets()
            // Freeform too: a spot that was fine in portrait can be off-screen or
            // under the status bar in landscape.
            applyOverlayPlacement()
        }
    }

    /**
     * Cutout detection used to hang on setOnApplyWindowInsetsListener of the
     * ComposeView itself. Since Compose 1.10 the AndroidComposeView child installs
     * its own OnApplyWindowInsetsListener on its *parent* when it attaches (see
     * InsetsListener.onViewAttachedToWindow), silently replacing ours: cutoutData
     * stayed NONE, so the island still docked to the correct edge via the rotation
     * fallback but rendered as the horizontal pill with the default gap in
     * landscape. This container is the window root; Compose never touches it,
     * and dispatchApplyWindowInsets runs before any listener anyway.
     */
    private class CutoutAwareContainer(
        context: Context,
        private val onInsets: (View, WindowInsets) -> Unit
    ) : FrameLayout(context) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
            onInsets(this, insets)
            return super.dispatchApplyWindowInsets(insets)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        if (overlayRoot != null) {
            try {
                windowManager?.removeView(overlayRoot)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayRoot = null
            composeView = null
        }
        super.onDestroy()
    }
}
