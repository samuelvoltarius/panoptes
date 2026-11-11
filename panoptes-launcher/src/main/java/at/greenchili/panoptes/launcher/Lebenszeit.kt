package at.greenchili.panoptes.launcher

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 0.1.41 (Nachtest F1): Nach einem Neustart der Activity (z. B. Größe „Größer“ → „Automatisch“ über eine Vorlage) lief
 * ein ausstehender Rückruf noch in der ALTEN Activity (`ladeApps` → `baueAlles` → `standbildLaden`) und übergab an
 * den schon beendeten Executor → RejectedExecutionException, Launcher-Absturz. Zwei Absicherungen an einer Stelle:
 *
 * - [SichererIo]: nimmt nach dem Beenden keine Arbeit mehr an (still verworfen statt Absturz).
 * - [LebensHandler]: führt Rückrufe auf `main` nur aus, solange die Activity lebt (`!isFinishing && !isDestroyed`).
 */
class SichererIo(private val lebt: () -> Boolean = { true }) {
    private val ex: ExecutorService = Executors.newSingleThreadExecutor()

    /** true, wenn die Arbeit angenommen wurde. */
    fun execute(arbeit: Runnable): Boolean {
        if (ex.isShutdown || !lebt()) return false
        return try { ex.execute(arbeit); true } catch (_: RejectedExecutionException) { false }
    }

    val isShutdown: Boolean get() = ex.isShutdown

    /** Laufende Arbeit unterbrechen, wartende verwerfen. */
    fun shutdownNow() { ex.shutdownNow() }

    /** Wartende Arbeit noch fertig machen (z. B. Layout speichern), keine neue annehmen. */
    fun shutdown() { ex.shutdown() }
}

/** Handler auf dem Hauptthread, der Nachrichten nach dem Ende der Activity verwirft. */
class LebensHandler(private val lebt: () -> Boolean) : Handler(Looper.getMainLooper()) {
    override fun dispatchMessage(msg: Message) {
        if (lebt()) super.dispatchMessage(msg)
    }
}
