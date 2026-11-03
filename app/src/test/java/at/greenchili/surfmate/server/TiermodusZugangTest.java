package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiermodus (03.11.2026): Einschalten nur am Autobildschirm oder in der Handy-App mit Recht {@code klima} – nie über
 * eine Kopplung (Cockpit, HA), nie aus einer Automation, nie über Telegram.
 */
public class TiermodusZugangTest {

    final List<String> aufrufe = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    HandyApiHandler.Umgebung vorher;
    java.util.function.BooleanSupplier direktVorher;

    @Before
    public void auf() {
        vorher = HandyApiHandler.umgebung;
        direktVorher = TiermodusApiHandler.direktAmAuto;
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        HandyApiHandler.kopplungen = kopplungen;
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                aufrufe.add("router:" + cmd.name());
                return VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.SDK, "", 1);
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, 2, jetzt - 1_000, true, true, jetzt);
            }
            public int echo(String aktion) { return BydDataCollector.ECHO_UNBEKANNT; }
            public void telegram(String key, Object... args) { telegram.add(key); }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
            public JSONObject tiermodus(String was, String quelle) {
                aufrufe.add(was + ":" + quelle);
                return json("{\"ok\":true}");
            }
            public JSONObject tiermodusStand() {
                return json("{\"aktiv\":true}");
            }
        };
    }

    static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
        TiermodusApiHandler.direktAmAuto = direktVorher;
    }

    private JSONObject handy(String kid, String was) throws Exception {
        return HandyApiHandler.aktion(new JSONObject().put("aktion", "tiermodus").put("was", was), kid);
    }

    @Test
    public void handyMitRechtKlimaDarf() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = handy(k, "an");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("an:handy:Pixel", aufrufe.get(0));
        assertTrue(r.getJSONObject("stand").getBoolean("aktiv"));
        assertTrue("Telegram erfährt es", telegram.contains("handy.tiermodus_an"));
        assertTrue(handy(k, "gesehen").getBoolean("ok"));
        assertTrue(handy(k, "aus").getBoolean("ok"));
        assertEquals("werte", handy(k, "laut").getString("grund"));
        assertTrue(HandyApiHandler.aktion(new JSONObject().put("aktion", "tiermodus_stand"), k).getBoolean("aktiv"));
        for (String a : aufrufe) assertFalse("kein eigener Fahrzeugbefehl im Handler: " + a, a.startsWith("router:"));
    }

    @Test
    public void handyOhneHinweisBekommtGrundUndKannBestaetigen() throws Exception {
        HandyApiHandler.Umgebung basis = HandyApiHandler.umgebung;
        final String[] version = {null};
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand c) { return basis.ausfuehren(c); }
            public CloudPowerGate.Decision gate(String b) { return basis.gate(b); }
            public int echo(String a) { return basis.echo(a); }
            public void telegram(String key, Object... args) { telegram.add(key); }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
            public JSONObject tiermodus(String was, String quelle) {
                return json("{\"ok\":false,\"fehler\":\"hinweis_fehlt\"}");
            }
            public JSONObject tiermodusHinweis(String v) { version[0] = v; return json("{\"ok\":true}"); }
            public JSONObject tiermodusStand() { return json("{\"aktiv\":false,\"hinweisBestaetigt\":false}"); }
        };
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        JSONObject r = handy(k, "an");
        assertFalse(r.getBoolean("ok"));
        assertEquals("hinweis_fehlt", r.getString("grund"));
        assertFalse(r.getJSONObject("stand").getBoolean("hinweisBestaetigt"));
        assertTrue(telegram.isEmpty());
        JSONObject b = HandyApiHandler.aktion(new JSONObject().put("aktion", "tiermodus").put("was", "hinweis")
                .put("version", at.greenchili.surfmate.camping.Tiermodus.HINWEIS_VERSION), k);
        assertTrue(b.getBoolean("ok"));
        assertEquals(at.greenchili.surfmate.camping.Tiermodus.HINWEIS_VERSION, version[0]);
    }

    @Test
    public void ohneRechtKlimaNicht() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(k, Collections.singletonList(Kopplungen.RECHT_ZUSPERREN));
        assertEquals("nicht_erlaubt", handy(k, "an").getString("grund"));
        assertEquals("nicht_erlaubt", HandyApiHandler.aktion(new JSONObject().put("aktion", "tiermodus_stand"), k)
                .getString("grund"));
        String cockpit = kopplungen.anlegen("PC", "cockpit", jetzt);
        assertEquals("nicht_erlaubt", handy(cockpit, "an").getString("grund"));
        assertTrue(aufrufe.isEmpty());
    }

    @Test
    public void gesehenZaehltNichtZumRatenlimit() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        for (int i = 0; i < 20; i++) assertTrue(handy(k, "gesehen").getBoolean("ok"));
    }

    @Test
    public void webStartNurDirektAmAuto() throws Exception {
        TiermodusApiHandler.direktAmAuto = () -> false;
        JSONObject r = TiermodusApiHandler.schalten(new JSONObject().put("an", true));
        assertEquals("nur_am_auto", r.getString("fehler"));
        assertEquals("an", TiermodusApiHandler.schalten(new JSONObject().put("an", "ja")).getString("fehler"));
    }

    @Test
    public void kopplungenUndAutomationenGesperrt() throws Exception {
        for (String art : new String[]{Kopplungen.ART_HANDY, "cockpit", Kopplungen.ART_DIENST}) {
            assertTrue(art, ExternGuard.blocks("POST", "/api/tiermodus", art));
            assertTrue(art, ExternGuard.blocks("POST", "/api/tiermodus/gesehen", art));
            assertTrue(art, ExternGuard.blocks("GET", "/api/tiermodus", art));
        }
        Method m = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        m.setAccessible(true);
        assertFalse((Boolean) m.invoke(null, "/api/tiermodus"));
        assertFalse((Boolean) m.invoke(null, "/api/tiermodus/gesehen"));
        assertFalse((Boolean) m.invoke(null, "/api/handy/v1/aktion"));
    }

    @Test
    public void telegramKannNichtEinschalten() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/telegram/TierCommandHandler.java"));
        assertFalse(h.contains("\"an\""));
        assertFalse(h.contains("Tiermodus.ein"));
        assertTrue(at.greenchili.surfmate.daemon.telegram.TierCommandHandler.istGesehen("ok"));
        assertFalse(at.greenchili.surfmate.daemon.telegram.TierCommandHandler.istGesehen("an"));
        String ipc = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java"));
        int i = ipc.indexOf("case \"TIERMODUS\"");
        assertTrue(i > 0);
        String block = ipc.substring(i, ipc.indexOf("case \"STATUS\"", i));
        assertFalse("IPC aus Telegram schaltet nie ein", block.contains("Tiermodus.ein"));
    }

    @Test
    public void seiteImMenueUndMitRoute() throws Exception {
        String shell = Quelltext.lies(Paths.get("src/main/assets/web/shared/app-shell.js"));
        assertTrue(shell.contains("{ href: 'tiermodus.html'"));
        String h = Quelltext.lies(Paths.get("src/main/assets/web/local/tiermodus.html"));
        assertTrue("Chromium 74: kein optional chaining", !h.contains("?.") && !h.contains("??"));
        for (String l : new String[]{"de:", "en:", "fr:", "it:", "nl:"}) assertTrue(l, h.contains(l + " { okay:"));
        assertTrue("immer sichtbar", h.contains("data-i18n=\"tiermodus.garantie\""));
        assertTrue(h.contains("/api/tiermodus/hinweis"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject w = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + l + ".json"))).getJSONObject("tiermodus");
            for (String key : new String[]{"hinweis_text", "hinweis_knopf", "garantie"}) assertTrue(l + ":" + key, w.has(key));
        }
    }
}
