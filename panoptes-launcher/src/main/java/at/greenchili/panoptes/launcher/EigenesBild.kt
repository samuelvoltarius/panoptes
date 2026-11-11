package at.greenchili.panoptes.launcher

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 0.1.40 (L2): eigenes Hintergrundbild, gewählt über die Android-Dateiauswahl (Datei oder USB-Stick).
 * Das Bild wird verkleinert (lange Seite höchstens [MAX_KANTE] px) als JPEG im Launcher gespeichert und nie
 * exportiert. Zu große oder unlesbare Dateien werden abgelehnt. Die Rechenregeln sind rein und im Unit-Test belegt.
 */
object EigenesBild {
    const val DATEI = "hintergrund_eigen.jpg"
    /** Größte Datei, die überhaupt gelesen wird (Schutz vor Speicher-Überlauf am Auto). */
    const val MAX_BYTES = 30L * 1024 * 1024
    /** Lange Seite des gespeicherten Bildes (Bildschirm ist 1920 breit oder kleiner). */
    const val MAX_KANTE = 1920
    const val QUALITAET = 85

    fun da(dir: File): Boolean = File(dir, DATEI).let { it.isFile && it.length() > 0 }

    /** Zweierpotenz für inSampleSize, damit das dekodierte Bild höchstens etwa 2 × [maxKante] lang ist. */
    fun teiler(breite: Int, hoehe: Int, maxKante: Int = MAX_KANTE): Int {
        var t = 1
        while (maxOf(breite, hoehe) / (t * 2) >= maxKante) t *= 2
        return t
    }

    /** Endgröße: lange Seite auf [maxKante] begrenzt, Seitenverhältnis bleibt, nie vergrößert. */
    fun zielGroesse(breite: Int, hoehe: Int, maxKante: Int = MAX_KANTE): Pair<Int, Int> {
        val lang = maxOf(breite, hoehe)
        if (lang <= maxKante || lang <= 0) return breite to hoehe
        val f = maxKante.toDouble() / lang
        return maxOf(1, Math.round(breite * f).toInt()) to maxOf(1, Math.round(hoehe * f).toInt())
    }

    /** Liest, verkleinert und speichert; true = übernommen. Läuft im Hintergrund-Thread. */
    fun uebernehmen(cr: ContentResolver, uri: Uri, dir: File): Boolean {
        return try { uebernehmenRoh(cr, uri, dir) } catch (_: Throwable) { false }
    }

    private fun uebernehmenRoh(cr: ContentResolver, uri: Uri, dir: File): Boolean {
        val bytes = cr.openInputStream(uri)?.use { s ->
            val puffer = java.io.ByteArrayOutputStream()
            val b = ByteArray(64 * 1024)
            var gesamt = 0L
            while (true) {
                val n = s.read(b); if (n < 0) break
                gesamt += n
                if (gesamt > MAX_BYTES) return false
                puffer.write(b, 0, n)
            }
            puffer.toByteArray()
        } ?: return false
        val grenzen = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, grenzen)
        if (grenzen.outWidth <= 0 || grenzen.outHeight <= 0) return false
        val roh = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = teiler(grenzen.outWidth, grenzen.outHeight) }) ?: return false
        val (w, h) = zielGroesse(roh.width, roh.height)
        val bmp = if (w == roh.width && h == roh.height) roh else Bitmap.createScaledBitmap(roh, w, h, true)
        val tmp = File(dir, "$DATEI.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITAET, it) }
        if (bmp !== roh) bmp.recycle(); roh.recycle()
        return tmp.renameTo(File(dir, DATEI))
    }

    fun laden(dir: File): Bitmap? = try {
        val f = File(dir, DATEI)
        if (!f.isFile) null else BitmapFactory.decodeFile(f.path)
    } catch (_: Throwable) {
        null
    }
}

/** Einträge einer Auswahl-Liste (rein): genau die Texte der Punkte, in derselben Reihenfolge. */
object Auswahl {
    fun eintraege(punkte: List<Pair<String, *>>): Array<String> = punkte.map { it.first }.toTypedArray()
}
