package at.greenchili.panoptes.launcher

/** Widgets der Startseite. Daten nur aus der Launcher-API (v1) bzw. der Android-Mediensitzung. */
/** 0.1.40 (L2): SCHLOSS, LADEN, KAMERA neu – zeigen nur Panoptes-Daten; Befehle bleiben in Panoptes (Regel A+). */
/** 0.1.41 (CP): PROJEKTION = CarPlay / Android Auto (Werks-Apps, Start im Vollbild), siehe [Projektion]. */
/** 0.1.41 (LK): REIFEN, FAHRT (letzte Fahrt), TEMPO – nur Kern-Daten, siehe [ZusatzKacheln].
 *  0.1.41 (LO): GERAETEWIDGET = Android-Widget einer fremden App (Element.wid), darf mehrfach vorkommen. */
/** 0.1.44 (P2): SPRACHE öffnet die Sprachbefehle von Panoptes (feste Kommandos, Fahrzeugbefehle nur nach Rückfrage). */
/** 0.1.44 (P4): HAUS = Haus-Status aus Home Assistant (nur lesen, siehe [Haus]), PROFIL = Fahrerprofil wechseln (siehe [Fahrerprofile]). */
/** 0.1.44 (D1): FAHRSTATUS (Tempo-Ring, Gang, Reichweite, Akku, Reifen) und AUTOANSICHT (eigene Fahrzeug-Ansicht + Fenster). */
enum class Kachel { FAHRZEUG, AKKU, NAVI, MUSIK, WAECHTER, UHR, SCHLOSS, LADEN, KAMERA, PROJEKTION, REIFEN, FAHRT, TEMPO, GERAETEWIDGET, SPRACHE, FAHRSTATUS, AUTOANSICHT,
    /** 0.1.44 (E2): Dock und Reiterzeile als frei platzierbare Elemente (dann entfällt die feste Leiste). */
    DOCKLEISTE, REITERLEISTE,
    /** 0.1.44 (P4): Haus-Kachel (Home Assistant, nur lesen) und Fahrerprofil. */
    HAUS, PROFIL,
    /** 0.1.44 (L1): Live-Umgebung (Skizze aus der Erkennung, ab Werk aus). */
    UMGEBUNG }

/** Fahrsicherheit: reine Regeln, im Unit-Test belegt. Starten und Ansehen geht immer. */
object Fahrsicherheit {
    /** Video-Hintergrund nur mit Schalter (Standard aus) UND bestätigtem Gang P. Unbekannter Gang = kein Video. */
    fun videoErlaubt(schalter: Boolean, gear: String?): Boolean = schalter && gear == "P"

    /**
     * Bedienen (Bearbeiten-Modus, Ordner, Dock ändern, Suche tippen) nur im Stand:
     * Gang P, oder Gang unbekannt bei Zündung aus. Tempo > 0 sperrt immer.
     */
    fun bearbeitenErlaubt(gear: String?, acc: String?, speedKmh: Double? = null): Boolean {
        if (speedKmh != null && speedKmh > 0.5) return false
        return gear == "P" || (gear == null && acc == "off")
    }

    /**
     * Video-Apps (Android-Kategorie CATEGORY_VIDEO, von der App selbst erklärt) starten nur in P – außer der Nutzer
     * hat in Panoptes den EINEN Haftungshinweis „Verstanden, ich entscheide selbst“ bestätigt (0.1.38.1, derselbe wie
     * am Tacho; kommt aus dem Summary, fehlt er, gilt nein). Unbekannter Gang ohne Hinweis sperrt.
     */
    fun appStartErlaubt(istVideo: Boolean, gear: String?, fahrtHinweis: Boolean = false): Boolean =
        !istVideo || gear == "P" || fahrtHinweis

    /**
     * 0.1.38.5: Klima-Stufe des Surf (1..17, Mitte 9) als (Art, Zahl): Art 1 = Heizen, -1 = Kühlen, 0 = Neutral;
     * Zahl 1..8. null außerhalb. Wie KlimaStufe im Kern, damit der Launcher nie eine Gradzahl erfindet.
     */
    fun klimaStufe(stufe: Int?): Pair<Int, Int>? {
        if (stufe == null || stufe !in 1..17) return null
        return when { stufe > 9 -> 1 to (stufe - 9); stufe < 9 -> -1 to (9 - stufe); else -> 0 to 0 }
    }

    /**
     * 0.1.38.5: welche Navi-App der Launcher direkt startet: eigene Wahl, sonst die am Tacho gewählte, sonst die erste
     * startbare bekannte; null = keine (dann Karte in Panoptes). Nur startbare Pakete.
     */
    // 0.1.39 (L1): Automatisch = Google Maps, sonst Werksnavi; eine eigene Wahl bleibt immer zuerst (KartenStatus)
    fun naviZiel(eigen: String?, tacho: String?, bekannt: List<String>, startbar: (String) -> Boolean): String? =
        KartenStatus.naviReihenfolge(eigen, tacho, bekannt).firstOrNull { startbar(it) }
}
