package at.greenchili.surfmate.tacho.oem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.36.1: Auch der zweite AutoContainer-Sender ({@link OemNavigationTransport}) hat eine
 * Erlaubt-Liste. Nur sendInfo(5, 0) und sendInfo2(4); nie Cluster-Kanal 1000, nie Warnleuchten 2/3.
 */
public class OemNavigationOpcodeSperreTest {

    @Test
    public void nurDieZweiNaviBefehle() {
        assertTrue(OemNavigationTransport.istErlaubtSendInfo(5, 0));
        assertTrue(OemNavigationTransport.istErlaubtSendInfo2(4));
        for (int typ = -5; typ <= 1100; typ++) {
            for (int info = -3; info <= 40; info++) {
                if (typ == 5 && info == 0) continue;
                assertFalse(typ + "/" + info, OemNavigationTransport.istErlaubtSendInfo(typ, info));
            }
            if (typ != 4) assertFalse("sendInfo2 " + typ, OemNavigationTransport.istErlaubtSendInfo2(typ));
        }
    }

    @Test
    public void warnleuchtenUndClusterKanalGesperrt() {
        for (int op : new int[] {2, 3}) {
            assertFalse(OemNavigationTransport.istErlaubtSendInfo(1000, op));
            assertFalse(OemNavigationTransport.istErlaubtSendInfo(op, 0));
            assertFalse(OemNavigationTransport.istErlaubtSendInfo(5, op));
            assertFalse(OemNavigationTransport.istErlaubtSendInfo2(op));
        }
        assertFalse(OemNavigationTransport.istErlaubtSendInfo2(1000));
    }

    @Test
    public void sperreStehtVorJedemBinderAufruf() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationTransport.java");
        pruefeVor(src, "private static Result sendInfo(int type, int info, String text) {",
                "istErlaubtSendInfo(type, info)", "resolve()", "method.invoke(");
        pruefeVor(src, "private static Result sendInfo2(int type, byte[] payload) {",
                "istErlaubtSendInfo2(type)", "resolve()", "method.invoke(");
        assertTrue(src.contains("bounded(() -> sendInfo(TYP_NAVI_MODUS, INFO_NAVI_MODUS, \"\"))"));
        assertTrue(src.contains("bounded(() -> sendInfo2(TYP_NAVI_DATEN, payload))"));
    }

    private static void pruefeVor(String src, String kopf, String sperre, String aufloesen, String senden) {
        int k = src.indexOf(kopf);
        assertTrue("fehlt: " + kopf, k >= 0);
        int g = src.indexOf(sperre, k);
        int r = src.indexOf(aufloesen, k);
        int s = src.indexOf(senden, k);
        assertTrue(kopf + ": Sperre vor Binder", g > k && r > g && s > g);
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path p : new Path[] {cur.resolve(rel), cur.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = at.greenchili.surfmate.testutil.RepoWurzel.hoch(cur);
        }
        throw new AssertionError("nicht gefunden: " + rel);
    }
}
