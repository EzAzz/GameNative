package app.gamenative.externaldisplay

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.gamenative.PluviaApp
import app.gamenative.data.TouchGestureConfig
import com.winlator.renderer.NativeTexture
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xserver.Drawable
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Span mode (AYN Thor dual screen): the Wine X screen is wider than the top panel, and the game
 * view sits in its CENTRE. The primary renderer shows X x in [topX, topX + splitX) at 1:1 on
 * display 0 (topX = (W - splitX) / 2: 620..2540 on a 3160 screen). Whatever is left over goes to
 * the presentation display, through "mirror" XServerView/VulkanRenderers fed by the primary, each
 * with a touchscreen-mode TouchpadView mapped to its strip.
 *
 * Two bottom layouts, chosen by the container extra `spanBottom` (see [BottomMode]):
 *  - `sides` (default, and what an absent/unknown value falls back to): the two side strips
 *    stitched together, left half = [0, topX), right half = [topX + splitX, W). This is the
 *    3160x1080 centre-game layout.
 *  - `left`: only [0, topX), filling the whole panel. With a 4400x1080 screen and splitX 1920 that
 *    is exactly the 1240 px "home screen" region at 1:1 on the 1240x1080 bottom panel; x >= 3160 is
 *    then shown nowhere and is expected to stay empty.
 *
 * Why centre: a game centres things on its window -- WoW's freelook cursor, crosshair, action
 * bars, dialogs, and its 16:9 login / character select / loading box -- so with the game view in
 * the middle all of those land on the top screen, and whatever the game anchors to the screen
 * edges (minimap, chat, bags) lands in the strips, i.e. on the bottom screen.
 *
 * The secondary views are never registered as xServer's renderer and never become
 * PluviaApp.xServerView / PluviaApp.touchpadView.
 *
 * Glue mode: WoW draws its login / character select / loading screens (and pre-rendered movies)
 * into a centred 16:9 box -- on a 3160x1080 screen that is x 620..2540, exactly the top panel --
 * with pure black on both sides. A probe on the Present path spots that; the bottom panel then
 * goes black and ignores touch, so menus sit on the top screen only. In-world the strips return.
 */
class SpanDisplayController(
    private val context: Context,
    private val xServer: XServer,
    private val primary: VulkanRenderer,
    private val splitX: Int,
    bottomPref: String = "",
) {
    /** What the presentation display shows. */
    enum class BottomMode { SIDES, LEFT }

    /** One side strip shown on the presentation display. */
    private class Strip(val srcX: Int, val srcW: Int, val view: XServerView, val renderer: VulkanRenderer, val touch: TouchpadView)

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: SpanPresentation? = null
    private var strips: List<Strip> = emptyList()
    private var started = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Main-thread state: true while the bottom panel is blanked for a glue screen. */
    private var glueMode = false

    private val screenW: Int get() = xServer.screenInfo.width.toInt()
    private val screenH: Int get() = xServer.screenInfo.height.toInt()

    /** Left edge of the top panel's source rect: the game view is centred on the X screen. */
    val topX: Int get() = maxOf(0, (screenW - splitX) / 2)

    /** What `spanBottom` asked for; [bottomMode] decides what the geometry actually supports. */
    private val requestedBottom: BottomMode = parseBottomPref(bottomPref)

    /**
     * `left` needs the supported geometry (the 1920-wide top panel, and a non-empty left region);
     * anything else falls back to the two-strip layout so a bad extra can never blank the panel.
     */
    private fun bottomMode(): BottomMode {
        if (requestedBottom != BottomMode.LEFT) return BottomMode.SIDES
        val tx = topX
        if (splitX != SUPPORTED_SPLIT_X || tx <= 0) {
            Timber.tag(TAG).w(
                "spanBottom=left needs splitX=%d and topX>0 but splitX=%d topX=%d (screen %dx%d); using sides",
                SUPPORTED_SPLIT_X, splitX, tx, screenW, screenH,
            )
            return BottomMode.SIDES
        }
        return BottomMode.LEFT
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                Timber.tag(TAG).i("display %d removed", displayId)
                teardown()
            }
            update()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) update()
        }
    }

    fun start() {
        if (started) return
        started = true
        val tx = topX
        Timber.tag(TAG).i("start: X screen %dx%d, splitX=%d, top src=(%d,0,%d,%d)", screenW, screenH, splitX, tx, splitX, screenH)
        primary.setSpanViewport(tx, 0, splitX, screenH)
        // Top-screen touches land in [topX, topX + splitX) at 1:1.
        PluviaApp.touchpadView?.setAbsoluteRegion(tx, 0, splitX, screenH)
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        update()
        if (glueX() > 0) primary.setSpanFrameProbe(GlueProbe())
    }

    fun stop() {
        if (!started) return
        started = false
        Timber.tag(TAG).i("stop")
        primary.setSpanFrameProbe(null)
        glueMode = false
        teardown()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        primary.setSpanViewport(0, 0, 0, 0)
    }

    private fun update() {
        if (!started) return
        val target = findPresentationDisplay()
        if (target == null) {
            Timber.tag(TAG).w("no presentation display found; only x in [%d, %d) is visible", topX, topX + splitX)
            teardown()
            return
        }
        if (presentation != null && presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return
        teardown()

        val w = screenW
        val h = screenH
        val tx = topX
        val rightX = tx + splitX
        val mode = bottomMode()
        // Left to right across the panel. "left" drops the right-hand strip; the remaining one is
        // the only child of the row, so it fills the panel.
        val regions = when (mode) {
            BottomMode.LEFT -> listOf(0 to tx)
            BottomMode.SIDES -> listOf(0 to tx, rightX to w - rightX)
        }.filter { it.second > 0 }
        Timber.tag(TAG).i(
            "display id=%d name=%s mode=%dx%d; bottom mode=%s (requested %s) strips %s",
            target.displayId, target.name, target.mode.physicalWidth, target.mode.physicalHeight,
            mode, requestedBottom, regions.joinToString { "x=${it.first}..${it.first + it.second}" },
        )

        val p = SpanPresentation(context, target)
        try {
            p.show()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "presentation show failed on display %d", target.displayId)
            return
        }
        presentation = p
        p.setOnDismissListener {
            if (presentation === p) {
                Handler(Looper.getMainLooper()).post {
                    teardown()
                    update()
                }
            }
        }
        Timber.tag(TAG).i("presentation shown on display %d", target.displayId)

        // The strips share the panel left to right, weighted by their source width: in "sides" that
        // is the left half / right half, in "left" the single strip takes all of it. 1:1 as long as
        // the widths add up to the panel's.
        val row = LinearLayout(p.context).apply { orientation = LinearLayout.HORIZONTAL }
        p.root.addView(
            row,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // One config object per update, shared by every strip; never the top screen's instance.
        val bottomGestures = bottomGestureConfig()
        val built = ArrayList<Strip>(regions.size)
        for ((srcX, srcW) in regions) {
            val strip = buildStrip(p, srcX, srcW, h, bottomGestures)
            if (strip == null) {
                Timber.tag(TAG).w("strip XServerView has no VulkanRenderer; dismissing")
                built.forEach { primary.removeMirror(it.renderer); it.view.release() }
                presentation = null
                p.dismiss()
                return
            }
            val cell = FrameLayout(p.context)
            cell.addView(strip.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(strip.touch)
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, srcW.toFloat()))
            built.add(strip)
            primary.addMirror(strip.renderer)
        }
        strips = built
        Timber.tag(TAG).i("mirror renderers attached: %s", built.joinToString { "x=${it.srcX}..${it.srcX + it.srcW}" })
        logPresentationLayout(p, mode, built.sumOf { it.srcW }, 0)
    }

    /**
     * Logs what the presentation root actually got laid out at (the panel is rotated, so its Display
     * mode is 1080x1240 while the content area is 1240x1080) and warns when the strips will not land
     * at 1:1: the mirror renderer scales its source rect onto the surface, so a strip whose source is
     * narrower or wider than the panel is stretched.
     */
    private fun logPresentationLayout(p: SpanPresentation, mode: BottomMode, srcW: Int, attempt: Int) {
        mainHandler.post {
            if (presentation !== p) return@post
            val vw = p.root.width
            val vh = p.root.height
            if ((vw <= 0 || vh <= 0) && attempt < LAYOUT_LOG_RETRIES) {
                mainHandler.postDelayed({ logPresentationLayout(p, mode, srcW, attempt + 1) }, LAYOUT_LOG_DELAY_MS)
                return@post
            }
            Timber.tag(TAG).i(
                "presentation root laid out %dx%d; bottom mode=%s source %dx%d",
                vw, vh, mode, srcW, screenH,
            )
            if (vw <= 0 || vh <= 0) {
                Timber.tag(TAG).w("presentation root never reported a size; cannot check the strip scale")
                return@post
            }
            if (mode == BottomMode.LEFT && vw != srcW) {
                Timber.tag(TAG).w("bottom mode=left: strip is %d px wide but the panel is %d px; it will be scaled", srcW, vw)
            }
            val srcAspect = srcW.toFloat() / screenH
            val panelAspect = vw.toFloat() / vh
            if (kotlin.math.abs(srcAspect - panelAspect) > ASPECT_TOLERANCE) {
                Timber.tag(TAG).w(
                    "bottom strip aspect %.3f (%dx%d) differs from the panel's %.3f (%dx%d); the image is distorted",
                    srcAspect, srcW, screenH, panelAspect, vw, vh,
                )
            }
        }
    }

    /**
     * The bottom strips' gestures: a COPY of the top screen's config (never the same object, so
     * nothing here can change the top screen), normalised for a touchscreen that drives a WoW UI --
     * tap = left click, drag = left-click drag, long press = right CLICK after 300 ms, and a
     * TWO-finger drag = mouse wheel (2026-09-25: scroll the Shop and other lists by touch; the
     * inherited top-screen default was arrow keys, which turned the character).
     * `holdMouseButtonWhileTouching` and double-tap are forced off because both pre-empt the long
     * press (TouchpadView.handleTsDown: the hold path returns before the long-press timer is
     * scheduled, and a second nearby tap cancels the timer and emits a double left click).
     */
    private fun bottomGestureConfig(): TouchGestureConfig {
        val top = PluviaApp.touchpadView?.getGestureConfig()
        if (top == null) Timber.tag(TAG).w("no top-screen gesture config; starting the bottom strips from defaults")
        return (top ?: TouchGestureConfig()).copy(
            tapEnabled = true,
            tapAction = TouchGestureConfig.ACTION_LEFT_CLICK,
            dragEnabled = true,
            dragAction = TouchGestureConfig.PAN_LEFT_CLICK_DRAG,
            twoFingerDragAction = TouchGestureConfig.PAN_MOUSE_WHEEL,
            holdMouseButtonWhileTouchingEnabled = false,
            doubleTapEnabled = false,
            longPressEnabled = true,
            longPressAction = TouchGestureConfig.ACTION_RIGHT_CLICK,
            longPressMouseBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_CLICK,
            longPressDelay = TouchGestureConfig.DEFAULT_DELAY_MS,
        )
    }

    private fun buildStrip(p: SpanPresentation, srcX: Int, srcW: Int, h: Int, gestures: TouchGestureConfig): Strip? {
        val view = XServerView(p.context, xServer, "vulkan")
        val r = view.renderer as? VulkanRenderer
        if (r == null) {
            view.release()
            return null
        }
        r.setMirror(true)
        r.setVkPresentMode(primary.getVkPresentMode())
        primary.getUnviewableWMClasses()?.let { r.setUnviewableWMClasses(*it) }
        // Off-canvas source = the clear colour, i.e. black while a glue screen is up.
        r.setSpanViewport(if (glueMode) screenW else srcX, 0, srcW, h)
        r.setCursorVisible(primary.isCursorVisible())

        val touch = TouchpadView(p.context, xServer, false)
        touch.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // The top screen's gestures, normalised for the bottom panel (see bottomGestureConfig).
        touch.setGestureConfig(gestures)
        touch.setTouchscreenMode(true)
        touch.setAbsoluteRegion(srcX, 0, srcW, h)
        if (glueMode) touch.setOnTouchListener(swallowTouch)
        return Strip(srcX, srcW, view, r, touch)
    }

    private fun teardown() {
        val s = strips
        val p = presentation
        if (s.isEmpty() && p == null) return
        Timber.tag(TAG).i("teardown: display=%s", p?.display?.displayId)
        presentation = null
        strips = emptyList()
        s.forEach { primary.removeMirror(it.renderer) }
        // Same reason as the glue transition: the views go away without an ACTION_UP, so release
        // anything a finger still holds before they do.
        for (strip in s) {
            try {
                strip.touch.cancelTouchInput()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "strip touch cancel failed")
            }
        }
        try {
            p?.root?.removeAllViews()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "removeAllViews failed")
        }
        for (strip in s) {
            try {
                strip.view.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "strip view release failed")
            }
        }
        try {
            p?.dismiss()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "presentation dismiss failed")
        }
        Timber.tag(TAG).i("teardown done")
    }

    /** Left edge of WoW's centred 16:9 glue box; 0 when the screen is not wider than 16:9. */
    private fun glueX(): Int {
        val boxW = minOf(screenW, screenH * 16 / 9)
        return if (boxW == splitX) (screenW - boxW) / 2 else 0
    }

    private val swallowTouch = View.OnTouchListener { _, _ -> true }

    private fun setGlueMode(on: Boolean) {
        mainHandler.post {
            if (!started || glueMode == on) return@post
            glueMode = on
            val w = screenW
            val h = screenH
            // The glue box is the centred 16:9 area, which is where the top panel already looks;
            // only a non-centred split would need to move it.
            val tx = if (on && glueX() > 0) glueX() else topX
            Timber.tag(TAG).i("glue mode %s: top src x=%d..%d, bottom %s", on, tx, tx + splitX, if (on) "blank" else "strips")
            if (primary.spanSrcX != tx) {
                primary.setSpanViewport(tx, 0, splitX, h)
                PluviaApp.touchpadView?.setAbsoluteRegion(tx, 0, splitX, h)
            }
            for (s in strips) {
                // Off-canvas source = the clear colour, i.e. a black bottom panel.
                s.renderer.setSpanViewport(if (on) w else s.srcX, 0, s.srcW, h)
                // The swallowing listener stops onTouchEvent being called at all, so a finger that is
                // down right now would never get its ACTION_UP/CANCEL: drop its gesture first, or a
                // held button (long press, drag) would stay pressed for the whole glue screen.
                if (on) s.touch.cancelTouchInput()
                s.touch.setOnTouchListener(if (on) swallowTouch else null)
            }
        }
    }

    /**
     * Runs on the Present thread for every WoW frame. Every PROBE_EVERY-th full-screen frame it reads
     * a few pixels straight from the frame's CPU mapping and decides glue vs world:
     *  - FFSpan's in-world marker (a small magenta square at the canvas's bottom-right corner, drawn
     *    only while the in-world UI exists) present = world. Addons never run on glue screens.
     *  - letterbox (both side strips pure black, something lit in the box) = glue.
     *  - neither: glue once the marker has been seen this session (FFSpan is known to work, so its
     *    absence means login / character select / loading), otherwise world (safe fallback when
     *    FFSpan is missing -- only letterboxed screens switch).
     * All-black frames (fades) count as neither. ENTER_RUN / EXIT_RUN consecutive verdicts flip the
     * mode, so it never flickers.
     */
    private inner class GlueProbe : VulkanRenderer.SpanFrameProbe {
        private var frame = 0
        private var probes = 0
        private var glueRun = 0
        private var worldRun = 0
        private var inGlue = false
        private var markerSeen = false
        private var loggedFirst = false
        private var loggedSample = false
        private val tally = IntArray(3)

        override fun onFrame(window: Window, pixmap: Drawable, rootX: Int, rootY: Int) {
            if (++frame % PROBE_EVERY != 0) return
            val w = screenW
            val h = screenH
            if (!loggedFirst) {
                loggedFirst = true
                val tex = pixmap.texture
                Timber.tag(TAG).i(
                    "glue probe: first frame window=%dx%d pixmap=%dx%d root=(%d,%d) texture=%s stride=%d",
                    window.width.toInt(), window.height.toInt(), pixmap.width.toInt(), pixmap.height.toInt(), rootX, rootY,
                    tex?.javaClass?.simpleName, (tex as? NativeTexture)?.stride?.toInt() ?: -1,
                )
            }
            if (rootX != 0 || rootY != 0 || window.width.toInt() != w || window.height.toInt() != h) return
            if (pixmap.height.toInt() != h || pixmap.width.toInt() < w) return
            val verdict = synchronized(pixmap.renderLock) { classify(pixmap, w, h) }
            tally[verdict]++
            if (++probes % SUMMARY_EVERY == 0) {
                Timber.tag(TAG).i(
                    "glue probe: %d probes unknown=%d glue=%d world=%d markerSeen=%s mode=%s",
                    probes, tally[UNKNOWN], tally[GLUE], tally[WORLD], markerSeen, if (inGlue) "glue" else "split",
                )
            }
            when (verdict) {
                GLUE -> { glueRun++; worldRun = 0 }
                WORLD -> { worldRun++; glueRun = 0 }
                else -> return
            }
            if (!inGlue && glueRun >= ENTER_RUN) { inGlue = true; setGlueMode(true) }
            if (inGlue && worldRun >= EXIT_RUN) { inGlue = false; setGlueMode(false) }
        }

        private fun classify(pixmap: Drawable, w: Int, h: Int): Int {
            val tex = pixmap.texture as? NativeTexture
            val buf = tex?.virtualData ?: pixmap.buffer ?: return UNKNOWN
            val stride = when {
                tex != null && tex.stride > 0 -> tex.stride.toInt()
                else -> buf.capacity() / (pixmap.height * 4)
            }
            if (stride < w) return UNKNOWN
            val gx = glueX()
            if (gx <= 0) return UNKNOWN
            val limit = buf.capacity()
            fun px(x: Int, y: Int): Int {
                val i = (y * stride + x) * 4
                if (i < 0 || i + 2 >= limit) return 0
                return (buf.get(i).toInt() and 0xff) or ((buf.get(i + 1).toInt() and 0xff) shl 8) or
                    ((buf.get(i + 2).toInt() and 0xff) shl 16)
            }
            fun lit(x: Int, y: Int) = px(x, y) != 0
            // Magenta is byte-order proof: channel 0 and 2 high, channel 1 low, in RGBA or BGRA.
            fun magenta(x: Int, y: Int): Boolean {
                val p = px(x, y)
                return (p and 0xff) > 200 && (p shr 8 and 0xff) < 60 && (p shr 16 and 0xff) > 200
            }
            if (!loggedSample) {
                loggedSample = true
                Timber.tag(TAG).i(
                    "glue probe: sample stride=%d cap=%d corner=%06x mid=%06x left=%06x right=%06x",
                    stride, limit, px(w - MARKER_INSET, h - MARKER_INSET), px(w / 2, h / 2), px(gx / 2, h / 2), px(w - gx / 2, h / 2),
                )
            }
            if (magenta(w - MARKER_INSET, h - MARKER_INSET) && magenta(w - MARKER_INSET - 1, h - MARKER_INSET - 1)) {
                markerSeen = true
                return WORLD
            }
            var sidesLit = false
            var boxLit = false
            for (fy in ROWS) {
                val y = (h * fy).toInt()
                for (fx in COLS) {
                    if (!sidesLit && (lit((gx * fx).toInt(), y) || lit(w - 1 - (gx * fx).toInt(), y))) sidesLit = true
                    if (!boxLit && lit(gx + ((w - 2 * gx) * fx).toInt(), y)) boxLit = true
                }
            }
            return when {
                !sidesLit && boxLit -> GLUE
                !sidesLit && !boxLit -> UNKNOWN
                markerSeen -> GLUE
                else -> WORLD
            }
        }
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = context.display ?: return null
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }

    private companion object {
        const val TAG = "SpanDisplay"

        /** The only top-panel width "left" mode is specified for (the Thor's 1920x1080 top panel). */
        const val SUPPORTED_SPLIT_X = 1920
        const val EXTRA_LEFT = "left"
        const val EXTRA_SIDES = "sides"
        const val LAYOUT_LOG_RETRIES = 5
        const val LAYOUT_LOG_DELAY_MS = 200L
        const val ASPECT_TOLERANCE = 0.02f

        /** Absent or unrecognised `spanBottom` -> today's two-strip layout, always logged. */
        fun parseBottomPref(pref: String): BottomMode {
            return when (val v = pref.trim().lowercase()) {
                EXTRA_LEFT -> BottomMode.LEFT
                EXTRA_SIDES -> BottomMode.SIDES
                "" -> {
                    Timber.tag(TAG).i("spanBottom not set; bottom = sides")
                    BottomMode.SIDES
                }
                else -> {
                    Timber.tag(TAG).w("spanBottom=\"%s\" is not %s or %s; bottom = sides", v, EXTRA_LEFT, EXTRA_SIDES)
                    BottomMode.SIDES
                }
            }
        }

        const val PROBE_EVERY = 8
        const val SUMMARY_EVERY = 450 // ~2 min at 30 fps
        const val ENTER_RUN = 3
        const val EXIT_RUN = 2
        /** FFSpan draws a 6px magenta square flush with the canvas's bottom-right corner; sample inside it. */
        const val MARKER_INSET = 3
        const val UNKNOWN = 0
        const val GLUE = 1
        const val WORLD = 2
        val ROWS = floatArrayOf(0.08f, 0.3f, 0.5f, 0.7f, 0.92f)
        val COLS = floatArrayOf(0.1f, 0.35f, 0.6f, 0.9f)
    }
}

private class SpanPresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {
    val root: FrameLayout by lazy {
        FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep focus / controller input on the main activity.
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        setContentView(root)
    }
}
