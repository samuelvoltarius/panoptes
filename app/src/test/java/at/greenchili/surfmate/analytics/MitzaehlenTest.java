package at.greenchili.surfmate.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 0.1.17 „Anonym mitzählen“ (27.10.2026): Standard aus, einmal am Tag, nur Kennung + Version, still. */
public class MitzaehlenTest {

    private static final long TAG = 86_400_000L;
    private static final long T0 = 1_793_000_000_000L;   // 2026-10-27 (UTC)

    private final JSONObject zustand = new JSONObject();
    private final List<JSONObject> gesendet = new ArrayList<>();
    private int antwort = 204;
    private boolean ohneNetz;

    private final Mitzaehlen.Speicher speicher = new Mitzaehlen.Speicher() {
        public JSONObject lese() {
            try {
                return new JSONObject(zustand.toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public void schreibe(JSONObject teil) {
            try {
                for (Iterator<String> it = teil.keys(); it.hasNext(); ) {
                    String k = it.next();
                    zustand.put(k, teil.get(k));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

    private final Mitzaehlen.Sender sender = (url, json) -> {
        if (ohneNetz) throw new java.net.UnknownHostException("byd.serveone.at");
        assertEquals(Mitzaehlen.STANDARD_URL, url);
        gesendet.add(new JSONObject(json));
        return antwort;
    };

    private void takt(long ms) {
        Mitzaehlen.takt(ms, "0.1.17", speicher, sender);
    }

    @Test
    public void standardAusSendetNichtsUndErzeugtKeineKennung() {
        takt(T0);
        assertTrue(gesendet.isEmpty());
        assertFalse(zustand.has("kennung"));
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "zaehlen.an");
        assertEquals(Boolean.FALSE, f.standard);
        assertEquals("datenschutz", f.gruppe);
    }

    @Test
    public void einmalAmTagNurKennungUndVersion() throws Exception {
        zustand.put("an", true);
        takt(T0);
        takt(T0 + 3_600_000L);
        assertEquals("höchstens einmal am Tag", 1, gesendet.size());
        JSONObject p = gesendet.get(0);
        assertEquals("genau id und version", 2, p.length());
        assertEquals("0.1.17", p.getString("version"));
        String id = zustand.getString("kennung");
        assertEquals(id, p.getString("id"));
        assertTrue("Zufalls-UUID", id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        takt(T0 + TAG);
        assertEquals(2, gesendet.size());
        assertEquals("Kennung bleibt", id, gesendet.get(1).getString("id"));
    }

    @Test
    public void ohneNetzOderFehlerStillUndSpaeterNochmal() throws Exception {
        zustand.put("an", true);
        ohneNetz = true;
        takt(T0);
        assertFalse("Tag erst nach Erfolg", zustand.optString("letzterTag").length() > 0);
        ohneNetz = false;
        antwort = 503;
        takt(T0 + 60_000L);
        assertEquals(1, gesendet.size());
        assertFalse(zustand.optString("letzterTag").length() > 0);
        antwort = 204;
        takt(T0 + 120_000L);
        assertEquals(2, gesendet.size());
        assertEquals(Mitzaehlen.tag(T0), zustand.getString("letzterTag"));
    }

    @Test
    public void versionNurImServerFormat() throws Exception {
        zustand.put("an", true);
        Mitzaehlen.takt(T0, "Manually Installed", speicher, sender);
        assertFalse(gesendet.get(0).has("version"));
    }

    /** 0.1.24: Update am selben UTC-Tag → ein weiterer Ping mit der neuen Version (Alfreds Auto meldete noch 0.1.21). */
    @Test
    public void neueVersionAmSelbenTagEinWeitererPing() throws Exception {
        zustand.put("an", true);
        Mitzaehlen.takt(T0, "0.1.21", speicher, sender);
        Mitzaehlen.takt(T0 + 3_600_000L, "0.1.21", speicher, sender);
        assertEquals(1, gesendet.size());
        assertEquals("0.1.21", zustand.getString("letzteVersion"));
        Mitzaehlen.takt(T0 + 7_200_000L, "0.1.23", speicher, sender);
        assertEquals("Update → sofort ein Ping", 2, gesendet.size());
        JSONObject p = gesendet.get(1);
        assertEquals("gleiche Regeln: nur id + version", 2, p.length());
        assertEquals("0.1.23", p.getString("version"));
        assertEquals(gesendet.get(0).getString("id"), p.getString("id"));
        Mitzaehlen.takt(T0 + 10_800_000L, "0.1.23", speicher, sender);
        assertEquals("danach wieder höchstens einmal am Tag", 2, gesendet.size());
        // Bestand ohne letzteVersion (vor 0.1.24): einmal nachmelden, dann Ruhe
        zustand.remove("letzteVersion");
        Mitzaehlen.takt(T0 + 14_400_000L, "0.1.24", speicher, sender);
        Mitzaehlen.takt(T0 + 18_000_000L, "0.1.24", speicher, sender);
        assertEquals(3, gesendet.size());
        // fehlgeschlagen → im nächsten Takt nochmal; unlesbare Version zählt nicht als neu
        antwort = 503;
        Mitzaehlen.takt(T0 + 21_600_000L, "0.1.25", speicher, sender);
        assertEquals("0.1.24", zustand.getString("letzteVersion"));
        antwort = 204;
        Mitzaehlen.takt(T0 + 25_200_000L, "0.1.25", speicher, sender);
        assertEquals("0.1.25", zustand.getString("letzteVersion"));
        int n = gesendet.size();
        Mitzaehlen.takt(T0 + 28_800_000L, "Manually Installed", speicher, sender);
        assertEquals(n, gesendet.size());
    }

    @Test
    public void ueberlastWiederholtNichtImSelbenTakt() throws Exception {
        zustand.put("an", true);
        antwort = 429;
        takt(T0);
        assertEquals("kein Wiederholen in der Schleife", 1, gesendet.size());
        assertFalse(zustand.optString("letzterTag").length() > 0);
        antwort = 400;   // ungültig: heute nicht nochmal
        takt(T0 + 3_600_000L);
        takt(T0 + 7_200_000L);
        assertEquals(2, gesendet.size());
        assertTrue(Mitzaehlen.STANDARD_URL.endsWith("byd.serveone.at/panoptes/ping"));
        String m = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/analytics/Mitzaehlen.java"));
        assertTrue("Timeout höchstens 10 s", m.contains(".callTimeout(10, TimeUnit.SECONDS)"));
    }

    @Test
    public void ausschaltenLoeschtAmServer() throws Exception {
        zustand.put("an", true);
        takt(T0);
        String id = zustand.getString("kennung");
        zustand.put("an", false);
        ohneNetz = true;
        takt(T0 + 60_000L);
        assertEquals("", zustand.getString("kennung"));
        assertEquals("Löschauftrag bleibt ohne Netz", id, zustand.getString("loeschen"));
        ohneNetz = false;
        takt(T0 + 120_000L);
        JSONObject l = gesendet.get(gesendet.size() - 1);
        assertEquals(id, l.getString("id"));
        assertTrue(l.getBoolean("loeschen"));
        assertEquals(2, l.length());
        assertEquals("", zustand.getString("loeschen"));
        int n = gesendet.size();
        takt(T0 + 180_000L);
        assertEquals("danach nichts mehr", n, gesendet.size());
    }

    @Test
    public void kennungNeuUndLoeschenUeberDieEinstellung() throws Exception {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "zaehlen.kennungAktion");
        assertEquals("behalten", f.zugriff.lese(null));
        JSONObject root = new JSONObject().put("zaehlen", new JSONObject().put("an", true).put("kennung", "alt-1"));

        Map<String, Object> neu = f.zugriff.schreibe("neu", root).get("zaehlen");
        assertEquals("alt-1", neu.get("loeschen"));
        assertTrue(((String) neu.get("kennung")).length() == 36);
        assertFalse(neu.containsKey("an"));

        Map<String, Object> weg = f.zugriff.schreibe("loeschen", root).get("zaehlen");
        assertEquals("", weg.get("kennung"));
        assertEquals(Boolean.FALSE, weg.get("an"));
        assertEquals("alt-1", weg.get("loeschen"));

        // aus: „neu“ legt keine Kennung auf Vorrat an
        JSONObject aus = new JSONObject().put("zaehlen", new JSONObject().put("an", false).put("kennung", "alt-2"));
        assertEquals("", f.zugriff.schreibe("neu", aus).get("zaehlen").get("kennung"));

        // nach „neu“ zählt der Takt mit der neuen Kennung und löscht die alte
        zustand.put("an", true).put("kennung", neu.get("kennung")).put("loeschen", neu.get("loeschen"));
        takt(T0);
        assertEquals("alt-1", gesendet.get(0).getString("id"));
        assertTrue(gesendet.get(0).getBoolean("loeschen"));
        assertEquals(neu.get("kennung"), gesendet.get(1).getString("id"));
    }

    @Test
    public void loeschlisteOhneDoppelteUndBegrenzt() {
        String l = "";
        for (int i = 0; i < 8; i++) l = Mitzaehlen.loeschenDazu(l, "k" + i);
        l = Mitzaehlen.loeschenDazu(l, "k7");
        assertEquals("k3,k4,k5,k6,k7", l);
    }

    @Test
    public void einrichtenUndKopplung() throws Exception {
        assertTrue("Kopplung darf nicht einschalten", Katalog.finde(Katalog.felder(new Katalog.Kontext()), "zaehlen.an").nurLesendExtern);
        assertTrue(Katalog.finde(Katalog.felder(new Katalog.Kontext()), "zaehlen.kennung").nurLesend);
        assertFalse(ExternGuard.erlaubt("POST", "/api/zaehlen"));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(d.contains("Mitzaehlen.taktImDaemon(System.currentTimeMillis())"));
        assertFalse("alter Pinger weg", d.contains("AnalyticsPinger"));
        String m = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/analytics/Mitzaehlen.java"));
        assertFalse("kein Fehlerbericht", m.contains("logger") || m.contains("Log."));
    }
}
