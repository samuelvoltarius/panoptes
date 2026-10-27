package at.greenchili.surfmate.ladeplan;

import at.greenchili.surfmate.charging.TariffProfile;
import at.greenchili.surfmate.charging.Zeittarif;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.Iterator;

/**
 * Ladeplan zu Hause (LADEPLAN_KONZEPT, 0.1.20): Einstellungen ({@code ladeplan.json}), Zustand
 * ({@code ladeplan_zustand.json}, übersteht Neustart), Takt und Ausführung über den Wallbox-Weg. Reines Java; Auto,
 * Orte, Tarif und Wallbox kommen über {@link Umgebung} und {@link Wallbox} herein (Tests: Attrappen).
 *
 * <p>Sicherheit (§5): schaltet nur am Heimort, in P, angesteckt, mit einmaliger Freigabe (sonst Probelauf), nur
 * Start/Stopp/Strom, Echo-Pflicht (ein Fehlschlag wird im nächsten Takt wiederholt, dann Pause bis zum nächsten
 * Einstecken), respektiert die Brücken-Sperre, Hand geht vor Plan, „Plan aus“ stoppt einmal und schaltet danach nie
 * mehr. Das Auto wird nie geweckt und am Auto nichts geschaltet.
 */
public final class LadeplanDienst {

    public static final int VERSION = 1;
    /** Nach einem eigenen Befehl so lange keine „Hand“-Erkennung (Wallbox/Auto brauchen etwas). */
    static final long HAND_RUHE_MS = 3 * 60_000L;
    /** Zustand der Wallbox älter als das ist unbrauchbar (Takt 60 s). */
    static final int MAX_FEHLVERSUCHE = 2;
    static final int PROTOKOLL_MAX = 30;

    /** Auto, Orte, Tarif. */
    public interface Umgebung {
        long jetzt();

        ZoneId zone();

        /** Telemetrie mit {@code soc}, {@code lat}, {@code lon} (oder null). */
        JSONObject telemetrie();

        /** Ortsliste (ORTE_VERTRAG) oder null. */
        JSONArray orte();

        /** Tarif nach Kennung oder null. */
        TariffProfile tarif(String id);

        /** Fester Strompreis (Einrichtung „Strompreis“), ≤ 0 = unbekannt. */
        double festerPreis();

        /** Nutzbare Akkukapazität in kWh, ≤ 0 = unbekannt. */
        double kapazitaetKwh();
    }

    /** Wallbox-Weg (Laufzeit: WallboxDienst). */
    public interface Wallbox {
        /** {@code /api/wallbox/status}-Antwort. */
        JSONObject status() throws JSONException;

        /** null = steht in P. */
        String fahrsperre();

        /** {@code {status, json}} wie WallboxDienst.steuernPlan. */
        JSONObject steuern(String ladepunkt, String aktion, Object wert) throws JSONException;

        long letzteHandSteuerung(String ladepunkt) throws JSONException;
    }

    private final File einstellungenDatei;
    private final File zustandDatei;
    private final Umgebung u;
    private final Wallbox w;

    public LadeplanDienst(File einstellungenDatei, File zustandDatei, Umgebung u, Wallbox w) {
        this.einstellungenDatei = einstellungenDatei;
        this.zustandDatei = zustandDatei;
        this.u = u;
        this.w = w;
    }

    // ------------------------------------------------------------------ Einstellungen

    public static JSONObject standard() throws JSONException {
        return new JSONObject().put("v", VERSION)
                .put("modus", "aus")
                .put("abfahrt", new JSONObject()
                        .put("werktag", new JSONObject().put("an", true).put("zeit", "07:00").put("ziel", 80))
                        .put("wochenende", new JSONObject().put("an", false).put("zeit", "09:00").put("ziel", 80)))
                .put("mindestladung", 30)
                .put("ziel", 80)
                .put("sofortZiel", 100)
                .put("fenster", new JSONArray().put(new JSONObject().put("tage", "alle").put("von", "11:00").put("bis", "15:00")))
                .put("hoechststromA", 0)
                .put("phasen", 3)
                .put("autoAcKw", 11)
                .put("wirkungsgrad", 0.9)
                .put("ladepunkt", JSONObject.NULL)
                .put("freigabe", new JSONObject().put("an", false).put("zeit", JSONObject.NULL).put("ladepunkt", JSONObject.NULL));
    }

    public synchronized JSONObject einstellungen() throws JSONException {
        JSONObject s = standard();
        JSONObject d = lies(einstellungenDatei);
        if (d != null) for (Iterator<String> it = d.keys(); it.hasNext(); ) {
            String k = it.next();
            if (s.has(k)) s.put(k, d.get(k));
        }
        return s;
    }

    /**
     * Änderung übernehmen (geprüft). {@code extern} = gekoppeltes Gerät: darf Plan, Modus, Abfahrt usw. ändern, aber nie
     * die Freigabe oder den Ladepunkt (§7). Liefert null bei Erfolg, sonst den Fehlergrund.
     */
    public synchronized String aendere(JSONObject neu, boolean extern) throws JSONException {
        if (neu == null) return "einstellung_ungueltig";
        if (extern && (neu.has("freigabe") || neu.has("ladepunkt"))) return "nur_am_auto";
        JSONObject alt = einstellungen();
        JSONObject s = new JSONObject(alt.toString());
        for (Iterator<String> it = neu.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!s.has(k) || "v".equals(k)) continue;
            s.put(k, neu.get(k));
        }
        String f = pruefe(s);
        if (f != null) return f;
        JSONObject fa = s.getJSONObject("freigabe");
        JSONObject altF = alt.getJSONObject("freigabe");
        if (fa.optBoolean("an") && !altF.optBoolean("an")) {
            if (s.isNull("ladepunkt")) return "ladepunkt_fehlt";
            fa.put("zeit", u.jetzt()).put("ladepunkt", s.getString("ladepunkt"));
        } else if (!fa.optBoolean("an")) {
            fa.put("zeit", JSONObject.NULL).put("ladepunkt", JSONObject.NULL);
        } else {
            fa.put("zeit", altF.opt("zeit")).put("ladepunkt", altF.opt("ladepunkt"));
        }
        // Ladepunkt gewechselt → Freigabe gilt nicht mehr (sie war für genau diesen Ladepunkt)
        if (fa.optBoolean("an") && !String.valueOf(s.opt("ladepunkt")).equals(String.valueOf(fa.opt("ladepunkt")))) {
            fa.put("an", false).put("zeit", JSONObject.NULL).put("ladepunkt", JSONObject.NULL);
        }
        // Not-Aus: Plan aus, während der Plan gerade lädt → einmal Stopp, danach schaltet er nie mehr
        if ("aus".equals(s.getString("modus")) && !"aus".equals(alt.getString("modus"))) notAus(alt);
        schreibe(einstellungenDatei, s);
        return null;
    }

    static String pruefe(JSONObject s) throws JSONException {
        String m = s.optString("modus");
        if (!"aus".equals(m) && !"guenstig".equals(m) && !"zeitfenster".equals(m)) return "modus_ungueltig";
        JSONObject a = s.optJSONObject("abfahrt");
        if (a == null) return "abfahrt_ungueltig";
        for (String t : new String[]{"werktag", "wochenende"}) {
            JSONObject e = a.optJSONObject(t);
            if (e == null || Planer.minute(e.optString("zeit")) < 0) return "abfahrt_ungueltig";
            int z = e.optInt("ziel", -1);
            if (z < 20 || z > 100) return "ziel_ungueltig";
        }
        if (!zahl(s, "mindestladung", 0, 80) || !zahl(s, "ziel", 20, 100) || !zahl(s, "sofortZiel", 20, 100)) return "ziel_ungueltig";
        if (!zahl(s, "hoechststromA", 0, 63)) return "strom_ungueltig";
        int ph = s.optInt("phasen", -1);
        if (ph != 1 && ph != 3) return "phasen_ungueltig";
        double ac = s.optDouble("autoAcKw", -1);
        if (Double.isNaN(ac) || ac < 1 || ac > 22) return "leistung_ungueltig";
        double wg = s.optDouble("wirkungsgrad", -1);
        if (Double.isNaN(wg) || wg < 0.5 || wg > 1) return "wirkungsgrad_ungueltig";
        JSONArray f = s.optJSONArray("fenster");
        if (f == null || f.length() > Zeittarif.MAX_BAENDER) return "fenster_ungueltig";
        for (int i = 0; i < f.length(); i++) {
            JSONObject e = f.optJSONObject(i);
            if (e == null || Planer.minute(e.optString("von")) < 0
                    || (Planer.minute(e.optString("bis")) < 0 && !"24:00".equals(e.optString("bis")))) return "fenster_ungueltig";
            String t = e.optString("tage", "alle");
            if (!"alle".equals(t) && !"werktag".equals(t) && !"wochenende".equals(t)) return "fenster_ungueltig";
        }
        if (!s.isNull("ladepunkt") && !s.optString("ladepunkt").matches("[a-z]{2,8}:[A-Za-z0-9_.:-]{1,64}")) return "ladepunkt_ungueltig";
        if (s.optJSONObject("freigabe") == null) return "einstellung_ungueltig";
        return null;
    }

    private static boolean zahl(JSONObject s, String k, int min, int max) {
        Object v = s.opt(k);
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return d == Math.rint(d) && d >= min && d <= max;
    }

    // ------------------------------------------------------------------ Zustand

    synchronized JSONObject zustand() throws JSONException {
        JSONObject z = lies(zustandDatei);
        if (z == null) z = new JSONObject();
        if (!z.has("protokoll")) z.put("protokoll", new JSONArray());
        return z;
    }

    /** „Jetzt sofort laden“ bis zum Sofort-Ziel bzw. Abstecken (§2.1). */
    public synchronized void sofort(boolean an) throws JSONException {
        JSONObject z = zustand();
        z.put("sofort", an);
        if (an) z.remove("pausiert");
        schreibe(zustandDatei, z);
    }

    /** Pause aufheben („Plan wieder aufnehmen“). */
    public synchronized void weiter() throws JSONException {
        JSONObject z = zustand();
        z.remove("pausiert");
        z.put("fehlversuche", 0);
        z.put("handAb", u.jetzt());
        z.remove("letzterBefehl");
        schreibe(zustandDatei, z);
    }

    private void notAus(JSONObject alt) throws JSONException {
        JSONObject z = zustand();
        z.put("sofort", false);
        JSONObject lb = z.optJSONObject("letzterBefehl");
        JSONObject fa = alt.optJSONObject("freigabe");
        if (lb != null && "sofort".equals(lb.optString("modus")) && fa != null && fa.optBoolean("an")) {
            String lp = fa.optString("ladepunkt");
            JSONObject r = w.steuern(lp, "modus", "aus");
            boolean ok = r.optJSONObject("json") != null && r.getJSONObject("json").optBoolean("uebernommen");
            notiere(z, "stopp", "plan_aus", ok);
            z.put("letzterBefehl", new JSONObject().put("modus", "aus").put("zeit", u.jetzt()));
        }
        schreibe(zustandDatei, z);
    }

    // ------------------------------------------------------------------ Takt

    /** Ein Takt (60 s): planen und – falls erlaubt und nötig – schalten. Liefert den Status wie GET /api/ladeplan. */
    public synchronized JSONObject takt() throws JSONException {
        return lauf(true);
    }

    /** Status ohne zu schalten (GET). */
    public synchronized JSONObject status() throws JSONException {
        return lauf(false);
    }

    private JSONObject lauf(boolean schalten) throws JSONException {
        long jetzt = u.jetzt();
        ZoneId zone = u.zone();
        JSONObject s = einstellungen();
        JSONObject z = zustand();
        JSONObject tel = u.telemetrie();
        double soc = tel == null ? Double.NaN : tel.optDouble("soc", Double.NaN);
        double lat = tel == null ? Double.NaN : tel.optDouble("lat", Double.NaN);
        double lon = tel == null ? Double.NaN : tel.optDouble("lon", Double.NaN);

        // Heimort + Tarif
        JSONArray orte = u.orte();
        JSONObject heim = null;
        boolean zuhause = false;
        for (int i = 0; orte != null && i < orte.length(); i++) {
            JSONObject o = orte.optJSONObject(i);
            JSONObject eig = o == null ? null : o.optJSONObject("eigenschaften");
            if (eig == null || !eig.optBoolean("zuhause")) continue;
            if (heim == null) heim = o;
            if (!Double.isNaN(lat) && !Double.isNaN(lon)
                    && at.greenchili.surfmate.orte.Orte.meter(lat, lon, o.optDouble("lat"), o.optDouble("lon")) <= o.optDouble("r")) {
                heim = o;
                zuhause = true;
            }
        }
        TariffProfile tarif = null;
        if (heim != null && heim.optJSONObject("verweise") != null) {
            String tid = heim.getJSONObject("verweise").optString("tarif", "");
            if (!tid.isEmpty()) tarif = u.tarif(tid);
        }
        Zeittarif zt = tarif == null ? null : tarif.getZeittarif();
        double fest = tarif != null && tarif.getAcRate() > 0 ? tarif.getAcRate() : u.festerPreis();
        String land = zt != null ? zt.land() : "";

        // Wallbox
        JSONObject wb;
        try {
            wb = w.status();
        } catch (Exception e) {
            wb = new JSONObject().put("erreichbar", false).put("ladepunkte", new JSONArray());
        }
        String lpId = s.isNull("ladepunkt") ? null : s.optString("ladepunkt");
        JSONObject lp = null;
        JSONArray lps = wb.optJSONArray("ladepunkte");
        for (int i = 0; lps != null && i < lps.length(); i++) {
            JSONObject x = lps.optJSONObject(i);
            if (x == null || !"wallbox".equals(x.optString("art"))) continue;
            if (lpId == null ? lp == null : lpId.equals(x.optString("id"))) lp = x;
        }

        // Leistung
        int strom = s.optInt("hoechststromA", 0);
        JSONObject fs = lp == null || lp.optJSONObject("faehigkeiten") == null ? null : lp.getJSONObject("faehigkeiten").optJSONObject("strom");
        int max = fs == null ? 16 : fs.optInt("max", 16);
        int min = fs == null ? 6 : fs.optInt("min", 6);
        int a = strom > 0 ? Math.max(min, Math.min(strom, max)) : max;
        double kw = Planer.leistungKw(a, s.optInt("phasen", 3), s.optDouble("autoAcKw", 11));

        // Plan
        Planer.Eingabe e = new Planer.Eingabe();
        e.jetzt = jetzt;
        e.zone = zone;
        e.socProzent = soc;
        e.kapazitaetKwh = u.kapazitaetKwh();
        e.wirkungsgrad = s.optDouble("wirkungsgrad", 0.9);
        e.leistungKw = kw;
        e.modus = s.optString("modus");
        e.sofort = z.optBoolean("sofort");
        e.sofortZiel = s.optInt("sofortZiel", 100);
        e.mindestladung = s.optInt("mindestladung", 30);
        e.ziel = s.optInt("ziel", 80);
        long[] ab = Planer.naechsteAbfahrt(s.optJSONObject("abfahrt"), jetzt, zone, land);
        if (ab != null) {
            e.abfahrt = ab[0];
            e.abfahrtZiel = (int) ab[1];
        }
        if (zt != null) {
            final Zeittarif fz = zt;
            e.preis = t -> fz.preis(t, zone);
            e.festerPreis = false;
        } else {
            final double p = Math.max(0, fest);
            e.preis = t -> p;
            e.festerPreis = true;
        }
        e.fenster = Planer.fenster(s.optJSONArray("fenster"), zone, land);
        Planer.Plan plan = Planer.plane(e);

        // Einstecken/Abstecken erkennen (setzt Pause, Fehlversuche, Schaltungen und „sofort“ zurück)
        boolean angesteckt = lp != null && lp.optBoolean("verbunden", false);
        boolean warAngesteckt = z.optBoolean("angesteckt", false);
        if (schalten && angesteckt != warAngesteckt) {
            z.put("angesteckt", angesteckt);
            if (angesteckt) {
                z.put("eingestecktSeit", jetzt).put("schaltungen", 0).put("fehlversuche", 0).put("handAb", jetzt);
                z.remove("pausiert");
                z.remove("letzterBefehl");
            } else {
                z.put("sofort", false);
                z.remove("letzterBefehl");
            }
        }

        boolean ist = lp != null && "sofort".equals(lp.optString("modus"));
        boolean soll = plan.ladeJetzt;
        JSONObject fa = s.getJSONObject("freigabe");
        boolean frei = fa.optBoolean("an") && lp != null && lp.optString("id").equals(fa.optString("ladepunkt"));

        String phase;
        if ("aus".equals(s.optString("modus")) && !z.optBoolean("sofort")) phase = "aus";
        else if (lp == null) phase = wb.optBoolean("erreichbar", false) || wb.optBoolean("eingerichtet", false) ? "kein_ladepunkt" : "wallbox_fehlt";
        else if (!zuhause) phase = "nicht_zuhause";
        else if (lp.has("fehler")) phase = "wallbox_nicht_erreichbar";
        else if (!angesteckt) phase = "nicht_angesteckt";
        else if (w.fahrsperre() != null) phase = "fahrsperre";
        else if (z.optJSONObject("pausiert") != null) phase = "pausiert";
        else if ("bruecke_gesperrt".equals(lp.optString("hinweis"))) phase = "bruecke_gesperrt";
        else if (!frei) phase = "probelauf";
        else phase = soll ? "laedt" : plan.bedarfKwh <= 0 ? "fertig" : "wartet";

        // Hand geht vor Plan
        if (schalten && "laedt".equals(phase) || schalten && "wartet".equals(phase) || schalten && "fertig".equals(phase)) {
            long hand = w.letzteHandSteuerung(lp.optString("id"));
            JSONObject lb = z.optJSONObject("letzterBefehl");
            boolean abweichung = lb != null && jetzt - lb.optLong("zeit") > HAND_RUHE_MS
                    && ist != "sofort".equals(lb.optString("modus")) && plan.bedarfKwh > 0;
            if (hand > z.optLong("handAb", 0) || abweichung) {
                z.put("pausiert", new JSONObject().put("grund", "hand").put("seit", jetzt));
                phase = "pausiert";
            }
        }

        if (schalten && ("laedt".equals(phase) || "wartet".equals(phase) || "fertig".equals(phase)) && soll != ist) {
            int sch = z.optInt("schaltungen", 0);
            if (soll && sch >= Planer.MAX_SCHALTUNGEN) {
                phase = "zu_viele_schaltungen";
            } else {
                String id = lp.optString("id");
                boolean ok = true;
                String grund = null;
                if (soll && strom > 0 && lp.optInt("stromA", -1) != a) {
                    JSONObject r = w.steuern(id, "strom", a);
                    ok = echo(r);
                    if (!ok) grund = grund(r);
                }
                if (ok) {
                    JSONObject r = w.steuern(id, "modus", soll ? "sofort" : "aus");
                    ok = echo(r);
                    if (!ok) grund = grund(r);
                }
                notiere(z, soll ? "start" : "stopp", ok ? plan.grund : "fehler:" + grund, ok);
                if (ok) {
                    z.put("letzterBefehl", new JSONObject().put("modus", soll ? "sofort" : "aus").put("zeit", jetzt));
                    z.put("schaltungen", sch + 1).put("fehlversuche", 0);
                    ist = soll;
                } else {
                    int fv = z.optInt("fehlversuche", 0) + 1;
                    z.put("fehlversuche", fv);
                    if (fv >= MAX_FEHLVERSUCHE) {
                        z.put("pausiert", new JSONObject().put("grund", "fehlschlag").put("seit", jetzt).put("fehler", grund));
                        phase = "pausiert";
                    }
                }
            }
        } else if (schalten && "probelauf".equals(phase) && soll != ist) {
            // Probelauf: nur zeigen, was der Plan täte (einmal je Wechsel)
            String wuerde = soll ? "start" : "stopp";
            if (!wuerde.equals(z.optString("probelaufZuletzt"))) {
                notiere(z, wuerde, "probelauf", true);
                z.put("probelaufZuletzt", wuerde);
            }
        }
        // Ziel erreicht bei „sofort“ → zurück zum Plan
        if (schalten && z.optBoolean("sofort") && plan.bedarfKwh <= 0) z.put("sofort", false);
        if (schalten) schreibe(zustandDatei, z);

        JSONObject o = new JSONObject().put("ok", true).put("version", VERSION).put("stand", jetzt);
        o.put("einstellungen", s);
        JSONObject zu = new JSONObject().put("phase", phase).put("zuhause", zuhause).put("angesteckt", angesteckt)
                .put("laedtJetzt", ist).put("wuerdeLaden", soll).put("freigegeben", frei)
                .put("sofort", z.optBoolean("sofort"))
                .put("pausiert", z.optJSONObject("pausiert") == null ? JSONObject.NULL : z.getJSONObject("pausiert"))
                .put("soc", Double.isNaN(soc) ? JSONObject.NULL : (Object) soc)
                .put("ladepunkt", lp == null ? JSONObject.NULL : lp.optString("id"))
                .put("ladepunktTitel", lp == null ? JSONObject.NULL : lp.optString("titel"))
                .put("weg", lp == null || lp.optJSONObject("weg") == null ? JSONObject.NULL : lp.getJSONObject("weg"))
                .put("stromA", a).put("leistungKw", Planer.r2(kw))
                .put("abfahrt", ab == null ? JSONObject.NULL : new JSONObject().put("zeit", ab[0]).put("ziel", ab[1]))
                .put("schaltungen", z.optInt("schaltungen", 0));
        o.put("zustand", zu);
        o.put("vorschau", plan.json());
        JSONObject ti = new JSONObject().put("art", zt != null ? "zeittarif" : "fest")
                .put("preis", zt != null ? zt.grundpreis() : fest > 0 ? (Object) fest : JSONObject.NULL)
                .put("tarifId", tarif == null ? JSONObject.NULL : tarif.getId())
                .put("tarifName", tarif == null ? JSONObject.NULL : tarif.getLabel())
                .put("heimort", heim == null ? JSONObject.NULL : heim.optString("name"))
                .put("waehrung", tarif == null || tarif.getCurrency().isEmpty() ? JSONObject.NULL : tarif.getCurrency());
        if (zt != null && tarif.toJson().optJSONObject("zeittarif") != null) ti.put("zeittarif", tarif.toJson().getJSONObject("zeittarif"));
        o.put("tarif", ti);
        o.put("letzte", z.optJSONArray("protokoll") == null ? new JSONArray() : z.getJSONArray("protokoll"));
        return o;
    }

    private static boolean echo(JSONObject r) {
        JSONObject j = r == null ? null : r.optJSONObject("json");
        return j != null && r.optInt("status") == 200 && j.optBoolean("uebernommen", false);
    }

    private static String grund(JSONObject r) {
        JSONObject j = r == null ? null : r.optJSONObject("json");
        if (j == null) return "unbekannt";
        String g = j.optString("grund", "");
        return g.isEmpty() ? "kein_echo" : g;
    }

    private void notiere(JSONObject z, String aktion, String grund, boolean ok) throws JSONException {
        JSONArray p = z.optJSONArray("protokoll");
        if (p == null) p = new JSONArray();
        JSONArray neu = new JSONArray();
        neu.put(new JSONObject().put("zeit", u.jetzt()).put("aktion", aktion).put("grund", grund).put("ok", ok));
        for (int i = 0; i < p.length() && neu.length() < PROTOKOLL_MAX; i++) neu.put(p.get(i));
        z.put("protokoll", neu);
    }

    // ------------------------------------------------------------------ Dateien

    private static JSONObject lies(File f) {
        try {
            if (!f.exists()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void schreibe(File f, JSONObject o) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }
}
