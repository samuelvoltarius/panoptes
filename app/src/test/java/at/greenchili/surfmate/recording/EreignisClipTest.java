package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Laenderprofil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Ereignis-Clip: Schalter nach Land, Schutz vor Überschreiben, Grenze, Weitergabe-Sperre. */
public class EreignisClipTest {

    private static final long MB = 1024L * 1024L;
    private static final long H = 3_600_000L;

    // ---------------------------------------------------------------- Schalter

    @Test
    public void standardNachLand() {
        assertTrue(EreignisClip.aktiv(new JSONObject(), Laenderprofil.Land.ANDERES));
        assertTrue(EreignisClip.aktiv(new JSONObject(), Laenderprofil.Land.UNBEKANNT));
        assertFalse(EreignisClip.aktiv(new JSONObject(), Laenderprofil.Land.SCHWEIZ));
        assertTrue(EreignisClip.aktiv(null, Laenderprofil.Land.ANDERES));
    }

    @Test
    public void bewussteWahlGiltUeberall() throws Exception {
        JSONObject an = new JSONObject().put("recording", new JSONObject().put("ereignisClip", true));
        JSONObject aus = new JSONObject().put("recording", new JSONObject().put("ereignisClip", false));
        assertTrue(EreignisClip.aktiv(an, Laenderprofil.Land.SCHWEIZ));
        assertFalse(EreignisClip.aktiv(aus, Laenderprofil.Land.ANDERES));
        JSONObject nichtGesetzt = new JSONObject().put("recording", new JSONObject().put("ereignisClip", JSONObject.NULL));
        assertFalse(EreignisClip.aktiv(nichtGesetzt, Laenderprofil.Land.SCHWEIZ));
    }

    @Test
    public void telegramStandardAus() throws Exception {
        assertFalse(EreignisClip.telegram(new JSONObject()));
        assertTrue(EreignisClip.telegram(new JSONObject().put("recording",
                new JSONObject().put("ereignisTelegram", true))));
    }

    // ---------------------------------------------------------------- Dateien

    @Test
    public void ereignisErkennen() {
        assertTrue(EreignisClip.istEreignis("ereignis_20261029_143200.mp4"));
        assertFalse(EreignisClip.istEreignis("replay_20261029_143200.mp4"));
        assertFalse(EreignisClip.istEreignis("event_20261029_143200.mp4"));
        assertFalse(EreignisClip.istEreignis("ereignis_20261029_143200.mp4.tmp"));
    }

    @Test
    public void weitergabeSperreErkenntPfadUndName() {
        assertTrue(EreignisClip.istEreignisPfad("/storage/usb/Panoptes/recordings/ereignisse/ereignis_20261029_143200.mp4"));
        assertTrue(EreignisClip.istEreignisPfad("ereignis_20261029_143200.mp4"));
        assertTrue(EreignisClip.istEreignisPfad("C:\\x\\recordings\\ereignisse\\anders.mp4"));
        assertFalse(EreignisClip.istEreignisPfad("/storage/usb/Panoptes/recordings/cam_20261029_143200.mp4"));
        assertFalse(EreignisClip.istEreignisPfad("/storage/usb/Panoptes/surveillance/event_20261029_143200.mp4"));
        assertFalse(EreignisClip.istEreignisPfad(null));
    }

    @Test
    public void ordnerIstUnterordnerDerAufnahmen() {
        assertEquals("ereignisse", EreignisClip.ordner(new java.io.File("/x/recordings")).getName());
        assertEquals("recordings", EreignisClip.ordner(new java.io.File("/x/recordings")).getParentFile().getName());
    }

    // ---------------------------------------------------------------- Grenze und Frist

    private static EreignisClip.Datei d(String zeit, long mb, long t) {
        return new EreignisClip.Datei("ereignis_20261029_" + zeit + ".mp4", mb * MB, t);
    }

    private static List<String> namen(List<EreignisClip.Datei> l) {
        List<String> out = new ArrayList<>();
        for (EreignisClip.Datei x : l) out.add(x.name);
        return out;
    }

    @Test
    public void unterGrenzeUndFrischWirdNichtsGeloescht() {
        long jetzt = 100 * H;
        List<EreignisClip.Datei> l = Arrays.asList(d("100000", 75, jetzt - H), d("110000", 75, jetzt - 2 * H));
        assertTrue(EreignisClip.zuLoeschen(l, jetzt, 72 * H, Collections.<String>emptySet(),
                EreignisClip.GRENZE_BYTES).isEmpty());
    }

    @Test
    public void ueberGrenzeAeltesteZuerst() {
        long jetzt = 100 * H;
        List<EreignisClip.Datei> l = new ArrayList<>();
        for (int i = 0; i < 30; i++) l.add(d(String.format("%06d", i), 75, jetzt - (30 - i) * 60_000L));
        // 30 × 75 MB = 2250 MB > 2048 MB → die 3 ältesten weg (2250-225 = 2025 ≤ 2048)
        List<String> weg = namen(EreignisClip.zuLoeschen(l, jetzt, 0, Collections.<String>emptySet(),
                EreignisClip.GRENZE_BYTES));
        assertEquals(Arrays.asList("ereignis_20261029_000000.mp4", "ereignis_20261029_000001.mp4",
                "ereignis_20261029_000002.mp4"), weg);
    }

    @Test
    public void behalteneUeberlebenGrenzeUndFrist() {
        long jetzt = 1_000 * H;
        List<EreignisClip.Datei> l = new ArrayList<>();
        for (int i = 0; i < 30; i++) l.add(d(String.format("%06d", i), 75, jetzt - (500 - i) * H));
        HashSet<String> behalten = new HashSet<>(Arrays.asList("ereignis_20261029_000000", "ereignis_20261029_000005"));
        List<String> weg = namen(EreignisClip.zuLoeschen(l, jetzt, 72 * H, behalten, EreignisClip.GRENZE_BYTES));
        assertEquals(28, weg.size());   // alle abgelaufen, außer den zwei behaltenen
        assertFalse(weg.contains("ereignis_20261029_000000.mp4"));
        assertFalse(weg.contains("ereignis_20261029_000005.mp4"));
    }

    @Test
    public void nurBehalteneUeberGrenzeBleiben() {
        long jetzt = 100 * H;
        List<EreignisClip.Datei> l = Arrays.asList(d("000001", 1500, jetzt - 3 * H), d("000002", 1500, jetzt - 2 * H));
        HashSet<String> behalten = new HashSet<>(Arrays.asList("ereignis_20261029_000001", "ereignis_20261029_000002"));
        assertTrue(EreignisClip.zuLoeschen(l, jetzt, 0, behalten, EreignisClip.GRENZE_BYTES).isEmpty());
    }

    @Test
    public void ohneFristNurNachGroesse() {
        long jetzt = 10_000 * H;
        List<EreignisClip.Datei> l = Arrays.asList(d("000001", 75, 1), d("000002", 75, 2));
        assertTrue(EreignisClip.zuLoeschen(l, jetzt, 0, Collections.<String>emptySet(),
                EreignisClip.GRENZE_BYTES).isEmpty());
    }

    @Test
    public void fremdeDateienImOrdnerWerdenNichtAngefasst() {
        long jetzt = 100 * H;
        List<EreignisClip.Datei> l = Arrays.asList(
                new EreignisClip.Datei("cam_20261029_000000.mp4", 3000 * MB, 1),
                d("000001", 75, jetzt - 1));
        assertTrue(EreignisClip.zuLoeschen(l, jetzt, 72 * H, Collections.<String>emptySet(),
                EreignisClip.GRENZE_BYTES).isEmpty());
    }

    // ---------------------------------------------------------------- offene Fragen

    @Test
    public void offeneFragenHinzuUndWeg() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject a = EreignisClip.mitOffen(root, "ereignis_20261029_143200", 5L, true);
        root.put("ereignisse", a);
        a = EreignisClip.mitOffen(root, "ereignis_20261029_150000", 6L, true);
        root.put("ereignisse", a);
        JSONArray o = EreignisClip.offen(root);
        assertEquals(2, o.length());
        assertEquals("ereignis_20261029_150000", o.getJSONObject(1).getString("datei"));
        root.put("ereignisse", EreignisClip.mitOffen(root, "ereignis_20261029_143200", 0, false));
        assertEquals(1, EreignisClip.offen(root).length());
    }

    @Test
    public void offeneFragenBegrenzt() throws Exception {
        JSONObject root = new JSONObject();
        for (int i = 0; i < 30; i++) {
            root.put("ereignisse", EreignisClip.mitOffen(root, "ereignis_20261029_1" + String.format("%05d", i), i, true));
        }
        assertEquals(20, EreignisClip.offen(root).length());
    }

    // ---------------------------------------------------------------- Fenster beim Nachfassen

    @Test
    public void nachfassenBehaeltZeitpunkt() {
        int[][] f0 = EreignisMonitor.fenster(0);
        assertEquals(20, f0[0][0]);
        assertEquals(20, f0[0][1]);
        int[][] f5 = EreignisMonitor.fenster(5);
        assertEquals(25, f5[0][0]);   // 5 s später: 25 s zurück, 15 s nach vorn
        assertEquals(15, f5[0][1]);
        for (int v = 0; v <= EreignisMonitor.NACHHER_WARTEN_S; v++) {
            for (int[] f : EreignisMonitor.fenster(v)) {
                assertTrue(f[0] >= 0 && f[1] >= 0 && f[0] + f[1] <= ManualClipWindow.MAX_SECONDS);
                ManualClipWindow.create(f[0], Math.max(f[1], f[0] == 0 ? 1 : f[1]));
            }
        }
    }
}
