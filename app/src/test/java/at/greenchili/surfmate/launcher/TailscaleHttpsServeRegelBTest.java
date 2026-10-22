package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Koordination 21.10. (0.1.9, Regel B): HTTPS über `tailscale serve` (ts.net-Zertifikat, z. B. für
 * Web-Push) nur auf ausdrücklichen Wunsch. Der Weg existiert bereits in TailscaleLauncher; dieser Test
 * hält die Bedingungen fest, damit sie nicht unbemerkt brechen:
 * <ul>
 *   <li>Standard AUS: nur der Inhalt „true“ der Merkdatei schaltet ein, und nur saveHttpsSettings(true)
 *       schreibt ihn.</li>
 *   <li>Ein Fehler beim HTTPS-serve beendet tailscaled nie (Startskript: `|| true`).</li>
 *   <li>Ausschalten zieht nur die eigene 443-Regel zurück – nie `serve reset`, das auch die Regel für
 *       den gesicherten Zugang (Weg 1) löschen würde.</li>
 *   <li>Ist die Option aus, ist der Start identisch: der HTTPS-Aufruf steht ausschließlich im Zweig
 *       „Merkdatei = true“.</li>
 * </ul>
 */
public class TailscaleHttpsServeRegelBTest {

    private static String launcher() throws Exception {
        return Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/launcher/TailscaleLauncher.kt"));
    }

    @Test
    public void standardAusNurAusdruecklichEin() throws Exception {
        String s = launcher();
        assertTrue(s.contains("callback(output.trim() == \"true\")"));
        Matcher m = Pattern.compile("writeHttpsSentinel\\(true\\)").matcher(s);
        int n = 0;
        while (m.find()) n++;
        assertEquals("nur saveHttpsSettings(true) darf einschalten", 1, n);
        int save = s.indexOf("fun saveHttpsSettings(enabled: Boolean");
        int ein = s.indexOf("writeHttpsSentinel(true)");
        assertTrue(save > 0 && ein > save);
    }

    @Test
    public void httpsFehlerBeendetTailscaledNie() throws Exception {
        String s = launcher();
        int zeile = s.indexOf("${secureHttpsServeArgs()} >/dev/null 2>&1 || true");
        assertTrue("HTTPS-serve im Startskript muss Fehler schlucken", zeile > 0);
        // …und nur im Zweig „Merkdatei = true“ (sonst bleibt der Start bytegleich)
        int bedingung = s.lastIndexOf("cat \\\"\\$HTTPS_FILE\\\" 2>/dev/null)\\\" = true", zeile);
        assertTrue("HTTPS-serve nur, wenn die Merkdatei true enthält", bedingung > 0 && zeile - bedingung < 400);
    }

    @Test
    public void ausschaltenNieServeReset() throws Exception {
        String s = launcher();
        assertFalse(s.contains("cmd = \"serve reset\""));
        assertFalse(s.contains("serve reset\"") && !s.contains("Deliberately never `serve reset`"));
        assertTrue(s.contains("HttpsServeOwnership.OWNED -> disableHttpsServeArgs()"));
        assertTrue(s.contains("\"serve --https=$HTTPS_PORT off\""));
    }
}
