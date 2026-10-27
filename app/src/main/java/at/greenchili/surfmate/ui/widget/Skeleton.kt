package at.greenchili.surfmate.ui.widget

import android.animation.ValueAnimator
import android.view.View

/**
 * A block overlays its value slot while the value stays `INVISIBLE`, so the row measures the same
 * before and after. Revealing is one-way: these pages poll, and re-showing a block reads as flicker.
 */
class Skeleton(private val host: View) {

    private class Slot(val block: View, val content: List<View>)

    private val slots = LinkedHashMap<Int, Slot>()
    private val followed = ArrayList<View>()
    private val loaded = HashSet<Int>()
    private var pulse: ValueAnimator? = null

    /** Registers a placeholder block and the views it stands in for, and shows the placeholder. */
    fun bind(blockId: Int, vararg content: View) {
        val block = host.findViewById<View>(blockId) ?: return
        slots[blockId] = Slot(block, content.toList())
        if (loaded.contains(blockId)) return
        block.visibility = View.VISIBLE
        for (view in content) view.visibility = View.INVISIBLE
        startPulse()
    }

    /** Pulses [blockId] whenever it is visible, for a placeholder the caller may show again. */
    fun follow(blockId: Int) {
        val block = host.findViewById<View>(blockId) ?: return
        followed.add(block)
        startPulse()
    }

    fun isLoaded(blockId: Int): Boolean = loaded.contains(blockId)

    /** Retires a placeholder for good and reveals the real value. */
    fun markLoaded(blockId: Int) {
        loaded.add(blockId)
        val slot = slots[blockId] ?: return
        slot.block.visibility = View.GONE
        for (view in slot.content) view.visibility = View.VISIBLE
        if (followed.isEmpty() && slots.keys.all { loaded.contains(it) }) stopPulse()
    }

    fun cancel() {
        stopPulse()
        slots.clear()
        followed.clear()
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            // 0.1.20 Tempo (Messung: Kaltstart 12–13 s eine Kern voll, RenderThread ~70 %): nicht mehr endlos – jeder
            // Puls-Frame zeichnet die ganze Startkarte neu. Nach PULSE_WIEDERHOLUNGEN bleibt der Platzhalter ruhig
            // stehen (Alpha RUHE_ALPHA), bis der Wert kommt.
            repeatCount = PULSE_WIEDERHOLUNGEN
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (pulse !== animation) return
                    for ((id, slot) in slots) if (!loaded.contains(id)) slot.block.alpha = RUHE_ALPHA
                    for (block in followed) if (block.visibility == View.VISIBLE) block.alpha = RUHE_ALPHA
                }
            })
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                for ((id, slot) in slots) {
                    if (!loaded.contains(id)) slot.block.alpha = alpha
                }
                for (block in followed) {
                    if (block.visibility == View.VISIBLE) block.alpha = alpha
                }
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        for (slot in slots.values) slot.block.alpha = 1f
        for (block in followed) block.alpha = 1f
    }

    companion object {
        private const val PULSE_MS = 620L
        /** 0.1.20: 4 Wechsel ≈ 2,5 s, dann Ruhe. */
        const val PULSE_WIEDERHOLUNGEN = 3
        const val RUHE_ALPHA = 0.7f
    }
}
