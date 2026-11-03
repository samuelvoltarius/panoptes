package at.greenchili.surfmate.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import at.greenchili.surfmate.automation.Automations;
import at.greenchili.surfmate.automation.action.Action;
import at.greenchili.surfmate.automation.action.Actions;
import at.greenchili.surfmate.automation.action.ApiAction;
import at.greenchili.surfmate.automation.action.VehicleControlAction;
import at.greenchili.surfmate.server.BefehlsLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * REGEL A+: Der KI-Assistent startet nie Hupe, Blinker, Warnblinker, Licht, „Auto finden“ oder Alarm –
 * weder direkt noch über Regel, Aktionsgruppe, Wenn/Schleife oder „Regel einschalten“.
 */
public class KiSperreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> angelegt = new ArrayList<>();

    @BeforeClass
    public static void leiseLogs() {
        at.greenchili.surfmate.logging.DaemonLogger.Config cfg =
                new at.greenchili.surfmate.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        at.greenchili.surfmate.logging.DaemonLogger.configure(cfg);
    }

    @After
    public void aufraeumen() {
        for (String id : angelegt) Automations.deleteAutomation(id);
        BefehlsLog.starten(null);
    }

    // ------------------------------------------------------------------ reine Prüfung

    /** Alles mit Wirkung nach außen, Schlösser, Fenster, Klappen, Ton, MQTT … ist für die KI gesperrt. */
    static final String[] MUSS_GESPERRT = {
            // Hupe, Blinker, Warnblinker, „Auto finden“, Licht
            "flash", "findCar", "hazard", "drl", "headlight_mode", "headlightLevel", "welcomeLight", "readingLight",
            "ambientMusic", "setAmbient", "ambientBrightness", "ambientPower",
            // Schlösser, Türen, Klappen, Spiegel
            "lock", "child_lock", "tailgate", "mirror_fold", "mirror_auto_follow_up",
            // Fenster, Sonnendach, Lüften
            "windows", "windowsPreset", "windowsAll", "windowsVent", "sunroof",
            // Audio innen, Ansagen, Ton, Video
            "playAudio", "playVideo", "speak", "stopAudio", "mediaVolume", "channelVolume", "volumeStep", "mediaControl",
            // MQTT, Shell, Wächter/Alarm
            "mqttPublish", "shell", "surveillance", "operatingMode",
            // Sitzposition (bewegt den Sitz), Fahr-/Assistenzsysteme, Laden, System
            "seatPosition", "seatSave", "applySeatPosition", "esp_control", "adas_aeb", "drive_mode",
            "startChargingNow", "wallboxStrom", "iviReboot", "radio", "openApp", "screenshot"};

    @Test
    public void jedeGesperrteAktionDirektWirdErkannt() throws Exception {
        for (String typ : MUSS_GESPERRT) {
            assertNotNull(typ, KiSperre.regelGesperrt(regel(aktion(typ)), quelle(new JSONObject(), new JSONObject())));
            assertFalse(typ, KiSperre.erlaubterTyp(typ));
        }
        assertEquals("unbekannt", KiSperre.regelGesperrt(regel(new JSONObject().put("variables", new JSONObject())),
                quelle(new JSONObject(), new JSONObject())));
        assertEquals("erfunden", KiSperre.regelGesperrt(regel(aktion("erfunden")),
                quelle(new JSONObject(), new JSONObject())));
    }

    @Test
    public void klimaSitzheizungSonnenrolloUndAnzeigeBleibenErlaubt() throws Exception {
        JSONObject r = regel(aktion("notification"), aktion("sunshade"), aktion("setAcTemp"), aktion("seat"),
                aktion("steeringHeat"), aktion("frontDefrost"), aktion("showToast"), aktion("showDialog"),
                aktion("pause"), aktion("setVariable"),
                new JSONObject().put("type", "if").put("variables", new JSONObject())
                        .put("childActions", new JSONArray().put(aktion("acAuto")))
                        .put("elseActions", new JSONArray().put(aktion("stepAcTemp"))));
        assertNull(KiSperre.regelGesperrt(r, quelle(new JSONObject(), new JSONObject())));
    }

    @Test
    public void waechterIstGesperrtAuchBeimAusschalten() throws Exception {
        for (String was : new String[]{"enable", "disable"}) {
            JSONObject r = regel(aktion("surveillance").put("variables", new JSONObject().put("action", was)));
            assertEquals("surveillance", KiSperre.regelGesperrt(r, quelle(new JSONObject(), new JSONObject())));
        }
    }

    @Test
    public void fensterKofferraumTonUndMqttVerschachteltGefunden() throws Exception {
        // Gruppe -> Schleife -> Wenn(sonst) -> Heckklappe; Regel -> Regel einschalten -> MQTT
        JSONObject gruppen = new JSONObject().put("g", gruppe(aktion("notification"),
                new JSONObject().put("type", "loop").put("variables", new JSONObject())
                        .put("childActions", new JSONArray().put(new JSONObject().put("type", "if")
                                .put("variables", new JSONObject())
                                .put("elseActions", new JSONArray().put(aktion("tailgate")))))));
        assertEquals("tailgate", KiSperre.regelGesperrt(regel(aktion("setAcTemp"), gruppenAufruf("g")),
                quelle(new JSONObject(), gruppen)));
        JSONObject alle = new JSONObject().put("ziel", regel(aktion("notification"), aktion("mqttPublish")));
        assertEquals("mqttpublish", KiSperre.regelGesperrt(regel(regelSteuerung("ziel", "enable")),
                quelle(alle, new JSONObject())));
        assertEquals("windowsvent", KiSperre.regelGesperrt(regel(new JSONObject().put("type", "if")
                .put("variables", new JSONObject())
                .put("childActions", new JSONArray().put(aktion("windowsVent")))), quelle(new JSONObject(), new JSONObject())));
        assertEquals("speak", KiSperre.regelGesperrt(regel(aktion("seat"), aktion("speak")),
                quelle(new JSONObject(), new JSONObject())));
    }

    @Test
    public void verschachteltUeberGruppeInGruppeImWennZweigWirdGefunden() throws Exception {
        // Regel → Wenn (sonst-Zweig) → Gruppe A → Schleife → Gruppe B → Warnblinker
        JSONObject gruppen = new JSONObject()
                .put("ga", gruppe(new JSONObject().put("type", "loop")
                        .put("variables", new JSONObject())
                        .put("childActions", new JSONArray().put(gruppenAufruf("gb")))))
                .put("gb", gruppe(aktion("notification"),
                        aktion("hazard").put("variables", new JSONObject().put("payload", "on"))));
        JSONObject r = regel(new JSONObject().put("type", "if").put("variables", new JSONObject())
                .put("childActions", new JSONArray().put(aktion("notification")))
                .put("elseActions", new JSONArray().put(gruppenAufruf("ga"))));
        assertEquals("hazard", KiSperre.regelGesperrt(r, quelle(new JSONObject(), gruppen)));
    }

    @Test
    public void regelDieAndereRegelEinschaltetErbtDieSperre() throws Exception {
        JSONObject alle = new JSONObject().put("ziel", regel(aktion("findCar")));
        JSONObject schaltetEin = regel(regelSteuerung("ziel", "enable"));
        JSONObject schaltetUm = regel(regelSteuerung("ziel", "toggle"));
        JSONObject schaltetAus = regel(regelSteuerung("ziel", "disable"));
        Quelle q = new Quelle(alle, new JSONObject());
        assertEquals("findcar", KiSperre.regelGesperrt(schaltetEin, q));
        assertEquals("findcar", KiSperre.regelGesperrt(schaltetUm, q));
        assertNull("Ausschalten ist harmlos", KiSperre.regelGesperrt(schaltetAus, q));
    }

    @Test
    public void zyklenEndenUndUnbekanntesIstGesperrt() throws Exception {
        JSONObject gruppen = new JSONObject()
                .put("ga", gruppe(gruppenAufruf("gb")))
                .put("gb", gruppe(gruppenAufruf("ga"), aktion("notification")));
        assertNull(KiSperre.regelGesperrt(regel(gruppenAufruf("ga")), quelle(new JSONObject(), gruppen)));
        assertEquals("unbekannte_gruppe",
                KiSperre.regelGesperrt(regel(gruppenAufruf("fehlt")), quelle(new JSONObject(), gruppen)));

        JSONObject alle = new JSONObject()
                .put("a", regel(regelSteuerung("b", "enable")))
                .put("b", regel(regelSteuerung("a", "enable")));
        assertNull(KiSperre.automationGesperrt("a", quelle(alle, new JSONObject())));
    }

    @Test
    public void zuTiefeVerschachtelungIstGesperrt() throws Exception {
        JSONObject innen = aktion("notification");
        for (int i = 0; i <= KiSperre.MAX_TIEFE + 1; i++) {
            innen = new JSONObject().put("type", "if").put("variables", new JSONObject())
                    .put("childActions", new JSONArray().put(innen));
        }
        assertEquals("zu_tief", KiSperre.regelGesperrt(regel(innen), quelle(new JSONObject(), new JSONObject())));
    }

    /**
     * Katalog-Wächter: Die Erlaubnisliste enthält nur Ids, die es gibt, erlaubte Api-Aktionen gehen nur an Klima,
     * Klima-Zeitplan oder Anzeige am Display, erlaubte Fahrzeug-Aktionen sind nur Sitzklima und Sonnenrollo.
     * Jede neue Aktion im Katalog ist für die KI automatisch gesperrt.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void katalogWaechterErlaubtNurKlimaSitzSonnenrolloAnzeige() throws Exception {
        Actions actions = new Actions();
        Field af = Actions.class.getDeclaredField("actions");
        af.setAccessible(true);
        Field pf = ApiAction.class.getDeclaredField("path");
        pf.setAccessible(true);
        Map<String, Action> alle = (Map<String, Action>) af.get(actions);
        java.util.Set<String> ids = new java.util.HashSet<>();
        int gesperrt = 0;
        for (Action a : alle.values()) {
            String id = a.getLabel().getId();
            ids.add(id.toLowerCase(Locale.ROOT));
            if (!KiSperre.erlaubterTyp(id)) {
                gesperrt++;
                continue;
            }
            if (a instanceof ApiAction) {
                String path = (String) pf.get(a);
                assertTrue(id + " geht an " + path, path.equals("/api/vehicle/climate")
                        || path.equals("/api/vehicle/climate-schedule") || path.equals("/api/vehicle/message"));
            } else if (a instanceof VehicleControlAction) {
                assertTrue(id, id.equals("seat") || id.equals("sunshade"));
            }
        }
        for (String e : KiSperre.ERLAUBT) assertTrue("erlaubt, aber nicht im Katalog: " + e, ids.contains(e));
        for (String m : MUSS_GESPERRT) assertTrue("nicht im Katalog: " + m, ids.contains(m.toLowerCase(Locale.ROOT)));
        assertTrue("fast alles gesperrt: " + gesperrt, gesperrt >= 100);
    }

    // ------------------------------------------------------------------ echter Weg über GenAiAction

    @Test
    public void kiDarfGespeicherteRegelMitBlinkernNichtStartenAuchNichtBeimAusfuehren() throws Exception {
        java.io.File log = tmp.newFolder("befehlslog");
        BefehlsLog.starten(log);
        String id = speichere(regel(aktion("flash")));

        // Sprach-Werkzeug
        try {
            GenAiAction.parseToolArguments(automationsAufruf(id));
            fail("Werkzeug-Vorschlag hätte abgelehnt werden müssen");
        } catch (GenAiAction.ValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().toLowerCase(Locale.ROOT).contains("assistant"));
        }
        // Tipp auf die Karte (/api/genai/action/execute)
        try {
            GenAiAction.validateClientAction(new JSONObject()
                    .put("type", "run_automation").put("automationId", id));
            fail("Ausführen hätte abgelehnt werden müssen");
        } catch (GenAiAction.ValidationException expected) {
        }
        String inhalt = allesAus(log);
        assertTrue(inhalt, inhalt.contains("ki.automation:gesperrt"));
        assertTrue(inhalt, inhalt.contains("\"ergebnis\":\"abgelehnt\""));
        assertFalse("keine Namen/IDs der Regel im Log", inhalt.contains(id));
    }

    @Test
    public void verschachtelteRegelUeberRegelSteuerungWirdAbgelehnt() throws Exception {
        String ziel = speichere(regel(aktion("hazard").put("variables", new JSONObject().put("payload", "on"))));
        String aussen = speichere(regel(harmlos(), regelSteuerung(ziel, "enable")));
        try {
            GenAiAction.parseToolArguments(automationsAufruf(aussen));
            fail("verschachtelte Regel hätte abgelehnt werden müssen");
        } catch (GenAiAction.ValidationException expected) {
        }
        // Der Assistent sieht die Sperre schon im Katalog
        JSONArray katalog = GenAiAction.context().getJSONArray("automationCatalog");
        boolean markiert = false;
        for (int i = 0; i < katalog.length(); i++) {
            JSONObject e = katalog.getJSONObject(i);
            if (aussen.equals(e.optString("id"))) markiert = e.optBoolean("assistantBlocked", false);
        }
        assertTrue(markiert);
    }

    @Test
    public void harmloseGespeicherteRegelDarfDieKiWeiterVorschlagen() throws Exception {
        String id = speichere(regel(harmlos()));
        GenAiAction.Proposal p = GenAiAction.parseToolArguments(automationsAufruf(id));
        assertEquals("run_automation", p.action.getString("type"));
        assertEquals(id, p.action.getString("automationId"));
    }

    @Test
    public void kiEntwurfMitHupeOderLichtWirdNichtAngelegt() throws Exception {
        // harmloser Entwurf geht durch (Gegenprobe: die Regel-Form ist gültig)
        assertNotNull(GenAiAutomation.validateForSave(regelMitAusloeser(harmlos())));
        for (JSONObject a : new JSONObject[]{aktion("flash"), aktion("findCar"), aktion("lock"),
                aktion("hazard").put("variables", new JSONObject().put("payload", "on")),
                aktion("windowsVent"), aktion("stopAudio"),
                aktion("tailgate").put("variables", new JSONObject().put("action", "open")),
                aktion("mqttPublish").put("variables", new JSONObject().put("topic", "t").put("payload", "p")
                        .put("retain", "false"))}) {
            JSONObject entwurf = new JSONObject()
                    .put("summary", "x").put("questions", new JSONArray())
                    .put("automationJson", regelMitAusloeser(a).toString());
            try {
                GenAiAutomation.parseProviderDraft(entwurf.toString());
                fail(a + " hätte abgelehnt werden müssen");
            } catch (GenAiAutomation.ValidationException e) {
                assertEquals(KiSperre.ablehnungEntwurf(), e.getMessage());
            }
            try {
                GenAiAutomation.validateForSave(regelMitAusloeser(a));
                fail(a + " (commit) hätte abgelehnt werden müssen");
            } catch (GenAiAutomation.ValidationException e) {
                assertEquals(KiSperre.ablehnungEntwurf(), e.getMessage());
            }
        }
        // Der Katalog für Entwürfe enthält die gesperrten Aktionen gar nicht
        String katalog = GenAiAutomation.schemaContext().getJSONObject("catalog")
                .getJSONArray("actions").toString();
        for (String m : MUSS_GESPERRT) assertFalse(m, katalog.contains("\"id\":\"" + m + "\""));
        assertTrue(katalog.contains("\"id\":\"notification\""));
        assertTrue(katalog.contains("\"id\":\"setAcTemp\""));
    }

    /** Nur der KI-Weg ist gesperrt: Regeln, Tasten, HA und „Jetzt testen“ kennen KiSperre nicht. */
    @Test
    public void nutzerWegeRufenDieSperreNichtAuf() throws Exception {
        Path main = Paths.get("src/main/java/at/greenchili/surfmate");
        List<String> nutzer = new ArrayList<>();
        try (Stream<Path> s = Files.walk(main)) {
            s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(p -> {
                try {
                    String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    String n = p.getFileName().toString();
                    if (t.contains("KiSperre") && !n.equals("KiSperre.java")) nutzer.add(n);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        java.util.Collections.sort(nutzer);
        assertEquals(java.util.Arrays.asList("GenAiAction.java", "GenAiAutomation.java"), nutzer);
    }

    // ------------------------------------------------------------------ Hilfen

    private String speichere(JSONObject r) throws Exception {
        String id = "ki-sperre-" + UUID.randomUUID().toString().substring(0, 8);
        JSONObject voll = regelMitAusloeser(null);
        voll.put("actions", r.getJSONArray("actions"));
        voll.put("disabled", true).put("manualOnly", true);
        assertTrue(Automations.updateAutomation(id, voll));
        angelegt.add(id);
        assertTrue(Automations.exists(id));
        return id;
    }

    private static JSONObject regelMitAusloeser(JSONObject eineAktion) throws Exception {
        JSONObject r = new JSONObject()
                .put("name", "KI Sperre Test")
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "variable")
                        .put("variables", new JSONObject().put("name", "ki_sperre_test"))))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray());
        if (eineAktion != null) r.getJSONArray("actions").put(eineAktion);
        return r;
    }

    private static JSONObject automationsAufruf(String id) throws Exception {
        return new JSONObject()
                .put("actionType", "run_automation")
                .put("temperatureC", -1).put("zone", -1).put("operation", "none")
                .put("automationId", id).put("automationName", "");
    }

    private static JSONObject harmlos() throws Exception {
        return new JSONObject().put("type", "setVariable")
                .put("variables", new JSONObject().put("name", "ki_sperre_out").put("value", "x"));
    }

    private static String allesAus(java.io.File dir) throws Exception {
        StringBuilder b = new StringBuilder();
        java.io.File[] f = dir.listFiles();
        if (f != null) for (java.io.File x : f) {
            b.append(new String(Files.readAllBytes(x.toPath()), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static JSONObject aktion(String typ) throws Exception {
        return new JSONObject().put("type", typ).put("variables", new JSONObject());
    }

    private static JSONObject gruppenAufruf(String gid) throws Exception {
        return new JSONObject().put("type", "actionGroup").put("variables", new JSONObject().put("groupId", gid));
    }

    private static JSONObject regelSteuerung(String ziel, String was) throws Exception {
        return new JSONObject().put("type", "automationControl")
                .put("variables", new JSONObject().put("target", ziel).put("action", was));
    }

    private static JSONObject gruppe(JSONObject... aktionen) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject x : aktionen) a.put(x);
        return new JSONObject().put("name", "g").put("actions", a);
    }

    private static JSONObject regel(JSONObject... aktionen) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject x : aktionen) a.put(x);
        return new JSONObject().put("actions", a);
    }

    private static Quelle quelle(JSONObject alle, JSONObject gruppen) {
        return new Quelle(alle, gruppen);
    }

    private static final class Quelle implements KiSperre.Quelle {
        private final JSONObject alle, gruppen;

        Quelle(JSONObject alle, JSONObject gruppen) {
            this.alle = alle;
            this.gruppen = gruppen;
        }

        @Override
        public JSONObject automationen() {
            return alle;
        }

        @Override
        public JSONObject gruppen() {
            return gruppen;
        }
    }
}
