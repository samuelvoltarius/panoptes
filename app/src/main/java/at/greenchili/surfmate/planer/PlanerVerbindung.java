package at.greenchili.surfmate.planer;

import org.json.JSONObject;

/**
 * Panoptes mit dem Routenplaner-Konto verbinden, ohne Abtippen (Planer-Vertrag API_FUER_SURFMATE.md §6,
 * Koordination 21.10.2026).
 *
 * <ol>
 *   <li>{@link #starten}: {@code POST geraet/start {geraet, typ:"auto"}} → Code + Link zum Anzeigen
 *       (Web + Display). Die {@code pollId} bleibt nur im Speicher dieses Daemons.</li>
 *   <li>{@link #takt}: alle {@link #ABFRAGE_MS} {@code POST geraet/abholen {pollId}} (nie in der URL).</li>
 *   <li>Bei „erlaubt“ wird der Geräte-Schlüssel (64 hex) verschlüsselt gespeichert
 *       ({@code planner.geraeteSchluessel}, CredentialCipher) und nie ausgegeben oder geloggt.</li>
 *   <li>{@link #trennen}: Schlüssel lokal löschen. Am Planer widerrufen kann nur das Mitglied
 *       („Meine Geräte“ auf /ev/), ein Geräte-Schlüssel darf keine Geräte verwalten.</li>
 * </ol>
 * Rein bis auf {@link #get()}; Netz, Speicher und Uhr kommen von außen.
 */
public final class PlanerVerbindung {

    static final long ABFRAGE_MS = 4_000L;
    /** Obergrenze für den Backoff bei Netzfehlern beim Abholen. */
    static final long ABFRAGE_MAX_MS = 30_000L;

    /** Planer-Aufruf: liefert {code, body} oder null bei Netzfehler. */
    public interface Netz {
        Antwort post(String op, JSONObject body);
    }

    public static final class Antwort {
        public final int code;
        public final JSONObject body;
        public Antwort(int code, JSONObject body) { this.code = code; this.body = body == null ? new JSONObject() : body; }
    }

    /** Ablage des Schlüssels (Config planner.*, verschlüsselt). */
    public interface Speicher {
        /** Schlüssel + Name/Id/Seit speichern; false = nicht gespeichert. */
        boolean speichere(String schluessel, String name, String geraeteId, long seit);
        boolean loesche();
        /** {name, seit} der bestehenden Verbindung oder null. */
        String[] verbunden();
    }

    private final Netz netz;
    private final Speicher speicher;
    private String pollId;
    private String codeAnzeige, link, letzterStatus;
    /** Warum es „abgelaufen“ ist: freigabe_kam_nicht | netz | planer (Koordination 21.10.). */
    private String letzterGrund;
    private long gueltigBis, naechsteAbfrage;
    /** Netzfehler beim Abholen in Folge (für Backoff und Anzeige während des Wartens). */
    private int netzFehler;
    private long letzterNetzFehler;

    public PlanerVerbindung(Netz netz, Speicher speicher) {
        this.netz = netz;
        this.speicher = speicher;
    }

    /** POST /api/planer/verbinden. */
    public synchronized JSONObject starten(String geraetName, long now) {
        JSONObject res = new JSONObject();
        try {
            JSONObject b = new JSONObject();
            b.put("geraet", name(geraetName));
            b.put("typ", "auto");
            Antwort a = netz.post("geraet/start", b);
            if (a == null || a.code == 0) return fehler(res, 502, "planer");
            if (a.code == 429) return fehler(res, 429, "ratenlimit");
            String pid = a.body.optString("pollId", "");
            if (a.code != 200 || !pid.matches("^[0-9a-f]{32}$")) return fehler(res, 502, "planer");
            pollId = pid;
            codeAnzeige = a.body.optString("code_anzeige", a.body.optString("code", ""));
            link = absolut(a.body.optString("link", ""));
            gueltigBis = now + Math.max(60, Math.min(1800, a.body.optLong("ablauf_s", 600))) * 1000L;
            naechsteAbfrage = now + ABFRAGE_MS;
            letzterStatus = "wartet";
            letzterGrund = null;
            netzFehler = 0;
            letzterNetzFehler = 0L;
            res.put("ok", true);
            res.put("code_anzeige", codeAnzeige);
            res.put("link", link);
            res.put("gueltigBis", gueltigBis);
        } catch (Exception e) {
            return fehler(res, 502, "planer");
        }
        return res;
    }

    /** Hintergrund-Takt: abholen, solange eine Anfrage wartet. */
    public synchronized void takt(long now) {
        if (pollId == null || now < naechsteAbfrage) return;
        if (now >= gueltigBis) {
            abgelaufen(netzFehler > 0 ? "netz" : "freigabe_kam_nicht");
            return;
        }
        naechsteAbfrage = now + ABFRAGE_MS;
        Antwort a;
        try {
            a = netz.post("geraet/abholen", new JSONObject().put("pollId", pollId));
        } catch (Exception e) {
            a = null;
        }
        if (a == null || a.code == 0 || a.code >= 500 || a.code == 429) {
            // Netz weg bzw. Planer gestört: sichtbar machen und mit Backoff weiter abholen, solange gültig.
            netzFehler++;
            letzterNetzFehler = now;
            naechsteAbfrage = now + Math.min(ABFRAGE_MAX_MS, ABFRAGE_MS << Math.min(netzFehler - 1, 4));
            return;
        }
        netzFehler = 0;
        if (a.code == 202) return;                                   // wartet auf Freigabe
        if (a.code == 403) { ende("abgelehnt"); return; }
        if (a.code == 410) { abgelaufen("planer"); return; }
        if (a.code != 200) return;
        String s = a.body.optString("schluessel", "");
        if (!"erlaubt".equals(a.body.optString("status")) || !s.matches("^[0-9a-f]{64}$")) {
            abgelaufen("planer");
            return;
        }
        // Erlaubt: der Schlüssel kommt genau einmal – sofort speichern, nie loggen.
        boolean ok = speicher.speichere(s, a.body.optString("name", ""), a.body.optString("geraet_id", ""), now);
        if (ok) autoIdSicherstellen();
        ende(ok ? "verbunden" : "nicht_gespeichert");
    }

    /**
     * Bugfix 0.1.9: planner.autoId entstand nur bei Bedarf (/api/planer/identitaet, HA). Ohne sie fragte das
     * Fahrtenbuch-Postfach nie ab. Beim Verbinden (und beim Start) deshalb sofort erzeugen.
     */
    static void autoIdSicherstellen() {
        try {
            at.greenchili.surfmate.server.EinstellungenApiHandler.autoId();
        } catch (Throwable ignored) {
        }
    }

    /** GET /api/planer/verbindung (nie mit Schlüssel). */
    public synchronized JSONObject status(long now) {
        JSONObject o = new JSONObject();
        try {
            if (pollId != null && now < gueltigBis) {
                o.put("status", "wartet");
                o.put("code_anzeige", codeAnzeige);
                o.put("link", link);
                o.put("gueltigBis", gueltigBis);
                // Netzprobleme beim Abholen sichtbar machen (Abholen läuft mit Backoff weiter).
                o.put("netzFehler", netzFehler);
                if (netzFehler > 0) o.put("letzterNetzFehler", letzterNetzFehler);
                return o;
            }
            if (pollId != null) abgelaufen(netzFehler > 0 ? "netz" : "freigabe_kam_nicht");
            String[] v = speicher.verbunden();
            if (v != null) {
                o.put("status", "verbunden");
                o.put("name", v[0]);
                o.put("seit", Long.parseLong(v[1]));
                o.put("typ", "auto");
                return o;
            }
            o.put("status", letzterStatus == null || "verbunden".equals(letzterStatus) ? "getrennt" : letzterStatus);
            if ("abgelaufen".equals(letzterStatus) && letzterGrund != null) o.put("grund", letzterGrund);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** POST /api/planer/trennen: lokal löschen (Widerruf am Planer nur über „Meine Geräte“). */
    public synchronized JSONObject trennen() {
        pollId = null;
        letzterStatus = null;
        letzterGrund = null;
        boolean ok = speicher.loesche();
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("hinweis", "am_planer_entfernen");
        } catch (Exception ignored) {
        }
        return o;
    }

    private void ende(String status) {
        pollId = null;
        letzterStatus = status;
    }

    /** status „abgelaufen“ (wie bisher, damit ältere Web-Stände passen) plus grund. */
    private void abgelaufen(String grund) {
        ende("abgelaufen");
        letzterGrund = grund;
    }

    private static JSONObject fehler(JSONObject res, int code, String grund) {
        try {
            res.put("ok", false);
            res.put("grund", grund);
            res.put("http", code);
        } catch (Exception ignored) {
        }
        return res;
    }

    static String absolut(String link) {
        if (link == null || link.isEmpty()) return "";
        if (link.startsWith("https://")) return link;
        return "https://byd.serveone.at" + (link.startsWith("/") ? link : "/" + link);
    }

    static String name(String n) {
        String s = n == null ? "" : n.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (s.isEmpty()) s = "Panoptes";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    // ---------------------------------------------------------------- Laufzeit (Daemon)

    private static volatile PlanerVerbindung instanz;

    public static PlanerVerbindung get() {
        PlanerVerbindung p = instanz;
        if (p == null) {
            synchronized (PlanerVerbindung.class) {
                if (instanz == null) {
                    instanz = new PlanerVerbindung(PlanerSchluessel::post, new PlanerSchluessel.UcmSpeicher());
                    java.util.concurrent.ScheduledExecutorService ex =
                            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "PlanerVerbindung");
                                t.setDaemon(true);
                                return t;
                            });
                    PlanerVerbindung pv = instanz;
                    ex.scheduleWithFixedDelay(() -> {
                        try { pv.takt(System.currentTimeMillis()); } catch (Throwable ignored) { }
                    }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
                }
                p = instanz;
            }
        }
        return p;
    }
}
