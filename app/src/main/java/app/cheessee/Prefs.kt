package app.cheessee

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. Read live by the overlay service, so changes apply immediately. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("cheessee", Context.MODE_PRIVATE)

    /** 0 = Fast, 1 = Balanced, 2 = Deep. */
    var strength: Int
        get() = sp.getInt("strength", 1)
        set(v) = sp.edit().putInt("strength", v).apply()

    /** Which colour is at the bottom of the screen: 0 = auto-detect, 1 = white, 2 = black. */
    var side: Int
        get() = sp.getInt("side", 0)
        set(v) = sp.edit().putInt("side", v).apply()

    var hints: Boolean
        get() = sp.getBoolean("hints", true)
        set(v) = sp.edit().putBoolean("hints", v).apply()
    var threatsAgainst: Boolean
        get() = sp.getBoolean("against", true)
        set(v) = sp.edit().putBoolean("against", v).apply()
    var threatsMine: Boolean
        get() = sp.getBoolean("mine", true)
        set(v) = sp.edit().putBoolean("mine", v).apply()
    var loose: Boolean
        get() = sp.getBoolean("loose", false)
        set(v) = sp.edit().putBoolean("loose", v).apply()
    var eval: Boolean
        get() = sp.getBoolean("eval", true)
        set(v) = sp.edit().putBoolean("eval", v).apply()
    var stickers: Boolean
        get() = sp.getBoolean("stickers", true)
        set(v) = sp.edit().putBoolean("stickers", v).apply()
    var trapCheck: Boolean
        get() = sp.getBoolean("trap", true)
        set(v) = sp.edit().putBoolean("trap", v).apply()
    /** Faster, weaker engine evaluation (Stockfish 15.1 only). Helps on slow phones. */
    var classicEngine: Boolean
        get() = sp.getBoolean("classic", false)
        set(v) = sp.edit().putBoolean("classic", v).apply()
    var noticeAccepted: Boolean
        get() = sp.getBoolean("notice", false)
        set(v) = sp.edit().putBoolean("notice", v).apply()

    var boardX: Int
        get() = sp.getInt("bx", 0)
        set(v) = sp.edit().putInt("bx", v).apply()
    var boardY: Int
        get() = sp.getInt("by", 0)
        set(v) = sp.edit().putInt("by", v).apply()
    var boardSize: Int
        get() = sp.getInt("bs", -1)
        set(v) = sp.edit().putInt("bs", v).apply()
    var templates: String?
        get() = sp.getString("templates", null)
        set(v) = sp.edit().putString("templates", v).apply()

    val calibrated: Boolean get() = boardSize > 0 && templates != null
}
