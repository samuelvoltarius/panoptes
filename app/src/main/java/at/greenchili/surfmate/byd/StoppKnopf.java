package at.greenchili.surfmate.byd;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 0.1.38.8 Der rote Knopf „Heizen stoppen“ (Startseite, Handy-App): nur zeigen, wenn wirklich etwas laufen könnte. Seit {@code acOn}
 * bei Zündung aus fehlt (0.1.38.7), war der Knopf bei abgestelltem Auto dauerhaft zu sehen und tat nichts Sichtbares (04.11.).
 * Eine Entscheidung im Kern, die alle Oberflächen lesen ({@code climate.stopp}). Rein.
 *
 * <p>Zeigen, wenn eines davon gilt (Beleg, nicht Vermutung):
 * <ul>
 *   <li>das Klima läuft laut Auto (lokal) oder laut frischem Cloud-Rücklesen ({@link KlimaLaeuft#LAEUFT});</li>
 *   <li>die Akku-Heizung läuft laut Cloud;</li>
 *   <li>ein Fern-Start laut Befehl ist aktiv ({@code FernKlima}) oder wurde vor weniger als {@link #BEENDET_NOCH_MS} beendet.</li>
 * </ul>
 * Bei „unbekannt“ ohne Fern-Sitzung bleibt er versteckt. {@code weg}: {@code cloud}, wenn die Quelle die Cloud ist oder ein Fern-Start
 * betroffen ist (dann geht der Stopp über die Cloud, {@code remote:true}), sonst {@code lokal}.
 */
public final class StoppKnopf {

    /** So lange nach dem Ende einer Fern-Sitzung bleibt der Knopf noch. */
    public static final long BEENDET_NOCH_MS = 15L * 60_000L;

    public static final String GRUND_AUTO = "auto", GRUND_CLOUD = "cloud", GRUND_AKKU = "akku", GRUND_FERN = "fern",
            GRUND_FERN_BEENDET = "fern_beendet", GRUND_KEINER = "keiner";
    public static final String WEG_LOKAL = "lokal", WEG_CLOUD = "cloud";

    private StoppKnopf() {}

    /** Fern-Sitzung läuft oder wurde vor weniger als {@link #BEENDET_NOCH_MS} beendet. */
    public static boolean fernRelevant(JSONObject fern, long jetzt) {
        if (fern == null) return false;
        if (fern.optBoolean("aktiv", false)) return true;
        if (fern.optBoolean("beendet", false) && !fern.isNull("beendetUm")) {
            long vor = jetzt - fern.optLong("beendetUm");
            return vor >= 0 && vor < BEENDET_NOCH_MS;
        }
        return false;
    }

    /**
     * Das Auto schläft nachweislich: Leistungsstufe bekannt und unter 2 (Zündung aus). Unbekannt (DiLink 5, kein Wert) gilt
     * nicht als schlafend, sonst ginge dort nie ein Stopp.
     */
    public static boolean schlaeft(boolean powerLevelBekannt, int powerLevel) {
        return powerLevelBekannt && powerLevel < 2;
    }

    public static final String WEG_NICHTS = "nichts";

    /**
     * Wohin ein Stopp OHNE ausdrücklichen Weg gehen darf: schläft das Auto, geht nie ein lokaler Befehl hin (er weckt Auto und
     * Display, 12 V; Deploy 04.11. 19:30). Mit Fern-Sitzung über die Cloud, sonst {@link #WEG_NICHTS}.
     */
    public static String stoppWeg(boolean schlaeft, boolean fernRelevant) {
        if (!schlaeft) return WEG_LOKAL;
        return fernRelevant ? WEG_CLOUD : WEG_NICHTS;
    }

    /**
     * @param laeuft      Bewertung ({@link KlimaZustand#aktuell}) oder null
     * @param fern        {@code climate.fern} ({@link FernKlima#json}) oder null
     * @param akkuHeizung Akku-Heizung laut Cloud an
     */
    public static JSONObject json(KlimaLaeuft.Ergebnis laeuft, JSONObject fern, boolean akkuHeizung, long jetzt) {
        boolean klimaLaeuft = laeuft != null && KlimaLaeuft.LAEUFT.equals(laeuft.zustand);
        boolean fernAktiv = fern != null && fern.optBoolean("aktiv", false);
        boolean fernKurzBeendet = !fernAktiv && fernRelevant(fern, jetzt);
        String grund;
        if (klimaLaeuft) grund = KlimaLaeuft.QUELLE_CLOUD.equals(laeuft.quelle) ? GRUND_CLOUD : GRUND_AUTO;
        else if (fernAktiv) grund = GRUND_FERN;
        else if (akkuHeizung) grund = GRUND_AKKU;
        else if (fernKurzBeendet) grund = GRUND_FERN_BEENDET;
        else grund = GRUND_KEINER;
        boolean zeigen = !GRUND_KEINER.equals(grund);
        boolean cloud = GRUND_CLOUD.equals(grund) || GRUND_FERN.equals(grund) || GRUND_FERN_BEENDET.equals(grund)
                || (klimaLaeuft && fernAktiv);
        try {
            return new JSONObject().put("zeigen", zeigen).put("grund", grund).put("weg", cloud ? WEG_CLOUD : WEG_LOKAL);
        } catch (JSONException e) {
            return null;
        }
    }
}
