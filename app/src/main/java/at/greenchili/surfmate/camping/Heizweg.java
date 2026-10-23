package at.greenchili.surfmate.camping;

/**
 * Stellglied hinter dem {@link CampingRegler} (0.1.16 Stufe 2b): führt EIN/AUS wirklich aus.
 * H1 = lokal über das SDK bei eingeschaltetem Auto, H2 = Fernklima-Sitzung über die BYD-Cloud bei
 * ausgeschaltetem Auto (CAMPING_PLAN.md 1.2, 2.1a, 4). In Tests ersetzbar.
 */
public interface Heizweg {

    /** Ergebnis eines Schaltversuchs. */
    final class Ergebnis {
        public enum Art { LOKAL, CLOUD, UEBERSPRUNGEN, FEHLER }
        public final Art art;
        /** Grund bei UEBERSPRUNGEN/FEHLER (Meldungs- bzw. Logcode), sonst null. */
        public final String grund;
        Ergebnis(Art art, String grund) { this.art = art; this.grund = grund; }
        public static Ergebnis lokal() { return new Ergebnis(Art.LOKAL, null); }
        public static Ergebnis cloud() { return new Ergebnis(Art.CLOUD, null); }
        public static Ergebnis uebersprungen(String g) { return new Ergebnis(Art.UEBERSPRUNGEN, g); }
        public static Ergebnis fehler(String g) { return new Ergebnis(Art.FEHLER, g); }
        public boolean geschaltet() { return art == Art.LOKAL || art == Art.CLOUD; }
        @Override public String toString() { return art + (grund == null ? "" : "(" + grund + ")"); }
    }

    /**
     * Heizen ein.
     *
     * @param stufe    Drehregler 26–33 (H1)
     * @param geblaese 1–7 (H1)
     * @param dauerMin geplante Dauer (H2: Cloud-Sitzung 10/15/20/25/30 min, aufgerundet)
     * @param cloudErlaubt Einstellung erlaubt H2
     */
    Ergebnis ein(int stufe, int geblaese, int dauerMin, CampingRegler.Lage l, boolean cloudErlaubt);

    /** Heizen aus. H1: lokal stop; H2: nichts – die Cloud-Sitzung läuft ab, nie CLOSEAIR. */
    Ergebnis aus(CampingRegler.Lage l);

    /** Zustand für den Status (Zähler, Sperren). */
    org.json.JSONObject stand();

    /** Beim Ein- bzw. Ausschalten von Camping: Zähler zurücksetzen. */
    void zuruecksetzen();

    /** Takt: z. B. prüfen, ob Weg 2 nach einer Cloud-Sitzung noch lebt. Liefert Meldungscodes (oder null). */
    String nachTakt(long jetztMs);
}
