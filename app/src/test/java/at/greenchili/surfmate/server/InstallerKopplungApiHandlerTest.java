package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.InstallerKopplung;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 0.1.43.8 Vertrag {@code POST /api/installer/kopplung} (docs/INSTALLER_KOPPLUNG.md): nur adb shell am Auto
 * (direkt am Auto, UID 2000, nicht tailscaled); ohne Besitzer Code wie vom Display, sonst „weiteres Gerät“;
 * Befehls-Log-Eintrag; der Code steht nie im Log.
 */
public class InstallerKopplungApiHandlerTest {

    static final class Kern implements InstallerKopplung.Quelle {
        boolean besitzer;
        JSONObject bruecke;
        String einmal;
        final List<Boolean> codes = new ArrayList<>();
        @Override public boolean besitzerDa() { return besitzer; }
        @Override public String code(long jetzt, boolean weiteres) { codes.add(weiteres); return "ABCD2345"; }
        @Override public JSONObject bruecke() { return bruecke; }
        @Override public String einmal(long bis) { return einmal; }
    }

    private final List<String> log = new ArrayList<>();

    private InstallerKopplungApiHandler.Quelle q(boolean direkt, int uid, boolean tailscaled, Kern k) {
        return new InstallerKopplungApiHandler.Quelle() {
            public boolean direktAmAuto() { return direkt; }
            public int quellport() { return direkt ? 45_000 : -1; }
            public int uid(int quellport) { return uid; }
            public boolean tailscaled(int quellport) { return tailscaled; }
            public InstallerKopplung.Quelle kopplung() { return k; }
            public long jetzt() { return 1_000_000L; }
            public void protokoll(String ergebnis) { log.add(ergebnis); }
        };
    }

    @After
    public void ab() {
        log.clear();
    }

    private static int status(Object[] a) { return (Integer) a[0]; }

    private static JSONObject qrInhalt(String qr) throws Exception {
        String b64 = qr.substring(qr.indexOf("#k=") + 3);
        return new JSONObject(new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8));
    }

    @Test
    public void adbShellOhneBesitzerBekommtBesitzerCode() throws Exception {
        Kern k = new Kern();
        Object[] a = InstallerKopplungApiHandler.antwort("POST", "{\"adresse\":\"http://192.168.10.10:8180\"}", q(true, 2000, false, k));
        assertEquals(200, status(a));
        JSONObject r = (JSONObject) a[1];
        assertTrue(r.getBoolean("besitzer"));
        assertEquals(List.of(false), k.codes);   // Code wie vom Display
        JSONObject in = qrInhalt(r.getString("qr"));
        assertEquals("ABCD2345", in.getString("c"));
        assertEquals("http://192.168.10.10:8180", in.getJSONArray("u").getString(0));
        assertEquals("aus", in.getString("bruecke"));
        assertFalse(r.getBoolean("unterwegs"));
        assertTrue(r.getString("qr").startsWith("https://byd.serveone.at/p/#k="));
        assertEquals(List.of("besitzer"), log);
    }

    @Test
    public void mitBesitzerNurWeiteresHandy() throws Exception {
        Kern k = new Kern();
        k.besitzer = true;
        Object[] a = InstallerKopplungApiHandler.antwort("POST", "{\"adresse\":\"http://192.168.10.10:8180\"}", q(true, 2000, false, k));
        assertEquals(200, status(a));
        assertFalse(((JSONObject) a[1]).getBoolean("besitzer"));
        assertEquals(List.of(true), k.codes);   // Code wie „weiteres Gerät koppeln“
        assertEquals(List.of("weiteres"), log);
    }

    @Test
    public void andereAppLauncherPanoptesSystemRootAbgelehnt() {
        for (int uid : new int[] {10_123, 10_200, 1000, 0, -1}) {
            Kern k = new Kern();
            Object[] a = InstallerKopplungApiHandler.antwort("POST", "{}", q(true, uid, false, k));
            assertEquals("uid " + uid, 403, status(a));
            assertTrue("kein Code für uid " + uid, k.codes.isEmpty());
        }
    }

    @Test
    public void tailscaledAbgelehntObwohlUid2000() {
        Kern k = new Kern();
        assertEquals(403, status(InstallerKopplungApiHandler.antwort("POST", "{}", q(true, 2000, true, k))));
        assertTrue(k.codes.isEmpty());
        assertEquals(List.of("abgelehnt"), log);
    }

    @Test
    public void vonAussenKopplungBrueckeAbgelehnt() {
        Kern k = new Kern();
        assertEquals(403, status(InstallerKopplungApiHandler.antwort("POST", "{}", q(false, 2000, false, k))));
        assertTrue(k.codes.isEmpty());
    }

    @Test
    public void nurPost() {
        Kern k = new Kern();
        assertEquals(405, status(InstallerKopplungApiHandler.antwort("GET", null, q(true, 2000, false, k))));
        assertTrue(k.codes.isEmpty());
    }

    @Test
    public void ohneAdresseUndOhneBrueckeKeinCode() throws Exception {
        Kern k = new Kern();
        Object[] a = InstallerKopplungApiHandler.antwort("POST", "{\"adresse\":\"http://8.8.8.8:8180\"}", q(true, 2000, false, k));
        assertEquals(409, status(a));
        assertEquals("kein_weg", ((JSONObject) a[1]).getString("grund"));
        assertTrue(k.codes.isEmpty());
    }

    @Test
    public void mitBrueckeEinmalZugangUndQrBasis() throws Exception {
        Kern k = new Kern();
        k.bruecke = new JSONObject().put("p", "P".repeat(43)).put("a", "A".repeat(43)).put("qrBasis", "https://byd.serveone.at/p/");
        k.einmal = "Z".repeat(43);
        Object[] a = InstallerKopplungApiHandler.antwort("POST", "", q(true, 2000, false, k));
        assertEquals(200, status(a));
        JSONObject r = (JSONObject) a[1];
        assertTrue(r.getBoolean("unterwegs"));
        JSONObject in = qrInhalt(r.getString("qr"));
        assertEquals("Z".repeat(43), in.getJSONObject("b").getString("z"));
        assertFalse(in.has("u"));
        assertFalse(in.has("bruecke"));
    }

    @Test
    public void brueckeOhneInternetSagtEsImQr() throws Exception {
        Kern k = new Kern();
        k.bruecke = new JSONObject().put("p", "P".repeat(43)).put("a", "A".repeat(43));
        Object[] a = InstallerKopplungApiHandler.antwort("POST", "{\"adresse\":\"http://10.0.2.2:18180\"}", q(true, 2000, false, k));
        JSONObject in = qrInhalt(((JSONObject) a[1]).getString("qr"));
        assertEquals("kein_internet", in.getString("bruecke"));
        assertEquals("http://10.0.2.2:18180", in.getJSONArray("u").getString(0));
    }

    @Test
    public void adresseNurPrivatMitPort() {
        assertEquals("http://192.168.10.10:8180", InstallerKopplung.adresse(" http://192.168.10.10:8180 "));
        assertEquals("https://172.20.1.1:8443", InstallerKopplung.adresse("https://172.20.1.1:8443"));
        for (String x : new String[] {null, "", "http://8.8.8.8:8180", "http://192.168.10.10", "http://192.168.1.300:80",
                "http://192.168.1.3:0", "ftp://192.168.1.3:21", "http://192.168.1.3:8180/x", "http://evil.com:80"}) {
            assertNull(x, InstallerKopplung.adresse(x));
        }
    }

    @Test
    public void kaputterKoerperGibt403OhneCode() {
        Kern k = new Kern();
        assertEquals(403, status(InstallerKopplungApiHandler.antwort("POST", "{kaputt", q(true, 2000, false, k))));
        assertTrue(k.codes.isEmpty());
    }

    @Test
    public void handlerHaengtImServerUndCodeNieImLog() throws Exception {
        Path server = Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        String s = new String(Files.readAllBytes(server), StandardCharsets.UTF_8);
        assertTrue(s.contains("InstallerKopplungApiHandler.handle(method, path, body, out)"));
        Path h = Paths.get("src/main/java/at/greenchili/surfmate/server/InstallerKopplungApiHandler.java");
        String quelle = new String(Files.readAllBytes(h), StandardCharsets.UTF_8);
        assertFalse(quelle.contains("logger."));
        assertFalse(quelle.contains("android.util.Log"));
        assertFalse(quelle.contains("System.out"));
        assertTrue(quelle.contains("sendBinaryNoStore"));
    }
}
