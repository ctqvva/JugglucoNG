package tk.glucodata.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ImageCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import tk.glucodata.ui.isAppInDarkTheme
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The materials a sensor is drawn in. Sensors are white or grey plastic, so the tones are the
 * theme's neutrals, white at the lit end and the surface tones in shadow, with only a breath of
 * the theme's colour; that keeps them reading as real objects on any wallpaper palette while
 * needing no colour of their own. The few coloured parts (an applicator's cap, a clear base's
 * tint) take the theme's primary.
 */
@Immutable
internal data class SensorTones(
    val highlight: Color,
    val body: Color,
    val shade: Color,
    val wall: Color,
    val wallDeep: Color,
    val ink: Color,
    val detail: Color,
    val accent: Color,
    val cap: Color,
    val glint: Color,
    val shadow: Color,
    val tint: Color,
    /** How far the sensor is turned, in degrees clockwise; the light stays put. */
    val turn: Float = 0f,
    /** How much the plan shortens front to back under the camera this part is seen from. */
    val squash: Float = SQUASH,
    /** How much of a part's thickness shows as wall under the same camera. */
    val wallShow: Float = WALL_SHOW,
) {
    /**
     * A move given in the picture's own directions (x right, y down, in drawing units on the
     * skin), as the turned sensor's drawing sees it: shadows and lit edges keep to the one light
     * however the sensor lies.
     */
    fun ground(x: Float, y: Float): Offset {
        val r = -turn * PI.toFloat() / 180f
        return Offset(x * cos(r) - y * sin(r), (x * sin(r) + y * cos(r)) / squash)
    }

    /** Straight down the picture by [screen] units. */
    fun drop(screen: Float): Offset = ground(0f, screen)

    /** A light casing with a little of the theme's third colour in it, warm on most palettes. */
    fun warm(): SensorTones = copy(
        highlight = lerp(highlight, tint, 0.05f),
        body = lerp(lerp(body, wall, 0.18f), tint, 0.12f),
        shade = lerp(lerp(shade, wall, 0.2f), tint, 0.14f),
        wall = lerp(wall, tint, 0.16f),
        wallDeep = lerp(wallDeep, tint, 0.14f),
    )

    /**
     * The colour of what the sensor lies on, thrown back into its shaded side and its shadow, as
     * a white object on a coloured card picks it up: the tile's colour carries into the drawing
     * without the plastic itself being coloured.
     */
    fun bounce(surface: Color): SensorTones = copy(
        shade = lerp(shade, surface, 0.1f),
        wall = lerp(wall, surface, 0.2f),
        wallDeep = lerp(wallDeep, surface, 0.28f),
        shadow = lerp(ink, surface, 0.25f).copy(alpha = shadow.alpha),
    )

    /** A mid grey casing, darker than [grey]: the Ottai's. */
    fun stone(): SensorTones = copy(
        highlight = lerp(highlight, wallDeep, 0.45f),
        body = lerp(body, wallDeep, 0.72f),
        shade = lerp(shade, wallDeep, 0.85f),
        wall = lerp(wall, ink, 0.3f),
        wallDeep = lerp(wallDeep, ink, 0.4f),
        detail = lerp(detail, ink, 0.3f),
    )

    /** A grey casing. */
    fun grey(): SensorTones = copy(
        highlight = lerp(highlight, wall, 0.3f),
        body = lerp(body, wall, 0.42f),
        shade = lerp(shade, wallDeep, 0.35f),
        wall = lerp(wall, wallDeep, 0.5f),
        wallDeep = lerp(wallDeep, ink, 0.22f),
        detail = lerp(detail, ink, 0.2f),
    )
}

@Composable
internal fun rememberSensorTones(): SensorTones {
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    return remember(scheme, dark) {
        if (dark) {
            val far = lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, 0.25f)
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, far, 0.06f),
                shade = lerp(Color.White, far, 0.24f),
                wall = lerp(Color.White, far, 0.46f),
                wallDeep = lerp(Color.White, far, 0.7f),
                ink = lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, 0.35f),
                detail = lerp(Color.White, far, 0.58f),
                accent = scheme.primary,
                cap = lerp(scheme.primary, scheme.primaryContainer, 0.5f),
                glint = Color.White,
                shadow = scheme.surfaceContainerLowest.copy(alpha = 0.2f),
                tint = scheme.tertiary,
            )
        } else {
            val far = lerp(scheme.outline, scheme.primary, 0.15f)
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, scheme.surfaceContainerHighest, 0.3f),
                shade = lerp(Color.White, scheme.surfaceContainerHighest, 0.85f),
                wall = lerp(scheme.surfaceContainerHighest, far, 0.3f),
                wallDeep = lerp(scheme.surfaceContainerHighest, far, 0.62f),
                ink = lerp(scheme.onSurface, scheme.onPrimaryContainer, 0.6f),
                detail = lerp(scheme.surfaceContainerHighest, far, 0.75f),
                accent = scheme.primary,
                cap = scheme.primary,
                glint = Color.White,
                shadow = scheme.onSurface.copy(alpha = 0.1f),
                tint = scheme.tertiary,
            )
        }
    }
}

/**
 * One sensor family, seen from above as its maker's top-down photos show it: the outline, the
 * real thickness against the real width, and the ports, windows, tabs and notches where they
 * really are, with no maker's name on them.
 * Drawn from code rather than shipped as bitmaps, so they follow the theme and stay sharp at any
 * size; the same drawing serves a list tile and a wizard hero.
 */
@Composable
internal fun SensorArt(type: SensorType, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember(context) { SensorImages.attach(context.applicationContext) }
    val tones = rememberSensorTones()
    Canvas(modifier = modifier) { drawSensorCached(type, tones) }
}

/**
 * Finished sensor drawings. Building a sensor up slice by slice takes far longer than a frame
 * allows for a whole list of them, so each is drawn once per type, tones and pixel size, kept in
 * memory, and saved to the app's cache so a fresh start shows it straight from disk instead of
 * drawing the list again in its first frame. The saved set is tied to the installed build, so an
 * update that changes a drawing never shows the old one.
 */
internal object SensorImages {
    private val memory = LruCache<String, ImageBitmap>(48)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sensor-art").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var folder: File? = null

    /** Where saved drawings live; the first call also clears drawings saved by older builds. */
    fun attach(context: Context) {
        if (folder != null) return
        val root = File(context.cacheDir, "sensor-art")
        val build = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        val current = File(root, build)
        folder = current
        writer.execute {
            current.mkdirs()
            root.listFiles()?.filter { it != current }?.forEach { it.deleteRecursively() }
        }
    }

    fun image(type: SensorType, tones: SensorTones, width: Int, height: Int, draw: () -> ImageBitmap): ImageBitmap {
        val key = "${type.name}-${Integer.toHexString(tones.hashCode())}-${width}x$height"
        memory.get(key)?.let { return it }
        val file = folder?.let { File(it, "$key.png") }
        val saved = file?.takeIf { it.isFile }?.let { stored ->
            runCatching { BitmapFactory.decodeFile(stored.path)?.asImageBitmap() }.getOrNull()
        }
        if (saved != null) {
            memory.put(key, saved)
            return saved
        }
        val drawn = draw()
        memory.put(key, drawn)
        if (file != null) {
            writer.execute {
                runCatching {
                    file.parentFile?.mkdirs()
                    val partial = File(file.path + ".part")
                    partial.outputStream().use { drawn.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                    partial.renameTo(file)
                }
            }
        }
        return drawn
    }
}

/** [drawSensor], drawn once per type, tones and size and shown from the image after that. */
internal fun DrawScope.drawSensorCached(type: SensorType, tones: SensorTones) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val image = SensorImages.image(type, tones, width, height) {
        ImageBitmap(width, height).also { image ->
            CanvasDrawScope().draw(this, layoutDirection, ImageCanvas(image), Size(width.toFloat(), height.toFloat())) {
                drawSensor(type, tones)
            }
        }
    }
    drawImage(image)
}

/**
 * Every drawing lives around the origin of a 100-unit square, scaled to fit the canvas.
 * Keeps the object's lean and light whatever the canvas is.
 */
private inline fun DrawScope.inUnitSquare(block: DrawScope.() -> Unit) {
    val unit = min(size.width, size.height) / 100f
    translate(size.width / 2f, size.height / 2f) {
        scale(unit, unit, pivot = Offset.Zero, block = block)
    }
}

/**
 * The sensor alone, as it comes out of its applicator: never the adhesive patch it is worn on,
 * which is tape, not part of the device.
 */
internal fun DrawScope.drawSensor(type: SensorType, tones: SensorTones) {
    inUnitSquare {
        // The camera looks down at the skin a little from the front: the plan shortens by
        // SQUASH while the side walls show by WALL_SHOW, the same angle for both. The plan is
        // shortened along the sensor's own length before it is turned, so a turned rectangle
        // keeps square corners instead of shearing into a parallelogram at list size.
        rotate(type.tilt, pivot = Offset.Zero) {
            scale(1f, SQUASH, pivot = Offset.Zero) {
                val turned = tones.copy(turn = type.tilt)
                when (type) {
                    SensorType.LIBRE -> drawLibre(turned)
                    SensorType.DEXCOM -> drawDexcom(turned)
                    SensorType.SIBIONICS -> drawSibionics(turned)
                    SensorType.AIDEX -> drawAiDex(turned)
                    SensorType.OTTAI -> drawOttai(turned)
                    SensorType.ICANHEALTH -> drawICan(turned)
                    SensorType.ANYTIME -> drawAnytime(turned)
                    SensorType.ACCUCHEK -> drawAccuChek(turned)
                    SensorType.CARESENS_AIR -> drawCareSensAir(turned)
                    SensorType.MQ -> drawMq(turned)
                    SensorType.MEDTRUM -> drawMedtrum(turned)
                }
            }
        }
    }
}

/**
 * How each sensor lies, in degrees clockwise: a few degrees off square, the way a
 * sensor put on by hand sits, alternating so a list of them doesn't lean one way.
 */
internal val SensorType.tilt: Float
    get() = when (this) {
        SensorType.LIBRE -> -8f
        SensorType.DEXCOM -> 9f
        SensorType.SIBIONICS -> 11f
        SensorType.AIDEX -> -10f
        SensorType.OTTAI -> 8f
        SensorType.ICANHEALTH -> -7f
        SensorType.ANYTIME -> -11f
        SensorType.ACCUCHEK -> 9f
        SensorType.CARESENS_AIR -> -9f
        SensorType.MQ -> -8f
        SensorType.MEDTRUM -> 10f
    }

/** The applicator a hero shows beside its sensor, for the families that have one drawn. */
internal fun DrawScope.drawApplicator(type: SensorType, tones: SensorTones) = inUnitSquare {
    when (type) {
        SensorType.ACCUCHEK -> drawAccuChekApplicator(tones)
        else -> drawCareSensApplicatorBody(tones)
    }
}

/** A phone held to a sensor, for the NFC and Bluetooth heroes. */
internal fun DrawScope.drawNfcPhone(tones: SensorTones) = inUnitSquare {
    drawPhoneBody(tones)
}

/**
 * A phone reading a sensor's code: on its screen the viewfinder's four corners, and a line
 * sweeping down it once as [sweep] runs from 0 to 1.
 */
internal fun DrawScope.drawScanPhone(tones: SensorTones, accent: Color, sweep: Float) = inUnitSquare {
    drawPhoneBody(tones)
    val half = 13f
    val arm = 6f
    val centre = Offset(0f, -2f)
    for (corner in listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f))) {
        val at = centre + Offset(corner.x * half, corner.y * half)
        val bracket = Path().apply {
            moveTo(at.x, at.y - corner.y * arm)
            lineTo(at.x, at.y)
            lineTo(at.x - corner.x * arm, at.y)
        }
        drawPath(bracket, accent, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
    if (sweep < 1f) {
        val y = centre.y - half + 2f * half * sweep
        drawLine(
            accent.copy(alpha = 0.9f * (1f - sweep * sweep)),
            Offset(centre.x - half + 2f, y),
            Offset(centre.x + half - 2f, y),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round,
        )
    }
}

// ---- The view ----
//
// Every sensor is seen from straight above, as the makers' own top-down shots show them, so its
// outline and the places of its ports and windows can be taken from a photo and checked against
// one. The camera leans 15 degrees towards the viewer, so the plan shortens to cos 15 and the
// side wall shows sin 15 of the sensor's thickness below its bottom edge: one angle for both, so
// a disc reads as a coin lying on the skin rather than a cut-out on a plinth. Light comes from
// the upper left, and each part casts its shadow down and to the right as far as it stands high.
// Sizes are in drawing units, the longest side of each sensor near 72, and each thickness is that
// sensor's real thickness against its real width.

/** How much of a sensor's thickness shows as wall below its bottom edge: sin 15°. */
private const val WALL_SHOW = 0.26f

/** How much the plan shortens from front to back: cos 15°. */
private const val SQUASH = 0.966f

/** The camera's lean towards the viewer, in degrees. */
private const val CAMERA_DEGREES = 15f

/** How far a part's shadow falls on the skin for each unit it stands high. */
private const val SHADOW_REACH = 0.32f

/** An outline in top view, shrunk evenly by its argument; insets give the rounded edge. */
private typealias Outline = (inset: Float) -> Path

private fun circle(radius: Float, at: Offset = Offset.Zero): Outline = { inset ->
    val r = max(radius - inset, 0.1f)
    Path().apply { addOval(Rect(at.x - r, at.y - r, at.x + r, at.y + r)) }
}

private fun roundRect(width: Float, height: Float, radius: Float, at: Offset = Offset.Zero): Outline = { inset ->
    val w = max(width / 2f - inset, 0.1f)
    val h = max(height / 2f - inset, 0.1f)
    Path().apply {
        addRoundRect(
            RoundRect(
                Rect(at.x - w, at.y - h, at.x + w, at.y + h),
                CornerRadius(min(max(radius - inset, 0f), min(w, h))),
            ),
        )
    }
}

private fun stadium(width: Float, height: Float, at: Offset = Offset.Zero): Outline =
    roundRect(width, height, min(width, height) / 2f, at)

/** An egg standing on its broad end: [taper] narrows the top and widens the bottom. */
private fun egg(a: Float, b: Float, taper: Float): Outline = { inset ->
    val ra = max(a - inset, 0.1f)
    val rb = max(b - inset, 0.1f)
    Path().apply {
        val steps = 144
        for (step in 0 until steps) {
            val t = step / steps.toFloat() * 2f * PI.toFloat()
            val x = ra * sin(t) * (1f - taper * cos(t))
            val y = -rb * cos(t)
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** A closed outline from a radius per angle (radians, 0 = +x, clockwise on screen). */
private fun polar(steps: Int = 240, radius: (Float) -> Float): Outline = { inset ->
    Path().apply {
        for (step in 0 until steps) {
            val angle = step / steps.toFloat() * 2f * PI.toFloat()
            val r = max(radius(angle) - inset, 0.1f)
            val x = cos(angle) * r
            val y = sin(angle) * r
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** A square-sided cut of [depth] centred on [at] radians, about [halfWidth] radians each side. */
private fun cut(angle: Float, at: Float, depth: Float, halfWidth: Float): Float {
    var delta = angle - at
    while (delta > PI) delta -= 2f * PI.toFloat()
    while (delta < -PI) delta += 2f * PI.toFloat()
    val x = delta / halfWidth
    return depth * exp(-(x * x * x * x * x * x))
}

/** A rounded rectangle whose left corners round by [left] and right corners by [right]. */
private fun roundEnds(width: Float, height: Float, left: Float, right: Float): Outline = { inset ->
    val w = max(width / 2f - inset, 0.1f)
    val h = max(height / 2f - inset, 0.1f)
    val l = CornerRadius(min(max(left - inset, 0f), min(w, h)))
    val r = CornerRadius(min(max(right - inset, 0f), min(w, h)))
    Path().apply { addRoundRect(RoundRect(Rect(-w, -h, w, h), l, r, r, l)) }
}

/** A regular octagon, flat side up, [apothem] from centre to side, its corners rounded by [round]. */
private fun octagon(apothem: Float, round: Float): Outline = { inset ->
    val a = max(apothem - inset, 0.1f)
    val corner = a / cos(PI.toFloat() / 8f)
    val cut = min(round, corner * 0.38f)
    Path().apply {
        for (side in 0 until 8) {
            val angle = (side * 45f - 112.5f) * PI.toFloat() / 180f
            val vertex = Offset(cos(angle) * corner, sin(angle) * corner)
            val prev = (side * 45f - 157.5f) * PI.toFloat() / 180f
            val next = (side * 45f - 67.5f) * PI.toFloat() / 180f
            val before = vertex + (Offset(cos(prev) * corner, sin(prev) * corner) - vertex) * (cut / (2f * corner * sin(PI.toFloat() / 8f)))
            val after = vertex + (Offset(cos(next) * corner, sin(next) * corner) - vertex) * (cut / (2f * corner * sin(PI.toFloat() / 8f)))
            if (side == 0) moveTo(before.x, before.y) else lineTo(before.x, before.y)
            quadraticTo(vertex.x, vertex.y, after.x, after.y)
        }
        close()
    }
}

/** Two circles joined by their common tangents: a teardrop when the radii differ. */
private fun capsule(from: Offset, fromRadius: Float, to: Offset, toRadius: Float) = Path().apply {
    val d = hypot(to.x - from.x, to.y - from.y)
    val phi = atan2(to.y - from.y, to.x - from.x)
    val alpha = acos(((fromRadius - toRadius) / d).coerceIn(-1f, 1f))
    val deg = 180f / PI.toFloat()
    arcTo(
        Rect(from.x - fromRadius, from.y - fromRadius, from.x + fromRadius, from.y + fromRadius),
        (phi + alpha) * deg,
        (2f * PI.toFloat() - 2f * alpha) * deg,
        forceMoveTo = true,
    )
    arcTo(
        Rect(to.x - toRadius, to.y - toRadius, to.x + toRadius, to.y + toRadius),
        (phi - alpha) * deg,
        2f * alpha * deg,
        forceMoveTo = false,
    )
    close()
}


/** A hole into the casing: dark, its far inner wall lit, a bright lip on the near edge. */
private fun DrawScope.hole(tones: SensorTones, center: Offset, radius: Float) {
    drawCircle(tones.ink, radius, center)
    drawArc(
        color = tones.detail,
        startAngle = 200f - tones.turn,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = center - Offset(radius * 0.72f, radius * 0.72f),
        size = Size(radius * 1.44f, radius * 1.44f),
        style = Stroke(width = radius * 0.4f, cap = StrokeCap.Round),
    )
    drawArc(
        color = tones.glint.copy(alpha = 0.8f),
        startAngle = 30f - tones.turn,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = center - Offset(radius * 1.1f, radius * 1.1f),
        size = Size(radius * 2.2f, radius * 2.2f),
        style = Stroke(width = radius * 0.22f, cap = StrokeCap.Round),
    )
}

/** A parting line or groove: a dark cut with the light catching its lower edge. */
private fun DrawScope.groove(tones: SensorTones, path: Path, width: Float = 0.9f, depth: Float = 0.8f) {
    val lip = tones.ground(0.25f, 0.5f)
    translate(lip.x, lip.y) {
        drawPath(path, tones.glint.copy(alpha = 0.55f), style = Stroke(width = width, cap = StrokeCap.Round))
    }
    drawPath(path, tones.detail.copy(alpha = depth), style = Stroke(width = width, cap = StrokeCap.Round))
}

/** Centres an object [thickness] thick in the square, so tall and flat sensors sit alike. */
private inline fun DrawScope.seated(tones: SensorTones, thickness: Float, block: DrawScope.() -> Unit) {
    val up = tones.drop(-thickness * tones.wallShow / 2f)
    translate(up.x, up.y, block)
}

/** Far enough out to hold every drawing, for cutting a band along the inside of a shape. */
private val EVERYWHERE = Rect(-200f, -200f, 200f, 200f)

/** [path] moved by [by]. */
private fun Path.moved(by: Offset): Path = Path().apply { addPath(this@moved, by) }

/** Everything except [path] moved by [by]: clipped to [path], a band along its far side. */
private fun outside(path: Path, by: Offset): Path = Path().apply {
    op(Path().apply { addRect(EVERYWHERE) }, path.moved(by), PathOperation.Difference)
}

/**
 * A shallow recess in the face, [depth] deep: its floor a shade down, the rim's shadow lying
 * along the side nearer the light and the far inner wall catching it.
 */
private fun DrawScope.recess(tones: SensorTones, path: Path, depth: Float = 0.8f, floor: Color? = null) {
    drawPath(path, floor ?: lerp(tones.body, tones.shade, 0.35f))
    clipPath(path) {
        drawPath(outside(path, tones.ground(depth, depth)), tones.ink.copy(alpha = 0.2f))
        drawPath(outside(path, tones.ground(-depth * 0.6f, -depth * 0.6f)), tones.highlight.copy(alpha = 0.85f))
    }
}

/** A dark opening through the face: black inside, its far inner wall just lit. */
private fun DrawScope.pit(tones: SensorTones, path: Path, depth: Float = 0.7f) {
    drawPath(path, tones.ink)
    clipPath(path) {
        drawPath(outside(path, tones.ground(-depth, -depth)), lerp(tones.ink, tones.detail, 0.55f))
    }
}

/**
 * A part standing [height] proud of the face: its shadow on the face, its side showing under
 * the near edge, its top lit along the edge towards the light and shaded along the other.
 */
private fun DrawScope.raised(tones: SensorTones, path: Path, height: Float, top: Color? = null) {
    val cast = tones.ground(0.55f, 1f) * (height * SHADOW_REACH * 2f) + tones.drop(height * tones.wallShow)
    drawPath(path.moved(cast), tones.shadow.copy(alpha = tones.shadow.alpha * 2.5f))
    drawPath(path.moved(tones.drop(height * tones.wallShow)), tones.wall)
    drawPath(path, top ?: tones.body)
    val edge = height * 0.5f + 0.3f
    clipPath(path) {
        drawPath(outside(path, tones.ground(edge, edge)), tones.highlight.copy(alpha = 0.9f))
        drawPath(outside(path, tones.ground(-edge, -edge)), tones.shade.copy(alpha = 0.9f))
    }
}

/**
 * One part of a sensor, built up from the skin as it really stands: its shadow, as long as the
 * part stands high; a straight side; then the top edge rounding over across [bevel] into the
 * face, flat or [dome]d. Each slice of the edge is shaded by how far it has turned towards the
 * light, so the edge rolls the way a moulded edge does instead of a lid sitting on a can. [base]
 * lifts the part onto the one below; [band] darkens the lowest share of the side, for a casing
 * on a darker foot, and [seam] draws the parting line of a two-piece casing at that share of
 * its height. A part on the skin casts the shadow of everything it carries, [stands] high in
 * all. [face] draws on the top.
 */
private fun DrawScope.part(
    tones: SensorTones,
    outline: Outline,
    thickness: Float,
    bevel: Float,
    base: Float = 0f,
    dome: Boolean = false,
    band: Float = 0f,
    stands: Float = thickness,
    shadow: Boolean = base == 0f,
    seam: Float = 0f,
    face: DrawScope.() -> Unit = {},
) {
    val top = outline(0f)
    val bounds = top.getBounds()
    val reach = max(bounds.width, bounds.height) / 2f
    val lift = tones.drop(-base * tones.wallShow)
    // The light's way across the part, upper left to lower right of the picture.
    val lit = bounds.center + tones.ground(-reach * 0.75f, -reach * 0.75f)
    val dim = bounds.center + tones.ground(reach * 0.75f, reach * 0.75f)

    if (shadow) {
        // The shadow, sharp near the part and fading as it reaches out: stacked passes stand in
        // for a blur, which a Canvas path cannot have.
        val far = tones.ground(0.55f, 1f) * (stands * SHADOW_REACH) + tones.drop(stands * tones.wallShow)
        for (pass in 1..5) {
            val at = far * (pass / 5f)
            translate(at.x, at.y) { drawPath(top, tones.shadow) }
        }
    }
    translate(lift.x, lift.y) {
        val side = Brush.linearGradient(
            0f to tones.shade,
            0.45f to tones.wall,
            1f to tones.wallDeep,
            start = bounds.center + tones.ground(-reach, 0f),
            end = bounds.center + tones.ground(reach, 0f),
        )
        val foot = Brush.linearGradient(
            0f to lerp(tones.wall, tones.ink, 0.35f),
            1f to lerp(tones.wallDeep, tones.ink, 0.55f),
            start = bounds.center + tones.ground(-reach, 0f),
            end = bounds.center + tones.ground(reach, 0f),
        )
        // The edge's colours along the light's way, from the upright side through the turn of
        // the edge to the face: one opaque fill per slice, so no seams show between them.
        val upright = listOf(tones.shade, tones.wall, tones.wallDeep)
        val turning = listOf(tones.highlight, lerp(tones.body, tones.shade, 0.5f), tones.wall)
        val facing = listOf(tones.highlight, tones.body, lerp(tones.body, tones.shade, 0.3f))
        fun edge(up: Float): Brush {
            val from = if (up < 0.5f) upright else turning
            val to = if (up < 0.5f) turning else facing
            val t = if (up < 0.5f) up * 2f else up * 2f - 1f
            return Brush.linearGradient(
                0f to lerp(from[0], to[0], t),
                0.5f to lerp(from[1], to[1], t),
                1f to lerp(from[2], to[2], t),
                start = lit,
                end = dim,
            )
        }
        val faceBrush = if (dome) {
            Brush.radialGradient(
                0f to tones.highlight,
                0.4f to tones.highlight,
                0.8f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.6f),
                center = bounds.center + tones.ground(-reach * 0.28f, -reach * 0.32f),
                radius = reach * 1.2f,
            )
        } else {
            Brush.linearGradient(
                0f to tones.highlight,
                0.55f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.3f),
                start = lit,
                end = dim,
            )
        }

        // The edge's vertical reach: a round as tall as it is wide, unless the part is thinner.
        val round = min(bevel, thickness * 0.75f)
        val straight = thickness - round
        // A small round at the foot, so the side meets the skin softly.
        val foot0 = min(thickness * 0.15f, 1.2f)
        val wallSteps = max(ceil(straight * tones.wallShow / 0.3f).toInt(), 1)
        for (step in 0 until wallSteps) {
            val z = straight * step / wallSteps
            val tuck = if (z < foot0) foot0 * (1f - sqrt(1f - ((foot0 - z) / foot0).let { it * it })) else 0f
            val at = tones.drop((thickness - z) * tones.wallShow)
            val dark = band > 0f && z < thickness * band
            translate(at.x, at.y) {
                drawPath(outline(tuck), if (dark) foot else side)
                if (seam > 0f && step == (wallSteps * seam / (1f - round / thickness)).toInt().coerceIn(0, wallSteps - 1)) {
                    drawPath(outline(tuck), tones.wallDeep.copy(alpha = 0.7f), style = Stroke(width = 0.6f))
                }
            }
        }
        val edgeSteps = max(ceil(bevel / 0.35f).toInt(), 6)
        // What shows of the top from above, the face and the turn of its edge: details stay on it.
        val seen = outline(bevel)
        for (step in 0 until edgeSteps) {
            val turn = (PI.toFloat() / 2f) * step / edgeSteps
            val z = straight + round * sin(turn)
            val inset = bevel * (1f - cos(turn))
            val at = tones.drop((thickness - z) * tones.wallShow)
            val slice = outline(inset)
            translate(at.x, at.y) { drawPath(slice, edge(sin(turn))) }
            if (step % 3 == 0) seen.op(seen, slice.moved(at), PathOperation.Union)
        }
        drawPath(outline(bevel), faceBrush)
        seen.op(seen, top, PathOperation.Intersect)
        clipPath(seen, block = face)
    }
}

/**
 * Clear plastic: the skin and tile show through, its edges catch the light, the edge towards the
 * light bright and the far one dark. [thickness] deep, standing [base] above the skin.
 */
private fun DrawScope.glass(
    tones: SensorTones,
    outline: Outline,
    thickness: Float,
    base: Float = 0f,
    stands: Float = thickness,
) {
    val top = outline(0f)
    val bounds = top.getBounds()
    val reach = max(bounds.width, bounds.height) / 2f
    val lit = bounds.center + tones.ground(-reach * 0.75f, -reach * 0.75f)
    val dim = bounds.center + tones.ground(reach * 0.75f, reach * 0.75f)
    if (base == 0f) {
        val far = tones.ground(0.55f, 1f) * (stands * SHADOW_REACH) + tones.drop(stands * tones.wallShow)
        for (pass in 1..5) {
            val at = far * (pass / 5f)
            translate(at.x, at.y) { drawPath(top, tones.shadow.copy(alpha = tones.shadow.alpha * 0.7f)) }
        }
    }
    val lift = tones.drop(-base * tones.wallShow)
    translate(lift.x, lift.y) {
        val floor = tones.drop(thickness * tones.wallShow)
        val body = Path().apply {
            op(top.moved(floor), top, PathOperation.Union)
        }
        val steps = max(ceil(thickness * tones.wallShow / 0.5f).toInt(), 1)
        for (step in 1..steps) {
            val at = tones.drop(thickness * tones.wallShow * step / steps)
            body.op(body, top.moved(at), PathOperation.Union)
        }
        val tint = lerp(tones.highlight, tones.accent, 0.45f)
        drawPath(body, tint.copy(alpha = 0.32f))
        drawPath(top.moved(floor), tones.wallDeep.copy(alpha = 0.5f), style = Stroke(width = 0.5f))
        drawPath(top, tint.copy(alpha = 0.18f))
        drawPath(
            top,
            Brush.linearGradient(
                0f to tones.glint,
                0.5f to tones.glint.copy(alpha = 0.35f),
                1f to tones.wallDeep.copy(alpha = 0.6f),
                start = lit,
                end = dim,
            ),
            style = Stroke(width = 0.8f),
        )
        drawPath(outline(1.4f), tones.glint.copy(alpha = 0.3f), style = Stroke(width = 0.5f))
    }
}

// ---- The sensors ----

/**
 * Libre 3: a 21 mm disc 2.9 mm thick. Its one feature, a third of the way up and just left of
 * centre, is a clear round window holding the keyed filament slot, with a small nub at its right.
 */
private fun DrawScope.drawLibre(tones: SensorTones) = seated(tones, 10f) {
    part(tones, circle(35f), thickness = 10f, bevel = 2.4f) {
        val window = Offset(-2.8f, -13f)
        drawCircle(lerp(tones.body, tones.shade, 0.35f), 9.5f, window)
        drawCircle(tones.wall.copy(alpha = 0.8f), 9.5f, window, style = Stroke(width = 0.9f))
        drawCircle(tones.wall.copy(alpha = 0.45f), 8.4f, window, style = Stroke(width = 0.5f))
        drawArc(
            color = tones.glint,
            startAngle = 190f - tones.turn,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = window - Offset(8.9f, 8.9f),
            size = Size(17.8f, 17.8f),
            style = Stroke(width = 0.7f, cap = StrokeCap.Round),
        )
        val slot = stadium(4.2f, 11.1f, window + Offset(-0.3f, 0f))(0f)
        drawPath(slot, tones.ink)
        drawPath(slot, tones.wall, style = Stroke(width = 0.6f))
        drawRect(tones.ink, topLeft = window + Offset(1.6f, -0.9f), size = Size(0.8f, 1.6f))
        drawCircle(lerp(tones.shade, tones.wall, 0.4f), 1.2f, window + Offset(7.6f, 0.3f))
    }
}

/**
 * CT4 radius every 5 degrees from +x, clockwise on screen, traced from a top-down photo: a
 * lopsided egg, its narrow end down and to the right.
 */
private val CT4_RADII = floatArrayOf(
    28.47f, 28.60f, 28.89f, 29.36f, 30.01f, 30.84f, 31.84f, 32.93f, 34.08f, 35.22f, 36.36f, 37.28f,
    37.87f, 38.00f, 37.83f, 37.47f, 37.14f, 36.74f, 36.34f, 35.65f, 34.72f, 33.55f, 32.42f, 31.44f,
    30.70f, 30.23f, 29.98f, 29.86f, 29.75f, 29.63f, 29.52f, 29.51f, 29.63f, 29.92f, 30.35f, 30.91f,
    31.50f, 32.10f, 32.69f, 33.24f, 33.74f, 34.18f, 34.55f, 34.87f, 35.12f, 35.31f, 35.45f, 35.60f,
    35.70f, 35.72f, 35.63f, 35.48f, 35.32f, 35.15f, 34.94f, 34.68f, 34.40f, 34.11f, 33.89f, 33.74f,
    33.60f, 33.33f, 32.93f, 32.43f, 31.90f, 31.26f, 30.52f, 29.77f, 29.16f, 28.74f, 28.53f, 28.44f,
)

/** The radius at [angle] in a table taken at even steps round the full turn. */
private fun tableRadius(table: FloatArray, angle: Float): Float {
    val turn = (angle / (2f * PI.toFloat())).mod(1f) * table.size
    val index = turn.toInt() % table.size
    val next = (index + 1) % table.size
    val t = turn - turn.toInt()
    return table[index] + (table[next] - table[index]) * t
}

/**
 * G7 radius every 5 degrees from +x, clockwise on screen, fitted to a top-down photo of the
 * sensor: an oval that is fuller on the right, wider than tall, the side cuts left out.
 */
private val G7_RADII = floatArrayOf(
    36.72f, 36.70f, 36.68f, 36.63f, 36.53f, 36.37f, 36.20f, 36.02f,
    35.85f, 35.65f, 35.39f, 35.03f, 34.60f, 34.16f, 33.73f, 33.34f,
    32.95f, 32.54f, 32.10f, 31.69f, 31.44f, 31.42f, 31.68f, 32.16f,
    32.73f, 33.29f, 33.77f, 34.21f, 34.66f, 35.17f, 35.72f, 36.22f,
    36.59f, 36.77f, 36.83f, 36.85f, 36.93f, 37.07f, 37.19f, 37.16f,
    36.90f, 36.41f, 35.78f, 35.15f, 34.59f, 34.09f, 33.60f, 33.04f,
    32.42f, 31.81f, 31.36f, 31.19f, 31.31f, 31.66f, 32.11f, 32.55f,
    32.94f, 33.29f, 33.65f, 34.06f, 34.50f, 34.91f, 35.24f, 35.50f,
    35.72f, 35.94f, 36.18f, 36.42f, 36.62f, 36.74f, 36.78f, 36.76f,
)

private fun g7Radius(angle: Float): Float = tableRadius(G7_RADII, angle)

/**
 * G7: 27.5 x 24 mm, 4.6 thick, its plan fitted to a top-down photo, a square cut in the middle of
 * each side. The top is a raised plateau set up and to the left, a broad slope around it widest
 * at the right and the foot. The round port sits left of centre on the long axis, the pinhole
 * below it.
 */
private fun DrawScope.drawDexcom(tones: SensorTones) = seated(tones, 12.4f) {
    val outline = polar { angle ->
        g7Radius(angle) - cut(angle, 0f, 2.3f, 0.085f) - cut(angle, PI.toFloat(), 2.3f, 0.085f)
    }
    val grey = tones.warm()
    part(grey, outline, thickness = 12.4f, bevel = 1.2f) {
        val plateau = Path().apply {
            val steps = 144
            for (step in 0 until steps) {
                val angle = step / steps.toFloat() * 2f * PI.toFloat()
                val r = g7Radius(angle)
                val x = cos(angle) * r * 0.92f - 1.9f
                val y = sin(angle) * r * 0.85f
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val bounds = plateau.getBounds()
        // The slope round the plateau: lit at the upper left, falling into shade at the lower right.
        drawPath(
            outline(0f),
            Brush.linearGradient(
                0f to grey.highlight,
                0.5f to lerp(grey.body, grey.wall, 0.45f),
                1f to grey.wall,
                start = tones.ground(-30f, -30f),
                end = tones.ground(30f, 30f),
            ),
        )
        drawPath(
            plateau,
            Brush.linearGradient(
                0f to grey.highlight,
                0.6f to grey.body,
                1f to lerp(grey.body, grey.shade, 0.4f),
                start = bounds.center + tones.ground(-26f, -26f),
                end = bounds.center + tones.ground(26f, 26f),
            ),
        )
        drawPath(plateau, grey.wall.copy(alpha = 0.35f), style = Stroke(width = 0.5f))
        hole(tones, Offset(-16f, -0.4f), 2.9f)
        drawRoundRect(
            tones.ink,
            topLeft = Offset(-15.7f, 9.3f),
            size = Size(1.6f, 3f),
            cornerRadius = CornerRadius(0.8f),
        )
    }
}

/**
 * The Sibionics pair, the Sibionics 2 tipped up behind the GS1 at their published sizes against
 * each other, so its clear base shows: GS1 33.5 x 20.0 x 5.3 mm, a flat white top with a lightly
 * rounded edge on a thin clear base, Sibionics 2 (GS1ECO, the reusable GS1-P2 transmitter) 35 x
 * 24.4 x 5.9 mm. Both traced from one top-down photo of the two side by side. GS1: a pill 1.62 as
 * long as it is wide, its corners rounded to 0.4 of its width so the ends stay a little flat; a
 * round hole 0.15 of its length off centre holds a white keyed plug. Sibionics 2: a flat oblong
 * 1.43 as long as it is wide, corners rounded to 0.3 of its width, a white cover over a clear
 * base that shows a clip at each short end.
 */
private fun DrawScope.drawSibionics(tones: SensorTones) = seated(tones, 8f) {
    turned(tones, Offset(-11f, -15f), -SensorType.SIBIONICS.tilt, 35f * PAIR_SCALE / 72f, lean = 62f) { tones ->
        val cover = roundRect(72f, 50.4f, 15f)
        val frame: Outline = { inset ->
            Path().apply {
                op(roundRect(72.6f, 51f, 15.2f)(inset), roundRect(75f, 16f, 2.5f)(inset), PathOperation.Union)
            }
        }
        glass(tones, frame, thickness = 4.6f, stands = 12.1f)
        // The electronics show dark through the clear base.
        val edge = nearEdge(tones, frame)
        drawPath(sidePatch(tones, edge, 4.6f, 0f, -26f, 26f, 0.8f, 3.8f), lerp(tones.ink, tones.accent, 0.3f).copy(alpha = 0.35f))
        drawPath(sidePatch(tones, edge, 4.6f, 0f, -30f, 30f, 3.9f, 4.4f), tones.glint.copy(alpha = 0.7f))
        part(tones, cover, thickness = 7.5f, bevel = 4.5f, base = 4.6f)
    }
    turned(tones, Offset(10f, 15f), -24f, 33.5f * PAIR_SCALE / 72f) { tones ->
        val shell = roundRect(72f, 44.4f, 19f)
        glass(tones, roundRect(72.4f, 44.8f, 19.2f), thickness = 2.6f, stands = 11.4f)
        part(tones, shell, thickness = 8.8f, bevel = 2.6f, base = 2.6f) {
            keyedPort(tones, Offset(-11f, 0f), 5.4f)
        }
    }
}

/** Millimetres to drawing units in a pair: both fit the tile at their true sizes against each other. */
private const val PAIR_SCALE = 1.86f

/** A round hole holding a white plug, its key cut in it: the filament port of a Sibionics or i6. */
private fun DrawScope.keyedPort(tones: SensorTones, at: Offset, radius: Float) {
    pit(tones, Path().apply { addOval(Rect(at.x - radius, at.y - radius, at.x + radius, at.y + radius)) })
    val plug = radius * 0.82f
    drawCircle(
        Brush.radialGradient(
            0f to tones.highlight,
            1f to tones.shade,
            center = at + tones.ground(-plug * 0.3f, -plug * 0.3f),
            radius = plug * 1.3f,
        ),
        plug,
        at,
    )
    val k = plug / 4.2f
    val key = Path().apply {
        moveTo(at.x + 1.6f * k, at.y - 2.2f * k)
        lineTo(at.x - 1.8f * k, at.y - 2.2f * k)
        lineTo(at.x - 1.8f * k, at.y + 2.2f * k)
        lineTo(at.x + 1.6f * k, at.y + 2.2f * k)
        moveTo(at.x + 0.2f * k, at.y - 0.5f * k)
        lineTo(at.x + 1.6f * k, at.y - 0.5f * k)
        lineTo(at.x + 1.6f * k, at.y + 0.7f * k)
    }
    drawPath(key, tones.ink, style = Stroke(width = 1.1f * k))
    for (notch in listOf(35f, 125f, 215f, 305f)) {
        drawArc(
            color = tones.ink,
            startAngle = notch,
            sweepAngle = 24f,
            useCenter = false,
            topLeft = at - Offset(plug - 0.5f * k, plug - 0.5f * k),
            size = Size((plug - 0.5f * k) * 2f, (plug - 0.5f * k) * 2f),
            style = Stroke(width = 1.3f * k),
        )
    }
}

/**
 * Draws one of a pair at [at], turned [by] degrees further and drawn at [size] of its own
 * measure, the light held where it was. [lean] tips it towards the viewer: 15 is the camera every
 * sensor is seen from, more shows more of its side, as a sensor lying tipped against another
 * does. The plan is shortened along its own axes, so a tipped part must end up square to the
 * picture ([by] undoing the family's tilt) and be drawn already turned, or it shears.
 */
private inline fun DrawScope.turned(
    tones: SensorTones,
    at: Offset,
    by: Float,
    size: Float,
    lean: Float = CAMERA_DEGREES,
    block: DrawScope.(SensorTones) -> Unit,
) {
    val radians = lean * PI.toFloat() / 180f
    val squash = cos(radians)
    // Undo the camera's shortening, turn, and shorten again, so this one doesn't shear either.
    translate(at.x, at.y) {
        scale(size, size / tones.squash, pivot = Offset.Zero) {
            rotate(by, pivot = Offset.Zero) {
                scale(1f, squash, pivot = Offset.Zero) {
                    block(tones.copy(turn = tones.turn + by, squash = squash, wallShow = sin(radians)))
                }
            }
        }
    }
}

/**
 * The near edge of [outline] as the camera in [tones] sees it, for drawing on a part's side:
 * for a place [across] the picture, the point of the outline lowest in the picture there.
 */
private fun nearEdge(tones: SensorTones, outline: Outline): (Float) -> Offset {
    val measure = PathMeasure().apply { setPath(outline(0f), true) }
    val down = tones.ground(0f, 1f).let { it / it.getDistance() }
    val side = Offset(down.y, -down.x)
    val points = (0 until 360).map { measure.getPosition(measure.length * it / 360f) }
    return { across ->
        points.filter { abs(it.x * side.x + it.y * side.y - across) < 1.2f }
            .maxByOrNull { it.x * down.x + it.y * down.y }
            ?: Offset.Zero
    }
}

/**
 * A patch on a part's near side: from [from] to [to] across the picture and from [low] to [high]
 * above the skin, on a part [thickness] thick standing [base] above it.
 */
private fun DrawScope.sidePatch(
    tones: SensorTones,
    edge: (Float) -> Offset,
    thickness: Float,
    base: Float,
    from: Float,
    to: Float,
    low: Float,
    high: Float,
): Path = Path().apply {
    val steps = 16
    fun at(across: Float, z: Float) = edge(across) + tones.drop((base + thickness - z) * tones.wallShow - base * tones.wallShow)
    for (step in 0..steps) {
        val p = at(from + (to - from) * step / steps, high)
        if (step == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    for (step in steps downTo 0) {
        val p = at(from + (to - from) * step / steps, low)
        lineTo(p.x, p.y)
    }
    close()
}

/**
 * AiDEX X and LinX, measured from a top-down photo: a disc 22 mm across and 4.22 mm thick, its top
 * flat and its edge rounded over. Just under half way out, nearly straight up from the centre, a
 * flat round plug sits in a dark gap, a rounded slot through it lying along the radius.
 */
private fun DrawScope.drawAiDex(tones: SensorTones) = seated(tones, 13.8f) {
    part(tones, circle(36f), thickness = 13.8f, bevel = 4f) {
        val port = Offset(5f, -16f)
        val gap = Path().apply { addOval(Rect(port.x - 10f, port.y - 10f, port.x + 10f, port.y + 10f)) }
        // The plug sits a little down in its gap, so the gap shows widest on the near side.
        val plugAt = port + tones.ground(0f, -0.5f)
        val plug = Path().apply { addOval(Rect(plugAt.x - 8.4f, plugAt.y - 8.4f, plugAt.x + 8.4f, plugAt.y + 8.4f)) }
        pit(tones, gap, depth = 0.5f)
        recess(tones, plug, depth = 0.5f, floor = lerp(tones.body, tones.shade, 0.35f))
        drawArc(
            color = tones.glint.copy(alpha = 0.8f),
            startAngle = -75f - tones.turn,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = port - Offset(9.3f, 9.3f),
            size = Size(18.6f, 18.6f),
            style = Stroke(width = 0.6f, cap = StrokeCap.Round),
        )
        // The slot lies along the radius, its long side pointing at the centre.
        val along = atan2(port.y, port.x) * 180f / PI.toFloat() + 90f
        rotate(along, pivot = port) {
            pit(tones, roundRect(8f, 12.5f, 2.2f, port)(0f), depth = 0.7f)
            drawPath(roundRect(4.4f, 9.4f, 1.2f, port)(0f), lerp(tones.ink, tones.wall, 0.4f))
        }
    }
}

/**
 * M8, traced from a top-down photo beside the others: a coin 24 mm across and 3.5 mm thick in mid
 * grey plastic, its edge rounded over. 0.37 of the way out, a raised rounded frame lying along
 * the radius holds a white-lined dark oblong opening.
 */
private fun DrawScope.drawOttai(tones: SensorTones) = seated(tones, 10.5f) {
    val stone = tones.stone()
    part(stone, circle(36f), thickness = 10.5f, bevel = 4.5f) {
        val toward = -5f
        val button = Offset(13.5f * cos(toward * PI.toFloat() / 180f), 13.5f * sin(toward * PI.toFloat() / 180f))
        rotate(toward, pivot = button) {
            raised(stone, roundRect(17f, 14f, 5.5f, button)(0f), 1.1f, top = lerp(stone.body, stone.highlight, 0.35f))
            recess(stone, roundRect(11f, 7.6f, 3f, button)(0f), depth = 0.5f, floor = lerp(tones.highlight, tones.body, 0.3f))
            pit(stone, roundRect(8.4f, 5f, 2.2f, button)(0f), depth = 0.5f)
        }
    }
}

/**
 * The iCan pair, the i3 behind the i6 at their published sizes against each other: i3 32 mm
 * across and 5.7 mm thick, i6 23.3 mm across and 3.95 mm thick. i3, traced from a top-down photo
 * beside the others: a shallow teardrop recess outlined by a groove, its round end towards the
 * rim holding a square window, its tip at the centre; two square contacts side by side and a
 * short one by the tip; small notches break the rim.
 * i6: a rounded octagon on a darker foot, a raised grey ring round its keyed port.
 */
private fun DrawScope.drawICan(tones: SensorTones) = seated(tones, 9f) {
    turned(tones, Offset(-11f, -10f), -6f, 32f * PAIR_SCALE / 72f) { tones ->
        part(tones, circle(36f), thickness = 12.8f, bevel = 4f) {
            val drop = capsule(Offset(0f, -19f), 13f, Offset(0f, 0f), 5.5f)
            recess(tones, drop, depth = 0.6f, floor = lerp(tones.body, tones.shade, 0.2f))
            groove(tones, drop, width = 1.1f)
            val window = roundRect(5.4f, 5.4f, 0.6f, Offset(0.5f, -22f))(0f)
            pit(tones, window, depth = 0.4f)
            recess(tones, roundRect(3.8f, 3.8f, 0.4f, Offset(0.5f, -22f))(0f), depth = 0.5f, floor = lerp(tones.body, tones.wall, 0.45f))
            pit(tones, roundRect(2.6f, 2.6f, 0.4f, Offset(-3.6f, -5f))(0f))
            pit(tones, roundRect(2.6f, 2.6f, 0.4f, Offset(3.6f, -5f))(0f))
            pit(tones, roundRect(2.2f, 2.2f, 0.4f, Offset(0f, 0.6f))(0f))
            for (notch in listOf(235f, 305f, 150f, 20f)) {
                rotate(notch, pivot = Offset.Zero) {
                    recess(tones, roundRect(1.6f, 4.4f, 0.6f, Offset(35.6f, 0f))(0f), depth = 0.5f)
                }
            }
        }
    }
    turned(tones, Offset(17f, 17f), -10f, 23.3f * PAIR_SCALE / 73.6f) { tones ->
        part(tones, octagon(34f, 7f), thickness = 12.5f, bevel = 4f, band = 0.3f) {
            val port = Offset(-3f, -10f)
            val ring = Path().apply { addOval(Rect(port.x - 7.4f, port.y - 7.4f, port.x + 7.4f, port.y + 7.4f)) }
            raised(tones, ring, 0.8f, top = lerp(tones.wall, tones.body, 0.35f))
            keyedPort(tones, port, 5f)
        }
    }
}

/**
 * The Anytime pair, the CT4 lying tipped up behind the 5 Pro so its side, seam and clip show. The 5 Pro is 23 mm across and 4.4 mm thick; the
 * CT4, which has no published size, is traced from a top-down photo beside sensors of known size:
 * 36.7 x 30.8 mm and about 6.5 mm thick, a lopsided egg, gently domed, its edge rounded over and
 * a seam low on its side. 5 Pro: a white disc set in a clear ring
 * a little lower than it, a clear band curving across its upper part, above it the port between
 * two short slots and a dot by the rim.
 */
private fun DrawScope.drawAnytime(tones: SensorTones) = seated(tones, 10f) {
    turned(tones, Offset(-6f, -13f), -SensorType.ANYTIME.tilt, 36.7f * PAIR_SCALE / 72f, lean = 62f) { tones ->
        // Lying with its narrow end to the left, its long side towards the viewer.
        val shell = polar { angle -> tableRadius(CT4_RADII, angle - 115f * PI.toFloat() / 180f) }
        part(tones, shell, thickness = 12.7f, bevel = 5f, dome = true, seam = 0.35f)
        // The lower shell's clip, a gap in its near side.
        val edge = nearEdge(tones, shell)
        drawPath(sidePatch(tones, edge, 12.7f, 0f, -20f, -6f, 0.4f, 3.8f), lerp(tones.wallDeep, tones.ink, 0.4f))
        drawPath(sidePatch(tones, edge, 12.7f, 0f, -18f, -8f, 0.4f, 2.6f), tones.wall)
    }
    turned(tones, Offset(18f, 17f), 8f, 23f * PAIR_SCALE / 72f) { tones ->
        glass(tones, circle(36f), thickness = 11.5f, stands = 13.8f)
        part(tones, circle(32.4f), thickness = 13.8f, bevel = 4.5f, shadow = false) {
            val band = Path().apply {
                op(
                    Path().apply { addOval(Rect(-34.3f, -76.5f, 34.3f, -7.5f)) },
                    Path().apply { addOval(Rect(-31f, -73.5f, 31f, -10.8f)) },
                    PathOperation.Difference,
                )
            }
            recess(tones, band, depth = 0.6f, floor = lerp(tones.highlight, tones.accent, 0.2f).copy(alpha = 0.55f))
            pit(tones, Path().apply { addOval(Rect(-1.2f, -21.2f, 1.2f, -18.8f)) })
            recess(tones, Path().apply { addOval(Rect(-3f, -23f, 3f, -17f)) }, depth = 0.5f)
            pit(tones, Path().apply { addOval(Rect(-1f, -21f, 1f, -19f)) })
            recess(tones, stadium(2.2f, 4.6f, Offset(-9f, -20f))(0f), depth = 0.5f)
            recess(tones, stadium(2.2f, 4.6f, Offset(9f, -20f))(0f), depth = 0.5f)
            recess(tones, Path().apply { addOval(Rect(-1.2f, -30.2f, 1.2f, -27.8f)) }, depth = 0.4f)
        }
    }
}

/**
 * SmartGuide sensor: 33.3 mm across and about 5.4 mm thick, measured from the maker's photos. A
 * cool light grey disc with one slight flat side, its edge rounded over, on a clear base that shows
 * round its foot. The filament port sits at the centre, a dark well under a clear lens in a grey
 * collar; a small dimple sits two thirds of the way towards the rim opposite the flat.
 */
private fun DrawScope.drawAccuChek(tones: SensorTones) = seated(tones, 11.7f) {
    // A circle with one short flat side, the corners where they meet rounded off.
    val disc: (Float) -> Outline = { grow ->
        polar { angle ->
            val round = 36f + grow
            val flat = (32.6f + grow * 0.4f) / max(sin(angle), 0.02f)
            val soft = 1.6f
            -soft * ln(exp(-round / soft) + exp(-flat / soft))
        }
    }
    val cool = tones.grey().let {
        it.copy(
            highlight = lerp(tones.highlight, tones.accent, 0.04f),
            body = lerp(it.body, tones.accent, 0.08f),
            shade = lerp(it.shade, tones.accent, 0.1f),
        )
    }
    glass(tones, disc(0.8f), thickness = 3.2f, stands = 11.7f)
    part(cool, disc(0f), thickness = 8.5f, bevel = 5f, base = 3.2f) {
        val port = Offset(0f, 0f)
        // A metal collar sunk in the cover, the dark well under its lens, the filament's key in it.
        recess(cool, Path().apply { addOval(Rect(-6.4f, -6.4f, 6.4f, 6.4f)) }, depth = 0.8f)
        drawCircle(
            Brush.radialGradient(
                0f to lerp(tones.highlight, cool.wall, 0.2f),
                1f to lerp(cool.wall, tones.ink, 0.25f),
                center = port + tones.ground(-1.8f, -1.8f),
                radius = 7f,
            ),
            5.4f,
            port,
        )
        drawCircle(lerp(tones.ink, cool.wall, 0.3f), 3.6f, port)
        drawRoundRect(lerp(tones.highlight, cool.wall, 0.35f), port - Offset(1.9f, 1.3f), Size(3.8f, 2.6f), CornerRadius(0.6f))
        pit(tones, roundRect(2.2f, 1f, 0.3f, port)(0f), depth = 0.3f)
        drawArc(
            color = tones.glint.copy(alpha = 0.75f),
            startAngle = 200f - tones.turn,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = port - Offset(4.6f, 4.6f),
            size = Size(9.2f, 9.2f),
            style = Stroke(width = 0.7f, cap = StrokeCap.Round),
        )
        // A small ring-shaped dimple two thirds out.
        val dimple = Offset(0f, -23f)
        recess(cool, Path().apply { addOval(Rect(dimple.x - 2.2f, dimple.y - 2.2f, dimple.x + 2.2f, dimple.y + 2.2f)) }, depth = 0.5f)
        drawCircle(cool.body, 1f, dimple)
    }
}

/** CareSens Air: 35.2 x 19.2 x 5.0 mm, a light grey cushioned capsule on a clear base; a ribbed dial with a button. */
private fun DrawScope.drawCareSensAir(tones: SensorTones) = seated(tones, 11.4f) {
    val grey = tones.grey()
    val outline = roundRect(80f, 43.6f, 20f)
    glass(tones, roundRect(80.8f, 44.4f, 20.4f), thickness = 2f, stands = 11.4f)
    part(grey, outline, thickness = 9.4f, bevel = 5.5f, base = 2f) {
        val dial = Offset(16.5f, 0f)
        val reach = 12.6f
        val disc = Path().apply { addOval(Rect(dial.x - reach, -reach, dial.x + reach, reach)) }
        recess(grey, disc, depth = 0.8f, floor = lerp(grey.body, grey.wall, 0.35f))
        clipPath(disc) {
            var y = -reach
            while (y < reach) {
                drawLine(grey.wall.copy(alpha = 0.7f), Offset(dial.x - reach, y), Offset(dial.x + reach, y), strokeWidth = 0.8f)
                y += 2.2f
            }
        }
        raised(grey, Path().apply { addOval(Rect(dial.x - 5.6f, -5.6f, dial.x + 5.6f, 5.6f)) }, 1f, top = lerp(tones.highlight, grey.body, 0.4f))
        pit(tones, Path().apply { addOval(Rect(-12.5f, -8f, -10.5f, -6f)) })
    }
}

/**
 * Glutec v3, traced from a top-down photo beside sensors of known size: 28.7 x 22 mm and about
 * 6 mm thick, light grey, tipped up so its side shows: a cover on a base, the base cut away
 * along its lower edge. Its corners round to 0.27 of its width at the tab end and 0.36 at the
 * other. A parting line runs across a sixth of its length in from the rounded end; at the tab end
 * a grey pull tab stands in a U-shaped dip in the top, open to the edge.
 */
private fun DrawScope.drawMq(tones: SensorTones) = seated(tones, 14.5f) {
    turned(tones, Offset.Zero, -SensorType.MQ.tilt, 1f, lean = 38f) { tones ->
        val grey = tones.grey()
        val body = roundEnds(72f, 55f, 15f, 20f)
        part(grey, body, thickness = 14.5f, bevel = 5.5f) {
            val dip = Path().apply {
                op(
                    Path().apply { addRect(Rect(-40f, -10f, -26f, 10f)) },
                    Path().apply { addOval(Rect(-36f, -10f, -16f, 10f)) },
                    PathOperation.Union,
                )
            }
            recess(grey, dip, depth = 1.4f)
            raised(
                grey.stone(),
                roundRect(5f, 14f, 1.6f, Offset(-33f, 0f))(0f),
                1.6f,
                top = lerp(grey.wall, grey.body, 0.3f),
            )
            groove(grey, Path().apply {
                moveTo(24f, -26.5f)
                lineTo(24f, 26.5f)
            }, width = 1.3f, depth = 1f)
        }
        // The base under the cover: its seam, and the cut-outs along its lower edge.
        val edge = nearEdge(grey, body)
        val seam = sidePatch(grey, edge, 14.5f, 0f, -31f, 30f, 6.2f, 6.7f)
        drawPath(seam, lerp(grey.wallDeep, grey.ink, 0.25f))
        drawPath(sidePatch(grey, edge, 14.5f, 0f, -9f, 11f, 0f, 4.4f), lerp(grey.wallDeep, grey.ink, 0.55f))
        for (notch in listOf(-27f to -21f, 21f to 27f)) {
            drawPath(sidePatch(grey, edge, 14.5f, 0f, notch.first, notch.second, 0f, 2.2f), lerp(grey.wallDeep, grey.ink, 0.35f))
        }
    }
}

/**
 * TouchCare Nano, traced from a top-down shot: a pillowy rounded slab 0.63 as wide as it is long,
 * the round tab of the clear base it clips into just showing past its top end.
 */
private fun DrawScope.drawMedtrum(tones: SensorTones) = seated(tones, 7f) {
    glass(tones, circle(7.5f, Offset(0f, -34f)), thickness = 1.6f)
    part(tones, roundRect(46f, 72f, 11f), thickness = 7f, bevel = 5.5f)
}

/**
 * The SmartGuide applicator, for the wizard hero: a white barrel with a flat top on a twist cap
 * ribbed at its foot, a pull tab running down a slot across the joint into a U cut in the cap.
 */
private fun DrawScope.drawAccuChekApplicator(tones: SensorTones) {
    val half = 25f
    val rise = 5.5f
    val top = -38f
    val shoulder = 4f
    val joint = 4f
    val bottom = 38f
    for (pass in 6 downTo 1) {
        drawOval(
            tones.shadow,
            topLeft = Offset(-half - pass * 0.6f + 1.5f, bottom - rise + pass * 0.7f),
            size = Size(half * 2f + pass * 1.2f, rise * 2f + 1f),
        )
    }
    fun band(upper: Float, lower: Float) = Path().apply {
        moveTo(-half, upper)
        lineTo(-half, lower)
        arcTo(Rect(-half, lower - rise, half, lower + rise), 180f, -180f, forceMoveTo = false)
        lineTo(half, upper)
        arcTo(Rect(-half, upper - rise, half, upper + rise), 0f, 180f, forceMoveTo = false)
        close()
    }
    val capDeep = lerp(tones.cap, tones.ink, 0.45f)
    val capLit = lerp(tones.cap, tones.highlight, 0.2f)
    val capBand = band(joint, bottom)
    drawPath(
        capBand,
        Brush.horizontalGradient(
            0f to capDeep,
            0.28f to capLit,
            0.6f to tones.cap,
            1f to capDeep,
            startX = -half,
            endX = half,
        ),
    )
    // The ribs follow the barrel: bunched at the edges, wide at the front.
    clipPath(capBand) {
        for (rib in 1 until 26) {
            val angle = rib / 26f * PI.toFloat()
            val x = -half * cos(angle)
            val curve = rise * sin(angle)
            drawLine(capDeep.copy(alpha = 0.5f), Offset(x, bottom - 17f + curve), Offset(x, bottom + curve), strokeWidth = 0.6f)
        }
        // The U cut the tab drops into.
        drawRoundRect(
            capDeep.copy(alpha = 0.55f),
            topLeft = Offset(-9f, joint - 2f),
            size = Size(18f, 26f),
            cornerRadius = CornerRadius(9f),
        )
    }
    val barrel = Path().apply {
        moveTo(-half, joint)
        lineTo(-half, top + shoulder)
        arcTo(Rect(-half, top - rise, half, top + rise + shoulder * 2f), 180f, 180f, forceMoveTo = false)
        lineTo(half, joint)
        arcTo(Rect(-half, joint - rise, half, joint + rise), 0f, 180f, forceMoveTo = false)
        close()
    }
    drawPath(
        barrel,
        Brush.horizontalGradient(
            0f to tones.shade,
            0.3f to tones.highlight,
            0.62f to tones.body,
            1f to tones.wall,
            startX = -half,
            endX = half,
        ),
    )
    val lid = Rect(-half + 1f, top - rise + 0.5f, half - 1f, top + rise + 0.5f)
    drawOval(
        Brush.linearGradient(
            0f to tones.highlight,
            1f to tones.body,
            start = lid.topLeft,
            end = lid.bottomRight,
        ),
        topLeft = lid.topLeft,
        size = lid.size,
    )
    // Slot and pull tab, front and centre.
    val slot = Path().apply { addRoundRect(RoundRect(Rect(-7f, -20f, 7f, 22f), CornerRadius(7f))) }
    drawPath(slot, Brush.verticalGradient(0f to tones.wall, 1f to tones.shade, startY = -20f, endY = 0f))
    val tab = Path().apply { addRoundRect(RoundRect(Rect(-5f, -8f, 5f, 24f), CornerRadius(5f))) }
    drawPath(tab, Brush.horizontalGradient(0f to capLit, 1f to capDeep, startX = -5f, endX = 5f))
    drawPath(tab, tones.glint.copy(alpha = 0.35f), style = Stroke(width = 0.5f))
}

private fun DrawScope.drawCareSensApplicatorBody(tones: SensorTones) {
    val half = 28f
    val rise = 8f
    val bottom = 34f
    rotate(-10f, pivot = Offset.Zero) {
        val shell = Path().apply {
            moveTo(-half, bottom)
            lineTo(-half, -12f)
            cubicTo(-half, -40f, half, -40f, half, -12f)
            lineTo(half, bottom)
            arcTo(Rect(-half, bottom - rise, half, bottom + rise), 0f, 180f, forceMoveTo = false)
            close()
        }
        drawPath(
            shell,
            Brush.horizontalGradient(
                0f to tones.shade,
                0.28f to tones.highlight,
                0.6f to tones.body,
                1f to tones.wall,
                startX = -half,
                endX = half,
            ),
        )
        // The rubber lip it stands on.
        val lip = Path().apply {
            arcTo(Rect(-half, bottom - rise - 4f, half, bottom + rise - 4f), 180f, -180f, forceMoveTo = true)
        }
        drawPath(lip, tones.detail, style = Stroke(width = 2.4f))
        val slot = Path().apply {
            addRoundRect(RoundRect(Rect(-14f, -22f, 2f, 16f), CornerRadius(8f)))
        }
        val slotDeep = lerp(tones.cap, tones.ink, 0.55f)
        drawPath(slot, Brush.verticalGradient(0f to tones.cap, 1f to slotDeep, startY = -22f, endY = 16f))
        clipPath(slot) {
            var y = -21f
            while (y < 16f) {
                drawLine(slotDeep.copy(alpha = 0.6f), Offset(-15f, y), Offset(3f, y + 2f), strokeWidth = 0.8f)
                y += 2.6f
            }
        }
        drawCircle(
            Brush.radialGradient(
                0f to lerp(tones.cap, tones.highlight, 0.45f),
                1f to tones.cap,
                center = Offset(-7.4f, 5f),
                radius = 8f,
            ),
            6.6f,
            Offset(-6f, 7f),
        )
    }
}

/** A phone seen leaning in: a thin slab with a dark glass face. */
private fun DrawScope.drawPhoneBody(tones: SensorTones) {
    val glass = RoundRect(Rect(-23f, -44f, 23f, 44f), CornerRadius(8f))
    val slab = Path().apply { addRoundRect(glass) }
    translate(1.8f, 2.2f) { drawPath(slab, tones.wallDeep) }
    drawPath(
        slab,
        Brush.linearGradient(
            0f to lerp(tones.ink, tones.detail, 0.35f),
            1f to tones.ink,
            start = Offset(-23f, -44f),
            end = Offset(23f, 44f),
        ),
    )
    clipPath(slab) {
        val sheen = Path().apply {
            moveTo(-23f, -8f)
            lineTo(23f, -44f)
            lineTo(-23f, -44f)
            close()
        }
        drawPath(sheen, tones.glint.copy(alpha = 0.1f))
    }
    drawPath(slab, tones.glint.copy(alpha = 0.32f), style = Stroke(width = 1f))
    drawCircle(tones.detail, 1.6f, Offset(0f, -38f))
}
