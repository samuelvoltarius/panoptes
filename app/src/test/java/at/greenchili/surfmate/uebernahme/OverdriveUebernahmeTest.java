package at.greenchili.surfmate.uebernahme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LADEN_VERTRAG.md Teil B: nur lesen, Vorschau, Bestätigen, keine Einwilligungen, Zugangsdaten maskiert. */
public class OverdriveUebernahmeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String GEHEIMNIS = "123456:AAH-geheimer-bot-schluessel";

    /** Test-Tabelle (die echte füllt sich nach der Bestandsaufnahme am Auto). */
    static final List<OverdriveUebernahme.Regel> REGELN = Arrays.asList(
            new OverdriveUebernahme.Regel("aufbewahrung", "waechter", "od_test.json", "surveillance.keepDays",
                    "surveillance.keepDays", OverdriveUebernahme.Klasse.EINSTELLUNG, null),
            new OverdriveUebernahme.Regel("ruhe", "waechter", "od_test.json", "surveillance.quiet",
                    "surveillance.deterrentQuietSeconds", OverdriveUebernahme.Klasse.EINSTELLUNG, null),
            new OverdriveUebernahme.Regel("ausloeser", "waechter", "od_test.json", "surveillance.trigger",
                    "surveillance.triggerMode", OverdriveUebernahme.Klasse.EINSTELLUNG, null),     // Einwilligung → manuell
            new OverdriveUebernahme.Regel("hupe", "waechter", "od_test.json", "deterrent.horn",
                    "deterrent.horn", OverdriveUebernahme.Klasse.EINSTELLUNG, null),             // Gefahr → manuell
            new OverdriveUebernahme.Regel("wetter", "system", "od_test.json", "wetter.online",
                    "wetter.online", OverdriveUebernahme.Klasse.EINSTELLUNG, null),             // Freigabe (Regel 2) → manuell
            new OverdriveUebernahme.Regel("karte", "system", "od_test.json", "map.source",
                    "karte.quelle", OverdriveUebernahme.Klasse.EINSTELLUNG, null),              // Gruppe datenschutz → manuell
            new OverdriveUebernahme.Regel("bot", "telegram", "telegram_test.properties", "bot_token",
                    "telegram.botToken", OverdriveUebernahme.Klasse.GEHEIM, null),
            new OverdriveUebernahme.Regel("kaputt", "waechter", "od_test.json", "surveillance.keepDays",
                    "surveillance.keepDays", OverdriveUebernahme.Klasse.EINSTELLUNG, r -> null));   // unbrauchbar → entfällt

    private static final OverdriveUebernahme.Texte T = (k, r) -> r;

    private File daten() throws Exception {
        File d = tmp.newFolder("tmp");
        Files.write(new File(d, "od_test.json").toPath(), ("{\"surveillance\":{\"keepDays\":14,\"quiet\":120,\"trigger\":\"ai_everywhere\"},"
                + "\"deterrent\":{\"horn\":true},\"wetter\":{\"online\":true},\"map\":{\"source\":\"osm\"}}").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(d, "telegram_test.properties").toPath(), ("bot_token=" + GEHEIMNIS + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(d, "fremd.json").toPath(), "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        return d;
    }

    private static OverdriveQuelle.Befund befund(File d) {
        return OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(REGELN), true);
    }

    @Test
    public void erkennung_ohneDaten_ausgeblendet() throws Exception {
        File leer = tmp.newFolder("leer");
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(leer, OverdriveUebernahme.dateinamen(REGELN), false);
        assertFalse(b.vorhanden());
        assertEquals(OverdriveQuelle.NICHT_INSTALLIERT, b.grund);
        assertEquals(OverdriveQuelle.KEINE_DATEN, OverdriveQuelle.erkenne(leer, OverdriveUebernahme.dateinamen(REGELN), true).grund);
        JSONObject v = OverdriveUebernahme.vorschau(b, new JSONObject(), REGELN, T);
        assertFalse(v.getBoolean("vorhanden"));
        assertFalse(v.has("posten"));
    }

    @Test
    public void erkennung_nurBekannteFlacheNamen_zuGrossNichtLesbar() throws Exception {
        File d = daten();
        OverdriveQuelle.Befund b = befund(d);
        assertTrue(b.vorhanden());
        assertFalse(b.dateien.containsKey("fremd.json"));
        assertTrue(OverdriveQuelle.erkenne(d, Arrays.asList("../etc/passwd", "a/b.json"), true).grund != null);
        File gross = tmp.newFolder("gross");
        byte[] viel = new byte[(int) OverdriveQuelle.MAX_BYTES + 1];
        Files.write(new File(gross, "od_test.json").toPath(), viel);
        assertEquals(OverdriveQuelle.NICHT_LESBAR, OverdriveQuelle.erkenne(gross, Arrays.asList("od_test.json"), true).grund);
    }

    @Test
    public void vorschau_aktionen_einwilligungenNie_geheimMaskiert() throws Exception {
        File d = daten();
        JSONObject root = new JSONObject().put("surveillance", new JSONObject().put("deterrentQuietSeconds", 120));
        JSONObject v = OverdriveUebernahme.vorschau(befund(d), root, REGELN, T);
        assertTrue(v.getBoolean("vorhanden"));
        Map<String, JSONObject> p = nachId(v.getJSONArray("posten"));
        assertEquals("neu", p.get("aufbewahrung").getString("aktion"));
        assertEquals(14, p.get("aufbewahrung").getInt("overdrive"));
        assertEquals("gleich", p.get("ruhe").getString("aktion"));
        assertEquals("manuell", p.get("ausloeser").getString("aktion"));
        assertEquals("manuell", p.get("hupe").getString("aktion"));
        assertEquals("manuell", p.get("karte").getString("aktion"));
        assertEquals("manuell", p.get("wetter").getString("aktion"));
        assertEquals("neu_eingeben", p.get("bot").getString("aktion"));
        assertFalse(p.get("bot").optString("hinweis").isEmpty());
        assertEquals("vorhanden", p.get("bot").getString("overdrive"));
        assertFalse(p.containsKey("kaputt"));
        assertFalse("Zugangsdaten in der Vorschau", v.toString().contains("geheimer"));
        assertEquals(16, v.getString("vorschau_id").length());
    }

    @Test
    public void uebernehmen_nurBestaetigte_manuellAbgelehnt_overdriveUnveraendert() throws Exception {
        File d = daten();
        File od = new File(d, "od_test.json");
        byte[] vorher = Files.readAllBytes(od.toPath());
        long zeitVorher = od.lastModified();
        JSONObject root = new JSONObject();
        String id = OverdriveUebernahme.vorschau(befund(d), root, REGELN, T).getString("vorschau_id");

        Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        OverdriveUebernahme.Schreiber s = new OverdriveUebernahme.Schreiber() {
            public boolean werte(String a, Map<String, Object> w) { geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w); return true; }
        };
        JSONObject r = OverdriveUebernahme.uebernehmen(befund(d), root, REGELN, id,
                new JSONArray().put("aufbewahrung").put("hupe").put("bot").put("gibtsnicht"), s);
        assertEquals(200, r.getInt("status"));
        assertEquals("[\"aufbewahrung\"]", r.getJSONArray("uebernommen").toString());
        assertEquals("neu_eingeben", r.getJSONObject("fehler").getString("bot"));   // Zugangsdaten nie übernommen
        assertEquals("manuell", r.getJSONObject("fehler").getString("hupe"));
        assertEquals("unbekannt", r.getJSONObject("fehler").getString("gibtsnicht"));
        assertEquals(14, geschrieben.get("surveillance").get("keepDays"));
        assertFalse(geschrieben.containsKey("deterrent"));
        assertFalse(geschrieben.containsKey("surveillance") && geschrieben.get("surveillance").containsKey("deterrentAction"));
        assertFalse("Zugangsdaten nirgends geschrieben", geschrieben.toString().contains("geheimer"));
        // Overdrive-Datei nie angefasst
        assertTrue(Arrays.equals(vorher, Files.readAllBytes(od.toPath())));
        assertEquals(zeitVorher, od.lastModified());
    }

    @Test
    public void uebernehmen_veraltet_wennSichEtwasGeaendertHat() throws Exception {
        File d = daten();
        JSONObject root = new JSONObject();
        String id = OverdriveUebernahme.vorschau(befund(d), root, REGELN, T).getString("vorschau_id");
        JSONObject geaendert = new JSONObject().put("surveillance", new JSONObject().put("keepDays", 3));
        OverdriveUebernahme.Schreiber nie = new OverdriveUebernahme.Schreiber() {
            public boolean werte(String a, Map<String, Object> w) { throw new AssertionError("darf nicht schreiben"); }
            public boolean mqtt(JSONObject v) { throw new AssertionError("darf nicht schreiben"); }
        };
        JSONObject r = OverdriveUebernahme.uebernehmen(befund(d), geaendert, REGELN, id, new JSONArray().put("aufbewahrung"), nie);
        assertEquals(409, r.getInt("status"));
        assertEquals("veraltet", r.getString("grund"));
        assertEquals(409, OverdriveUebernahme.uebernehmen(befund(d), root, REGELN, null, new JSONArray(), nie).getInt("status"));
    }

    /** Echte Tabelle (OVERDRIVE_BESTAND.md): nur overdrive_config.json, keine Orts-/Verlaufsziele, Grenzen greifen. */
    @Test
    public void echteTabelle_ausBestand() throws Exception {
        List<OverdriveUebernahme.Regel> echt = OverdriveUebernahme.REGELN;
        assertEquals(new java.util.TreeSet<>(Arrays.asList("overdrive_config.json", "abrp_config.properties",
                "mqtt_connections.json", "overdrive_version")), new java.util.TreeSet<>(OverdriveUebernahme.dateinamen(echt)));
        for (OverdriveUebernahme.Regel r : echt) {
            if (r.klasse == OverdriveUebernahme.Klasse.GEHEIM) continue;   // nur „neu eingeben“, nie geschrieben
            String z = r.ziel.toLowerCase(java.util.Locale.ROOT);
            for (String verboten : new String[] {"lat", "lng", "lon", "geo", "gps", "zone", "token", "vapid", "telegram", "planner"}) {
                assertFalse(r.id + " → " + r.ziel, z.contains(verboten));
            }
        }
        File d = tmp.newFolder("od");
        Files.write(new File(d, "overdrive_config.json").toPath(), ("{\"surveillance\":{\"preRecordSeconds\":7,\"postRecordSeconds\":999,"
                + "\"detectPerson\":true,\"aiConfidence\":0.4,\"sensitivityLevel\":3,\"screenDeterrentEnabled\":true,"
                + "\"screenDeterrentMessage\":\"Kamera läuft\",\"deterrentAction\":\"flash_lights\",\"surveillanceEnabled\":true},"
                + "\"telegram\":{\"botToken\":\"geheim\"}}").getBytes(StandardCharsets.UTF_8));
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(echt), true);
        JSONObject root = new JSONObject().put("surveillance", new JSONObject().put("preRecordSeconds", 5).put("detectPerson", true));
        Map<String, JSONObject> p = nachId(OverdriveUebernahme.vorschau(b, root, echt, T).getJSONArray("posten"));
        assertEquals("ueberschreiben", p.get("vorlauf").getString("aktion"));
        assertFalse("außerhalb der Grenzen → entfällt", p.containsKey("nachlauf"));
        assertEquals("gleich", p.get("person").getString("aktion"));
        assertEquals("neu", p.get("anzeigeAbschreckung").getString("aktion"));
        assertEquals("manuell", p.get("abschreckungAktion").getString("aktion"));
        assertEquals("manuell", p.get("waechterAn").getString("aktion"));
        assertFalse(p.containsKey("kameraVorne"));   // nicht in der Datei → kein Posten
        assertEquals("neu_eingeben", p.get("telegramBot").getString("aktion"));
    }

    /** Stufe 1: ABRP (properties) und MQTT; Zugangsdaten nur „neu eingeben“, MQTT immer aus und ohne Steuerung. */
    @Test
    public void abrpUndMqtt_ohneZugangsdaten_ausgeschaltet() throws Exception {
        List<OverdriveUebernahme.Regel> echt = OverdriveUebernahme.REGELN;
        File d = tmp.newFolder("od2");
        Files.write(new File(d, "abrp_config.properties").toPath(),
                "user_token=abc-geheimer-abrp\nenabled=true\nmax_interval_seconds=60\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(d, "mqtt_connections.json").toPath(), ("[{\"id\":\"x1\",\"name\":\"Heim\",\"brokerUrl\":\"tcp://ha.local\",\"port\":1883,"
                + "\"topic\":\"auto\",\"username\":\"u-geheim\",\"password\":\"p-geheim\",\"enabled\":true,\"allowControl\":true,"
                + "\"qos\":1,\"homeAssistantDiscovery\":true},"
                + "{\"name\":\"ohne Port\",\"brokerUrl\":\"tcp://x\"},"
                + "{\"name\":\"schon da\",\"brokerUrl\":\"TCP://zweit.local\",\"port\":8883,\"topic\":\"t\"}]").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(d, "overdrive_version").toPath(), "56.0\n".getBytes(StandardCharsets.UTF_8));
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(echt), true);
        JSONArray panoptesMqtt = new JSONArray().put(new JSONObject().put("name", "Zweit").put("brokerUrl", "tcp://zweit.local").put("port", 8883).put("topic", "t"));
        JSONObject v = OverdriveUebernahme.vorschau(b, new JSONObject(), panoptesMqtt, echt, T);
        assertEquals("56.0", v.getString("overdriveVersion"));
        Map<String, JSONObject> p = nachId(v.getJSONArray("posten"));
        assertEquals("neu_eingeben", p.get("abrpSchluessel").getString("aktion"));
        assertEquals("manuell", p.get("abrpAn").getString("aktion"));
        assertEquals("neu", p.get("mqtt:1").getString("aktion"));
        assertTrue(p.get("mqtt:1").getString("titel").contains("Heim"));
        assertFalse("kaputte Verbindung entfällt", p.containsKey("mqtt:2"));
        assertEquals("gleich", p.get("mqtt:3").getString("aktion"));
        assertFalse("keine Zugangsdaten in der Vorschau", v.toString().contains("geheim"));

        List<JSONObject> angelegt = new java.util.ArrayList<>();
        OverdriveUebernahme.Schreiber s = new OverdriveUebernahme.Schreiber() {
            public boolean werte(String a, Map<String, Object> w) { return true; }
            public boolean mqtt(JSONObject c) { angelegt.add(c); return true; }
        };
        JSONObject r = OverdriveUebernahme.uebernehmen(b, new JSONObject(), panoptesMqtt, echt, v.getString("vorschau_id"),
                new JSONArray().put("mqtt:1").put("mqtt:3").put("abrpSchluessel").put("abrpAn"), s);
        assertEquals(1, angelegt.size());                                   // „gleich“ legt nichts doppelt an
        JSONObject c = angelegt.get(0);
        assertFalse(c.getBoolean("enabled"));
        assertFalse(c.getBoolean("allowControl"));
        assertEquals("", c.getString("username"));
        assertEquals("", c.getString("password"));
        assertEquals(1883, c.getInt("port"));
        assertTrue(c.getBoolean("homeAssistantDiscovery"));
        assertEquals("[\"mqtt:1\",\"mqtt:3\"]", r.getJSONArray("uebernommen").toString());
        assertEquals("neu_eingeben", r.getJSONObject("fehler").getString("abrpSchluessel"));
        assertEquals("manuell", r.getJSONObject("fehler").getString("abrpAn"));
    }

    /** Regel 1: andere Overdrive-Versionen – fehlende/fremde Schlüssel und Typen still überspringen, nichts erfinden. */
    @Test
    public void tolerant_gegenAndereVersionen() throws Exception {
        List<OverdriveUebernahme.Regel> echt = OverdriveUebernahme.REGELN;
        File d = tmp.newFolder("od3");
        Files.write(new File(d, "overdrive_config.json").toPath(), ("{\"version\":41,\"surveillance\":{\"preRecordSeconds\":\"5\","
                + "\"detectPerson\":\"ja\",\"neuesFeld\":1,\"cameraFront\":false},\"telegram\":{\"botToken\":\"\"},\"fremd\":[1,2]}").getBytes(StandardCharsets.UTF_8));
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(echt), true);
        JSONObject v = OverdriveUebernahme.vorschau(b, new JSONObject(), echt, T);
        assertTrue(v.getBoolean("vorhanden"));
        assertEquals("41", v.getString("overdriveVersion"));                // ohne Versionsdatei aus der Config
        Map<String, JSONObject> p = nachId(v.getJSONArray("posten"));
        assertEquals(1, p.size());                                          // nur cameraFront passt
        assertEquals("neu", p.get("kameraVorne").getString("aktion"));
        // kaputte Haupt-Config allein → nicht lesbar; nur die Versionsdatei → keine Daten
        File k = tmp.newFolder("od4");
        Files.write(new File(k, "overdrive_config.json").toPath(), "{kaputt".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(k, "overdrive_version").toPath(), "56.0".getBytes(StandardCharsets.UTF_8));
        JSONObject v2 = OverdriveUebernahme.vorschau(OverdriveQuelle.erkenne(k, OverdriveUebernahme.dateinamen(echt), true), new JSONObject(), echt, T);
        assertFalse(v2.getBoolean("vorhanden"));
        assertEquals("nicht_lesbar", v2.getString("grund"));
        File nurV = tmp.newFolder("od5");
        Files.write(new File(nurV, "overdrive_version").toPath(), "56.0".getBytes(StandardCharsets.UTF_8));
        assertEquals("keine_daten", OverdriveUebernahme.vorschau(OverdriveQuelle.erkenne(nurV, OverdriveUebernahme.dateinamen(echt), true),
                new JSONObject(), echt, T).getString("grund"));
    }

    /** TRIPKOSTEN_BEFUND: 0,01 €/kWh aus Overdrive nie übernehmen, nur „bitte prüfen“; Prüfung über PreisPlausibel. */
    @Test
    public void strompreis_unplausibelNieUebernommen() throws Exception {
        List<OverdriveUebernahme.Regel> echt = OverdriveUebernahme.REGELN;
        Map<String, JSONObject> p = preisPosten("{\"tripAnalytics\":{\"electricityRate\":0.01,\"currency\":\"€\"},"
                + "\"chargingAnalytics\":{\"dcRate\":0.59}}");
        assertEquals("manuell", p.get("strompreis").getString("aktion"));
        assertEquals("Bitte prüfen.", p.get("strompreis").getString("hinweis"));
        assertEquals(0.01, p.get("strompreis").getDouble("overdrive"), 1e-9);   // „in Overdrive war: 0,01“
        assertEquals("neu", p.get("strompreisDc").getString("aktion"));          // 0,59 € ist plausibel
        assertEquals("neu", p.get("waehrung").getString("aktion"));
        // ohne bzw. mit unbekannter Währung nicht prüfbar → auch nicht übernehmen
        assertEquals("manuell", preisPosten("{\"tripAnalytics\":{\"electricityRate\":0.30}}").get("strompreis").getString("aktion"));
        assertEquals("manuell", preisPosten("{\"tripAnalytics\":{\"electricityRate\":0.30,\"currency\":\"XYZ\"}}").get("strompreis").getString("aktion"));
        assertEquals("manuell", preisPosten("{\"tripAnalytics\":{\"electricityRate\":5.0,\"currency\":\"EUR\"}}").get("strompreis").getString("aktion"));
        assertEquals("neu", preisPosten("{\"tripAnalytics\":{\"electricityRate\":0.30,\"currency\":\"EUR\"}}").get("strompreis").getString("aktion"));
        // 0 aus Overdrive = nicht still „gratis“, sondern zur Prüfung zeigen (Kern 4)
        Map<String, JSONObject> null0 = preisPosten("{\"tripAnalytics\":{\"electricityRate\":0,\"currency\":\"€\"}}");
        assertEquals("manuell", null0.get("strompreis").getString("aktion"));
        assertEquals(0.0, null0.get("strompreis").getDouble("overdrive"), 0);
        // Übernahme lehnt den unplausiblen Preis ab
        File d = tmp.newFolder("preis");
        Files.write(new File(d, "overdrive_config.json").toPath(),
                "{\"tripAnalytics\":{\"electricityRate\":0.01,\"currency\":\"€\"}}".getBytes(StandardCharsets.UTF_8));
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(echt), true);
        String id = OverdriveUebernahme.vorschau(b, new JSONObject(), echt, T).getString("vorschau_id");
        Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        JSONObject r = OverdriveUebernahme.uebernehmen(b, new JSONObject(), echt, id, new JSONArray().put("strompreis").put("waehrung"),
                (a, w) -> { geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w); return true; });
        assertEquals("manuell", r.getJSONObject("fehler").getString("strompreis"));
        assertFalse(geschrieben.containsKey("tripAnalytics") && geschrieben.get("tripAnalytics").containsKey("electricityRate"));
        assertEquals("€", geschrieben.get("tripAnalytics").get("currency"));
    }

    private Map<String, JSONObject> preisPosten(String cfg) throws Exception {
        File d = tmp.newFolder();
        Files.write(new File(d, "overdrive_config.json").toPath(), cfg.getBytes(StandardCharsets.UTF_8));
        OverdriveQuelle.Befund b = OverdriveQuelle.erkenne(d, OverdriveUebernahme.dateinamen(OverdriveUebernahme.REGELN), true);
        return nachId(OverdriveUebernahme.vorschau(b, new JSONObject(), OverdriveUebernahme.REGELN, T).getJSONArray("posten"));
    }

    @Test
    public void titelInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            java.nio.file.Path p = java.nio.file.Paths.get("src/main/assets/server-i18n/" + l + ".json");
            if (!Files.exists(p)) p = java.nio.file.Paths.get("app").resolve(p);
            JSONObject u = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).getJSONObject("uebernahme");
            for (String k : new String[] {"vorhanden", "nichtGesetzt", "manuell", "neuEingeben", "mqttAus", "preisPruefen"}) assertFalse(l + ":" + k, u.optString(k).isEmpty());
            assertFalse(l + ": mqtt", u.getJSONObject("posten").optString("mqtt").isEmpty());
            for (OverdriveUebernahme.Regel r : OverdriveUebernahme.REGELN) {
                assertFalse(l + ": " + r.titelKey(), u.getJSONObject("posten").optString(r.id).isEmpty());
            }
        }
    }

    private static Map<String, JSONObject> nachId(JSONArray a) throws Exception {
        Map<String, JSONObject> m = new HashMap<>();
        for (int i = 0; i < a.length(); i++) m.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i));
        return m;
    }
}
