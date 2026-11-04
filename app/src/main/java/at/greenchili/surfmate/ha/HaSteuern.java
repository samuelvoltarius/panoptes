package at.greenchili.surfmate.ha;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HA-Geräte Stufe 2 (0.1.38.8, HA_VERTRAG v1.1 Teil D): Tor und Haustür-Schloss über die HA-Integration steuern.
 * Kein HA-Token im Auto: Panoptes schickt {@code event: befehl {id, entity, dienst, daten}} nur an den SSE-Stream der
 * meldenden HA-Kopplung, die Integration führt aus und meldet {@code POST /api/ha/v1/geraete/echo}. Erfolg gibt es nur
 * mit passendem Echo; ohne Echo nach {@link #ABLAUF_MS} heißt es „nicht bestätigt“.
 *
 * <p>Regeln: Gerät freigegeben (je Gerät Standard AUS), Kopplung mit Recht {@code geraete_steuern} (Standard AUS),
 * Ratenlimit 1 je {@link #JE_GERAET_MS} je Gerät und {@link #MAX_JE_MINUTE} gesamt, Auftrags-ids einmalig (letzte
 * {@link #MAX_IDS}). Jede Aktion geht an den {@link Melder} (Befehls-Log + Meldung, ohne Gerätenamen, ohne Raum).
 * Alarmanlagen und Sirenen gibt es hier gar nicht: nur die Dienste aus {@link #dienst}.
 */
public final class HaSteuern {

    public static final long ABLAUF_MS = 30_000L;
    static final long JE_GERAET_MS = 3_000L;
    static final int MAX_JE_MINUTE = 20;
    static final int MAX_IDS = 200;
    static final Pattern ID = Pattern.compile("b-[0-9a-f]{16}");

    public static final String OFFEN = "offen", OK = "ok", FEHLER = "fehler", NICHT_BESTAETIGT = "nicht_bestaetigt";

    /** Schickt einen Befehl an die Streams genau dieser Kopplung; false = kein offener Stream. */
    public interface Sender {
        boolean sende(String kid, JSONObject befehl);
    }

    /** Protokoll + Meldung. {@code ergebnis}: gesendet|ok|fehler|nicht_bestaetigt|abgelehnt:<grund>. */
    public interface Melder {
        void aktion(String art, String aktion, String entity, String herkunft, String ergebnis);
    }

    /** Ein Auftrag (nur im Speicher). */
    static final class Auftrag {
        final String id, kid, entity, art, aktion, herkunft;
        final long zeit;
        String status = OFFEN, zustand, grund;

        Auftrag(String id, String kid, String entity, String art, String aktion, String herkunft, long zeit) {
            this.id = id;
            this.kid = kid;
            this.entity = entity;
            this.art = art;
            this.aktion = aktion;
            this.herkunft = herkunft;
            this.zeit = zeit;
        }
    }

    private final Sender sender;
    private final Melder melder;
    private final SecureRandom zufall = new SecureRandom();
    private final Map<String, Auftrag> auftraege = new LinkedHashMap<String, Auftrag>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Auftrag> e) {
            return size() > MAX_IDS;
        }
    };
    private final Map<String, Long> letzteJeGeraet = new HashMap<>();
    private final ArrayDeque<Long> letzteMinute = new ArrayDeque<>();

    public HaSteuern(Sender sender, Melder melder) {
        this.sender = sender;
        this.melder = melder;
    }

    /** HA-Dienst für Art + Aktion oder null. Nur Tor und Schloss; nie Alarm, Sirene oder andere Dienste. */
    public static String dienst(String art, String aktion) {
        if ("tor".equals(art)) {
            if ("oeffnen".equals(aktion)) return "cover.open_cover";
            if ("schliessen".equals(aktion)) return "cover.close_cover";
        }
        if ("schloss".equals(art)) {
            if ("aufsperren".equals(aktion)) return "lock.unlock";
            if ("zusperren".equals(aktion)) return "lock.lock";
        }
        return null;
    }

    /** Öffnen und Aufsperren machen das Haus auf; Schließen und Zusperren nicht. */
    public static boolean oeffnet(String aktion) {
        return "oeffnen".equals(aktion) || "aufsperren".equals(aktion);
    }

    /**
     * Neuer Auftrag. {@code bestand} = ha_geraete.json, {@code kid} = Kopplung, die die Geräte gemeldet hat,
     * {@code steuerRecht} = sie hat {@code geraete_steuern}. Antwort {@code {ok,id}} oder {@code {ok:false,grund}}.
     */
    public synchronized JSONObject auftrag(JSONObject bestand, String kid, boolean steuerRecht, String entity,
                                           String aktion, String herkunft, long jetzt) {
        aufraeumen(jetzt);
        JSONObject g = HaGeraete.geraet(bestand, entity);
        String art = g == null ? "" : g.optString("art");
        String grund = null;
        String dienst = dienst(art, aktion);
        if (g == null) grund = "unbekannt";
        else if (dienst == null) grund = "aktion";
        else if (!HaGeraete.freigegeben(bestand, entity)) grund = "nicht_freigegeben";
        else if (kid == null) grund = "keine_kopplung";
        else if (!steuerRecht) grund = "recht_fehlt";
        else if (zuOft(entity, jetzt)) grund = "zu_oft";
        if (grund != null) {
            if (g != null) melder.aktion(art, aktion, entity, herkunft, "abgelehnt:" + grund);
            return antwort(false, null, grund);
        }
        String id = neueId();
        JSONObject befehl = new JSONObject();
        try {
            befehl.put("id", id).put("entity", entity).put("dienst", dienst).put("daten", new JSONObject());
        } catch (Exception e) {
            return antwort(false, null, "fehler");
        }
        if (!sender.sende(kid, befehl)) {
            melder.aktion(art, aktion, entity, herkunft, "abgelehnt:nicht_verbunden");
            return antwort(false, null, "nicht_verbunden");
        }
        merkeRate(entity, jetzt);
        auftraege.put(id, new Auftrag(id, kid, entity, art, aktion, herkunft, jetzt));
        melder.aktion(art, aktion, entity, herkunft, "gesendet");
        return antwort(true, id, null);
    }

    /**
     * {@code POST /api/ha/v1/geraete/echo {id, ok, zustand, grund}} der Kopplung {@code kid}. Nur ein offener Auftrag
     * genau dieser Kopplung zählt; spätes, fremdes oder doppeltes Echo wird abgelehnt.
     */
    public synchronized JSONObject echo(String kid, JSONObject echo, long jetzt) {
        aufraeumen(jetzt);
        String id = echo == null ? "" : echo.optString("id", "");
        Auftrag a = ID.matcher(id).matches() ? auftraege.get(id) : null;
        if (a == null || kid == null || !kid.equals(a.kid)) return antwort(false, null, "unbekannt");
        if (!OFFEN.equals(a.status)) return antwort(false, null, "schon_beantwortet");
        boolean ok = echo.optBoolean("ok", false);
        a.status = ok ? OK : FEHLER;
        a.zustand = echo.isNull("zustand") ? null : HaGeraete.text(echo.optString("zustand", ""), 32);
        String grund = echo.optString("grund", "");
        a.grund = grund.matches("[a-z_]{1,32}") ? grund : null;
        melder.aktion(a.art, a.aktion, a.entity, a.herkunft, a.status);
        return antwort(true, null, null);
    }

    /** Stand eines Auftrags für die Oberfläche (ohne Namen) oder null. */
    public synchronized JSONObject status(String id, long jetzt) {
        aufraeumen(jetzt);
        Auftrag a = id == null ? null : auftraege.get(id);
        if (a == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("id", a.id).put("entity", a.entity).put("aktion", a.aktion).put("status", a.status)
                    .put("zustand", a.zustand == null ? JSONObject.NULL : a.zustand)
                    .put("grund", a.grund == null ? JSONObject.NULL : a.grund)
                    .put("herkunft", a.herkunft).put("zeit", a.zeit);
        } catch (Exception ignored) { }
        return o;
    }

    /** Letzter Auftrag (für „Zu“-Knopf nach dem Heimkommen) oder null. */
    public synchronized JSONObject letzter(long jetzt) {
        aufraeumen(jetzt);
        String id = null;
        for (String k : auftraege.keySet()) id = k;
        return id == null ? null : status(id, jetzt);
    }

    /** Offene Aufträge ohne Echo nach {@link #ABLAUF_MS} → „nicht bestätigt“ (mit Meldung). */
    public synchronized void aufraeumen(long jetzt) {
        for (Iterator<Auftrag> it = auftraege.values().iterator(); it.hasNext(); ) {
            Auftrag a = it.next();
            if (OFFEN.equals(a.status) && jetzt - a.zeit >= ABLAUF_MS) {
                a.status = NICHT_BESTAETIGT;
                melder.aktion(a.art, a.aktion, a.entity, a.herkunft, NICHT_BESTAETIGT);
            }
        }
        while (!letzteMinute.isEmpty() && jetzt - letzteMinute.peekFirst() >= 60_000L) letzteMinute.pollFirst();
    }

    /** Widerruf/Recht weg: offene Aufträge dieser Kopplung zählen nicht mehr. */
    public synchronized void vergessen(String kid) {
        for (Iterator<Auftrag> it = auftraege.values().iterator(); it.hasNext(); ) {
            if (it.next().kid.equals(kid)) it.remove();
        }
    }

    private boolean zuOft(String entity, long jetzt) {
        Long l = letzteJeGeraet.get(entity);
        if (l != null && jetzt - l < JE_GERAET_MS && jetzt >= l) return true;
        return letzteMinute.size() >= MAX_JE_MINUTE;
    }

    private void merkeRate(String entity, long jetzt) {
        letzteJeGeraet.put(entity, jetzt);
        if (letzteJeGeraet.size() > MAX_IDS) letzteJeGeraet.clear();
        letzteMinute.addLast(jetzt);
    }

    private String neueId() {
        String id;
        do {
            StringBuilder b = new StringBuilder("b-");
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", zufall.nextInt(256)));
            id = b.toString();
        } while (auftraege.containsKey(id));
        return id;
    }

    private static JSONObject antwort(boolean ok, String id, String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            if (id != null) o.put("id", id);
            if (grund != null) o.put("grund", grund);
        } catch (Exception ignored) { }
        return o;
    }

    /** Kurzer, nicht rückrechenbarer Schlüssel einer Entität fürs Befehls-Log (nie der Name). */
    public static String kurzHash(String entity) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("panoptes-ha:" + entity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("g-");
            for (int i = 0; i < 4; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) {
            return "g-0";
        }
    }
}
