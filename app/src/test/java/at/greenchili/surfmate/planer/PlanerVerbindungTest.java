package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** „Mit Routenplaner verbinden“ (Planer-Vertrag §6) gegen einen nachgebauten Planer, ohne Netz. */
public class PlanerVerbindungTest {

    static final String POLL = "0123456789abcdef0123456789abcdef";
    static final String KEY = "aa" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd";

    /** Nachgebauter Planer: Antworten der Reihe nach, merkt Aufrufe. */
    static final class Planer implements PlanerVerbindung.Netz {
        final ArrayDeque<PlanerVerbindung.Antwort> antworten = new ArrayDeque<>();
        final List<String> aufrufe = new ArrayList<>();
        final List<JSONObject> bodies = new ArrayList<>();

        Planer dann(int code, String json) throws Exception {
            antworten.add(new PlanerVerbindung.Antwort(code, new JSONObject(json)));
            return this;
        }

        public PlanerVerbindung.Antwort post(String op, JSONObject body) {
            aufrufe.add(op);
            bodies.add(body);
            return antworten.isEmpty() ? null : antworten.poll();
        }
    }

    static final class Ablage implements PlanerVerbindung.Speicher {
        String schluessel, name;
        long seit;
        boolean klappt = true;

        public boolean speichere(String s, String n, String id, long t) {
            if (!klappt) return false;
            schluessel = s; name = n; seit = t;
            return true;
        }
        public boolean loesche() { schluessel = null; return true; }
        public String[] verbunden() { return schluessel == null ? null : new String[] {name, String.valueOf(seit)}; }
    }

    static Planer start() throws Exception {
        return new Planer().dann(200, "{\"ok\":true,\"code\":\"ABCD1234\",\"code_anzeige\":\"ABCD-1234\","
                + "\"link\":\"/ev/verbinden?c=ABCD1234\",\"ablauf_s\":600,\"pollId\":\"" + POLL + "\"}");
    }

    @Test
    public void connectShowsCodeKeepsPollIdAndStoresKeyOnce() throws Exception {
        Planer p = start().dann(202, "{\"status\":\"wartet\"}")
                .dann(200, "{\"status\":\"erlaubt\",\"schluessel\":\"" + KEY + "\",\"geraet_id\":\"g1\",\"name\":\"Surf\",\"typ\":\"auto\"}");
        Ablage a = new Ablage();
        PlanerVerbindung v = new PlanerVerbindung(p, a);
        JSONObject r = v.starten("Surf", 0);
        assertTrue(r.getBoolean("ok"));
        assertEquals("ABCD-1234", r.getString("code_anzeige"));
        assertEquals("https://byd.serveone.at/ev/verbinden?c=ABCD1234", r.getString("link"));
        assertEquals(600_000L, r.getLong("gueltigBis"));
        assertFalse("pollId bleibt im Daemon", r.toString().contains(POLL));
        assertEquals("auto", p.bodies.get(0).getString("typ"));
        assertEquals("Surf", p.bodies.get(0).getString("geraet"));

        JSONObject s = v.status(1000);
        assertEquals("wartet", s.getString("status"));
        assertFalse(s.toString().contains(POLL));

        v.takt(1000);                         // zu früh
        assertEquals(1, p.aufrufe.size());
        v.takt(PlanerVerbindung.ABFRAGE_MS);  // 202 wartet
        assertEquals("geraet/abholen", p.aufrufe.get(1));
        assertEquals("pollId nur im Body", POLL, p.bodies.get(1).getString("pollId"));
        v.takt(2 * PlanerVerbindung.ABFRAGE_MS);   // erlaubt
        assertEquals(KEY, a.schluessel);
        JSONObject fertig = v.status(2 * PlanerVerbindung.ABFRAGE_MS + 1);
        assertEquals("verbunden", fertig.getString("status"));
        assertEquals("Surf", fertig.getString("name"));
        assertFalse("Schlüssel nie in der Antwort", fertig.toString().contains(KEY));
        v.takt(10 * PlanerVerbindung.ABFRAGE_MS);
        assertEquals("nach Erfolg keine Abfrage mehr", 3, p.aufrufe.size());
    }

    @Test
    public void declinedExpiredAndRateLimited() throws Exception {
        PlanerVerbindung v = new PlanerVerbindung(start().dann(403, "{\"status\":\"abgelehnt\"}"), new Ablage());
        v.starten("Surf", 0);
        v.takt(PlanerVerbindung.ABFRAGE_MS);
        assertEquals("abgelehnt", v.status(PlanerVerbindung.ABFRAGE_MS + 1).getString("status"));

        PlanerVerbindung w = new PlanerVerbindung(start().dann(410, "{\"status\":\"abgelaufen\"}"), new Ablage());
        w.starten("Surf", 0);
        w.takt(PlanerVerbindung.ABFRAGE_MS);
        assertEquals("abgelaufen", w.status(PlanerVerbindung.ABFRAGE_MS + 1).getString("status"));

        Planer still = start();
        PlanerVerbindung x = new PlanerVerbindung(still, new Ablage());
        x.starten("Surf", 0);
        assertEquals("abgelaufen", x.status(600_000L).getString("status"));
        x.takt(700_000L);
        assertEquals("nach Ablauf kein Abholen", 1, still.aufrufe.size());

        JSONObject r = new PlanerVerbindung(new Planer().dann(429, "{}"), new Ablage()).starten("Surf", 0);
        assertFalse(r.getBoolean("ok"));
        assertEquals("ratenlimit", r.getString("grund"));
        JSONObject n = new PlanerVerbindung(new Planer(), new Ablage()).starten("Surf", 0);
        assertEquals("planer", n.getString("grund"));
    }

    @Test
    public void badKeyOrStorageFailureIsNeverConnected() throws Exception {
        Ablage a = new Ablage();
        PlanerVerbindung v = new PlanerVerbindung(start().dann(200, "{\"status\":\"erlaubt\",\"schluessel\":\"kurz\"}"), a);
        v.starten("Surf", 0);
        v.takt(PlanerVerbindung.ABFRAGE_MS);
        assertNull(a.schluessel);
        assertFalse("verbunden".equals(v.status(PlanerVerbindung.ABFRAGE_MS + 1).getString("status")));

        Ablage kaputt = new Ablage();
        kaputt.klappt = false;
        PlanerVerbindung w = new PlanerVerbindung(start().dann(200,
                "{\"status\":\"erlaubt\",\"schluessel\":\"" + KEY + "\",\"name\":\"Surf\"}"), kaputt);
        w.starten("Surf", 0);
        w.takt(PlanerVerbindung.ABFRAGE_MS);
        assertEquals("nicht_gespeichert", w.status(PlanerVerbindung.ABFRAGE_MS + 1).getString("status"));
    }

    @Test
    public void disconnectDeletesLocally() throws Exception {
        Ablage a = new Ablage();
        a.schluessel = KEY;
        a.name = "Surf";
        PlanerVerbindung v = new PlanerVerbindung(new Planer(), a);
        assertEquals("verbunden", v.status(0).getString("status"));
        JSONObject t = v.trennen();
        assertTrue(t.getBoolean("ok"));
        assertEquals("am_planer_entfernen", t.getString("hinweis"));
        assertNull(a.schluessel);
        assertEquals("getrennt", v.status(0).getString("status"));
    }

    @Test
    public void deviceKeyPreferredMemberKeyAsFallback() throws Exception {
        PlanerSchluessel.Entschluessler e = g -> g.startsWith("ENC:") ? g.substring(4) : g;
        String[] k = PlanerSchluessel.kopf(new JSONObject().put("geraeteSchluessel", "ENC:" + KEY)
                .put("memberToken", "0123456789abcdef0123456789abcdef"), e);
        assertEquals("X-Geraete-Token", k[0]);
        assertEquals(KEY, k[1]);
        String[] m = PlanerSchluessel.kopf(new JSONObject().put("memberToken", "0123456789abcdef0123456789abcdef"), e);
        assertEquals("X-Member-Token", m[0]);
        String[] kaputt = PlanerSchluessel.kopf(new JSONObject().put("geraeteSchluessel", "ENC:xx")
                .put("memberToken", "0123456789abcdef0123456789abcdef"), g -> "");
        assertEquals("unlesbarer Geräte-Schlüssel → Rückfall", "X-Member-Token", kaputt[0]);
        assertNull(PlanerSchluessel.kopf(new JSONObject(), e));
        assertNull(PlanerSchluessel.kopf(null, e));
    }

    @Test
    public void couplingTokenCannotConnectOrDisconnect() throws Exception {
        assertTrue(ExternGuard.blocks("POST", "/api/planer/verbinden"));
        assertTrue(ExternGuard.blocks("GET", "/api/planer/verbindung"));
        assertTrue(ExternGuard.blocks("POST", "/api/planer/trennen"));
        assertFalse("Kennung bleibt lesbar", ExternGuard.blocks("GET", "/api/planer/identitaet"));
        String eh = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        for (String p : new String[] {"/api/planer/verbinden", "/api/planer/verbindung", "/api/planer/trennen"}) {
            int i = eh.indexOf("pathOnly.equals(\"" + p + "\")");
            assertTrue(p, i > 0);
            assertTrue(p, eh.substring(i, i + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        }
        String ps = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/planer/PlanerSchluessel.java"));
        assertTrue("verschlüsselt gespeichert", ps.contains("CredentialCipher.encrypt(schluessel)"));
        assertTrue("nie Klartext ablegen", ps.contains("enc.equals(schluessel)) return false;"));
        String rw = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java"));
        assertTrue("F1 nutzt den Geräte-Schlüssel", rw.contains("PlanerSchluessel.kopf()"));
    }

    @Test
    public void netzfehlerSichtbarMitBackoffUndEhrlichemGrund() throws Exception {
        // Planer antwortet beim Abholen nicht (null = Netz weg)
        Planer stumm = start();
        PlanerVerbindung v = new PlanerVerbindung(stumm, new Ablage());
        v.starten("Surf", 0);
        long t = PlanerVerbindung.ABFRAGE_MS;
        v.takt(t);                                          // 1. Fehler
        JSONObject w = v.status(t + 1);
        assertEquals("wartet", w.getString("status"));
        assertEquals(1, w.getInt("netzFehler"));
        v.takt(t + PlanerVerbindung.ABFRAGE_MS);            // Backoff 4 s erreicht -> 2. Fehler
        int nachZwei = stumm.aufrufe.size();
        v.takt(t + PlanerVerbindung.ABFRAGE_MS + 1_000);    // innerhalb Backoff (8 s): kein Aufruf
        assertEquals(nachZwei, stumm.aufrufe.size());
        assertTrue("weiter abholen, solange gültig", nachZwei >= 3);
        JSONObject ende = v.status(600_001L);
        assertEquals("abgelaufen", ende.getString("status"));
        assertEquals("netz", ende.getString("grund"));

        // Planer meldet nur „wartet“ bis zum Ablauf
        Planer geduldig = start();
        for (int k = 0; k < 200; k++) geduldig.dann(202, "{\"status\":\"wartet\"}");
        PlanerVerbindung x = new PlanerVerbindung(geduldig, new Ablage());
        x.starten("Surf", 0);
        for (long z = PlanerVerbindung.ABFRAGE_MS; z < 600_000L; z += PlanerVerbindung.ABFRAGE_MS) x.takt(z);
        x.takt(600_000L);
        assertEquals("freigabe_kam_nicht", x.status(600_001L).getString("grund"));

        // Planer meldet selbst „abgelaufen“ (410)
        PlanerVerbindung y = new PlanerVerbindung(start().dann(410, "{\"status\":\"abgelaufen\"}"), new Ablage());
        y.starten("Surf", 0);
        y.takt(PlanerVerbindung.ABFRAGE_MS);
        assertEquals("planer", y.status(PlanerVerbindung.ABFRAGE_MS + 1).getString("grund"));
    }
}
