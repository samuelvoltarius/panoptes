package at.greenchili.surfmate.byd;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 0.1.38.7 Fernklima ehrlich anzeigen (04.11., 16:49: Heizen Stufe 3 für 10 min per Cloud gestartet, die Heizung lief,
 * Panoptes zeigte „Klima aus“). Bei Zündung aus liefert das SDK {@code climate_on=0, ac_on=0, ac_fan=0}, und die Cloud meldet
 * keinen gemessenen Klima-Zustand ({@link KlimaLaeuft#cloudGemessen()} ist falsch). Das Einzige, was Panoptes weiß, ist der eigene
 * erfolgreiche Befehl. Diese Klasse hält ihn als <b>Sitzung</b> fest und beschreibt ihn immer als „laut Befehl“, nie als Messwert:
 * <ul>
 *   <li>{@code aktiv}: Start erfolgreich, die gewählte Dauer läuft noch;</li>
 *   <li>{@code beendet}: Dauer um oder per „aus“ beendet; eine Stunde lang sichtbar, danach weg;</li>
 *   <li>hat das Auto einen echten Zustand ({@link KlimaLaeuft}), gewinnt der, das entscheiden die Anzeigen.</li>
 * </ul>
 * Rein, liest nichts selbst.
 */
public final class FernKlima {

    /** So lange bleibt „Fernklima beendet“ sichtbar. */
    public static final long BEENDET_SICHTBAR_MS = 60L * 60_000L;

    public static final String ENDE_ABGELAUFEN = "abgelaufen", ENDE_AUS = "aus";

    private FernKlima() {}

    /** Ein per Cloud gestartetes Fernklima (unveränderlich). */
    public static final class Sitzung {
        public final long startMs, bisMs;
        public final int dauerMin;
        /** Gewünschter Wert in der SDK-Skala (Surf: Stufe + 16). */
        public final int tempSdk;
        /** 0 = läuft nach der Uhr noch, sonst Ende (Uhrzeit). */
        public final long endeMs;
        public final String endeGrund;

        private Sitzung(long startMs, long bisMs, int dauerMin, int tempSdk, long endeMs, String endeGrund) {
            this.startMs = startMs;
            this.bisMs = bisMs;
            this.dauerMin = dauerMin;
            this.tempSdk = tempSdk;
            this.endeMs = endeMs;
            this.endeGrund = endeGrund;
        }

        public static Sitzung neu(long startMs, int dauerMin, int tempSdk) {
            return new Sitzung(startMs, startMs + dauerMin * 60_000L, dauerMin, tempSdk, 0L, null);
        }

        public Sitzung beendet(long jetzt, String grund) {
            if (endeMs != 0L) return this;
            return new Sitzung(startMs, bisMs, dauerMin, tempSdk, Math.min(jetzt, bisMs), grund);
        }

        /** Nach der Uhr abgelaufen? Dann gilt sie ab {@link #bisMs} als beendet. */
        public Sitzung stand(long jetzt) {
            if (endeMs == 0L && jetzt >= bisMs) return new Sitzung(startMs, bisMs, dauerMin, tempSdk, bisMs, ENDE_ABGELAUFEN);
            return this;
        }

        public boolean aktiv(long jetzt) {
            return endeMs == 0L && jetzt < bisMs;
        }
    }

    /**
     * JSON für {@code climate.fern} oder null (keine Sitzung, oder beendet vor mehr als {@link #BEENDET_SICHTBAR_MS}).
     * {@code stufe}/{@code art} nur auf Stufen-Modellen (Surf); sonst {@code tempC} in der SDK-Skala. {@code laut} ist immer
     * {@code befehl}: ein Beleg des Autos ist das nicht.
     */
    public static JSONObject json(Sitzung s0, long jetzt) {
        if (s0 == null) return null;
        Sitzung s = s0.stand(jetzt);
        boolean aktiv = s.aktiv(jetzt);
        if (!aktiv && jetzt - s.endeMs > BEENDET_SICHTBAR_MS) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("aktiv", aktiv);
            o.put("laut", "befehl");
            o.put("gestartet", s.startMs);
            o.put("bis", s.bisMs);
            o.put("dauerMinuten", s.dauerMin);
            boolean stufen = KlimaStufe.stufenModell();
            int stufe = stufen ? KlimaStufe.ausSdk(s.tempSdk) : -1;
            if (stufen && KlimaStufe.gueltig(stufe)) {
                o.put("stufe", stufe);
                o.put("art", stufe >= KlimaStufe.HEIZEN_AB ? KlimaStufe.HEIZEN
                        : stufe < KlimaStufe.STUFE_MITTE ? KlimaStufe.KUEHLEN : KlimaStufe.NEUTRAL);
            } else {
                o.put("stufe", JSONObject.NULL);
                o.put("art", JSONObject.NULL);
                o.put("tempSdk", s.tempSdk);
            }
            o.put("beendet", !aktiv);
            o.put("beendetUm", aktiv ? JSONObject.NULL : (Object) s.endeMs);
            o.put("endeGrund", aktiv ? JSONObject.NULL : (Object) s.endeGrund);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }
}
