package at.greenchili.surfmate.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Energie;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** Koordination 0.1.9 (#7): Software-Drossel, fps-Felder, Leerlauf sofort beim Parken. */
public class FpsDrosselTest {

    static final long MS = 1_000_000L;

    /** Lässt den HAL {@code halFps} liefern und zählt, wie viele Frames die Drossel in {@code sekunden} durchlässt. */
    static int durchgelassen(FpsDrossel d, int halFps, int ziel, Boolean setzbar, int sekunden) {
        long schritt = 1000 * MS / halFps;
        int n = 0;
        for (long t = 0; t < sekunden * 1000 * MS; t += schritt) {
            if (d.annehmen(t, ziel, setzbar)) n++;
        }
        return n;
    }

    @Test
    public void halLehntAbDrosseltAufZiel() {
        FpsDrossel d = new FpsDrossel();
        int n = durchgelassen(d, 30, 5, Boolean.FALSE, 20);
        assertEquals(100, n, 2);                     // 5 fps × 20 s
        assertTrue(d.aktiv());
        assertEquals(30.0, d.gemessenFps(), 0.5);    // geliefert wird weiter gemessen
    }

    @Test
    public void rasterHaeltDenMittelwert() {
        // 25 → 10 fps: ohne festes Raster käme nur jeder 3. Frame durch (8,3 fps)
        FpsDrossel d = new FpsDrossel();
        assertEquals(200, durchgelassen(d, 25, 10, Boolean.FALSE, 20), 2);
    }

    @Test
    public void halSetzbarUndPasstVerwirftNichts() {
        FpsDrossel d = new FpsDrossel();
        assertEquals(300, durchgelassen(d, 15, 15, Boolean.TRUE, 20), 1);
        assertFalse(d.aktiv());
        assertEquals(0, d.verworfen());
    }

    @Test
    public void halSetzbarAberZuSchnellWirdGemessenUndGedrosselt() {
        // setCameraFps meldete true, der HAL liefert trotzdem 30: nach dem ersten Messfenster greift die Drossel
        FpsDrossel d = new FpsDrossel();
        int n = durchgelassen(d, 30, 5, Boolean.TRUE, 20);
        assertTrue(n < 30 * 2 + 5 * 18 + 5);
        assertTrue(d.aktiv());
    }

    @Test
    public void halLiefertWenigerAlsZielVerwirftNie() {
        // DiLink 4: HAL fest ~4,5 fps, lehnt setCameraFps ab, Ziel 15 → nichts wegwerfen
        FpsDrossel d = new FpsDrossel();
        long schritt = 222 * MS;
        int n = 0, gesamt = 0;
        for (long t = 0; t < 20_000 * MS; t += schritt) {
            gesamt++;
            if (d.annehmen(t, 15, Boolean.FALSE)) n++;
        }
        assertEquals(gesamt, n);
    }

    @Test
    public void unbekannterHalNurNachMessung() {
        FpsDrossel d = new FpsDrossel();
        // erste 2 s: noch keine Messung → alles durch
        int frueh = durchgelassen(d, 30, 5, null, 1);
        assertEquals(0, d.verworfen());
        assertEquals(d.angenommen(), frueh);
    }

    @Test
    public void keinFrameNimmtZaehlungZurueck() {
        FpsDrossel d = new FpsDrossel();
        d.annehmen(0, 5, Boolean.FALSE);
        assertFalse(d.annehmen(10 * MS, 5, Boolean.FALSE));
        assertEquals(1, d.verworfen());
        d.keinFrame();
        assertEquals(0, d.verworfen());
    }

    // ------------------------------------------------------------- Felder kamera.fpsRuhe / fpsBewegung

    @Test
    public void felderGewinnenGegenProfil() throws Exception {
        JSONObject leer = new JSONObject();
        assertEquals(5, Energie.kameraLeerlaufFps(leer));                // Profil normal: wie bisher
        assertFalse(Energie.kameraDrossel(leer));                         // Bestand: keine Drossel
        assertEquals(15, Energie.kameraBewegungFps(leer, 15));

        JSONObject gesetzt = new JSONObject().put("kamera",
                new JSONObject().put("fpsRuhe", "2").put("fpsBewegung", 10));
        assertEquals(2, Energie.kameraLeerlaufFps(gesetzt));
        assertTrue(Energie.kameraDrossel(gesetzt));                       // Ruhe gewählt → Drossel an
        assertEquals(10, Energie.kameraBewegungFps(gesetzt, 15));

        JSONObject ungueltig = new JSONObject().put("kamera", new JSONObject().put("fpsRuhe", 7));
        assertEquals(-1, Energie.kameraWahl(ungueltig, "fpsRuhe"));
        assertEquals(5, Energie.kameraLeerlaufFps(ungueltig));
    }

    @Test
    public void schemaMitSperreNurWennHalNachweislichNichtLiefert() {
        Katalog.Kontext k = new Katalog.Kontext();
        Feld ruhe = null, bewegung = null;
        for (Feld f : Katalog.felder(k)) {
            if (f.key.equals("kamera.fpsRuhe")) ruhe = f;
            if (f.key.equals("kamera.fpsBewegung")) bewegung = f;
        }
        assertTrue(ruhe != null && bewegung != null);
        assertEquals("waechter", ruhe.gruppe);
        assertEquals(null, ruhe.standard);
        assertEquals(4, bewegung.optionen.size());
        for (Feld.Option o : bewegung.optionen) assertFalse(o.gesperrt);

        k.kameraHalMaxFps = 5;   // z. B. DiLink 4 fest ~4,5 fps
        for (Feld f : Katalog.felder(k)) {
            if (!f.key.equals("kamera.fpsBewegung")) continue;
            for (Feld.Option o : f.optionen) {
                boolean hoch = Integer.parseInt(o.wert) > 6;
                assertEquals(o.wert, hoch, o.gesperrt);
                if (hoch) assertEquals("settings.kamera.fps.gesperrtHal", o.gesperrtGrundKey);
            }
        }
    }

    // ------------------------------------------------------------- Verdrahtung

    @Test
    public void drosselSitztVorDerBindung() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/camera/PanoramicCameraGpu.java"));
        int drossel = s.indexOf("verwerfeImageReaderFrame();\n                    return;");
        if (drossel < 0) drossel = s.indexOf("verwerfeImageReaderFrame();\r\n                    return;");
        int bind = s.indexOf("if (!consumeLatestImageAndBind()) {", s.indexOf("private void renderLoop()"));
        assertTrue(drossel > 0 && drossel < bind);
        assertTrue(s.contains("halFpsSetzbar = AvmCameraHelper.setCameraFps(cam, fps);"));
        assertTrue(s.contains("halFpsSetzbar = AvmCameraHelper.setCameraFps(cameraObj, targetFps);"));
        String api = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java"));
        assertTrue(api.contains("KameraFpsStatus.alsJson()"));
    }

    @Test
    public void leerlaufSofortBeimParken() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java"));
        int en = s.indexOf("private void enableLocked() {");
        assertTrue(en > 0);
        int reset = s.indexOf("lastReconciledActiveState = -1;", en);
        int guard = s.indexOf("AccMonitor.isAccOn()", en);
        assertTrue(reset > en && reset < guard);
    }

    @Test
    public void statusIstEhrlich() throws Exception {
        KameraFpsStatus.setze(5, 29.6, 5.0, Boolean.FALSE, true, "imagereader");
        JSONObject j = KameraFpsStatus.alsJson();
        assertEquals(5, j.getInt("angefordert"));
        assertEquals(29.6, j.getDouble("geliefert"), 0.01);
        assertFalse(j.getBoolean("halSetzbar"));
        assertTrue(j.getBoolean("drosselAktiv"));
        assertEquals(30, KameraFpsStatus.halMaxFps());
    }
}
