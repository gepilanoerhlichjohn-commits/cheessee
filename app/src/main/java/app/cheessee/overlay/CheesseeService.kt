package app.cheessee.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.cheessee.Prefs
import app.cheessee.R
import app.cheessee.brain.Coach
import app.cheessee.brain.Hint
import app.cheessee.brain.HintPicker
import app.cheessee.engine.Stockfish
import app.cheessee.ui.MainActivity
import app.cheessee.vision.ScreenReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foreground service: watches the screen, finds the board, asks Stockfish, and draws the overlay.
 * Also owns the little floating bubble with the tool buttons.
 */
class CheesseeService : Service() {
    companion object {
        const val ACTION_STOP = "app.cheessee.STOP"

        @Volatile
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var engine: Stockfish
    private val coach = Coach()

    private var overlay: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var calibRow: LinearLayout? = null
    private var drawBtn: TextView? = null
    private var colorBtn: TextView? = null

    private var grabber: ScreenGrabber? = null
    private var worker: Thread? = null
    private var screenW = 0
    private var screenH = 0
    private var snapBmp: Bitmap? = null
    private var templates: ScreenReader.Templates? = null

    @Volatile
    private var calibStart = false

    @Volatile
    private var calibLock = false
    private var colorIdx = 0
    private var lastStatus: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForegroundNow()
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            shutdown()
            return START_NOT_STICKY
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        engine = Stockfish(this)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (projection == null) {
            toast("Screen capture was not allowed.")
            shutdown()
            return START_NOT_STICKY
        }
        grabber = ScreenGrabber(projection, screenW, screenH, dm.densityDpi)
        running = true
        buildOverlay()
        buildBubble()
        worker = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ notification

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cheessee", "Cheessee", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "cheessee")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Cheessee is on")
            .setContentText("Overlay running. Tap to open settings.")
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------------ windows

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            screenW, screenH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVERLAY_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        v.onSquareTap = { sq -> showMovesFor(sq) }
        wm.addView(v, lp)
        overlay = v
        overlayParams = lp
    }

    private val OVERLAY_FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun updateTouchable() {
        val ov = overlay ?: return
        val lp = overlayParams ?: return
        lp.flags = if (ov.drawMode || ov.calibMode) OVERLAY_FLAGS else OVERLAY_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(ov, lp)
    }

    private fun roundButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xE61A1D24.toInt())
        bg.setStroke(dp(1), 0xFF39D98A.toInt())
        tv.background = bg
        val lp = LinearLayout.LayoutParams(dp(42), dp(42))
        lp.topMargin = dp(6)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun buildBubble() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val menuCol = LinearLayout(this)
        menuCol.orientation = LinearLayout.VERTICAL
        menuCol.visibility = View.GONE
        menu = menuCol

        val calib = LinearLayout(this)
        calib.orientation = LinearLayout.VERTICAL
        calib.visibility = View.GONE
        calibRow = calib

        val ov = overlay!!
        val draw = roundButton("✎") {
            ov.drawMode = !ov.drawMode
            drawBtn?.setTextColor(if (ov.drawMode) 0xFF39D98A.toInt() else Color.WHITE)
            updateTouchable()
        }
        drawBtn = draw
        val color = roundButton("●") {
            colorIdx = (colorIdx + 1) % OverlayView.ARROW_COLORS.size
            ov.drawColor = OverlayView.ARROW_COLORS[colorIdx]
            colorBtn?.setTextColor(ov.drawColor)
        }
        color.setTextColor(ov.drawColor)
        colorBtn = color
        menuCol.addView(draw)
        menuCol.addView(color)
        menuCol.addView(roundButton("🗑") { ov.clearUser() })
        menuCol.addView(roundButton("⇅") {
            prefs.side = (prefs.side + 1) % 3
            toast("Bottom of the board: " + arrayOf("auto-detect", "White", "Black")[prefs.side])
        })
        menuCol.addView(roundButton("✛") { calibStart = true; menu?.visibility = View.GONE })
        menuCol.addView(roundButton("⚙") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        menuCol.addView(roundButton("■") { shutdown() })

        calib.addView(roundButton("✓") { calibLock = true })
        calib.addView(roundButton("✕") {
            ov.calibMode = false
            showCalibButtons(false)
            updateTouchable()
            ov.invalidate()
        })

        val mainBtn = roundButton("🧀") {}
        mainBtn.setOnClickListener(null)
        col.addView(mainBtn)
        col.addView(menuCol)
        col.addView(calib)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = screenW - dp(56)
        lp.y = dp(110)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        mainBtn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        wm.updateViewLayout(col, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    menuCol.visibility = if (menuCol.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }
        wm.addView(col, lp)
        bubble = col
        bubbleParams = lp
    }

    private fun showCalibButtons(on: Boolean) {
        calibRow?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun status(msg: String?) {
        if (msg == lastStatus) return
        lastStatus = msg
        main.post {
            overlay?.statusText = msg
            overlay?.invalidate()
        }
    }

    // ------------------------------------------------------------------ tap a piece -> show its moves

    private fun showMovesFor(sq: Int) {
        val ov = overlay ?: return
        val pos = ov.report?.pos
        if (pos == null || pos.b[sq] == 0) {
            ov.dots = emptyList()
            return
        }
        val dest = Coach.movesFrom(pos, sq)
        ov.dots = if (ov.dots.isNotEmpty() && ov.dots == dest) emptyList() else dest
    }

    // ------------------------------------------------------------------ screenshot of our own overlay

    /** Our overlay is part of the captured screen; this renders it separately so the reader can subtract it. */
    private fun overlaySnapshot(): IntArray? {
        val ov = overlay ?: return null
        if (!ov.hasContent()) return null
        val latch = CountDownLatch(1)
        var out: IntArray? = null
        main.post {
            try {
                val bmp = snapBmp ?: Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888).also { snapBmp = it }
                bmp.eraseColor(0)
                ov.draw(Canvas(bmp))
                val px = IntArray(screenW * screenH)
                bmp.getPixels(px, 0, screenW, 0, 0, screenW, screenH)
                out = px
            } catch (e: Exception) {
                // fall through with null
            } finally {
                latch.countDown()
            }
        }
        latch.await(600, TimeUnit.MILLISECONDS)
        return out
    }

    // ------------------------------------------------------------------ main loop

    private fun applyPrefs(p: Prefs) {
        main.post {
            val ov = overlay ?: return@post
            ov.showHints = p.hints
            ov.showAgainst = p.threatsAgainst
            ov.showMine = p.threatsMine
            ov.showLoose = p.loose
            ov.showEval = p.eval
            ov.showStickers = p.stickers
            if (!ov.calibMode && p.boardSize > 0) {
                ov.bx = p.boardX.toFloat()
                ov.by = p.boardY.toFloat()
                ov.size = p.boardSize.toFloat()
            }
            ov.invalidate()
        }
    }

    private fun loop() {
        val p = prefs
        val ok = engine.start(p.classicEngine)
        if (!ok) toast("Stockfish could not start. Hints are off (see the guide: 'engine missing').")
        templates = p.templates?.let { ScreenReader.Templates.decode(it) }

        var pending: IntArray? = null
        var pendingN = 0
        var accepted: IntArray? = null
        var acceptedWb = true
        var fail = 0
        var lastEngineFlag = p.classicEngine

        while (running) {
            try {
                Thread.sleep(300)
                val frame = grabber?.grab() ?: continue
                applyPrefs(p)
                if (calibStart) {
                    calibStart = false
                    doCalibStart(frame, p)
                }
                if (calibLock) {
                    calibLock = false
                    doCalibLock(frame, p)
                }
                if (overlay?.calibMode == true) continue
                val t = templates
                if (t == null || p.boardSize <= 0) {
                    status("Not calibrated yet: open the 🧀 menu → ✛")
                    continue
                }

                val ov = overlaySnapshot()
                val rd = ScreenReader.read(frame.px, ov, frame.w, p.boardX, p.boardY, p.boardSize, t)
                val key = rd.screen
                if (pending != null && key.contentEquals(pending)) pendingN++ else {
                    pending = key
                    pendingN = 1
                }
                if (pendingN < 2) continue

                val wb = when (p.side) {
                    1 -> true
                    2 -> false
                    else -> ScreenReader.guessWhiteAtBottom(key)
                }
                if (accepted != null && key.contentEquals(accepted) && wb == acceptedWb) continue
                if (wb != acceptedWb) coach.reset()
                if (p.classicEngine != lastEngineFlag) {
                    lastEngineFlag = p.classicEngine
                    engine.quit()
                    engine.start(p.classicEngine)
                }

                val board = ScreenReader.toBoard(key, wb)
                val lf = if (rd.lastFrom >= 0) ScreenReader.screenToSquare(rd.lastFrom, wb) else -1
                val lt = if (rd.lastTo >= 0) ScreenReader.screenToSquare(rd.lastTo, wb) else -1
                val res = coach.resolve(board, lf, lt, wb)
                if (res.pos == null) {
                    fail++
                    if (fail == 3) retrack(frame, p)
                    if (fail >= 4) {
                        status("Can't read the board (is Lichess showing it?)")
                        main.post { overlay?.report = null; overlay?.invalidate() }
                    }
                    continue
                }
                fail = 0
                accepted = key
                acceptedWb = wb
                status(null)
                if (!res.changed) continue

                // 1) threats are instant: show them while the engine thinks
                val quick = coach.complete(res, null, wb)
                main.post {
                    overlay?.whiteBottom = wb
                    overlay?.clearUser()
                    overlay?.report = quick
                    overlay?.invalidate()
                }
                // 2) engine
                val cfg = when (p.strength) {
                    0 -> intArrayOf(10, 700, 4)
                    2 -> intArrayOf(16, 5000, 5)
                    else -> intArrayOf(13, 2000, 5)
                }
                status("Analysing…")
                val lines = engine.analyze(res.pos.toFen(), cfg[2], cfg[0], cfg[1])
                val rep = coach.complete(res, lines, wb)
                // 3) optional trap check for the yellow hint
                if (p.trapCheck && rep.myTurn) {
                    val tricky = rep.hints.firstOrNull { it.kind == Hint.Kind.TRICKY }
                    if (tricky != null) {
                        val child = res.pos.play(tricky.move)
                        tricky.trap = HintPicker.isTrap(engine.analyze(child.toFen(), 2, 9, 500))
                    }
                }
                status(null)
                main.post {
                    overlay?.report = rep
                    overlay?.invalidate()
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // keep the loop alive; the next frame will try again
            }
        }
    }

    private fun retrack(frame: ScreenGrabber.Frame, p: Prefs) {
        val y = ScreenReader.findY(frame.px, frame.w, frame.h, p.boardX, p.boardSize)
        if (y >= 0 && abs(y - p.boardY) > 2) p.boardY = y
    }

    private fun doCalibStart(frame: ScreenGrabber.Frame, p: Prefs) {
        val saved = if (p.boardSize > 0) intArrayOf(p.boardX, p.boardY, p.boardSize) else null
        val found = try {
            ScreenReader.findBoard(frame.px, frame.w, frame.h)
        } catch (e: Exception) {
            null
        } ?: saved
        if (found == null) {
            toast("Couldn't find the board. Open Lichess so the whole board is visible and try again.")
            return
        }
        main.post {
            val ov = overlay ?: return@post
            ov.bx = found[0].toFloat()
            ov.by = found[1].toFloat()
            ov.size = found[2].toFloat()
            ov.report = null
            ov.clearUser()
            ov.calibMode = true
            showCalibButtons(true)
            updateTouchable()
            ov.invalidate()
        }
    }

    private fun doCalibLock(frame: ScreenGrabber.Frame, p: Prefs) {
        val ov = overlay ?: return
        val x = ov.bx.roundToInt()
        val y = ov.by.roundToInt()
        val s = ov.size.roundToInt()
        val snap = overlaySnapshot()
        val t = try {
            if (x < 0 || y < 0 || x + s > frame.w || y + s > frame.h) null
            else ScreenReader.learn(frame.px, snap, frame.w, x, y, s)
        } catch (e: Exception) {
            null
        }
        if (t == null) {
            toast("Couldn't see all 32 starting pieces inside the frame. Open a NEW game or analysis board (nothing moved yet), fit the frame exactly on the board and tap ✓ again.")
            return
        }
        p.boardX = x
        p.boardY = y
        p.boardSize = s
        p.templates = t.encode()
        templates = t
        coach.reset()
        toast("Calibrated ✓  Cheessee now knows your pieces.")
        main.post {
            ov.calibMode = false
            showCalibButtons(false)
            updateTouchable()
            ov.invalidate()
        }
    }

    // ------------------------------------------------------------------ shutdown

    private fun shutdown() {
        running = false
        worker?.interrupt()
        main.post {
            try {
                overlay?.let { wm.removeView(it) }
                bubble?.let { wm.removeView(it) }
            } catch (_: Exception) {
            }
            overlay = null
            bubble = null
        }
        try {
            grabber?.release()
        } catch (_: Exception) {
        }
        grabber = null
        try {
            if (::engine.isInitialized) engine.quit()
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
