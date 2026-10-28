package at.greenchili.surfmate.gewohnheiten;

import at.greenchili.surfmate.automation.Automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.1.23 Gewohnheiten E3/E4 (KONZEPT §4/§5): die EINE große Karte – Display, Handy-API und Cockpit lesen dieselbe.
 * Reihenfolge: Freigabe („3-mal gepasst. Ab jetzt wirklich?“) vor Probelauf-Rückfrage („Jetzt hätte ich … Passt
 * das?“) vor einem neuen Vorschlag. Höchstens ein offener Vorschlag, höchstens ein neuer je Tag; „Später“ frühestens
 * in 3 Tagen wieder, beim 3. „Später“ gilt es als Nein. „Nein“ sperrt die Gewohnheit für immer.
 *
 * <p>Die Karte liefert nur Bausteine (Aktion, Wert, Tagestyp, Uhrzeit, Ort, Bedingung, meiste Herkunft); den kurzen
 * Satz baut die Oberfläche in der Sprache des Nutzers (keine festen Texte).
 */
public final class Angebot {

    public static final int RICHTIG_NOETIG = 3, NICHT_FRAGEN = 3, SPAETER_MAX = 3;
    static final long SPAETER_MS = 3L * 86_400_000L, PROBE_FRAGE_MS = 2L * 3_600_000L, PROBE_SPERRE_MS = 10L * 60_000L;

    /** Zugriff auf die Automationen (in Tests ersetzbar). */
    public interface Regeln {
        Map<String, Automation> alle();

        /** Neu (id null) oder ersetzen; liefert die Id oder null. */
        String speichere(String id, Automation a);

        boolean modus(String id, String modus);

        boolean loesche(String id);

        JSONArray orte();
    }

    private final Gewohnheiten g;
    private final Regeln regeln;
    private final File zustandDatei;

    public Angebot(Gewohnheiten g, Regeln regeln) {
        this.g = g;
        this.regeln = regeln;
        this.zustandDatei = new File(g.gesperrtDatei.getParentFile(), "zustand.json");
    }

    // ---------------------------------------------------------------- Karte

    /** Die eine Karte oder {@code {"art":"keine"}}. */
    public synchronized JSONObject karte() throws Exception {
        long jetzt = g.u.jetzt();
        JSONObject z = zustand();
        if (!g.aktiv()) return new JSONObject().put("art", "keine");
        // 1) Freigabe bzw. Aufräumen nach dem Probelauf
        for (Map.Entry<String, Automation> e : regeln.alle().entrySet()) {
            Automation a = e.getValue();
            JSONObject gw = a.getGewohnheit();
            if (gw == null || !a.isProbe()) continue;
            if (gw.optInt("richtig") >= RICHTIG_NOETIG) return karte("freigabe", e.getKey(), gw);
            if (gw.optInt("nicht") >= NICHT_FRAGEN) return karte("aufraeumen", e.getKey(), gw);
        }
        // 2) Rückfrage zu einem Probelauf (bis 2 h danach)
        JSONObject probe = z.optJSONObject("probe");
        if (probe != null) {
            for (java.util.Iterator<String> it = probe.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject p = probe.getJSONObject(id);
                Automation a = regeln.alle().get(id);
                if (a == null || !a.isProbe() || a.getGewohnheit() == null || p.optBoolean("beantwortet")) continue;
                if (jetzt - p.optLong("ausgeloest") <= PROBE_FRAGE_MS) {
                    return karte("probelauf", id, a.getGewohnheit()).put("ausgeloest", p.optLong("ausgeloest"));
                }
            }
        }
        // 3) Neuer Vorschlag
        JSONObject v = naechsterVorschlag(z, jetzt);
        if (v == null) return new JSONObject().put("art", "keine");
        String heute = tag(jetzt);
        if (!heute.equals(z.optString("neuTag"))) {
            z.put("neuSchluessel", v.getString("schluessel")).put("neuTag", heute).put("neuErledigt", false);
            schreibe(z);
        }
        return new JSONObject().put("art", "vorschlag").put("vorschlag", v);
    }

    /** Der gezeigte bzw. nächste Vorschlag; null, wenn heute schon ein anderer neuer gezeigt wurde. */
    JSONObject naechsterVorschlag(JSONObject z, long jetzt) throws Exception {
        Set<String> belegt = new HashSet<>();
        List<GewohnheitsErkenner.Abdeckung> abgedeckt = new ArrayList<>();
        for (Automation a : regeln.alle().values()) {
            JSONObject gw = a.getGewohnheit();
            if (gw != null) {
                belegt.add(gw.optString("schluessel"));
                abgedeckt.add(new GewohnheitsErkenner.Abdeckung(gw.optString("aktion"), gw.optInt("minute")));
            }
        }
        Set<String> gesperrt = GewohnheitsErkenner.menge(g.gesperrt());
        JSONObject spaeter = z.optJSONObject("spaeter");
        boolean heuteSchon = tag(jetzt).equals(z.optString("neuTag"));
        if (heuteSchon && z.optBoolean("neuErledigt", false)) return null;   // höchstens ein neuer je Tag
        for (JSONObject v : GewohnheitsErkenner.erkenne(g.log.lies(), jetzt, g.u.zone(), gesperrt, abgedeckt)) {
            String s = v.getString("schluessel");
            if (belegt.contains(s)) continue;
            JSONObject sp = spaeter == null ? null : spaeter.optJSONObject(s);
            if (sp != null && sp.optLong("bis") > jetzt) continue;
            // heute schon einer gezeigt: nur genau dieser, kein anderer
            if (heuteSchon && !s.equals(z.optString("neuSchluessel"))) continue;
            return v;
        }
        return null;
    }

    private JSONObject karte(String art, String id, JSONObject gw) throws Exception {
        return new JSONObject().put("art", art).put("automation", id).put("gewohnheit", new JSONObject(gw.toString()));
    }

    // ---------------------------------------------------------------- Antworten

    /**
     * {@code {art, schluessel|automation, antwort}}: vorschlag ja|nein|spaeter, probelauf passt|nicht,
     * freigabe ja|weiter|loeschen, aufraeumen loeschen|behalten. Liefert {@code {ok, …}} oder {@code {ok:false, grund}}.
     */
    public synchronized JSONObject antworte(JSONObject b) throws Exception {
        String art = b.optString("art"), antwort = b.optString("antwort");
        long jetzt = g.u.jetzt();
        JSONObject z = zustand();
        switch (art) {
            case "vorschlag": {
                String s = b.optString("schluessel");
                JSONObject v = null;
                for (JSONObject x : GewohnheitsErkenner.erkenne(g.log.lies(), jetzt, g.u.zone(),
                        GewohnheitsErkenner.menge(g.gesperrt()), null)) {
                    if (x.getString("schluessel").equals(s)) v = x;
                }
                if (v == null) return fehler("vorschlag_unbekannt");
                z.put("neuErledigt", true);
                if ("nein".equals(antwort)) {
                    sperre(s);
                } else if ("spaeter".equals(antwort)) {
                    JSONObject sp = z.optJSONObject("spaeter");
                    if (sp == null) z.put("spaeter", sp = new JSONObject());
                    int n = sp.optJSONObject(s) == null ? 1 : sp.getJSONObject(s).optInt("n") + 1;
                    if (n >= SPAETER_MAX) {
                        sperre(s);
                        sp.remove(s);
                    } else {
                        sp.put(s, new JSONObject().put("n", n).put("bis", jetzt + SPAETER_MS));
                    }
                } else if ("ja".equals(antwort)) {
                    Automation a;
                    try {
                        a = RegelBau.baue(v, regeln.orte());
                    } catch (RegelBau.Fehler f) {
                        return fehler(f.grund);
                    }
                    String id = regeln.speichere(null, a);
                    if (id == null) return fehler("nicht_gespeichert");
                    schreibe(z);
                    return new JSONObject().put("ok", true).put("automation", id).put("modus", Automation.MODE_PROBE);
                } else {
                    return fehler("antwort_ungueltig");
                }
                schreibe(z);
                return new JSONObject().put("ok", true);
            }
            case "probelauf": {
                String id = b.optString("automation");
                Automation a = regeln.alle().get(id);
                if (a == null || a.getGewohnheit() == null) return fehler("automation_unbekannt");
                if (!"passt".equals(antwort) && !"nicht".equals(antwort)) return fehler("antwort_ungueltig");
                JSONObject probe = z.optJSONObject("probe");
                JSONObject p = probe == null ? null : probe.optJSONObject(id);
                if (p != null) p.put("beantwortet", true);
                schreibe(z);
                zaehle(id, a, "passt".equals(antwort) ? "richtig" : "nicht");
                return new JSONObject().put("ok", true).put("richtig", a.getGewohnheit().optInt("richtig"));
            }
            case "freigabe":
            case "aufraeumen": {
                String id = b.optString("automation");
                Automation a = regeln.alle().get(id);
                if (a == null || a.getGewohnheit() == null) return fehler("automation_unbekannt");
                JSONObject gw = a.getGewohnheit();
                if ("ja".equals(antwort) && "freigabe".equals(art)) {
                    gw.put("freigegeben", jetzt);
                    regeln.speichere(id, a);
                    if (!regeln.modus(id, Automation.MODE_AUTOMATIC)) return fehler("nicht_gespeichert");
                    return new JSONObject().put("ok", true).put("modus", Automation.MODE_AUTOMATIC);
                }
                if ("loeschen".equals(antwort)) {
                    sperre(gw.optString("schluessel"));
                    regeln.loesche(id);
                    return new JSONObject().put("ok", true).put("geloescht", true);
                }
                if ("weiter".equals(antwort) || "behalten".equals(antwort)) {
                    gw.put("richtig", 0).put("nicht", 0);   // weiter nur melden; nach 3 neuen richtigen wieder fragen
                    regeln.speichere(id, a);
                    return new JSONObject().put("ok", true).put("modus", Automation.MODE_PROBE);
                }
                return fehler("antwort_ungueltig");
            }
            default:
                return fehler("art_ungueltig");
        }
    }

    // ---------------------------------------------------------------- Probelauf (E4)

    /** Eine Probelauf-Regel hat ausgelöst: merken (für die Rückfrage), höchstens einmal je 10 min. */
    public synchronized boolean ausgeloest(String id) throws Exception {
        JSONObject z = zustand();
        JSONObject probe = z.optJSONObject("probe");
        if (probe == null) z.put("probe", probe = new JSONObject());
        JSONObject p = probe.optJSONObject(id);
        long jetzt = g.u.jetzt();
        if (p != null && jetzt - p.optLong("ausgeloest") < PROBE_SPERRE_MS) return false;
        probe.put(id, new JSONObject().put("ausgeloest", jetzt).put("beantwortet", false));
        schreibe(z);
        return true;
    }

    /**
     * Eine eigene Handlung des Nutzers, die zu einem offenen Probelauf passt (gleiche Aktion, binnen 30 min nach
     * dem Auslösen), zählt als richtig – der Nutzer hat es selbst so gemacht.
     */
    public synchronized void handlung(String aktion, long t) throws Exception {
        JSONObject z = zustand();
        JSONObject probe = z.optJSONObject("probe");
        if (probe == null) return;
        boolean geaendert = false;
        for (java.util.Iterator<String> it = probe.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject p = probe.getJSONObject(id);
            Automation a = regeln.alle().get(id);
            if (a == null || !a.isProbe() || a.getGewohnheit() == null || p.optBoolean("beantwortet")) continue;
            long seit = t - p.optLong("ausgeloest");
            if (seit < 0 || seit > GewohnheitsErkenner.TOLERANZ_MIN * 60_000L) continue;
            if (!aktion.equals(a.getGewohnheit().optString("aktion"))) continue;
            p.put("beantwortet", true).put("durchHandlung", true);
            geaendert = true;
            zaehle(id, a, "richtig");
        }
        if (geaendert) schreibe(z);
    }

    private void zaehle(String id, Automation a, String feld) throws Exception {
        JSONObject gw = a.getGewohnheit();
        gw.put(feld, gw.optInt(feld) + 1);
        regeln.speichere(id, a);
    }

    // ---------------------------------------------------------------- Zustand

    void sperre(String schluessel) throws Exception {
        if (schluessel == null || schluessel.isEmpty()) return;
        JSONArray s = g.gesperrt();
        for (int i = 0; i < s.length(); i++) if (schluessel.equals(s.optString(i))) return;
        s.put(schluessel);
        File f = g.gesperrtDatei;
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.toString().getBytes(StandardCharsets.UTF_8));
    }

    JSONObject zustand() {
        try {
            if (zustandDatei.isFile()) return new JSONObject(new String(Files.readAllBytes(zustandDatei.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    void schreibe(JSONObject z) throws Exception {
        if (zustandDatei.getParentFile() != null) zustandDatei.getParentFile().mkdirs();
        Files.write(zustandDatei.toPath(), z.toString().getBytes(StandardCharsets.UTF_8));
    }

    String tag(long t) {
        ZoneId zone = g.u.zone();
        return Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString();
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }
}
