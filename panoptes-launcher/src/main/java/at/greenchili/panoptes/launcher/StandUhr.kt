package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock

/**
 * 0.1.42 (L48, LUECKEN_0142.md Punkt 8): Stand-Modus. In P nach X Minuten ohne Bedienung zeigt der Launcher eine
 * große Uhr über allem, abgedunkelt (nachts stärker, Farben aus dem aktuellen Design inkl. Tag/Nacht automatisch).
 * Ein Tipp beendet sie. Ab Werk AUS. Verlässt das Auto P (oder ist der Gang nicht frisch bekannt), verschwindet sie.
 */
object StandUhr {
    val MINUTEN = listOf(0, 2, 5, 10, 15)   // 0 = aus
    private const val PREFS = "launcher"
    const val KEY = "stand_uhr_minuten"

    fun minuten(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).takeIf { it in MINUTEN } ?: 0

    fun setzen(ctx: Context, m: Int) {
        if (m in MINUTEN) ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, m).apply()
    }

    /** Soll die Uhr jetzt zu sehen sein? Nur in P (frischer Gang vom Kern), nur ohne offenes Feld/Bearbeiten. */
    fun zeigen(minuten: Int, gang: String?, sichtbar: Boolean, frei: Boolean, jetztMs: Long, letzteBedienungMs: Long): Boolean =
        minuten > 0 && gang == "P" && sichtbar && frei && jetztMs - letzteBedienungMs >= minuten * 60_000L

    /** Deckung der Abdunkelung: nachts stärker. */
    fun deckung(nacht: Boolean): Float = if (nacht) 0.92f else 0.80f

    fun ansicht(ctx: Context, ui: Ui, nacht: Boolean, beenden: () -> Unit): View = FrameLayout(ctx).apply {
        setBackgroundColor(Color.argb((deckung(nacht) * 255).toInt(), 0, 0, 0))
        isClickable = true; isFocusable = true
        setOnClickListener { beenden() }
        val spalte = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        // auf dunklem Grund immer helle Schrift (auch beim hellen Design), nachts gedämpft
        val farbe = if (nacht) Color.rgb(150, 162, 172) else Color.rgb(232, 238, 242)
        spalte.addView(TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"; textSize = 160f; setTextColor(farbe)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        spalte.addView(TextClock(ctx).apply {
            val muster = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM"); format24Hour = muster; format12Hour = muster; textSize = 30f; setTextColor(farbe)
            alpha = 0.8f; gravity = Gravity.CENTER
        })
        spalte.addView(ui.leise(ctx.getString(R.string.stand_uhr_tipp), 18f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(36), 0, 0) })
        addView(spalte, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        contentDescription = ctx.getString(R.string.stand_uhr)
    }
}
