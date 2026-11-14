package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 0.1.43.4 (Paket S „Launcher eigenständig“): EIN Baustein für die Frage „Ist der Panoptes-Kern da?“.
 *
 * Nur die Abfrage in MainActivity.refresh() misst ([sollPruefen], [melde]); alle anderen (Kamera, QR, Sprache, Design) lesen nur:
 * - [verfuegbar]: Kern hat zuletzt geantwortet → Kern-Wege benutzen.
 * - [ohnePanoptes]: Panoptes fehlt oder hat seit dem Start nie geantwortet → statt leerer Kacheln ruhig „Mit Panoptes: …“.
 *   War der Kern schon einmal da und ist nur kurz weg, bleibt es bei den alten Werten mit „zuletzt vor X min“ (Frische).
 * - [beimFreischalten]: wird gerufen, sobald der Kern (wieder) antwortet; dann baut die Oberfläche sich selbst um.
 *
 * Gedrosselt: Fehlt die Panoptes-App, versucht der Launcher gar keine Verbindung (nur alle [FEHLT_MS] ein Blick, ob sie
 * inzwischen installiert ist, billig über den PackageManager). Ist sie da, antwortet aber nicht, werden die Versuche
 * seltener (erst im normalen Takt, dann alle 30 s, dann alle 2 min). Keine Fehlermeldungen, keine Dauerversuche.
 * Die Regeln ([abstandMs], [ohnePanoptes]) sind rein und JVM-testbar.
 */
/**
 * 0.1.43.4 (E2E H4/H2): Wohin „Panoptes ansehen“ führt – die Installationsanleitung auf byd.serveone.at, nicht GitHub.
 * Sprachpfade wie auf der Seite (hreflang): Deutsch unter /panoptes/#installieren, Englisch unter /panoptes/en/#install.
 * Andere Sprachen gibt es dort (noch) nicht, dann Deutsch.
 */
object PanoptesSeite {
    const val DE = "https://byd.serveone.at/panoptes/#installieren"
    const val EN = "https://byd.serveone.at/panoptes/en/#install"
    /** Startseite der Community-Karte; als Ziel der Bitte um ein 3D-Modell vorerst durch die Anleitung ersetzt (H2). */
    const val STARTSEITE = "https://byd.serveone.at/"

    fun adresse(sprache: String?): String = if (sprache?.lowercase(java.util.Locale.ROOT) == "en") EN else DE

    fun sprache(): String = java.util.Locale.getDefault().language

    /** H2: solange es keine eigene Einsendeseite gibt, führt die Bitte zur Anleitung; eine eigene Seite im Katalog gilt. */
    fun bitteZiel(url: String, sprache: String?): String = if (url.trimEnd('/') == STARTSEITE.trimEnd('/')) adresse(sprache) else url
}

object KernVerbindung {
    enum class Stand { UNBEKANNT, DA, WEG, FEHLT }

    const val PANOPTES_PAKET = "at.greenchili.surfmate"
    /** 0.1.43.4 (E2E H4): Panoptes-Seite = Installationsanleitung in der Gerätesprache ([PanoptesSeite]). */
    val PANOPTES_SEITE: String get() = PanoptesSeite.adresse(PanoptesSeite.sprache())

    /** So viele Fehlversuche in Folge im normalen Takt (Kern startet gerade), dann seltener. */
    const val SCHNELL_VERSUCHE = 3
    const val LANGSAM_AB = 10
    const val MITTEL_MS = 30_000L
    const val LANGSAM_MS = 120_000L
    /** Panoptes nicht installiert: so selten wird nachgesehen (ohne Netz, nur PackageManager). */
    const val FEHLT_MS = 10 * 60_000L
    /** Rückkehr in den Launcher stößt höchstens so oft eine Prüfung an. */
    const val ANSTOSS_MS = 15_000L

    /**
     * Kacheln, die ohne Kern nichts zeigen können (Fahrzeugdaten, Wächter, Kamera, Haus, Umgebung, Sprache). Ohne Panoptes
     * werden sie zur ruhigen Kachel „Mit Panoptes: …“. Musik, Uhr, Navi, Projektion, Android-Widgets, Profil und die
     * Leisten gehen ohne Kern.
     */
    val BRAUCHT_KERN: Set<Kachel> = setOf(Kachel.FAHRZEUG, Kachel.AKKU, Kachel.WAECHTER, Kachel.SCHLOSS, Kachel.LADEN,
        Kachel.KAMERA, Kachel.REIFEN, Kachel.FAHRT, Kachel.TEMPO, Kachel.FAHRSTATUS, Kachel.AUTOANSICHT, Kachel.HAUS,
        Kachel.UMGEBUNG, Kachel.SPRACHE)

    /** Platzhalter statt Kachel? */
    fun platzhalter(k: Kachel, ohne: Boolean = ohnePanoptes()): Boolean = ohne && k in BRAUCHT_KERN

    @Volatile var stand: Stand = Stand.UNBEKANNT
        private set
    @Volatile var fehlversuche: Int = 0
        private set
    @Volatile var warSchonDa: Boolean = false
        private set
    @Volatile private var naechsteMs = 0L
    @Volatile private var letzteMs = 0L
    private val hoerer = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Kern hat zuletzt geantwortet. */
    fun verfuegbar(): Boolean = stand == Stand.DA

    /** „Mit Panoptes: …“ statt Kachel? (Panoptes fehlt, oder der Kern hat seit dem Start nie geantwortet.) */
    fun ohnePanoptes(): Boolean = ohnePanoptes(stand, fehlversuche, warSchonDa)

    fun ohnePanoptes(s: Stand, fehl: Int, warDa: Boolean): Boolean = when (s) {
        Stand.FEHLT -> true
        Stand.WEG -> !warDa && fehl >= SCHNELL_VERSUCHE
        else -> false
    }

    /** Wartezeit bis zur nächsten Prüfung nach einem Ergebnis (0 = im normalen Takt der Abfrage). */
    fun abstandMs(s: Stand, fehl: Int): Long = when (s) {
        Stand.UNBEKANNT, Stand.DA -> 0L
        Stand.FEHLT -> FEHLT_MS
        Stand.WEG -> when {
            fehl < SCHNELL_VERSUCHE -> 0L
            fehl < LANGSAM_AB -> MITTEL_MS
            else -> LANGSAM_MS
        }
    }

    /**
     * Darf die Abfrage jetzt den Kern fragen? Antwortet er, immer (normaler Takt). Sonst erst, wenn die Wartezeit um
     * ist; erst dann wird [installiert] (PackageManager) gefragt. Fehlt Panoptes: kein Netz, Stand FEHLT.
     */
    @Synchronized
    fun sollPruefen(jetzt: Long, installiert: () -> Boolean): Boolean {
        if (stand == Stand.DA) return true
        if (jetzt < naechsteMs) return false
        if (!installiert()) { setze(Stand.FEHLT, jetzt); return false }
        if (stand == Stand.FEHLT) { stand = Stand.UNBEKANNT; fehlversuche = 0 }   // eben installiert: gleich fragen
        return true
    }

    // Stand ohne Panoptes (GPS-Tempo, sonst „Ich stehe“): StandOhneKern.kt

    /** Ergebnis der Abfrage. Wechsel auf „da“ ruft die [beimFreischalten]-Hörer. */
    @Synchronized
    fun melde(ok: Boolean, jetzt: Long) {
        letzteMs = jetzt
        if (ok) { fehlversuche = 0; warSchonDa = true; setze(Stand.DA, jetzt) }
        else { fehlversuche++; setze(Stand.WEG, jetzt) }
    }

    /** Rückkehr in den Launcher: eine baldige Prüfung erlauben, aber höchstens alle [ANSTOSS_MS]. */
    @Synchronized
    fun anstossen(jetzt: Long) {
        if (stand == Stand.WEG) naechsteMs = minOf(naechsteMs, maxOf(jetzt, letzteMs + ANSTOSS_MS))
    }

    private fun setze(neu: Stand, jetzt: Long) {
        val alt = stand
        stand = neu
        naechsteMs = jetzt + abstandMs(neu, fehlversuche)
        if (neu == Stand.DA && alt != Stand.DA) hoerer.forEach { runCatching { it() } }
    }

    fun beimFreischalten(f: () -> Unit) { hoerer += f }
    fun hoererWeg(f: () -> Unit) { hoerer -= f }

    /** Nur für Tests. */
    @Synchronized
    fun zuruecksetzen() { stand = Stand.UNBEKANNT; fehlversuche = 0; warSchonDa = false; naechsteMs = 0L; letzteMs = 0L; hoerer.clear() }

    // ---------- Android ----------

    fun installiert(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(PANOPTES_PAKET, 0); true
    } catch (_: Exception) {
        false
    }

    /** Link „Mit Panoptes“: ist Panoptes installiert, die App öffnen, sonst die Panoptes-Seite. false = nichts ließ sich öffnen. */
    fun panoptesOeffnen(c: Context): Boolean {
        val app = if (installiert(c)) c.packageManager.getLaunchIntentForPackage(PANOPTES_PAKET) else null
        val ziel = app ?: Intent(Intent.ACTION_VIEW, Uri.parse(PANOPTES_SEITE))
        return try {
            c.startActivity(ziel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) {
            false
        }
    }
}
