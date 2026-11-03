package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.Automations;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.CommandResult;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;
import at.greenchili.surfmate.genai.KiSperre;
import at.greenchili.surfmate.gewohnheiten.Herkunft;
import at.greenchili.surfmate.server.AutomationApiHandler;
import at.greenchili.surfmate.server.BefehlsLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.37 Assistenzsysteme (03.11.2026: „das sollen sie selbst entscheiden, nicht ich“): Ausschalten ist erlaubt,
 * aber nur nach bestätigtem Haftungshinweis. Einschalten geht immer. AEB bleibt nur einschaltbar. Die KI bleibt
 * gesperrt. Jede Abschaltung steht mit Herkunft im Befehls-Log.
 */
public class AssistenzHinweisTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String regelId;

    @BeforeClass
    public static void leiseLogs() {
        at.greenchili.surfmate.logging.DaemonLogger.Config cfg =
                new at.greenchili.surfmate.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        at.greenchili.surfmate.logging.DaemonLogger.configure(cfg);
    }

    @Before
    public void vorher() throws Exception {
        AssistenzHinweis.dateiFuerTest = new File(tmp.newFolder("daemon"), AssistenzHinweis.DATEI);
        BefehlsLog.starten(tmp.newFolder("befehlslog"));
        Herkunft.setze(null);
    }

    @After
    public void nachher() {
        AssistenzHinweis.dateiFuerTest = null;
        BefehlsLog.starten(null);
        Herkunft.setze(null);
        if (regelId != null) Automations.deleteAutomation(regelId);
    }

    /** Alle Befehle, die ein Assistenzsystem ausschalten (oder einen Teil davon). */
    static List<VehicleCommand> ausBefehle() {
        return Arrays.asList(
                new VehicleCommandRouter.AdasSpeedLimitWarningCommand(false),
                new VehicleCommandRouter.AdasSpeedLimitControlCommand(false),
                new VehicleCommandRouter.AdasTrafficSignCommand(false),
                new VehicleCommandRouter.AdasBlindSpotCommand(false),
                new VehicleCommandRouter.AdasRearCrossTrafficCommand(false),
                new VehicleCommandRouter.AdasFrontCrossTrafficCommand(false),
                new VehicleCommandRouter.AdasTrafficLightAttentionCommand(false),
                new VehicleCommandRouter.AdasOpenDoorWarningCommand(false),
                new VehicleCommandRouter.AdasRearCollisionWarningCommand(false),
                new VehicleCommandRouter.AdasEmergencyLaneKeepCommand(false),
                new VehicleCommandRouter.AdasRearCrossBrakeCommand(false),
                new VehicleCommandRouter.AdasFrontCrossBrakeCommand(false),
                new VehicleCommandRouter.AdasEspCommand(false),
                new VehicleCommandRouter.AdasItacCommand(false),
                new VehicleCommandRouter.AdasLaneAssistCommand(0),
                new VehicleCommandRouter.AdasLaneAssistCommand(1),
                new VehicleCommandRouter.AdasLaneAssistCommand(2),
                new VehicleCommandRouter.AdasFcwLevelCommand(0),
                new VehicleCommandRouter.SettingChildPresenceDetectionCommand(2),
                new VehicleCommandRouter.CarSettingCommand("esp_assist", 0),
                new VehicleCommandRouter.CarSettingCommand("lane_keeping", 0));
    }

    static List<VehicleCommand> einBefehle() {
        return Arrays.asList(
                new VehicleCommandRouter.AdasSpeedLimitWarningCommand(true),
                new VehicleCommandRouter.AdasSpeedLimitControlCommand(true),
                new VehicleCommandRouter.AdasTrafficSignCommand(true),
                new VehicleCommandRouter.AdasBlindSpotCommand(true),
                new VehicleCommandRouter.AdasEmergencyLaneKeepCommand(true),
                new VehicleCommandRouter.AdasEspCommand(true),
                new VehicleCommandRouter.AdasItacCommand(true),
                new VehicleCommandRouter.AdasLaneAssistCommand(3),
                new VehicleCommandRouter.AdasFcwLevelCommand(1),
                new VehicleCommandRouter.AdasFcwLevelCommand(3),
                new VehicleCommandRouter.SettingChildPresenceDetectionCommand(1),
                new VehicleCommandRouter.SettingChildPresenceDetectionCommand(3),
                new VehicleCommandRouter.AdasEmergencyBrakingCommand(true),
                new VehicleCommandRouter.CarSettingCommand("esp_assist", 1),
                new VehicleCommandRouter.CarSettingCommand("aeb", 1),
                // Anderes, das mit Assistenz nichts zu tun hat
                new VehicleCommandRouter.ClimateOffCommand(),
                new VehicleCommandRouter.CarSettingCommand("auto_wipe", 0));
    }

    // ------------------------------------------------------------------ ohne Hinweis kein Ausschalten

    @Test
    public void ohneHinweisSchaltetPanoptesNichtsAus() {
        assertFalse(AssistenzHinweis.bestaetigt());
        for (VehicleCommand c : ausBefehle()) {
            assertNotNull(c.name(), AssistenzHinweis.abschaltung(c));
            assertEquals(c.name(), AssistenzHinweis.GRUND, AssistenzHinweis.sperrGrund(c));
            CommandResult r = VehicleCommandRouter.checkAssistenzHinweis(c);
            assertNotNull(c.name(), r);
            assertEquals(c.name(), VehicleCommandRouter.Outcome.AUTH_REQUIRED, r.outcome);
            assertEquals(c.name(), VehicleCommandRouter.Path.NONE, r.path);
            assertEquals(c.name(), AssistenzHinweis.GRUND, r.grund);
        }
    }

    @Test
    public void routerLehntAbBevorEinWegLaeuft() {
        // Echter Eingang: kommt ohne SDK und Cloud zurück, weil die Sperre vor jedem Weg steht
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(false));
        assertEquals(VehicleCommandRouter.Outcome.AUTH_REQUIRED, r.outcome);
        assertEquals(AssistenzHinweis.GRUND, r.grund);
        CommandResult s = VehicleCommandRouter.getInstance()
                .executeSdkOnly(new VehicleCommandRouter.AdasLaneAssistCommand(0));
        assertEquals(VehicleCommandRouter.Outcome.AUTH_REQUIRED, s.outcome);
        assertEquals(AssistenzHinweis.GRUND, s.grund);
    }

    @Test
    public void sperreStehtInBeidenEingaengenHinterDerFahrsperre() throws Exception {
        String src = lies("src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        Matcher m = Pattern.compile("CommandResult blocked = checkDrivingSafety\\(cmd\\);\\s*"
                + "if \\(blocked != null\\) return blocked;\\s*"
                + "CommandResult assistenz = checkAssistenzHinweis\\(cmd\\);\\s*"
                + "if \\(assistenz != null\\) return assistenz;").matcher(src);
        int n = 0;
        while (m.find()) n++;
        assertEquals("execute und executeSdkOnly", 2, n);
    }

    // ------------------------------------------------------------------ Einschalten ohne Hinweis

    @Test
    public void einschaltenGehtImmerOhneHinweis() {
        assertFalse(AssistenzHinweis.bestaetigt());
        for (VehicleCommand c : einBefehle()) {
            assertNull(c.name(), AssistenzHinweis.abschaltung(c));
            assertNull(c.name(), AssistenzHinweis.sperrGrund(c));
            assertNull(c.name(), VehicleCommandRouter.checkAssistenzHinweis(c));
        }
    }

    // ------------------------------------------------------------------ Bestätigen, Version, Datei

    @Test
    public void nachBestaetigungDarfAusgeschaltetWerden() throws Exception {
        JSONObject falsch = AssistenzHinweis.bestaetigen("alt");
        assertFalse(falsch.getBoolean("ok"));
        assertFalse(AssistenzHinweis.bestaetigt());

        AssistenzHinweis.uhr = () -> 1_700_000_000_000L;
        try {
            assertTrue(AssistenzHinweis.bestaetigen(AssistenzHinweis.HINWEIS_VERSION).getBoolean("ok"));
        } finally {
            AssistenzHinweis.uhr = System::currentTimeMillis;
        }
        assertTrue(AssistenzHinweis.bestaetigt());
        for (VehicleCommand c : ausBefehle()) assertNull(c.name(), AssistenzHinweis.sperrGrund(c));

        // Nur Textversion und Zeit, keine Personendaten
        JSONObject datei = new JSONObject(new String(Files.readAllBytes(AssistenzHinweis.dateiFuerTest.toPath()),
                StandardCharsets.UTF_8));
        assertEquals(new HashSet<>(Arrays.asList("version", "zeit")), schluessel(datei));
        assertEquals(AssistenzHinweis.HINWEIS_VERSION, datei.getString("version"));
        assertEquals(1_700_000_000_000L, datei.getLong("zeit"));
    }

    @Test
    public void textAenderungVerlangtNeueBestaetigung() throws Exception {
        Files.write(AssistenzHinweis.dateiFuerTest.toPath(),
                new JSONObject().put("version", "2000-01-01").put("zeit", 5L).toString()
                        .getBytes(StandardCharsets.UTF_8));
        assertFalse(AssistenzHinweis.bestaetigt());
        assertEquals(AssistenzHinweis.GRUND,
                AssistenzHinweis.sperrGrund(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(false)));
        assertFalse(AssistenzHinweis.status().getBoolean("hinweisBestaetigt"));
    }

    @Test
    public void httpStandUndBestaetigen() throws Exception {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        assertTrue(AssistenzHinweis.handle("GET", "/api/assistenz/hinweis", null, a));
        assertTrue(text(a).contains("\"hinweisBestaetigt\":false"));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertTrue(AssistenzHinweis.handle("POST", "/api/assistenz/hinweis",
                new JSONObject().put("version", AssistenzHinweis.HINWEIS_VERSION).toString(), b));
        assertTrue(text(b).contains("\"ok\":true"));
        assertTrue(AssistenzHinweis.bestaetigt());
        assertFalse(AssistenzHinweis.handle("GET", "/api/assistenz/anderes", null, new ByteArrayOutputStream()));
    }

    // ------------------------------------------------------------------ Notbremsassistent bleibt nur EIN

    @Test
    public void notbremsassistentBleibtNurEinschaltbarAuchMitHinweis() throws Exception {
        AssistenzHinweis.bestaetigen(AssistenzHinweis.HINWEIS_VERSION);
        assertTrue(AssistenzHinweis.bestaetigt());
        for (VehicleCommand c : Arrays.<VehicleCommand>asList(
                new VehicleCommandRouter.AdasEmergencyBrakingCommand(false),
                new VehicleCommandRouter.CarSettingCommand("aeb", 0))) {
            assertTrue(c.name(), AssistenzHinweis.aebAus(c));
            CommandResult r = VehicleCommandRouter.checkAssistenzHinweis(c);
            assertNotNull(c.name(), r);
            assertEquals(AssistenzHinweis.GRUND_NUR_EIN, r.grund);
            assertEquals(VehicleCommandRouter.Outcome.NOT_SUPPORTED, r.outcome);
        }
        assertNull(VehicleCommandRouter.checkAssistenzHinweis(new VehicleCommandRouter.AdasEmergencyBrakingCommand(true)));
        // Automation kann AEB weiterhin nur einschalten
        assertEquals(AssistenzHinweis.GRUND_NUR_EIN, AssistenzHinweis.speichernGrund(
                AssistenzHinweis.regelSchaltetAb(regel(aktion("adas_aeb", "payload", "off")), leer())));
        assertNull(AssistenzHinweis.regelSchaltetAb(regel(aktion("adas_aeb", "payload", "on")), leer()));
    }

    // ------------------------------------------------------------------ Befehls-Log mit Herkunft

    @Test
    public void jedeAbschaltungStehtMitHerkunftImBefehlsLog() throws Exception {
        VehicleCommandRouter.getInstance().execute(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(false));
        Herkunft.setze(Herkunft.HANDY);
        VehicleCommandRouter.getInstance().execute(new VehicleCommandRouter.AdasEspCommand(false));
        Herkunft.setze(Herkunft.DISPLAY);
        VehicleCommandRouter.getInstance().executeSdkOnly(new VehicleCommandRouter.AdasFcwLevelCommand(0));
        Herkunft.setze(null);
        // Einschalten kommt nicht als Abschaltung ins Log
        VehicleCommandRouter.checkAssistenzHinweis(new VehicleCommandRouter.AdasEspCommand(true));

        JSONArray e = BefehlsLog_alle();
        JSONObject slw = finde(e, "assistenz_aus.adas_slw:");
        JSONObject esp = finde(e, "assistenz_aus.esp_control:");
        JSONObject fcw = finde(e, "assistenz_aus.adas_fcw:");
        assertNotNull(slw);
        assertNotNull(esp);
        assertNotNull(fcw);
        assertEquals("automation", slw.getString("herkunft"));
        assertEquals("handy", esp.getString("herkunft"));
        assertEquals("nutzer", fcw.getString("herkunft"));
        for (JSONObject z : new JSONObject[]{slw, esp, fcw}) {
            assertEquals("abgelehnt", z.getString("ergebnis"));
            assertEquals(AssistenzHinweis.GRUND, z.getString("grund"));
            assertEquals("pflicht", z.getString("klasse"));
        }
        assertEquals("adas_slw", slw.getString("system"));
    }

    // ------------------------------------------------------------------ Automation speichern verlangt den Hinweis

    @Test
    public void regelErkennungAuchVerschachteltUndInGruppen() throws Exception {
        assertEquals("adas_slw", AssistenzHinweis.regelSchaltetAb(regel(aktion("adas_slw", "payload", "off")), leer()));
        assertNull(AssistenzHinweis.regelSchaltetAb(regel(aktion("adas_slw", "payload", "on")), leer()));
        assertEquals("adas_islc", AssistenzHinweis.regelSchaltetAb(regel(new JSONObject().put("type", "if")
                .put("variables", new JSONObject())
                .put("childActions", new JSONArray().put(aktion("setAcTemp", "temperature", "21")))
                .put("elseActions", new JSONArray().put(aktion("adas_islc", "payload", "off")))), leer()));
        assertEquals("lane_assist", AssistenzHinweis.regelSchaltetAb(regel(aktion("lane_assist", "payload", "1")), leer()));
        assertNull(AssistenzHinweis.regelSchaltetAb(regel(aktion("lane_assist", "payload", "3")), leer()));
        assertEquals("cpd", AssistenzHinweis.regelSchaltetAb(regel(aktion("cpd", "action", "2")), leer()));
        assertNull(AssistenzHinweis.regelSchaltetAb(regel(aktion("cpd", "action", "3")), leer()));
        assertEquals("adas_fcw", AssistenzHinweis.regelSchaltetAb(regel(aktion("adas_fcw", "payload", "0")), leer()));
        // Variable statt festem Wert gilt als Ausschalten (sicherer Fall)
        assertEquals("esp_control",
                AssistenzHinweis.regelSchaltetAb(regel(aktion("esp_control", "payload", "${x}")), leer()));
        JSONObject gruppen = new JSONObject().put("g1", new JSONObject().put("actions",
                new JSONArray().put(aktion("adas_elka", "payload", "off"))));
        assertEquals("adas_elka", AssistenzHinweis.regelSchaltetAb(regel(new JSONObject().put("type", "actionGroup")
                .put("variables", new JSONObject().put("groupId", "g1"))), () -> gruppen));
        assertNull(AssistenzHinweis.regelSchaltetAb(regel(aktion("setAcTemp", "temperature", "21")), leer()));
    }

    @Test
    public void automationSpeichernVerlangtDenHinweis() throws Exception {
        regelId = "assistenz-test-" + UUID.randomUUID().toString().substring(0, 8);
        assertTrue(Automations.updateAutomation(regelId, automation("on")));

        // Ohne Hinweis: abgelehnt mit grund hinweis_fehlt, gespeicherte Regel bleibt unverändert
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle("PUT", "/api/automations/automation/" + regelId,
                automation("off").toString(), a));
        String ta = text(a);
        assertTrue(ta, ta.contains("\"grund\":\"hinweis_fehlt\""));
        assertTrue(ta, ta.contains("\"success\":false"));
        assertEquals("on", gespeichertesPayload());

        // Einschalten speichern geht ohne Hinweis
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle("PUT", "/api/automations/automation/" + regelId,
                automation("on").toString(), b));
        // (Im Unit-Test fehlt der Speicherort; die Regel landet im Speicher, das reicht für die Sperre.)
        assertFalse(text(b), text(b).contains("hinweis_fehlt"));
        assertEquals("on", gespeichertesPayload());

        // Nach „Verstanden, ich entscheide selbst“: gespeichert
        assertTrue(AssistenzHinweis.bestaetigen(AssistenzHinweis.HINWEIS_VERSION).getBoolean("ok"));
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle("PUT", "/api/automations/automation/" + regelId,
                automation("off").toString(), c));
        assertFalse(text(c), text(c).contains("hinweis_fehlt"));
        assertEquals("off", gespeichertesPayload());
    }

    @Test
    public void importUndGruppeVerlangenDenHinweis() throws Exception {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle("POST", "/api/automations/import",
                new JSONObject().put("automations", new JSONObject().put("x-" + UUID.randomUUID(), automation("off")))
                        .toString(), a));
        assertTrue(text(a), text(a).contains("\"grund\":\"hinweis_fehlt\""));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle("POST", "/api/action-groups",
                new JSONObject().put("name", "t").put("actions",
                        new JSONArray().put(aktion("adas_slw", "payload", "off"))).toString(), b));
        assertTrue(text(b), text(b).contains("\"grund\":\"hinweis_fehlt\""));
    }

    @Test
    public void tastenbelegungMitAbschaltungWirdErkannt() throws Exception {
        JSONArray aus = new JSONArray().put(new JSONObject().put("keyCode", 1).put("action",
                new JSONObject().put("kind", "sequence").put("steps", new JSONArray()
                        .put(new JSONObject().put("kind", "catalog").put("key", "adas_slw").put("payload", "toggle")))));
        assertEquals("adas_slw", AssistenzHinweis.tastenSchaltenAb(aus));
        JSONArray ein = new JSONArray().put(new JSONObject().put("action",
                new JSONObject().put("kind", "catalog").put("key", "adas_slw").put("payload", "on")));
        assertNull(AssistenzHinweis.tastenSchaltenAb(ein));
        JSONArray anderes = new JSONArray().put(new JSONObject().put("action",
                new JSONObject().put("kind", "catalog").put("key", "drl").put("payload", "off")));
        assertNull(AssistenzHinweis.tastenSchaltenAb(anderes));
        assertEquals("lane_assist", AssistenzHinweis.tastenSchaltenAb(new JSONArray().put(new JSONObject()
                .put("kind", "catalog").put("key", "lane_assist").put("payload", "0"))));
    }

    // ------------------------------------------------------------------ KI bleibt gesperrt (nur prüfen)

    @Test
    public void kiDarfKeinAssistenzsystemSchalten() throws Exception {
        Set<String> alle = new HashSet<>(AssistenzHinweis.SCHALTER);
        alle.addAll(AssistenzHinweis.NUR_EIN);
        alle.addAll(Arrays.asList(AssistenzHinweis.SPUR, AssistenzHinweis.FCW, AssistenzHinweis.CPD, "adas_cpd"));
        for (String typ : alle) {
            assertFalse(typ, KiSperre.erlaubterTyp(typ));
            for (String p : new String[]{"on", "off"}) {
                assertNotNull(typ, KiSperre.regelGesperrt(regel(aktion(typ, "payload", p))));
            }
        }
    }

    // ------------------------------------------------------------------ Web und Texte

    @Test
    public void webListeStimmtMitKernUeberein() throws Exception {
        String js = lies("src/main/assets/web/shared/assistenz-hinweis.js");
        Matcher m = Pattern.compile("var SCHALTER = \\[([^\\]]*)\\]").matcher(js);
        assertTrue(m.find());
        Set<String> web = new HashSet<>();
        Matcher k = Pattern.compile("'([a-z_]+)'").matcher(m.group(1));
        while (k.find()) web.add(k.group(1));
        assertEquals(AssistenzHinweis.SCHALTER, web);
        Matcher n = Pattern.compile("var NUR_EIN = \\[([^\\]]*)\\]").matcher(js);
        assertTrue(n.find());
        Set<String> nurEin = new HashSet<>();
        Matcher k2 = Pattern.compile("'([a-z_]+)'").matcher(n.group(1));
        while (k2.find()) nurEin.add(k2.group(1));
        assertEquals(AssistenzHinweis.NUR_EIN, nurEin);
        for (String seite : new String[]{"automations.html", "vehicle-control.html", "key-mapping.html"}) {
            assertTrue(seite, lies("src/main/assets/web/local/" + seite).contains("assistenz-hinweis.js"));
        }
    }

    @Test
    public void hinweisInFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject web = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            JSONObject a = web.getJSONObject("assistenz");
            for (String key : new String[]{"hinweis_titel", "hinweis_text", "hinweis_knopf", "hinweis_fehler",
                    "kennzeichnung"}) {
                assertFalse(l + "." + key, a.optString(key, "").trim().isEmpty());
            }
            assertTrue(l, a.getString("hinweis_text").contains("Panoptes"));
            assertTrue(l, a.getString("hinweis_text").contains("EU") || a.getString("hinweis_text").contains("UE"));
            JSONObject server = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json"))
                    .getJSONObject("vehicle_control");
            assertFalse(l, server.optString("assistenz_hinweis_fehlt", "").isEmpty());
            assertFalse(l, server.optString("assistenz_nur_einschalten", "").isEmpty());
            assertFalse(l, web.getJSONObject("protokoll").optString("a_assistenz_aus", "").isEmpty());
        }
        assertEquals("Verstanden, ich entscheide selbst", new JSONObject(lies("src/main/assets/web/i18n/de.json"))
                .getJSONObject("assistenz").getString("hinweis_knopf"));
    }

    // ------------------------------------------------------------------ Hilfen

    private static JSONObject aktion(String typ, String feld, String wert) throws Exception {
        return new JSONObject().put("type", typ).put("variables", new JSONObject().put(feld, wert));
    }

    private static JSONObject regel(JSONObject... aktionen) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject x : aktionen) a.put(x);
        return new JSONObject().put("actions", a);
    }

    private static AssistenzHinweis.Gruppen leer() {
        return JSONObject::new;
    }

    private JSONObject automation(String payload) throws Exception {
        return new JSONObject()
                .put("triggers", new JSONArray().put(new JSONObject().put("type", "variable")
                        .put("variables", new JSONObject().put("name", "assistenz_test_" + regelId))))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray().put(aktion("adas_slw", "payload", payload)))
                .put("name", "assistenz test")
                .put("disabled", true);
    }

    private String gespeichertesPayload() throws Exception {
        return Automations.toJson().getJSONObject(regelId).getJSONArray("actions").getJSONObject(0)
                .getJSONObject("variables").getString("payload");
    }

    private static JSONArray BefehlsLog_alle() throws Exception {
        return BefehlsLog.alsJson(1000, System.currentTimeMillis()).getJSONArray("eintraege");
    }

    private static JSONObject finde(JSONArray e, String ereignisAnfang) {
        for (int i = 0; i < e.length(); i++) {
            JSONObject z = e.optJSONObject(i);
            JSONArray ev = z == null ? null : z.optJSONArray("ereignisse");
            if (ev != null && ev.length() > 0 && ev.optString(0).startsWith(ereignisAnfang)) return z;
        }
        return null;
    }

    private static Set<String> schluessel(JSONObject o) {
        Set<String> s = new HashSet<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) s.add(it.next());
        return s;
    }

    private static String text(ByteArrayOutputStream o) {
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
