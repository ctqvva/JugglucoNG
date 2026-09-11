package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
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
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
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
        val channelName = getString(tk.glucodata.R.string.app_name)
        
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
            .setContentTitle(getString(tk.glucodata.R.string.app_name))
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
    private var freeformX = 0
    private var freeformY = 0
    
    // ...

    private fun setupOverlay() {
        if (composeView != null) return

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
            
            // Listen for Insets to detect Cutout reliably
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    cutoutData.value = resolveCutoutData(v, insets.displayCutout)
                    if (dynamicIslandEnabled) {
                        applyOverlayPlacement()
                    }
                }
                insets
            }
            
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
        
        // Initial position
        val (startX, startY) = settingsRepository.getPosition()
        freeformX = startX
        freeformY = startY
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = startX
        layoutParams.y = startY

        try {
            windowManager?.addView(composeView, layoutParams)
            composeView?.requestApplyInsets()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateViewPosition(xDelta: Int, yDelta: Int) {
        if (composeView == null || windowManager == null) return
        
        layoutParams.x += xDelta
        layoutParams.y += yDelta
        freeformX = layoutParams.x
        freeformY = layoutParams.y
        
        try {
            windowManager?.updateViewLayout(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistViewPosition() {
        if (dynamicIslandEnabled) return
        serviceScope.launch {
            settingsRepository.savePosition(freeformX, freeformY)
        }
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
        if (composeView == null || windowManager == null) return

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
            layoutParams.x = freeformX
            layoutParams.y = freeformY
        }

        try {
            windowManager?.updateViewLayout(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        composeView?.post {
            composeView?.requestApplyInsets()
            if (dynamicIslandEnabled) {
                applyOverlayPlacement()
            }
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        if (composeView != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
        super.onDestroy()
    }
}
