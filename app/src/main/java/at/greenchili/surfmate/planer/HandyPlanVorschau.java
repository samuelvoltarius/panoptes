package at.greenchili.surfmate.planer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 0.1.38.6 Handy plant ÜBER Panoptes (Weg B, PLANER_VERTRAG.md §4): Handy-Aktion {@code plan_vorschau {ziel,
 * zwischenziele?}} → Panoptes ruft {@code POST plan} mit dem eigenen Geräte-Schlüssel (Typ auto), der letzten
 * Auto-Position, Fahrzeug und Lade-Vorlieben des Autos, {@code "ausgabe":"kompakt"} und {@code "geometrie":"kurz"} und
 * reicht das Ergebnis {@code plan-ergebnis/1} samt {@code plan_id} UNVERÄNDERT weiter (nur die Geometrie darf wegfallen,
 * wenn das Handy sie nicht will). Die Position verlässt das Auto nur Richtung Planer, wie bei {@code route}; das Handy
 * schickt nie einen Standort. Kein neues Recht (gleiches Recht „route“), kein neuer Datenfluss.
 *
 * <p>Der Planer rechnet bis über eine Minute: {@link #starten} antwortet sofort mit {@code status:"plant"}, {@link #stand}
 * liefert danach {@code bereit} (mit {@code plan_id} und {@code ergebnis}) oder {@code fehler} (mit Grund). „Ins Auto“ ist
 * {@code route {plan_id}} ({@link HandyRoute}): das Auto übernimmt genau diesen Plan statt neu zu planen. Die
 * {@code plan_id}s merkt sich Panoptes nur im Arbeitsspeicher, höchstens {@value #GUELTIG_MS} ms, je Kopplung.
 */
public final class HandyPlanVorschau {

    /** Die Plan-Kennung gilt 2 h (PLANER_VERTRAG §2a); danach 410 und ehrlich „neu planen“. */
    public static final long GUELTIG_MS = 2L * 60 * 60 * 1000;
    /** Ratenbremse des Planers: höchstens 10 Planungen je Minute (nginx, je IP), hier schon davor. */
    static final int MAX_JE_MINUTE = 10;
    static final int MAX_PLAENE = 20;
    static final int MAX_ZWISCHEN = 5;
    /** Eine Antwort darf nie größer werden als das, was die Brücke sicher trägt. */
    static final int MAX_ANTWORT_ZEICHEN = 200_000;

    private final HandyRoute.Umgebung env;
    private final Executor ausfuehrer;
    /** je Kopplung der letzte Stand. */
    private final Map<String, JSONObject> staende = new HashMap<>();
    /** plan_id → {kid, Zeit, Ziel-Titel, km}; nur RAM. */
    private final Map<String, Object[]> plaene = new HashMap<>();
    private final Deque<Long> zeiten = new ArrayDeque<>();
    private boolean laeuft;

    HandyPlanVorschau(HandyRoute.Umgebung env, Executor ausfuehrer) {
        this.env = env;
        this.ausfuehrer = ausfuehrer;
    }

    /** Prüft die Anfrage und startet das Planen im Hintergrund. Antwort sofort. */
    public JSONObject starten(String kid, JSONObject req, long jetzt) throws Exception {
        JSONObject ziel = req.optJSONObject("ziel");
        if (!HandyRoute.ort(ziel)) return fehler("werte");
        JSONArray zz = req.optJSONArray("zwischenziele");
        if (zz != null && zz.length() > MAX_ZWISCHEN) return fehler("zu_viele_zwischenziele");
        JSONArray via = new JSONArray();
        for (int i = 0; zz != null && i < zz.length(); i++) {
            if (!HandyRoute.ort(zz.optJSONObject(i))) return fehler("werte");
            via.put(HandyRoute.ortKurz(zz.getJSONObject(i)));
        }
        String[] token = env.token();
        String auto = env.autoId();
        if (token == null || auto == null || auto.isEmpty()) return fehler("kein_planer");
        double[] pos = env.position();
        if (pos == null) return fehler("keine_position");
        JSONObject zielKurz = HandyRoute.ortKurz(ziel);
        // geometrie: "keine" (Standard, auch false) | "kurz" | "voll" (auch true) – mehr als die kurze Linie liefert der Planer nicht
        Object g = req.opt("geometrie");
        boolean mitGeometrie = Boolean.TRUE.equals(g) || "kurz".equals(g) || "voll".equals(g);
        synchronized (this) {
            if (laeuft) return fehler("laeuft_schon");
            while (!zeiten.isEmpty() && jetzt - zeiten.peekFirst() >= 60_000L) zeiten.pollFirst();
            if (zeiten.size() >= MAX_JE_MINUTE) return fehler("zu_oft");
            zeiten.addLast(jetzt);
            laeuft = true;
            staende.put(kid, new JSONObject().put("status", "plant").put("zeit", jetzt));
        }
        ausfuehrer.execute(() -> {
            JSONObject e;
            try {
                e = ausfuehren(kid, token, auto, pos, zielKurz, via, mitGeometrie, jetzt);
            } catch (Throwable t) {
                e = stand("fehler", "intern");
            }
            synchronized (HandyPlanVorschau.this) {
                try { e.put("zeit", jetzt); } catch (Exception ignored) { }
                staende.put(kid, e);
                laeuft = false;
            }
        });
        return new JSONObject().put("ok", true).put("status", "plant");
    }

    /** Planen (blockiert; im Hintergrund) und das Ergebnis unverändert als Stand ablegen. */
    JSONObject ausfuehren(String kid, String[] token, String auto, double[] pos, JSONObject ziel, JSONArray via,
                          boolean mitGeometrie, long jetzt) throws Exception {
        JSONObject b = new JSONObject()
                .put("start", new JSONArray().put(pos[0]).put(pos[1]))
                .put("ziel", new JSONArray().put(ziel.getDouble("lat")).put(ziel.getDouble("lon")))
                .put("auto", auto);
        if (via.length() > 0) b.put("via", via);
        double soc = env.soc();
        if (!Double.isNaN(soc)) b.put("soc_start", Math.round(soc));
        // Fahrzeug und Lade-Vorlieben des Autos einsetzen, dann der Vertrag: kompakt, kurze Geometrie, Quelle Auto
        JSONObject body = new JSONObject(env.planBody(b.toString()));
        body.put("ausgabe", "kompakt").put("geometrie", "kurz");
        if (!body.has("vorlieben_quelle")) body.put("vorlieben_quelle", "auto");
        Object[] p = env.http("POST", "plan", token, body.toString(), 150_000);
        if (p == null) return stand("fehler", "netz");
        int code = (Integer) p[0];
        JSONObject plan = (JSONObject) p[1];
        if (code == 401) return stand("fehler", "kein_planer");
        if (code == 403) return stand("fehler", "planer_recht_fehlt");
        if (code == 429) return stand("fehler", "zu_oft");
        if (code == 502 || code == 503) return stand("fehler", "planer_fehler");
        if (code != 200 || plan == null || !plan.optBoolean("ok", true)) return stand("fehler", "planer");
        if (!"plan-ergebnis/1".equals(plan.optString("vertrag", ""))) return stand("fehler", "planer_alt");
        String planId = plan.isNull("plan_id") ? null : plan.optString("plan_id", null);
        if (planId != null && !planId.matches("^p_[A-Za-z0-9_-]{3,80}$")) planId = null;
        // „Das Handy braucht die Linie nicht“: nur dann fällt die Geometrie weg, sonst bleibt alles wie vom Planer
        if (!mitGeometrie) plan.remove("geometrie");
        if (plan.toString().length() > MAX_ANTWORT_ZEICHEN) return stand("fehler", "zu_gross");
        merke(kid, planId, plan, jetzt);
        JSONObject r = stand("bereit", null);
        r.put("plan_id", planId == null ? JSONObject.NULL : planId);
        r.put("ergebnis", plan);
        return r;
    }

    private synchronized void merke(String kid, String planId, JSONObject plan, long jetzt) {
        aufraeumen(jetzt);
        if (planId == null) return;
        while (plaene.size() >= MAX_PLAENE) {
            String aeltester = null;
            long t = Long.MAX_VALUE;
            for (Map.Entry<String, Object[]> e : plaene.entrySet()) {
                if ((Long) e.getValue()[1] < t) { t = (Long) e.getValue()[1]; aeltester = e.getKey(); }
            }
            plaene.remove(aeltester);
        }
        JSONObject ziel = plan.optJSONObject("ziel");
        plaene.put(planId, new Object[]{kid, jetzt, ziel == null ? "" : ziel.optString("name", ""), plan.opt("km")});
    }

    private void aufraeumen(long jetzt) {
        for (Iterator<Map.Entry<String, Object[]>> it = plaene.entrySet().iterator(); it.hasNext(); ) {
            if (jetzt - (Long) it.next().getValue()[1] >= GUELTIG_MS) it.remove();
        }
    }

    /**
     * Darf diese Kopplung diesen Plan ins Auto schicken? null = ja, sonst Grund: {@code plan_abgelaufen} (älter als 2 h
     * oder nach einem Neustart) bzw. {@code plan_unbekannt} (nie gesehen, fremd). Nie der Plan einer anderen Kopplung.
     */
    public synchronized String pruefe(String kid, String planId, long jetzt) {
        Object[] p = plaene.get(planId);
        if (p == null) return "plan_abgelaufen";   // nach Neustart oder aufgeräumt: ehrlich „neu planen“
        if (!kid.equals(p[0])) return "plan_unbekannt";
        if (jetzt - (Long) p[1] >= GUELTIG_MS) {
            plaene.remove(planId);
            return "plan_abgelaufen";
        }
        return null;
    }

    /** Titel und km des gemerkten Plans (für den Stand nach „ins Auto“) oder null. */
    synchronized Object[] kurz(String planId) {
        Object[] p = plaene.get(planId);
        return p == null ? null : new Object[]{p[2], p[3]};
    }

    synchronized void vergessen(String planId) {
        plaene.remove(planId);
    }

    /** Letzter Stand dieser Kopplung: {ok, status keiner|plant|bereit|fehler, grund?, plan_id?, plan?}. */
    public synchronized JSONObject stand(String kid) throws Exception {
        JSONObject s = staende.get(kid);
        JSONObject r = new JSONObject().put("ok", true);
        if (s == null) return r.put("status", "keiner");
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String k = it.next();
            r.put(k, s.get(k));
        }
        return r;
    }

    private static JSONObject stand(String status, String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
            if (grund != null) o.put("grund", grund).put("code", grund);
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile HandyPlanVorschau instanz;

    public static HandyPlanVorschau get() {
        HandyPlanVorschau r = instanz;
        if (r == null) {
            synchronized (HandyPlanVorschau.class) {
                if (instanz == null) {
                    instanz = new HandyPlanVorschau(new HandyRoute.Echt(), cmd -> {
                        Thread t = new Thread(cmd, "HandyPlanVorschau");
                        t.setDaemon(true);
                        t.start();
                    });
                }
                r = instanz;
            }
        }
        return r;
    }
}
