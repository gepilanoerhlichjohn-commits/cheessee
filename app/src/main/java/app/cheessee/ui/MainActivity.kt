package app.cheessee.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.cheessee.Prefs
import app.cheessee.engine.Stockfish
import app.cheessee.overlay.CheesseeService

/** Home screen: permissions, start/stop, settings and a short how-to. Built in code to keep the project small. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val reqCapture = 42

    private val bg = 0xFF0E1015.toInt()
    private val card = 0xFF171A21.toInt()
    private val neon = 0xFF39D98A.toInt()
    private val dim = 0xFF9AA3B2.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(28), dp(18), dp(28))

        root.addView(text("🧀 Cheessee", 30f, Color.WHITE, true))
        root.addView(text("Your private chess practice overlay: hints, threats, move labels and your own arrows on top of Lichess.", 14f, dim, false))

        // practice notice
        root.addView(section("Practice tool"))
        root.addView(text("Cheessee is for analysis, puzzles, bot games and study. Using engine help in a rated or live game against a real person breaks Lichess's rules, so keep it for practice.", 13f, 0xFFFFC857.toInt(), false))

        // setup
        root.addView(section("1 · Setup"))
        val canDraw = Settings.canDrawOverlays(this)
        root.addView(statusRow(if (canDraw) "✓ Draw over other apps" else "✗ Draw over other apps", canDraw, if (canDraw) null else "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            root.addView(statusRow(if (notif) "✓ Notifications" else "✗ Notifications (needed for the running notice)", notif, if (notif) null else "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        val engineOk = Stockfish(this).available()
        root.addView(statusRow(if (engineOk) "✓ Stockfish engine included" else "✗ Stockfish missing in this build (see guide)", engineOk, null) {})
        root.addView(statusRow(if (prefs.calibrated) "✓ Board calibrated" else "• Board not calibrated yet (do it after starting)", prefs.calibrated, null) {})

        val running = CheesseeService.running
        val start = button(if (running) "■  Stop Cheessee" else "▶  Start Cheessee", if (running) 0xFFFF5555.toInt() else neon)
        start.setOnClickListener {
            if (running) {
                startService(Intent(this, CheesseeService::class.java).setAction(CheesseeService.ACTION_STOP))
                root.postDelayed({ render() }, 400)
            } else {
                begin()
            }
        }
        root.addView(start)

        // settings
        root.addView(section("2 · Settings"))
        root.addView(choice("Analysis strength", arrayOf("Fast", "Balanced", "Deep"), prefs.strength) { prefs.strength = it })
        root.addView(choice("Bottom of the board is", arrayOf("Auto", "White", "Black"), prefs.side) { prefs.side = it })
        root.addView(toggle("Hint arrows (green / yellow / blue)", prefs.hints) { prefs.hints = it })
        root.addView(toggle("Trap check for the yellow hint", prefs.trapCheck) { prefs.trapCheck = it })
        root.addView(toggle("Threats against me (red dotted)", prefs.threatsAgainst) { prefs.threatsAgainst = it })
        root.addView(toggle("My threats (purple dotted)", prefs.threatsMine) { prefs.threatsMine = it })
        root.addView(toggle("Loose pieces (dashed rings)", prefs.loose) { prefs.loose = it })
        root.addView(toggle("Eval bar + number", prefs.eval) { prefs.eval = it })
        root.addView(toggle("Move stickers (brilliant … blunder)", prefs.stickers) { prefs.stickers = it })
        root.addView(toggle("Faster classic engine (weaker, easier on the phone)", prefs.classicEngine) { prefs.classicEngine = it })

        // how-to
        root.addView(section("3 · How to use"))
        root.addView(text(
            "• Press Start, allow screen capture and choose \"Entire screen\" if Android asks.\n" +
                "• Open Lichess on a NEW game or analysis board (nothing moved yet).\n" +
                "• Tap the floating 🧀 bubble → ✛, drag/pinch the cyan frame onto the board edges, tap ✓. This teaches Cheessee your piece style once.\n" +
                "• Park the bubble outside the board. ✎ = draw arrows with your finger (drag between squares, tap a square to highlight it, tap a piece to see its moves), ● = colour, 🗑 = clear, ⇅ = which side is at the bottom.\n" +
                "• Colours: dark green = best, light green = good, yellow = playable but worse (TRAP = the opponent must find an only move), blue = brilliant. Red dotted = what they threaten, purple dotted = what you threaten.",
            13f, dim, false
        ))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun begin() {
        if (!prefs.noticeAccepted) {
            AlertDialog.Builder(this)
                .setTitle("Practice use only")
                .setMessage("Cheessee gives engine hints. Use it for analysis, puzzles, bot games and learning, not in live games against real people (that is cheating and against Lichess's rules).")
                .setPositiveButton("I understand") { _, _ ->
                    prefs.noticeAccepted = true
                    begin()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "First allow 'Draw over other apps'.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CheesseeService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        } else if (requestCode == reqCapture) {
            Toast.makeText(this, "Screen capture is needed to read the board.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ tiny UI kit

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun section(s: String): TextView {
        val t = text(s.uppercase(), 12f, neon, true)
        t.setPadding(0, dp(22), 0, dp(6))
        return t
    }

    private fun box(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        val g = GradientDrawable()
        g.setColor(card)
        g.cornerRadius = dp(12).toFloat()
        l.background = g
        l.setPadding(dp(14), dp(10), dp(14), dp(10))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        l.layoutParams = lp
        return l
    }

    private fun statusRow(label: String, ok: Boolean, action: String?, onClick: () -> Unit): View {
        val row = box()
        val t = text(label, 14f, if (ok) Color.WHITE else 0xFFFFC857.toInt(), false)
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) {
            val b = Button(this)
            b.text = action
            b.setOnClickListener { onClick() }
            row.addView(b)
        }
        return row
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = box()
        row.addView(text(label, 14f, Color.WHITE, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        return row
    }

    private fun choice(label: String, options: Array<String>, current: Int, onPick: (Int) -> Unit): View {
        val row = box()
        row.addView(text(label, 14f, Color.WHITE, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val b = Button(this)
        var idx = current
        b.text = options[idx]
        b.setOnClickListener {
            idx = (idx + 1) % options.size
            b.text = options[idx]
            onPick(idx)
        }
        row.addView(b)
        return row
    }

    private fun button(label: String, color: Int): Button {
        val b = Button(this)
        b.text = label
        b.setTextColor(0xFF0E1015.toInt())
        b.isAllCaps = false
        b.textSize = 17f
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(14).toFloat()
        b.background = g
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        lp.topMargin = dp(10)
        b.layoutParams = lp
        return b
    }
}
