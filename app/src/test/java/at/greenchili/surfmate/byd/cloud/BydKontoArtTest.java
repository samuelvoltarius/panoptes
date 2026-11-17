package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.cloud.crypto.BydCryptoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/**
 * BYD-Cloud code=1001 seit 16.11.2026: geteilte Konten (Gast) brauchen {@code userType:"1"} im
 * äußeren Umschlag, mitsigniert. Prüft Signatur, Erkennung Gast/Besitzer und den Rückfall.
 */
public class BydKontoArtTest {

    private static JSONObject inner() throws Exception {
        JSONObject in = new JSONObject();
        in.put("deviceType", "0");
        in.put("imeiMD5", "abc");
        in.put("networkType", "wifi");
        in.put("random", "0123456789abcdef");
        in.put("timeStamp", "1763250000000");
        in.put("version", "1");
        in.put("energyType", "0");
        in.put("tboxVersion", "3");
        in.put("vin", "TESTVIN0000000001");
        return in;
    }

    @Test
    public void signaturEnthaeltUserTypeNurBeiGast() throws Exception {
        JSONObject gast = BydCloudClient.tokenSignFelder(inner(), "AT", "u1", "abc", "de",
                "1763250000001", BydKontoArt.USER_TYPE_GAST);
        JSONObject besitzer = BydCloudClient.tokenSignFelder(inner(), "AT", "u1", "abc", "de",
                "1763250000001", null);

        String sGast = BydCryptoUtils.buildSignString(gast, "k");
        String sBesitzer = BydCryptoUtils.buildSignString(besitzer, "k");

        // Sortiert eingereiht: ... timeStamp, userType, version, vin ... &password=
        assertTrue(sGast, sGast.contains("&timeStamp=1763250000000&userType=1&version=1&"));
        assertTrue(sGast.endsWith("&password=k"));
        assertFalse(sBesitzer.contains("userType"));
        assertEquals(sBesitzer, sGast.replace("&userType=1", ""));
        assertNotEquals(BydCryptoUtils.sha1Mixed(sGast), BydCryptoUtils.sha1Mixed(sBesitzer));
        // Das innere Paket bleibt unverändert (userType nur außen)
        assertFalse(inner().has("userType"));
    }

    @Test
    public void erkennungAusFahrzeugliste() throws Exception {
        assertEquals(Boolean.FALSE, BydKontoArt.gastAusFahrzeug(
                new JSONObject().put("empowerType", 3).put("empowerId", "")));
        assertEquals(Boolean.FALSE, BydKontoArt.gastAusFahrzeug(
                new JSONObject().put("empowerType", "3")));
        assertEquals(Boolean.TRUE, BydKontoArt.gastAusFahrzeug(
                new JSONObject().put("empowerType", -1).put("empowerId", 4711)));
        assertEquals(Boolean.TRUE, BydKontoArt.gastAusFahrzeug(
                new JSONObject().put("empowerType", "-1").put("empowerId", "4711")));
        assertNull(BydKontoArt.gastAusFahrzeug(new JSONObject().put("empowerType", -1)));
        assertNull(BydKontoArt.gastAusFahrzeug(
                new JSONObject().put("empowerType", -1).put("empowerId", JSONObject.NULL)));
        assertNull(BydKontoArt.gastAusFahrzeug(new JSONObject().put("vin", "X")));
        assertNull(BydKontoArt.gastAusFahrzeug(null));
    }

    /** Merkt sich, mit welcher Kontoart gesendet wurde, und antwortet nach Vorgabe. */
    private static final class FakeCloud implements BydKontoArt.Sender {
        final List<Boolean> gesendet = new ArrayList<>();
        final boolean istGast;
        FakeCloud(boolean istGast) { this.istGast = istGast; }
        @Override
        public JSONObject sende(boolean gast) throws IOException {
            gesendet.add(gast);
            try {
                if (gast == istGast) return new JSONObject().put("code", "0");
                return new JSONObject().put("code", gast ? "8003" : "1001");
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @Test
    public void gastOhneAnnahme_einmalMitUserType_undGemerkt() throws Exception {
        BydKontoArt k = new BydKontoArt();
        FakeCloud cloud = new FakeCloud(true);
        assertEquals("0", k.mitRueckfall("V1", "/rt", cloud).optString("code"));
        assertEquals(java.util.Arrays.asList(false, true), cloud.gesendet);
        assertEquals(Boolean.TRUE, k.gemerkt("V1"));

        cloud.gesendet.clear();
        assertEquals("0", k.mitRueckfall("V1", "/rt", cloud).optString("code"));
        assertEquals(java.util.Arrays.asList(true), cloud.gesendet);
    }

    @Test
    public void gastAusListe_gleichRichtig() throws Exception {
        BydKontoArt k = new BydKontoArt();
        k.annahme("V1", Boolean.TRUE);
        FakeCloud cloud = new FakeCloud(true);
        k.mitRueckfall("V1", "/rt", cloud);
        assertEquals(java.util.Arrays.asList(true), cloud.gesendet);
    }

    @Test
    public void besitzerFaelschlichAlsGast_8003_einmalOhne() throws Exception {
        BydKontoArt k = new BydKontoArt();
        k.annahme("V2", Boolean.TRUE);
        FakeCloud cloud = new FakeCloud(false);
        assertEquals("0", k.mitRueckfall("V2", "/rt", cloud).optString("code"));
        assertEquals(java.util.Arrays.asList(true, false), cloud.gesendet);
        assertEquals(Boolean.FALSE, k.gemerkt("V2"));
        // Spätere Annahme aus der Liste überschreibt das Bestätigte nicht
        k.annahme("V2", Boolean.TRUE);
        assertEquals(Boolean.FALSE, k.gemerkt("V2"));
    }

    @Test
    public void besitzerOhneAnnahme_keinUserType() throws Exception {
        BydKontoArt k = new BydKontoArt();
        FakeCloud cloud = new FakeCloud(false);
        k.mitRueckfall("V3", "/rt", cloud);
        assertEquals(java.util.Arrays.asList(false), cloud.gesendet);
        assertEquals(Boolean.FALSE, k.gemerkt("V3"));
    }

    @Test
    public void beideAbgelehnt_ersteAntwort_undKeinPingPong() throws Exception {
        BydKontoArt k = new BydKontoArt();
        final List<Boolean> gesendet = new ArrayList<>();
        BydKontoArt.Sender immerFalsch = gast -> {
            gesendet.add(gast);
            try {
                return new JSONObject().put("code", gast ? "8003" : "1001").put("gast", gast);
            } catch (Exception e) { throw new IOException(e); }
        };
        JSONObject a = k.mitRueckfall("V4", "/rt", immerFalsch);
        assertEquals("1001", a.optString("code"));
        assertEquals(java.util.Arrays.asList(false, true), gesendet);
        gesendet.clear();
        assertEquals("1001", k.mitRueckfall("V4", "/rt", immerFalsch).optString("code"));
        assertEquals(java.util.Arrays.asList(false), gesendet);
    }

    @Test
    public void andererFehlerNachWechsel_wechselGemerkt_fehlerGemeldet() throws Exception {
        BydKontoArt k = new BydKontoArt();
        final JSONObject[] zweite = new JSONObject[1];
        BydKontoArt.Sender s = gast -> {
            try {
                if (!gast) return new JSONObject().put("code", "1001");
                zweite[0] = new JSONObject().put("code", "6002");
                return zweite[0];
            } catch (Exception e) { throw new IOException(e); }
        };
        JSONObject antwort = k.mitRueckfall("V5", "/rt", s);
        assertSame(zweite[0], antwort);
        assertEquals("6002", antwort.optString("code"));
        assertEquals(Boolean.TRUE, k.gemerkt("V5"));
    }

    @Test
    public void andererFehler_keinWechsel() throws Exception {
        BydKontoArt k = new BydKontoArt();
        final List<Boolean> gesendet = new ArrayList<>();
        BydKontoArt.Sender s = gast -> {
            gesendet.add(gast);
            try { return new JSONObject().put("code", "1005"); } catch (Exception e) { throw new IOException(e); }
        };
        assertEquals("1005", k.mitRueckfall("V6", "/rt", s).optString("code"));
        assertEquals(java.util.Arrays.asList(false), gesendet);
        assertNull(k.gemerkt("V6"));
        // 1001, obwohl schon als Gast gesendet: kein Wechsel
        assertFalse(BydKontoArt.falscheKontoArt(true, "1001"));
        assertFalse(BydKontoArt.falscheKontoArt(false, "8003"));
    }

    @Test
    public void nurLeseAbfragen_fernbefehleUnveraendert() throws Exception {
        String src = read("app/src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudClient.java")
                .replace("\r\n", "\n");
        // Realtime-Anfrage und -Ergebnis laufen über die Kontoart
        assertTrue(src.contains("postFahrzeugLesen(\n                \"/vehicleInfo/vehicle/vehicleRealTimeRequest\""));
        assertTrue(src.contains("postFahrzeugLesen(\n                        \"/vehicleInfo/vehicle/vehicleRealTimeResult\""));
        // userType außen nur, wenn gesetzt, und mitsigniert
        assertTrue(src.contains("if (userType != null) outer.put(\"userType\", userType);"));
        assertTrue(src.contains("tokenSignFelder(inner, config.countryCode, s.userId,"));
        // Fernbefehl-Pfad (remoteControl/remoteControlResult) bleibt ohne userType
        int start = src.indexOf("private CloudCommandResult executeRemoteCommandIntern(");
        int ende = src.indexOf("public void cancelRequestForThread(");
        assertTrue(start > 0 && ende > start);
        String fern = src.substring(start, ende);
        assertTrue(fern.contains("/control/remoteControl"));
        assertFalse(fern.contains("postFahrzeugLesen"));
        assertFalse(fern.contains("USER_TYPE_GAST"));
        assertFalse(fern.contains("userType"));
        // Die Fahrzeugliste liefert die erste Annahme
        assertTrue(src.contains("kontoArt.annahme(vehicle.optString(\"vin\", \"\"), BydKontoArt.gastAusFahrzeug(vehicle));"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(direct);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
