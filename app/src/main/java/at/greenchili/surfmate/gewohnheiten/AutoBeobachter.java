package at.greenchili.surfmate.gewohnheiten;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2a): Bedienung direkt am Auto (BYD-Tasten/-Menüs) aus Zustandsänderungen der
 * Fahrzeugdaten. Vergleicht jeden Schnappschuss mit dem vorigen; rein (testbar), die Laufzeit füttert ihn aus
 * {@code BydEvent.bydEvent}.
 *
 * <p>Regeln für eine Änderung gültig → anderer gültiger Wert (unbekannt → Wert ist nie eine Handlung):
 * <ol>
 *   <li>trifft sie eine offene {@link Erwartungen Erwartung} (Panoptes/Automation hat es befohlen) → nichts;</li>
 *   <li>Zündung unbekannt → {@link Herkunft#AUTO_SYSTEM} (Grenzfall);</li>
 *   <li>bis 30 s nach Zündung an (das Auto stellt die letzte Einstellung wieder her) → AUTO_SYSTEM;</li>
 *   <li>Abschalten bis 60 s nach Zündung aus bzw. Ladeende/Stecker gezogen → AUTO_SYSTEM;</li>
 *   <li>sonst Zündung an → {@link Herkunft#AUTO}, Zündung aus → {@link Herkunft#BYD_APP} (ein Schluss).</li>
 * </ol>
 * Soll-Temperaturen werden erst nach {@link #RUHE_MS} ohne weitere Änderung gewertet (Drehrad: Zwischenwerte).
 * AUTO_SYSTEM wird mitgeschrieben (mit Grenzfall-Grund), damit das Log am echten Auto zeigt, ob die Grenzen stimmen.
 */
public final class AutoBeobachter {

    public static final int UNBEKANNT = Integer.MIN_VALUE;
    static final long NACH_AN_MS = 30_000L, NACH_AUS_MS = 60_000L, RUHE_MS = 10_000L;

    public static final String KLIMA = "klima", TEMP_FAHRER = "temp_fahrer", TEMP_BEIFAHRER = "temp_beifahrer",
            HEIZ_FAHRER = "sitzheizung_fahrer", HEIZ_BEIFAHRER = "sitzheizung_beifahrer",
            LUEFT_FAHRER = "sitzlueftung_fahrer", LUEFT_BEIFAHRER = "sitzlueftung_beifahrer", LENKRAD = "lenkrad";

    /** Ein Schnappschuss der beobachteten Werte; {@link #UNBEKANNT} bzw. null = unbekannt. */
    public static final class Stand {
        public final Map<String, Integer> werte = new LinkedHashMap<>();
        public Boolean zuendung, laedt, angesteckt;

        public Stand setze(String signal, int wert) {
            werte.put(signal, wert);
            return this;
        }

        public Stand zuendungAn() {
            zuendung = true;
            return this;
        }
    }

    /** Eine erkannte Bedienhandlung (oder ein mitgeschriebener Grenzfall). */
    public static final class Ereignis {
        public final String aktion, quelle, grenzfall;
        public final JSONObject wert;

        Ereignis(String aktion, JSONObject wert, String quelle, String grenzfall) {
            this.aktion = aktion;
            this.wert = wert;
            this.quelle = quelle;
            this.grenzfall = grenzfall;
        }
    }

    private static final class Offen {
        final int alt;
        int neu;
        long seit;
        final String quelle, grenzfall;

        Offen(int alt, int neu, long seit, String quelle, String grenzfall) {
            this.alt = alt;
            this.neu = neu;
            this.seit = seit;
            this.quelle = quelle;
            this.grenzfall = grenzfall;
        }
    }

    private final Erwartungen erwartungen;
    private Stand vorher;
    private long zuendungAnSeit = Long.MIN_VALUE / 2, zuendungAusSeit = Long.MIN_VALUE / 2, ladeEndeSeit = Long.MIN_VALUE / 2;
    private final Map<String, Offen> offen = new HashMap<>();

    public AutoBeobachter(Erwartungen erwartungen) {
        this.erwartungen = erwartungen;
    }

    public synchronized List<Ereignis> beobachte(Stand neu, long jetzt) throws JSONException {
        List<Ereignis> aus = new ArrayList<>();
        Stand alt = vorher;
        vorher = neu;
        if (alt != null) {
            if (!Boolean.TRUE.equals(alt.zuendung) && Boolean.TRUE.equals(neu.zuendung)) zuendungAnSeit = jetzt;
            if (Boolean.TRUE.equals(alt.zuendung) && Boolean.FALSE.equals(neu.zuendung)) zuendungAusSeit = jetzt;
            if ((Boolean.TRUE.equals(alt.laedt) && Boolean.FALSE.equals(neu.laedt))
                    || (Boolean.TRUE.equals(alt.angesteckt) && Boolean.FALSE.equals(neu.angesteckt))) ladeEndeSeit = jetzt;
            for (Map.Entry<String, Integer> e : neu.werte.entrySet()) {
                String s = e.getKey();
                int n = e.getValue();
                Integer a = alt.werte.get(s);
                if (a == null || a == UNBEKANNT || n == UNBEKANNT || a == n) continue;
                boolean temp = s.startsWith("temp_");
                Offen o = temp ? offen.get(s) : null;
                if (o != null) {            // Drehrad läuft noch: nur Endwert und Zeit nachführen
                    o.neu = n;
                    o.seit = jetzt;
                    continue;
                }
                if (erwartungen.verbrauche(s, a, n, jetzt)) continue;
                String[] q = einordnen(s, n, neu.zuendung, jetzt);
                if (temp) offen.put(s, new Offen(a, n, jetzt, q[0], q[1]));
                else aus.add(ereignis(s, a, n, q[0], q[1]));
            }
        }
        for (java.util.Iterator<Map.Entry<String, Offen>> it = offen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Offen> e = it.next();
            Offen o = e.getValue();
            if (jetzt - o.seit < RUHE_MS) continue;
            it.remove();
            if (o.neu != o.alt) aus.add(ereignis(e.getKey(), o.alt, o.neu, o.quelle, o.grenzfall));
        }
        return aus;
    }

    /** {quelle, grenzfall} nach den Regeln 2–5. */
    private String[] einordnen(String signal, int neu, Boolean zuendung, long jetzt) {
        if (zuendung == null) return new String[] {Herkunft.AUTO_SYSTEM, "zuendung_unbekannt"};
        if (zuendung && jetzt - zuendungAnSeit < NACH_AN_MS) return new String[] {Herkunft.AUTO_SYSTEM, "nach_zuendung_an"};
        if (istAus(signal, neu)) {
            if (jetzt - zuendungAusSeit < NACH_AUS_MS) return new String[] {Herkunft.AUTO_SYSTEM, "nach_zuendung_aus"};
            if (jetzt - ladeEndeSeit < NACH_AUS_MS) return new String[] {Herkunft.AUTO_SYSTEM, "nach_ladeende"};
        }
        return new String[] {zuendung ? Herkunft.AUTO : Herkunft.BYD_APP, null};
    }

    static boolean istAus(String signal, int neu) {
        return !signal.startsWith("temp_") && neu == 0;
    }

    static Ereignis ereignis(String s, int alt, int neu, String quelle, String grenzfall) throws JSONException {
        JSONObject w = new JSONObject();
        String aktion;
        switch (s) {
            case KLIMA:
                aktion = neu == 1 ? Aktionen.KLIMA_AN : Aktionen.KLIMA_AUS;
                break;
            case TEMP_FAHRER:
            case TEMP_BEIFAHRER:
                aktion = Aktionen.KLIMA_TEMP;
                w.put("zone", s.equals(TEMP_FAHRER) ? "fahrer" : "beifahrer").put("temp", neu);
                break;
            case HEIZ_FAHRER:
            case HEIZ_BEIFAHRER:
                aktion = Aktionen.SITZHEIZUNG;
                w.put("sitz", s.equals(HEIZ_FAHRER) ? "fahrer" : "beifahrer").put("stufe", neu);
                break;
            case LUEFT_FAHRER:
            case LUEFT_BEIFAHRER:
                aktion = Aktionen.SITZLUEFTUNG;
                w.put("sitz", s.equals(LUEFT_FAHRER) ? "fahrer" : "beifahrer").put("stufe", neu);
                break;
            default:
                aktion = Aktionen.LENKRADHEIZUNG;
                w.put("an", neu == 1);
                break;
        }
        return new Ereignis(aktion, w, quelle, grenzfall);
    }
}
