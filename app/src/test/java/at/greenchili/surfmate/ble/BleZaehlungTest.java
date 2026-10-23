package at.greenchili.surfmate.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 0.1.16 BLE-Zählen: nur Anzahl/Typen, keine Adressen; schaltet Bluetooth/Standort nie ein. */
public class BleZaehlungTest {

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void typen() {
        assertEquals("bthome", BleZaehlung.typ(Collections.singletonList(0xFCD2), null));
        assertEquals("xiaomi", BleZaehlung.typ(Collections.singletonList(0xFE95), null));
        assertEquals("switchbot", BleZaehlung.typ(Collections.singletonList(0xFD3D), null));
        assertEquals("govee", BleZaehlung.typ(null, Collections.singletonList(0xEC88)));
        assertEquals("apple", BleZaehlung.typ(Collections.emptyList(), Arrays.asList(0x004C)));
        assertEquals("sonstige", BleZaehlung.typ(null, null));
    }

    @Test
    public void ergebnisOhneAdressen() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("A4:C1:38:00:00:01", "bthome");
        m.put("A4:C1:38:00:00:02", "bthome");
        m.put("11:22:33:44:55:66", "apple");
        JSONObject r = BleZaehlung.ergebnis(m, 8000);
        assertTrue(r.getBoolean("ok"));
        assertEquals(3, r.getInt("anzahl"));
        assertEquals(2, r.getJSONObject("typen").getInt("bthome"));
        assertFalse("keine Adressen", r.toString().contains("A4:C1"));
        assertFalse(r.toString().contains("11:22"));
    }

    @Test
    public void antwortVonAmBroadcast() throws Exception {
        String aus = "Broadcasting: Intent { act=at.greenchili.surfmate.BLE_ZAEHLEN flg=0x400000 cmp=at.greenchili.surfmate/.receiver.BleZaehlReceiver }\n"
                + "Broadcast completed: result=0, data=\"{\"ok\":true,\"anzahl\":4,\"typen\":{\"bthome\":1,\"sonstige\":3},\"scanMs\":8000,\"geheim\":\"x\"}\"\n";
        JSONObject r = BleZaehlung.ausAmAusgabe(aus);
        assertEquals(4, r.getInt("anzahl"));
        assertFalse("nur erlaubte Felder", r.has("geheim"));
        assertEquals("bt_aus", BleZaehlung.ausAmAusgabe(
                "Broadcast completed: result=0, data=\"{\"ok\":false,\"zustand\":\"bt_aus\"}\"").getString("zustand"));
        assertNull(BleZaehlung.ausAmAusgabe("Broadcast completed: result=0"));   // App hat nicht geantwortet
        assertNull(BleZaehlung.ausAmAusgabe(""));
        assertNull(BleZaehlung.ausAmAusgabe(null));
        assertEquals("am broadcast -a at.greenchili.surfmate.BLE_ZAEHLEN -n at.greenchili.surfmate/.receiver.BleZaehlReceiver",
                BleZaehlung.amBefehl());
    }

    @Test
    public void schaltetNieEtwasEin() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/receiver/BleZaehlReceiver.kt");
        for (String verboten : new String[]{".enable()", "ACTION_REQUEST_ENABLE", "LOCATION_MODE", "Settings.Secure",
                "setBluetooth", "ACTION_LOCATION_SOURCE_SETTINGS", "startActivity", "result.device.name",
                "device.name"}) {
            assertFalse(verboten, k.contains(verboten));
        }
        assertTrue(k.contains("if (!adapter.isEnabled) return BleZaehlung.BT_AUS"));
        assertTrue(k.contains("if (!standortAn) return BleZaehlung.STANDORT_AUS"));
        assertTrue(k.contains("SCAN_MODE_LOW_POWER"));
        // nur über Shell/System erreichbar
        String m = lies("src/main/AndroidManifest.xml");
        int i = m.indexOf("receiver.BleZaehlReceiver");
        assertTrue(i > 0);
        assertTrue(m.substring(i, i + 300).contains("android:permission=\"android.permission.DUMP\""));
        // Route im Daemon
        assertTrue(lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java")
                .contains("path.startsWith(\"/api/ble/\") && BleApiHandler.handle(method, path, out)"));
    }
}
