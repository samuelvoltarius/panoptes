package at.greenchili.surfmate.einrichtung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.20 P7 „Panoptes bei Zündung an öffnen“: Standard aus, nicht vor CarPlay/Android Auto, nur Activity. */
public class ZuendungOeffnenTest {

    static final JSONObject PAKETE;

    static {
        try {
            PAKETE = new JSONObject()
                    .put("carplay", new JSONArray().put(new JSONObject().put("paket", "com.byd.carplay.ui")))
                    .put("androidauto", new JSONArray().put(new JSONObject().put("paket", "com.google.android.projection.gearhead")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String LAUNCHER = "  mResumedActivity: ActivityRecord{1 u0 com.byd.launcher/.Launcher t5}";
    static final String CARPLAY = "  mResumedActivity: ActivityRecord{2 u0 com.byd.carplay.ui/.MainActivity t9}";
    static final String AA = "  topResumedActivity=ActivityRecord{3 u0 com.google.android.projection.gearhead/.Car t11}";
    static final String PANOPTES = "  mResumedActivity: ActivityRecord{4 u0 at.greenchili.surfmate/.ui.MainActivity t12}";

    @Test
    public void entscheidung() {
        assertEquals("aus", ZuendungOeffnen.entscheide(false, LAUNCHER, PAKETE));
        assertEquals("oeffnen", ZuendungOeffnen.entscheide(true, LAUNCHER, PAKETE));
        assertEquals("oeffnen", ZuendungOeffnen.entscheide(true, null, PAKETE));
        assertEquals("projektion_carplay", ZuendungOeffnen.entscheide(true, CARPLAY, PAKETE));
        assertEquals("projektion_androidauto", ZuendungOeffnen.entscheide(true, AA, PAKETE));
        assertEquals("schon_vorn", ZuendungOeffnen.entscheide(true, PANOPTES, PAKETE));
    }

    @Test
    public void schalterStandardAus() throws Exception {
        assertFalse(ZuendungOeffnen.schalter(null));
        assertFalse(ZuendungOeffnen.schalter(new JSONObject()));
        assertFalse(ZuendungOeffnen.schalter(new JSONObject().put("autostart", new JSONObject())));
        assertTrue(ZuendungOeffnen.schalter(new JSONObject().put("autostart", new JSONObject().put("beiZuendung", true))));
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "autostart.beiZuendung");
        assertTrue("im Katalog", f != null);
        assertEquals(Feld.Typ.BOOL, f.typ);
        assertEquals(Boolean.FALSE, f.standard);
    }

    static String lies(String rel) throws Exception {
        File d = new File(rel);
        if (!d.isFile()) d = new File("app/" + rel);
        return new String(Files.readAllBytes(d.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void nurActivityKeinTonUndAnbindung() throws Exception {
        String src = lies("src/main/java/at/greenchili/surfmate/einrichtung/ZuendungOeffnen.java");
        int code = src.indexOf("public final class ZuendungOeffnen");
        String rumpf = src.substring(code);
        for (String verboten : new String[]{"Audio", "Sound", "Tone", "playSound", "horn", "flash", "VehicleCommand"}) {
            assertFalse(verboten, rumpf.contains(verboten));
        }
        assertTrue(rumpf.contains("AppLauncher.launch(PAKET)"));
        String daemon = lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        int a = daemon.indexOf("ParkingHooks.onAccOn(transitionGeneration);");
        assertTrue(daemon.indexOf("ZuendungOeffnen.beiAccAn(transitionGeneration)", a) > a);
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json"));
            JSONObject z = s.getJSONObject("settings").getJSONObject("autostart").getJSONObject("beiZuendung");
            assertFalse(l, z.optString("title").isEmpty());
            assertFalse(l, z.optString("help").isEmpty());
        }
    }
}
