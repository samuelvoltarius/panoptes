package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.PcZugang;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 0.1.39 PC über die Brücke (PLAN_BRUECKE_PC_0139 P1): Pfade für Art {@code pc}, harte Grenzen, Code vom Besitzer-Handy,
 * Einstellungs-Stufe, Katalog-Schalter, Web-Manifest.
 */
public class PcBrueckeTest {

    static boolean an;

    static final class Falsch implements BesitzerApiHandler.Wirkung {
        final List<String> aufrufe = new ArrayList<>();
        final List<String> meldungen = new ArrayList<>();
        @Override public String code(long now, String art, List<String> rechte) {
            aufrufe.add("code:" + art + ":" + rechte);
            return "ABCD2345";
        }
        @Override public String code(long now, String art, List<String> rechte, long dauerMs) {
            aufrufe.add("code:" + art + ":" + rechte + ":" + dauerMs);
            return "ABCD2345";
        }
        @Override public JSONObject brueckeEinmal(long bis) { return new JSONObject(); }
        @Override public boolean pcErlaubt() { return an; }
        @Override public void melde(String text) { meldungen.add(text); }
    }

    private Kopplungen k;
    private Falsch w;
    private String besitzer, zweit, pc;

    @Before
    public void auf() {
        an = true;
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return an; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        PcZugang.leeren();
        Kopplungen.besitzerMelder = null;
        k = new Kopplungen(new BesitzerHandyTest.Mem());
        besitzer = k.anlegen("Projektinhaber", "handy", 1L, true);
        zweit = k.anlegen("Zweit", "handy", 2L, true);
        pc = k.anlegenGebunden("Chrome · Windows", Kopplungen.ART_PC, Collections.singletonList("web"), 3L, 0L);
        BesitzerApiHandler.kopplungen = k;
        w = new Falsch();
        BesitzerApiHandler.wirkung = w;
        BesitzerApiHandler.ratenlimitLeeren();
    }

    @After
    public void ab() {
        BesitzerApiHandler.kopplungen = null;
        BesitzerApiHandler.wirkung = new BesitzerApiHandler.Wirkung() { };
        BesitzerApiHandler.ratenlimitLeeren();
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    // ------------------------------------------------------------------ ExternGuard

    @Test
    public void pcDarfNurBrueckeAbmeldenUndWebSitzung() {
        String art = Kopplungen.ART_PC;
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/bruecke", art));
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/bruecke/aus", art));
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/websitzung", art));
        assertFalse("allgemein lesen", ExternGuard.blocks("GET", "/api/vehicle/state", art));
        assertFalse(ExternGuard.blocks("GET", WebManifest.PFAD, art));
        for (String p : new String[]{"/api/handy/v1/aktion", "/api/handy/v1/geraet", "/api/handy/v1/passkey/start",
                "/api/handy/v1/passkey", "/api/handy/v1/push", "/api/handy/v1/dateien", BesitzerApiHandler.CODE,
                BesitzerApiHandler.ENTFERNEN, BesitzerApiHandler.WEITERGEBEN, BesitzerApiHandler.WAECHTER,
                BesitzerApiHandler.DIENST, "/api/vehicle/lock", "/api/vehicle/flash", "/api/vehicle/findcar",
                "/api/vehicle/warnblinker", "/api/appupdate/fern", "/api/kopplung/code", "/api/bruecke",
                "/api/system/tailscale", "/api/notzugang", "/api/wireguard"}) {
            assertTrue("PC nie: " + p, ExternGuard.blocks("POST", p, art));
        }
        assertTrue("GET Besitzer-Liste nie", ExternGuard.blocks("GET", BesitzerApiHandler.LISTE, art));
        assertTrue("Cockpit bekommt die PC-Pfade nicht", ExternGuard.blocks("POST", "/api/handy/v1/websitzung", "cockpit"));
    }

    @Test
    public void pcWebSitzungLaeuftDurchWebHandyGuard() {
        // Die PC-Web-Sitzung trägt denselben Scope wie die der Handy-App → dieselben Sperren
        for (String p : new String[]{"/api/kopplungen", "/api/kopplung/code", "/api/system/tailscale", "/api/tailscale/up",
                "/api/wireguard", "/api/notzugang", "/api/hotspot", "/api/bruecke", "/api/appupdate/installieren",
                "/api/update/check", "/api/backup/export", "/api/telegram/token", "/api/bydcloud/setup", "/api/handy/v1/aktion",
                "/api/einrichtung"}) {
            assertTrue("Web-Sitzung nie: " + p, WebHandyGuard.blocks("POST", p) || WebHandyGuard.blocks("GET", p));
        }
        assertTrue(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"bruecke\"}"));
        assertTrue(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"tailscale\"}"));
        assertFalse("PC hat nie Recht update", WebHandyGuard.updateErlaubt("POST", "/api/appupdate/installieren",
                k.hatRecht(pc, Kopplungen.RECHT_UPDATE)));
    }

    @Test
    public void pcSchreibtNieFahrzeugAuchNichtUeberDieWebSitzung() throws Exception {
        // E2E 04.11.: Stufe B ließ Zusperren/Kofferraum/Klima/Ton offen – für PC jetzt Erlaubnisliste
        for (String p : new String[]{"/api/vehicle/lock", "/api/vehicle/trunk", "/api/vehicle/climate", "/api/vehicle/window",
                "/api/vehicle/seat", "/api/vehicle/play-audio", "/api/vehicle/speak", "/api/vehicle/system",
                "/api/vehicle/start-charging", "/api/camping", "/api/camping/plan", "/api/ha/v1/aktion", "/api/ladeplan",
                "/api/quick-controls/fire/x", "/api/automations/test/x", "/api/surveillance/enable", "/api/settings/unified",
                "/api/recordings/delete", "/api/navi/unterwegs/ablehnen"}) {
            assertTrue("Web-Sitzung PC: " + p, WebHandyGuard.pcBlocks("POST", p));
            assertTrue("Kopplung PC: " + p, ExternGuard.blocks("POST", p, Kopplungen.ART_PC));
        }
        for (String p : WebHandyGuard.PC_WEB_SCHREIBEN) assertFalse(p, WebHandyGuard.pcBlocks("POST", p));
        assertFalse(WebHandyGuard.pcBlocks("GET", "/api/vehicle/state"));
        assertFalse(ExternGuard.blocks("GET", "/api/vehicle/state", Kopplungen.ART_PC));
        assertFalse(ExternGuard.blocks("POST", "/api/einstellungen", Kopplungen.ART_PC));
        assertTrue(WebHandyGuard.pcBlocks("DELETE", "/api/einstellungen/../vehicle/lock"));
        // HttpServer wendet die Liste auf die Web-Sitzung eines PCs an
        String q = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/server/HttpServer.java")), StandardCharsets.UTF_8);
        assertTrue(q.contains("WebHandyGuard.pcBlocks(method, pathOnly)"));
        assertTrue(q.contains("BefehlsLog.grund(\"pc_nur_lesen\")"));
        // Oberfläche: PC-Sitzung blendet Fahrzeug-Knöpfe aus (app-shell.js, Cookie pn_einbettung=pc)
        String shell = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/assets/web/shared/app-shell.js")), StandardCharsets.UTF_8);
        assertTrue(shell.contains("pn_einbettung=pc") && shell.contains("data-nur-lesen"));
        assertTrue(shell.contains("[data-home-widget=\"quick\"]"));
    }

    // ------------------------------------------------------------------ Besitzer-Handy legt den PC-Code an

    @Test
    public void pcCodeNurBeiSchalterAnMitDauer() throws Exception {
        an = false;
        JSONObject r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true)
                .put("rechte", new JSONArray().put("web")));
        assertEquals(409, r.optInt("_status"));
        assertEquals("pc_aus", r.optString("grund"));
        an = true;
        r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true)
                .put("rechte", new JSONArray().put("web")).put("dauer", "heute"));
        assertTrue(r.toString(), r.optBoolean("ok"));
        assertEquals("heute", r.optString("dauer"));
        assertEquals("code:pc:[web]:" + PcZugang.NUR_HEUTE_MS, w.aufrufe.get(w.aufrufe.size() - 1));
        r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true)
                .put("rechte", new JSONArray().put("web")));
        assertEquals("30tage", r.optString("dauer"));
        assertEquals("code:pc:[web]", w.aufrufe.get(w.aufrufe.size() - 1));
        r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true).put("dauer", "ewig"));
        assertEquals("dauer", r.optString("grund"));
    }

    @Test
    public void pcCodeNieMitBesitzerUpdateAufsperrenSignal() throws Exception {
        for (String recht : new String[]{"besitzer", "update", "launcher", "tacho"}) {
            JSONObject r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true)
                    .put("rechte", new JSONArray().put("web").put(recht)));
            assertEquals(recht, "recht", r.optString("grund"));
        }
        // Fahrzeug-Aktionen vergibt nur das Display (Kopplungen.NUR_DISPLAY_RECHTE, 0.1.38.8)
        for (String recht : Kopplungen.NUR_DISPLAY_RECHTE) {
            JSONObject r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true)
                    .put("rechte", new JSONArray().put("web").put(recht)));
            assertEquals(recht, "recht_nur_display", r.optString("grund"));
        }
        assertEquals("nur Besitzer", "nur_besitzer", post(BesitzerApiHandler.CODE, zweit, new JSONObject().put("art", "pc")
                .put("bestaetigt", true)).optString("grund"));
        assertEquals("PC selbst darf keinen Code", "nur_handy", post(BesitzerApiHandler.CODE, pc, new JSONObject()
                .put("art", "pc").put("bestaetigt", true)).optString("grund"));
    }

    @Test
    public void hoechstensFuenfPcsBeimCode() throws Exception {
        for (int i = 0; i < PcZugang.MAX_PC - 1; i++) {
            assertNotNull(k.anlegenGebunden("PC" + i, Kopplungen.ART_PC, Collections.singletonList("web"), 10L + i, 0L));
        }
        JSONObject r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("art", "pc").put("bestaetigt", true));
        assertEquals("pc_voll", r.optString("grund"));
    }

    @Test
    public void besitzerMeldetPcAuchAltAb() throws Exception {
        JSONObject r = BesitzerApiHandler.antwort("POST", BesitzerApiHandler.ENTFERNEN,
                new JSONObject().put("id", pc).put("bestaetigt", true).toString(), besitzer, 999_000_000_000L);
        assertTrue(r.toString(), r.optBoolean("ok"));
        assertFalse(k.pruefe(pc, 999_000_000_001L - 1));
        assertEquals(1, w.meldungen.size());
    }

    // ------------------------------------------------------------------ Einstellungen

    @Test
    public void pcNieBesitzerStufeAuchInDerWebSitzung() throws Exception {
        assertEquals(Einstellungen.Stufe.KEIN_RECHT, BesitzerMeldung.stufe(false, null, true, pc, k));
        k.rechteSetzen(pc, java.util.Arrays.asList(Kopplungen.RECHT_WEB, Kopplungen.RECHT_EINSTELLUNGEN));
        assertEquals(Einstellungen.Stufe.KOPPLUNG, BesitzerMeldung.stufe(false, null, true, pc, k));
        assertEquals(Einstellungen.Stufe.KOPPLUNG, BesitzerMeldung.stufe(true, pc, false, null, k));
    }

    @Test
    public void schalterStandardAusNieUeberKopplung() {
        Feld an = null, mb = null;
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if (f.key.equals(PcZugang.SCHALTER)) an = f;
            if (f.key.equals(PcZugang.TAGES_MB_SCHLUESSEL)) mb = f;
        }
        assertNotNull(an);
        assertNotNull(mb);
        assertEquals(Boolean.FALSE, an.standard);
        assertTrue(an.nieExtern);
        assertTrue(mb.nieExtern);
        assertEquals("nurAmAuto", Einstellungen.sperre(an, Einstellungen.Stufe.BESITZER));
        assertNotNull(Einstellungen.sperre(an, Einstellungen.Stufe.KOPPLUNG));
        assertNotNull(Einstellungen.sperre(an, Einstellungen.Stufe.KEIN_RECHT));
        assertNull("am Display / mit Login", Einstellungen.sperre(an, Einstellungen.Stufe.LOGIN));
        // Abschnitt „bruecke“ ist für die Web-Sitzung geheim
        JSONObject req = new JSONObject();
        try {
            req.put("werte", new JSONObject().put(PcZugang.SCHALTER, true));
        } catch (Exception ignored) { }
        assertEquals(Collections.singletonList(PcZugang.SCHALTER),
                WebHandyGuard.gesperrteFelder(Katalog.felder(new Katalog.Kontext()), req));
    }

    @Test
    public void schalterTexteInFuenfSprachen() throws Exception {
        for (String sprache : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                    new File("src/main/assets/server-i18n/" + sprache + ".json").toPath()), StandardCharsets.UTF_8));
            JSONObject b = d.getJSONObject("settings").getJSONObject("bruecke");
            for (String x : new String[]{"pcAnmeldung", "pcTagesMB"}) {
                assertFalse(sprache + x, b.getJSONObject(x).optString("title").isEmpty());
                assertFalse(sprache + x, b.getJSONObject(x).optString("help").isEmpty());
            }
            assertTrue(sprache, d.getJSONObject("telegram").getJSONObject("kopplung").getString("neu_pc").contains("{0}"));
            assertFalse(sprache, d.getJSONObject("telegram").getJSONObject("kopplung").optString("knopf_abmelden").isEmpty());
            assertTrue(sprache, d.getJSONObject("notifications").getString("neuer_pc_band_titel").contains("{0}"));
            assertTrue(sprache, d.getJSONObject("pc").getString("push_neu").contains("{0}"));
            assertFalse(sprache, d.getJSONObject("pc").optString("push_neu_text").isEmpty());
        }
    }

    // ------------------------------------------------------------------ Web-Manifest

    @Test
    public void manifestNurSharedUndI18nMitSha256() throws Exception {
        File apk = File.createTempFile("pc-manifest", ".apk");
        apk.deleteOnExit();
        byte[] js = "console.log(1)".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(apk))) {
            for (String[] e : new String[][]{{"assets/web/shared/a.js", "console.log(1)"}, {"assets/web/i18n/de.json", "{}"},
                    {"assets/web/local/index.html", "<html>"}, {"assets/server-i18n/de.json", "{}"}, {"classes.dex", "x"}}) {
                z.putNextEntry(new ZipEntry(e[0]));
                z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        JSONObject m = WebManifest.bauen(apk, "0.1.39", 80);
        JSONObject d = m.getJSONObject("dateien");
        assertEquals(2, d.length());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(js)), d.getString("/shared/a.js"));
        assertTrue(d.has("/i18n/de.json"));
        assertFalse("Seiten gehen immer durch die Brücke", d.has("/local/index.html"));
        assertEquals(80, m.getInt("versionCode"));
        assertNull(WebManifest.urlFuer("assets/web/shared/../../x"));
        assertNull(WebManifest.urlFuer("assets/web/shared/"));
    }

    private JSONObject post(String pfad, String kid, JSONObject body) throws Exception {
        return BesitzerApiHandler.antwort("POST", pfad, body.toString(), kid, 10_000L);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
