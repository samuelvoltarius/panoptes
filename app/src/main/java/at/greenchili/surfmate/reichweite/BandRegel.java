package at.greenchili.surfmate.reichweite;

import java.util.Locale;

/**
 * F1 Band am Mittelschirm (F1_KONZEPT.md §5a): wann es erscheint und was darin steht. Rein.
 *
 * <ul>
 *   <li>8 s bei jedem Stufenwechsel (auch der ersten Bewertung) und alle 10 min als Erinnerung.</li>
 *   <li>Ändert sich bei gleicher Stufe die Aussage (Stopp voll, neuer Vorschlag), erscheint es
 *       erneut, aber höchstens 1× je Minute.</li>
 *   <li>Höchstens 2 Zeilen, keine Knöpfe.</li>
 *   <li>Ansage (Standard aus): nur beim Stufenwechsel, höchstens alle 10 min, nur die erste Zeile.</li>
 * </ul>
 */
public final class BandRegel {

    static final long SICHTBAR_MS = 8_000L;
    static final long ERINNERUNG_MS = 10 * 60_000L;
    static final long ANSAGE_ABSTAND_MS = 10 * 60_000L;
    static final long INHALT_ABSTAND_MS = 60_000L;

    /** Text-Quelle (im Daemon {@code Messages::get}), im Test eine Tabelle. */
    public interface Texte {
        String get(String key, Object... args);
    }

    /** Eine Anzeige. */
    public static final class Anzeige {
        public final String titel, zeile2;
        /** passt | knapp | reicht_nicht */
        public final String stufe;
        public final long dauerMs;
        public final boolean ansagen;

        Anzeige(String titel, String zeile2, String stufe, long dauerMs, boolean ansagen) {
            this.titel = titel; this.zeile2 = zeile2; this.stufe = stufe;
            this.dauerMs = dauerMs; this.ansagen = ansagen;
        }

        @Override
        public String toString() {
            return "Band[" + stufe + " | " + titel + " | " + zeile2 + (ansagen ? " | Ansage" : "") + "]";
        }
    }

    private final Texte texte;
    private RangeWatch.Stufe gezeigteStufe;
    private String gezeigterInhalt;
    private long gezeigtAm = Long.MIN_VALUE / 2;
    private long angesagtAm = Long.MIN_VALUE / 2;

    public BandRegel(Texte texte) {
        this.texte = texte;
    }

    /** Neue Route oder F1 inaktiv: nächste Bewertung gilt wieder als Wechsel. */
    public void zuruecksetzen() {
        gezeigteStufe = null;
        gezeigterInhalt = null;
        gezeigtAm = Long.MIN_VALUE / 2;
    }

    /**
     * @param l         aktuelle Lage (aktiv, Stufe gesetzt)
     * @param belegung  Live-Belegung des nächsten Stopps oder null
     * @param vorschlag Neuplanungs-Vorschlag oder null
     * @param ansage    Einstellung „Ansage“ (Standard aus)
     * @return Anzeige oder null (nichts zeigen)
     */
    public Anzeige pruefe(long now, RangeWatch.Lage l, StoppBelegung belegung, Neuplanung.Vorschlag vorschlag,
                          boolean ansage) {
        if (l == null || !l.aktiv || l.stufe == null) return null;
        String[] z = zeilen(l, belegung, vorschlag);
        String inhalt = l.stufe + "|" + z[0] + "|" + z[1].replaceAll("\\d", "");
        boolean wechsel = l.stufe != gezeigteStufe;
        boolean erinnerung = now - gezeigtAm >= ERINNERUNG_MS;
        boolean neuerInhalt = !inhalt.equals(gezeigterInhalt) && now - gezeigtAm >= INHALT_ABSTAND_MS;
        if (!wechsel && !erinnerung && !neuerInhalt) return null;
        boolean sprechen = ansage && wechsel && now - angesagtAm >= ANSAGE_ABSTAND_MS;
        gezeigteStufe = l.stufe;
        gezeigterInhalt = inhalt;
        gezeigtAm = now;
        if (sprechen) angesagtAm = now;
        return new Anzeige(z[0], z[1], l.stufe.name().toLowerCase(Locale.ROOT), SICHTBAR_MS, sprechen);
    }

    /** Die zwei Zeilen nach F1_KONZEPT §5a. */
    String[] zeilen(RangeWatch.Lage l, StoppBelegung b, Neuplanung.Vorschlag v) {
        String ziel = l.naechsterName == null || l.naechsterName.isEmpty()
                ? texte.get("notifications.f1_ziel") : l.naechsterName;
        long ankunft = Math.max(0, Math.round(l.prognoseProzent));
        boolean voll = !l.naechsterIstZiel && b != null && b.voll;
        if (voll) {
            String z2 = b.ausweichName != null
                    ? texte.get("notifications.f1_voll_ausweich", b.ausweichName, km(b.ausweichKm))
                    : vorschlagZeile(v);
            return new String[] {texte.get("notifications.f1_voll_titel", ziel), z2};
        }
        switch (l.stufe) {
            case PASST:
                return new String[] {texte.get("notifications.f1_passt_titel", ziel),
                        texte.get("notifications.f1_ankunft", ankunft)};
            case KNAPP:
                return new String[] {texte.get("notifications.f1_knapp_titel", ziel),
                        texte.get("notifications.f1_knapp_zeile", ankunft)};
            default:
                return new String[] {texte.get("notifications.f1_reicht_nicht_titel", ziel), vorschlagZeile(v)};
        }
    }

    private String vorschlagZeile(Neuplanung.Vorschlag v) {
        if (v != null && "stopp".equals(v.art) && v.name != null) {
            return texte.get("notifications.f1_neuer_stopp", v.name, km(v.kmAbHier));
        }
        return texte.get("notifications.f1_neu_planen");
    }

    /** Zahl für {n} (MessageFormat formatiert nach Sprache: 3,5 / 3.5); unter 10 km eine Nachkommastelle. */
    private static Object km(double km) {
        if (Double.isNaN(km)) return "?";
        return km < 10 ? (Object) (Math.round(km * 10) / 10.0) : (Object) Math.round(km);
    }
}
