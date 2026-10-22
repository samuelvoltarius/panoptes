package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * Energie-Profil (Batterieschutz im Stand): nicht gesetzt = exakt bisheriges Verhalten (Alfreds Auto bis zur
 * Messung), ausdrückliche Einzelwerte gewinnen, Untergrenzen schützen Wächter und Dauerbetrieb.
 */
public class EnergieTest {

    static JSONObject profil(String p) throws Exception {
        return new JSONObject().put("energie", new JSONObject().put("profil", p));
    }

    @Test
    public void ohneProfilWieBisher() throws Exception {
        JSONObject alt = new JSONObject();   // Alt-Installation
        assertEquals("normal", Energie.profil(alt));
        assertTrue(Energie.kuenstlicheAktivitaet(alt, false));   // bisher: Standard an
        assertTrue(Energie.appWachhalterImStand(alt, false));
        assertEquals(1, Energie.gpsStandSekunden(alt));
        assertEquals(5, Energie.weckMinuten(alt));
        assertFalse(Energie.kameraDrossel(alt));                  // bisher: Drossel aus
        assertEquals(5, Energie.kameraLeerlaufFps(alt));
        assertEquals(5, Energie.kameraRuheMinuten(alt));
        assertEquals(3, Energie.kameraRuheFps(alt));
        assertEquals("normal", Energie.profil(profil("quatsch")));
    }

    @Test
    public void sparsamSchontOhneWaechterZuSchwaechen() throws Exception {
        JSONObject s = profil("sparsam");
        assertTrue("Wächter an → Aktivität bleibt", Energie.kuenstlicheAktivitaet(s, true));
        assertFalse("Wächter aus → keine Aktivität", Energie.kuenstlicheAktivitaet(s, false));
        assertTrue(Energie.appWachhalterImStand(s, true));
        assertFalse(Energie.appWachhalterImStand(s, false));
        assertEquals(10, Energie.gpsStandSekunden(s));
        assertEquals(5, Energie.weckMinuten(s));
        assertTrue(Energie.kameraDrossel(s));
        assertEquals(5, Energie.kameraLeerlaufFps(s));
    }

    @Test
    public void minimalMitUntergrenzen() throws Exception {
        JSONObject m = profil("minimal");
        assertFalse(Energie.kuenstlicheAktivitaet(m, true));
        assertFalse(Energie.appWachhalterImStand(m, true));
        assertEquals(30, Energie.gpsStandSekunden(m));
        assertEquals(10, Energie.weckMinuten(m));            // nie über 10 (WLAN-Kappung der MCU)
        assertTrue(Energie.kameraDrossel(m));
        assertTrue(Energie.kameraLeerlaufFps(m) >= 2);       // Gesundheitswächter BILD_MAX 60 s
        assertTrue(Energie.kameraRuheFps(m) >= 2);
    }

    @Test
    public void ausdruecklicheWerteGewinnen() throws Exception {
        JSONObject r = profil("minimal");
        r.put("surveillance", new JSONObject().put("keepUsbPowerOnAccOff", true));
        r.put("camera", new JSONObject().put("surveillanceIdleThrottle", false).put("surveillanceIdleFps", 8));
        r.getJSONObject("energie").put("gpsStandSekunden", 3).put("weckMinuten", 99);
        assertTrue(Energie.kuenstlicheAktivitaet(r, false));
        assertFalse(Energie.kameraDrossel(r));
        assertEquals(8, Energie.kameraLeerlaufFps(r));
        assertEquals(3, Energie.gpsStandSekunden(r));
        assertEquals(10, Energie.weckMinuten(r));            // geklemmt
        JSONObject n = profil("normal");
        n.put("surveillance", new JSONObject().put("keepUsbPowerOnAccOff", false));
        assertFalse(Energie.kuenstlicheAktivitaet(n, true));
    }

    @Test
    public void verdrahtung() throws Exception {
        String acc = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java"));
        assertTrue(acc.contains("Energie.kuenstlicheAktivitaet(root,"));
        String ucm = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/config/UnifiedConfigManager.kt"));
        assertTrue(ucm.contains("Energie.kameraDrossel(loadConfig())"));
        assertTrue(ucm.contains("Energie.kameraLeerlaufFps(loadConfig())"));
        assertTrue(ucm.contains("Energie.kameraRuheMinuten(loadConfig())"));
        assertTrue(ucm.contains("Energie.kameraRuheFps(loadConfig())"));
        String ka = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/services/DaemonKeepaliveService.kt"));
        assertTrue(ka.contains("} else if (!energieWachhalterImStand()) {"));
        String gps = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/services/LocationSidecarService.java"));
        assertTrue(gps.contains("handler.postDelayed(this, naechsterSendeAbstandMs());"));
        assertTrue(gps.contains("Math.max(lastCallbackFixAtMs, lastProcessedTime) < STAND_NACH_MS) return 1000L;"));
        String rev = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/receiver/ProcessRevivalReceiver.kt"));
        assertFalse(rev.contains("now + INTERVAL_MS"));
        assertTrue(rev.contains("now + intervallMs()"));
    }

    @Test
    public void schema() {
        Katalog.Kontext k = new Katalog.Kontext();
        Feld prof = null;
        for (Feld f : Katalog.felder(k)) if ("energie.profil".equals(f.key)) prof = f;
        assertTrue(prof != null);
        assertEquals("normal", prof.standard);
        assertEquals("sparsam", prof.empfohlen);
        assertEquals(3, prof.optionen.size());
    }

    /** Neue Einrichtung (Onboarding, nicht im Wiederholungsmodus) setzt „sparsam“, nur wenn nie gesetzt. */
    @Test
    public void einrichtungSetztSparsam() throws Exception {
        String o = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/onboarding/OnboardingHost.kt"));
        assertTrue(o.contains("private const val ENERGIE_KEY = \"energie.profil\""));
        assertTrue(o.contains("private const val ENERGIE_DEFAULT = \"sparsam\""));
        assertTrue(o.contains("ENERGIE_KEY to ENERGIE_DEFAULT"));
        assertTrue(o.contains("if (!w.optBoolean(\"gesetzt\", false)) neu.put(key, wert)"));
        assertTrue(o.contains("if (!replayMode) setRetentionDefaultIfUnset()"));
    }
}
