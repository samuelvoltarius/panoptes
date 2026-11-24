package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.1.43.20 (FIX_014320_CLIP, Projektinhaber: „Es hilft mir nichts, wenn ich nach einem Unfall nicht mehr drücken kann“):
 * Ein Ereignis-Clip nach starkem Bremsen oder Stoß ist sofort und ohne Rückfrage dauerhaft gesichert, auch wenn
 * niemand tippt und auch wenn die Zündung kurz danach ausgeht. Obergrenze: die ältesten gesicherten werden ersetzt.
 */
public class EreignisClipAutoTest {

    private static final long MB = 1024L * 1024L;
    private static final long H = 3_600_000L;
    private static final long S_US = 1_000_000L;

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/surfmate/" + p)),
                StandardCharsets.UTF_8);
    }

    private static String asset(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/assets/" + p)), StandardCharsets.UTF_8);
    }

    private static EreignisClip.Datei d(String hhmmss, long mb, long t) {
        return new EreignisClip.Datei("ereignis_20261124_" + hhmmss + ".mp4", mb * MB, t);
    }

    // ---------------------------------------------------------------- kein Tipp → gesichert, 30 Tage

    /**
     * Kein Tipp: der Clip ist ohne Frage gesichert und bleibt 30 Tage, weit über die alte 72-h-Frist hinaus (auch
     * ohne Behalten). Laufzeit: keine offene Frage, kein Fenster.
     */
    @Test
    public void keinTippClipGesichert() throws Exception {
        long gesichert = 10 * H;
        EreignisClip.Datei alt = d("090000", 75, gesichert - 2 * H);
        EreignisClip.Datei clip = d("143200", 75, gesichert);
        List<EreignisClip.Datei> l = new ArrayList<>();
        l.add(alt);
        l.add(clip);
        Set<String> nichts = Collections.emptySet();
        for (long spaeter : new long[] {gesichert + 73 * H, gesichert + 29 * 24 * H}) {
            assertTrue("nach " + (spaeter - gesichert) / H + " h",
                    EreignisClip.zuLoeschen(l, spaeter, EreignisClip.LOESCHEN_MS, nichts, EreignisClip.GRENZE_BYTES)
                            .isEmpty());
        }
        assertEquals(30, EreignisClip.LOESCHEN_TAGE);

        String mon = lies("recording/EreignisMonitor.java");
        int g = mon.indexOf("private void gesichert(");
        String block = mon.substring(g, mon.indexOf("private static void meldungAmDisplay", g));
        assertTrue(block.contains("Ereignis-Clip gesichert (ohne Rückfrage"));
        assertTrue(block.contains("EreignisAblage.pflege();"));
        assertFalse("keine offene Frage mehr", block.contains("frageMerken"));
        assertFalse("kein Fenster „Behalten?“", mon.contains("\"am\", \"start\", \"-n\", AKTIVITAET"));
        String ablage = lies("recording/EreignisAblage.java");
        assertTrue("eigene Frist 30 Tage statt 72 h", ablage.contains("long maxAlter = EreignisClip.LOESCHEN_MS;"));
        assertFalse(ablage.contains("Aufbewahrung.maxAlterMs(config)"));
    }

    /** Clip älter als 30 Tage und nicht markiert → weg; markiert („Behalten“ in der Clip-Liste) → bleibt. */
    @Test
    public void nach30TagenWegAusserMarkiert() {
        long t0 = 1_000 * H;
        EreignisClip.Datei markiert = d("080000", 75, t0);
        EreignisClip.Datei offen = d("090000", 75, t0 + H);
        EreignisClip.Datei neu = d("100000", 75, t0 + 40 * 24 * H);
        List<EreignisClip.Datei> l = new ArrayList<>();
        l.add(markiert);
        l.add(offen);
        l.add(neu);
        Set<String> behalten = new HashSet<>(Collections.singleton(EreignisClip.stamm(markiert.name)));
        long jetzt = t0 + 31 * 24 * H + H;
        List<EreignisClip.Datei> weg = EreignisClip.zuLoeschen(l, jetzt, EreignisClip.LOESCHEN_MS, behalten,
                EreignisClip.GRENZE_BYTES);
        assertEquals(1, weg.size());
        assertEquals(offen.name, weg.get(0).name);
        // auch ein Jahr später bleibt der markierte
        weg = EreignisClip.zuLoeschen(l, t0 + 365 * 24 * H, EreignisClip.LOESCHEN_MS, behalten, EreignisClip.GRENZE_BYTES);
        assertFalse(weg.contains(markiert));
        // genau an der Grenze noch da
        assertTrue(EreignisClip.zuLoeschen(java.util.Arrays.asList(offen, neu), offen.zeit + EreignisClip.LOESCHEN_MS,
                EreignisClip.LOESCHEN_MS, Collections.<String>emptySet(), EreignisClip.GRENZE_BYTES).isEmpty());
        // Resttage für die Liste
        assertEquals(30, EreignisClip.restTage(t0, t0));
        assertEquals(1, EreignisClip.restTage(t0, t0 + 29 * 24 * H + 1));
        assertEquals(0, EreignisClip.restTage(t0, t0 + 31 * 24 * H));
    }

    /** Markierte Ereignis-Clips: unbefristet, keine 30-Tage-Erinnerung; übrige Clips wie bisher mit Rückfrage. */
    @Test
    public void behaltenListeOhneErinnerungFuerEreignisse() throws Exception {
        long seit = 1_000_000L;
        org.json.JSONObject root = new org.json.JSONObject()
                .put("aufbewahrung", new org.json.JSONObject().put("behalten", new org.json.JSONObject()
                        .put("ereignis_20261124_143200", seit)
                        .put("event_20261124_143200", seit)));
        org.json.JSONArray l = at.greenchili.surfmate.storage.Aufbewahrung.liste(root, seit + 100L * 24 * H);
        assertEquals(2, l.length());
        for (int i = 0; i < l.length(); i++) {
            org.json.JSONObject o = l.getJSONObject(i);
            boolean ereignis = o.getString("datei").startsWith("ereignis_");
            assertEquals(o.toString(), ereignis, o.getBoolean("unbefristet"));
            assertEquals(o.toString(), !ereignis, o.getBoolean("rueckfrage"));
        }
        String api = lies("server/EinstellungenApiHandler.java");
        assertTrue(api.contains("res.put(\"ereignisTage\", at.greenchili.surfmate.recording.EreignisClip.LOESCHEN_TAGE);"));
    }

    @Test
    public void offeneFragenAltWerdenUebernommen() throws Exception {
        String ablage = lies("recording/EreignisAblage.java");
        assertTrue(ablage.contains("EreignisClip.frageErledigt(s)"));
        assertTrue("alte Fragen schützen nicht mehr", ablage.contains("Set<String> behalten = new HashSet<>();"));
    }

    @Test
    public void clipListeZeigtResttageInFuenfSprachen() throws Exception {
        String js = asset("web/shared/clip-keep.js");
        assertTrue(js.contains("t('events.keep_ereignis_rest', { tage: restTage(this.clip, this.ereignisTage) })"));
        assertTrue(js.contains("t('events.keep_ereignis_kept')"));
        assertTrue(js.contains("t('guardLegal.keep.body_ereignis', { tage: this.ereignisTage })"));
        assertTrue(asset("web/local/events.html").contains("clip-keep.js?v=pn8"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            org.json.JSONObject j = new org.json.JSONObject(asset("web/i18n/" + l + ".json"));
            org.json.JSONObject ev = j.getJSONObject("events");
            for (String k : new String[] {"keep_ereignis_rest", "keep_ereignis_kept", "keep_released_ereignis"}) {
                String s = ev.getString(k);
                assertFalse(l + k, s.isEmpty() || s.contains("Erprobung") || s.contains("Testversion"));
            }
            assertTrue(l, ev.getString("keep_ereignis_rest").contains("{tage}"));
            String body = j.getJSONObject("guardLegal").getJSONObject("keep").getString("body_ereignis");
            assertTrue(l, body.contains("{tage}"));
        }
        for (String d : new String[] {"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String x = new String(Files.readAllBytes(Paths.get("src/main/res/" + d + "/strings_ereignis.xml")),
                    StandardCharsets.UTF_8);
            int i = x.indexOf("name=\"ereignis_pflichttext_b\"");
            String z = x.substring(i, x.indexOf("</string>", i));
            assertTrue(d, z.contains("%1$d"));
            assertFalse(d, z.contains("Erprobung") || z.contains("Testversion"));
        }
    }

    @Test
    public void meldungFragtNichtMehr() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String j = asset("server-i18n/" + l + ".json");
            int i = j.indexOf("\"ereignis_body\"");
            String body = j.substring(i, j.indexOf('\n', i));
            assertFalse(l, body.contains("?"));
            assertFalse(l, body.contains("Erprobung") || body.contains("Testversion"));
        }
        assertFalse(lies("recording/EreignisMeldung.java").contains("Keep it?"));
        assertTrue(asset("server-i18n/de.json").contains("Das Video ist 30 Tage gesichert"));
    }

    // ---------------------------------------------------------------- Zündung aus 2 s nach dem Ereignis

    /**
     * Zündung aus 2 s nach dem Ereignis: der Nachlauf kommt nicht mehr an. Früher wurde dann der GANZE Clip
     * verworfen; jetzt wird bis zum letzten Bild gesichert (20 s davor + 2 s danach).
     */
    @Test
    public void zuendungAus2sNachEreignisClipGesichert() throws Exception {
        long ereignis = 1_000 * S_US;
        long start = ereignis - 20 * S_US;
        long gewuenscht = ereignis + 20 * S_US;
        long letztesBild = ereignis + 2 * S_US;
        assertEquals(letztesBild, EreignisHalt.ende(gewuenscht, letztesBild, start, S_US));
        assertEquals("voll angekommen", gewuenscht, EreignisHalt.ende(gewuenscht, gewuenscht + S_US, start, S_US));
        assertEquals("nichts im Ring", Long.MIN_VALUE, EreignisHalt.ende(gewuenscht, Long.MIN_VALUE, start, S_US));
        assertEquals("zu kurz", Long.MIN_VALUE, EreignisHalt.ende(gewuenscht, start + S_US / 2, start, S_US));

        // Abbau (Zündung aus → pipeline.stop) beendet den Nachlauf sofort und wartet, bis der Export fertig ist
        EreignisHalt halt = new EreignisHalt();
        assertTrue("ohne Ereignis-Clip sofort", halt.vorAbbauSichern(10));
        halt.beginnen();
        AtomicBoolean exportiert = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            // nachgestellter Export-Thread: sammelt Nachlauf (20 s), bis der Abbau „jetzt sichern“ meldet
            long bis = System.currentTimeMillis() + 20_000L;
            while (!halt.vorzeitig().get() && System.currentTimeMillis() < bis) {
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
            exportiert.set(halt.vorzeitig().get());
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }   // Export dauert etwas
            halt.beenden();
        });
        worker.start();
        Thread.sleep(2_000L);   // Zündung aus 2 s nach dem Ereignis
        long t0 = System.currentTimeMillis();
        assertTrue(halt.vorAbbauSichern(EreignisHalt.ABBAU_WARTEN_MS));
        worker.join(5_000L);
        assertTrue("Nachlauf vorzeitig beendet und gesichert", exportiert.get());
        assertFalse(halt.laeuft());
        assertTrue("Abbau wartet nur auf den Export", System.currentTimeMillis() - t0 < 4_000L);
        assertEquals(EreignisHalt.ABBAU_WARTEN_MS, ManualClipService.EREIGNIS_ABBAU_WARTEN_MS);

        // Verdrahtung: jeder Abbau der Kamera und das Abschalten der Aufnahme-Spur sichert vorher
        String pipe = lies("surveillance/GpuSurveillancePipeline.java");
        int stop = pipe.indexOf("public void stop() {");
        int hook = pipe.indexOf("ereignisVorAbbauSichern(\"pipeline.stop\")", stop);
        int sync = pipe.indexOf("synchronized (this)", stop);
        assertTrue("vor dem Monitor", stop > 0 && hook > stop && sync > hook);
        int lane = pipe.indexOf("public void setRecorderLaneEnabled(boolean enabled)");
        assertTrue(pipe.indexOf("ereignisVorAbbauSichern(\"recorderLane.aus\")", lane) - lane < 400);
        String mcs = lies("recording/ManualClipService.java");
        assertTrue(mcs.contains("if (vorzeitig != null && vorzeitig.get()) return false;"));
        assertTrue(mcs.contains("ereignisEnde(endPtsUs, requestedEncoder.getLatestPreRecordPtsUs(), startPtsUs,"));
        assertTrue(mcs.contains("if (ereignis != null) ereignisHalt.beginnen();"));
        assertTrue(mcs.contains("ereignisHalt.beenden();"));
    }

    // ---------------------------------------------------------------- Obergrenze

    @Test
    public void obergrenzeErsetztAeltesteGesicherte() {
        long jetzt = 100 * H;
        List<EreignisClip.Datei> l = new ArrayList<>();
        Set<String> behalten = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            EreignisClip.Datei x = d(String.format("%06d", i), 75, jetzt - (30 - i) * 60_000L);
            l.add(x);
            behalten.add(EreignisClip.stamm(x.name));
        }
        List<EreignisClip.Datei> weg = EreignisClip.zuLoeschen(l, jetzt, 72 * H, behalten, EreignisClip.GRENZE_BYTES);
        assertEquals(3, weg.size());   // 30 × 75 MB = 2250 > 2048 → die drei ältesten
        assertEquals("ereignis_20261124_000000.mp4", weg.get(0).name);
        // nicht behaltene gehen vor behaltenen
        Set<String> teil = new HashSet<>(behalten);
        teil.remove("ereignis_20261124_000010");
        weg = EreignisClip.zuLoeschen(l, jetzt, 0, teil, EreignisClip.GRENZE_BYTES);
        assertEquals("ereignis_20261124_000010.mp4", weg.get(0).name);
        // der neueste nie, auch wenn er allein über der Grenze liegt
        List<EreignisClip.Datei> riesig = new ArrayList<>();
        riesig.add(d("000001", 3000, 1));
        assertTrue(EreignisClip.zuLoeschen(riesig, jetzt, 0, behalten, EreignisClip.GRENZE_BYTES).isEmpty());
    }

    @Test
    public void grenzeNachDatentraeger() {
        assertEquals(EreignisClip.GRENZE_BYTES, EreignisClip.grenze(0));
        assertEquals(EreignisClip.GRENZE_BYTES, EreignisClip.grenze(64L * 1024 * MB));
        assertEquals(8L * 1024 * MB / 100 * 20, EreignisClip.grenze(8L * 1024 * MB));
        assertEquals(EreignisClip.MIN_GRENZE_BYTES, EreignisClip.grenze(1024 * MB));
    }

    @Test
    public void ersetzenWirdGeloggtUndBehaltenFreigegeben() throws Exception {
        String ablage = lies("recording/EreignisAblage.java");
        assertTrue(ablage.contains("ältesten gesicherten Ereignis-Clip ersetzt"));
        assertTrue(ablage.contains("Aufbewahrung.freigeben(stamm)"));
        assertTrue(ablage.contains("EreignisClip.grenze(gesamt)"));
    }
}
