package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/** 0.1.19 Panoptes-Wallbox-Brücke über MQTT (Vertrag WALLBOX_BRUECKE_MQTT.md v1). */
public class WallboxBrueckeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String ID = "bf4839172db96045cdaeg0";
    final AtomicLong jetzt = new AtomicLong(1_793_100_000_000L);

    /** Nachgebauter Broker mit Brücke: retained zustand/verfuegbar, befehl → quittung. */
    final class Broker implements OpenWbAdapter.Mqtt {
        final Map<String, String> retained = new HashMap<>();
        final List<String> befehle = new ArrayList<>();
        final List<String> zugang = new ArrayList<>();
        Function<JSONObject, JSONObject> quittung = b -> {
            try {
                return new JSONObject().put("kennung", b.getString("kennung")).put("ok", true).put("grund", JSONObject.NULL);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        Broker zustand(boolean erlaubt, long zeit) throws Exception {
            retained.put("wallbox/" + ID + "/zustand", new JSONObject().put("v", 1).put("id", ID).put("name", "Wallbox")
                    .put("modell", "dé EV Charger").put("erreichbar", true).put("zeit_ms", zeit).put("steuern_erlaubt", erlaubt)
                    .put("status", "charging").put("laedt", true).put("angesteckt", true).put("leistung_kw", 4.2)
                    .put("strom_soll_a", 6).put("strom_max_a", 16)
                    .put("strom_stufen_a", new JSONArray().put(6).put(8).put(10).put(13).put(16)).toString());
            retained.put("wallbox/" + ID + "/verfuegbar", "online");
            return this;
        }

        @Override
        public Map<String, String> lies(String host, int port, String b, String p, List<String> topics,
                                        Predicate<Map<String, String>> fertig, long wartenMs) {
            zugang.add(host + ":" + port + " " + b + "/" + p);
            Map<String, String> aus = new HashMap<>();
            for (String t : topics) {
                if (t.equals("wallbox/+/zustand")) {
                    for (Map.Entry<String, String> e : retained.entrySet()) if (e.getKey().endsWith("/zustand")) aus.put(e.getKey(), e.getValue());
                } else if (retained.containsKey(t)) {
                    aus.put(t, retained.get(t));
                }
            }
            return aus;
        }

        @Override
        public void sende(String host, int port, String b, String p, String topic, String wert) {
            fail("die Brücke bekommt Befehle nur mit Quittung (frage)");
        }

        @Override
        public String frage(String host, int port, String b, String p, String antwortTopic, String sendeTopic, String wert,
                            Predicate<String> passt, long wartenMs) throws WallboxFehler {
            assertEquals("wallbox/" + ID + "/befehl", sendeTopic);
            assertEquals("wallbox/" + ID + "/quittung", antwortTopic);
            assertEquals(BrueckeAdapter.QUITTUNG_MS, wartenMs);
            befehle.add(wert);
            try {
                JSONObject q = quittung.apply(new JSONObject(wert));
                if (q == null) return null;
                // fremde Quittung zuerst: darf nicht zählen
                assertFalse(passt.test(new JSONObject().put("kennung", "fremd").put("ok", true).toString()));
                return passt.test(q.toString()) ? q.toString() : null;
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private BrueckeAdapter adapter(Broker b) throws Exception {
        return new BrueckeAdapter("100.64.0.20", 1883, ID, "", "", b, jetzt::get);
    }

    @Test
    public void liestZustandImVertragsformat() throws Exception {
        Broker b = new Broker().zustand(true, jetzt.get() - 5_000);
        JSONObject o = adapter(b).lies();
        assertTrue(o.getBoolean("laedt"));
        assertTrue(o.getBoolean("verbunden"));
        assertEquals(4200, o.getLong("leistungW"));
        assertEquals(6, o.getInt("stromA"));
        assertEquals(WallboxQuelle.MODUS_SOFORT, o.getString("modus"));
        JSONObject strom = o.getJSONObject("faehigkeiten").getJSONObject("strom");
        assertEquals(6, strom.getInt("min"));
        assertEquals(16, strom.getInt("max"));
        assertFalse(o.has("hinweis"));
        assertEquals("gesperrt → Hinweis", "bruecke_gesperrt", adapter(new Broker().zustand(false, jetzt.get())).lies().getString("hinweis"));
    }

    @Test
    public void veraltetOfflineOderFremdesSchemaIstNichtErreichbar() throws Exception {
        assertCode("nicht_erreichbar", new Broker().zustand(true, jetzt.get() - 61_000));
        Broker off = new Broker().zustand(true, jetzt.get());
        off.retained.put("wallbox/" + ID + "/verfuegbar", "offline");
        assertCode("nicht_erreichbar", off);
        assertCode("nicht_erreichbar", new Broker());
        Broker v2 = new Broker().zustand(true, jetzt.get());
        v2.retained.put("wallbox/" + ID + "/zustand", "{\"v\":2,\"erreichbar\":true}");
        assertCode("nicht_unterstuetzt", v2);
    }

    private void assertCode(String code, Broker b) throws Exception {
        try {
            adapter(b).lies();
            fail(code);
        } catch (WallboxFehler f) {
            assertEquals(code, f.code);
        }
    }

    @Test
    public void befehleMitKennungUndQuittung() throws Exception {
        Broker b = new Broker().zustand(true, jetzt.get());
        BrueckeAdapter a = adapter(b);
        a.setze("strom", 10);
        JSONObject c = new JSONObject(b.befehle.get(0));
        assertEquals(10, c.getInt("strom"));
        assertTrue(c.getString("kennung").startsWith("panoptes-"));
        assertEquals(jetzt.get(), c.getLong("zeit_ms"));
        assertFalse(c.has("laden"));
        a.setze("modus", WallboxQuelle.MODUS_AUS);
        assertEquals("stop", new JSONObject(b.befehle.get(1)).getString("laden"));
        a.setze("modus", WallboxQuelle.MODUS_SOFORT);
        assertEquals("start", new JSONObject(b.befehle.get(2)).getString("laden"));
        // nicht in strom_stufen_a → gar kein Befehl
        try {
            a.setze("strom", 9);
            fail();
        } catch (WallboxFehler f) {
            assertEquals("wert_ungueltig", f.code);
        }
        assertEquals(3, b.befehle.size());
    }

    @Test
    public void abgelehntOderOhneQuittungIstFehlschlag() throws Exception {
        Broker b = new Broker().zustand(true, jetzt.get());
        b.quittung = x -> {
            try {
                return new JSONObject().put("kennung", x.getString("kennung")).put("ok", false).put("grund", "gesperrt");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            adapter(b).setze("modus", WallboxQuelle.MODUS_SOFORT);
            fail();
        } catch (WallboxFehler f) {
            assertEquals("bruecke_gesperrt", f.code);
        }
        b.quittung = x -> null;
        try {
            adapter(b).setze("modus", WallboxQuelle.MODUS_SOFORT);
            fail();
        } catch (WallboxFehler f) {
            assertEquals("bruecke_keine_quittung", f.code);
        }
        assertEquals("bruecke_abgelehnt", BrueckeAdapter.fehlercode("nicht_uebernommen"));
        assertEquals("bruecke_nicht_angesteckt", BrueckeAdapter.fehlercode("nicht_angesteckt"));
    }

    @Test
    public void findenUndUebernehmenOhneGeheimnisImVorschlag() throws Exception {
        Broker b = new Broker().zustand(false, jetzt.get());
        JSONObject broker = new JSONObject().put("host", "100.64.0.20").put("port", 1883).put("benutzer", "panoptes")
                .put("passwort", "nur-im-daemon");
        JSONObject r = new WallboxSuche(new WallboxSucheTestNetz(), (m, u, k, x) -> new WallboxHttp.Antwort(404, ""))
                .mitBruecke(Collections.singletonList(broker), b).suche();
        JSONArray t = r.getJSONArray("treffer");
        assertEquals(1, t.length());
        JSONObject x = t.getJSONObject(0);
        assertEquals("bruecke", x.getString("art"));
        assertEquals("bruecke_gesperrt", x.getString("hinweis"));
        assertFalse("kein Passwort an den Browser", x.toString().contains("nur-im-daemon"));
        assertEquals("100.64.0.20:1883 panoptes/nur-im-daemon", b.zugang.get(0));
        JSONObject k = WallboxKonfig.uebernimm(WallboxKonfig.leer(), x.getJSONObject("vorschlag"), WallboxTest.GEHEIM, 0);
        assertEquals(ID, k.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).getString("geraeteId"));
        // ungültige Topics bzw. Schema
        assertNull(WallboxSuche.brueckeTreffer("wallbox/zu-kurz/zustand", "{\"v\":1}", broker));
        assertNull(WallboxSuche.brueckeTreffer("wallbox/" + ID + "/zustand", "{\"v\":2}", broker));
        assertNull(WallboxSuche.brueckeTreffer("wallbox/" + ID + "/zustand", "kein json", broker));
    }

    @Test
    public void dienstNutztDenPanoptesZugangZumBroker() throws Exception {
        Broker b = new Broker().zustand(true, jetzt.get());
        JSONObject zugang = new JSONObject().put("host", "100.64.0.20").put("port", 1883).put("benutzer", "panoptes")
                .put("passwort", "pw");
        WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "w.json"), new File(tmp.getRoot(), "p.jsonl"),
                WallboxTest.GEHEIM, (m, u, k, x) -> new WallboxHttp.Antwort(404, ""), WallboxTest.DNS, () -> "steht",
                jetzt::get, ms -> { })
                .mitOpenWb(b)
                .mitBroker(() -> Collections.singletonList(zugang));
        JSONObject v = new JSONObject().put("quelle", "direkt").put("direkt", new JSONObject().put("geraete", new JSONArray()
                .put(new JSONObject().put("schluessel", "lp1").put("titel", "Wallbox").put("adapter", "bruecke")
                        .put("adresse", "100.64.0.20").put("port", 1883).put("geraeteId", ID))));
        assertEquals(200, d.speichereEinstellungen(v).status);
        JSONObject s = d.status().json;
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        assertEquals("100.64.0.20:1883 panoptes/pw", b.zugang.get(b.zugang.size() - 1));
        // falsche Geräte-ID wird abgelehnt
        v.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).put("geraeteId", "X!");
        assertEquals(400, d.speichereEinstellungen(v).status);
    }

    @Test
    public void ohneEinrichtungWirdDieBrueckeVonSelbstAngeboten() throws Exception {
        Broker b = new Broker().zustand(true, jetzt.get());
        JSONObject zugang = new JSONObject().put("host", "100.64.0.20").put("port", 1883);
        WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "w.json"), new File(tmp.getRoot(), "p.jsonl"),
                WallboxTest.GEHEIM, (m, u, k, x) -> new WallboxHttp.Antwort(404, ""), WallboxTest.DNS, () -> "steht",
                jetzt::get, ms -> { })
                .mitOpenWb(b).mitBroker(() -> Collections.singletonList(zugang));
        JSONObject s = d.status().json;
        assertEquals("bruecke", s.getJSONArray("gefunden").getJSONObject(0).getString("art"));
        int n = b.zugang.size();
        d.status();
        assertEquals("höchstens einmal je Minute", n, b.zugang.size());
        jetzt.addAndGet(WallboxDienst.GEFUNDEN_MS);
        d.status();
        assertEquals(n + 1, b.zugang.size());
        assertFalse("nichts gespeichert", new File(tmp.getRoot(), "w.json").exists());
    }

    /** Leeres WLAN für die Suche (nur die Brücke soll gefunden werden). */
    static final class WallboxSucheTestNetz implements WallboxSuche.Netz {
        public List<WallboxSuche.Paket> lausche(int port, long ms) { return Collections.emptyList(); }
        public List<WallboxSuche.Paket> rufe(byte[] d, int z, int e, long ms) { return Collections.emptyList(); }
        public List<WallboxSuche.Paket> mdns(byte[] q, long ms) { return Collections.emptyList(); }
        public List<String> nachbarn() { return Collections.emptyList(); }
        public boolean tcpOffen(String ip, int port, int ms) { return false; }
    }
}
