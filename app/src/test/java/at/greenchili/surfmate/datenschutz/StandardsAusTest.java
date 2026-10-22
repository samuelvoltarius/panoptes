package at.greenchili.surfmate.datenschutz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Regel Nr. 2 (Koordination 21.10.): Blitzer Standard aus mit Rechtshinweis; Telegram-Parknachricht bei neuer Einrichtung aus. */
public class StandardsAusTest {

    static Feld feld(String key) {
        for (Feld f : Katalog.felder(new Katalog.Kontext())) if (key.equals(f.key)) return f;
        return null;
    }

    @Test
    public void blitzerStandardAusMitRechtshinweis() throws Exception {
        Feld b = feld("speedcam.enabled");
        assertEquals(Boolean.FALSE, b.standard);
        assertEquals("speedcam.rechtshinweis", b.bestaetigung);
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/blitzer/BlitzerDienst.java"));
        assertTrue(d.contains("if (!cfg.optBoolean(\"enabled\", false)) return;"));
    }

    @Test
    public void telegramParknachrichtBeiNeuerEinrichtungAus() throws Exception {
        Feld p = feld("telegram.parkingMessages");
        assertEquals(Boolean.TRUE, p.standard);   // Bestand unverändert
        assertTrue(p.verborgen);
        String o = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/onboarding/OnboardingHost.kt"));
        assertTrue(o.contains("\"telegram.parkingMessages\" to false"));
    }

    @Test
    public void blitzerEinschaltenNurMitBestaetigungAusschaltenOhne() throws Exception {
        at.greenchili.surfmate.einstellungen.Katalog.Kontext k = new at.greenchili.surfmate.einstellungen.Katalog.Kontext();
        java.util.Map<String, java.util.Map<String, Object>> w = new java.util.HashMap<>();
        at.greenchili.surfmate.einstellungen.Einstellungen.Schreiber s = (a, v) -> { w.computeIfAbsent(a, x -> new java.util.HashMap<>()).putAll(v); return true; };
        org.json.JSONObject an = new org.json.JSONObject().put("werte", new org.json.JSONObject().put("speedcam.enabled", true));
        assertEquals("bestaetigungFehlt", at.greenchili.surfmate.einstellungen.Einstellungen.aendern(
                Katalog.felder(k), new org.json.JSONObject(), k, an, false, s, 0L).fehler.getString("speedcam.enabled"));
        an.put("bestaetigt", new org.json.JSONArray().put("speedcam.enabled"));
        assertTrue(at.greenchili.surfmate.einstellungen.Einstellungen.aendern(
                Katalog.felder(k), new org.json.JSONObject(), k, an, false, s, 0L).ok());
        org.json.JSONObject aus = new org.json.JSONObject().put("werte", new org.json.JSONObject().put("speedcam.enabled", false));
        assertTrue(at.greenchili.surfmate.einstellungen.Einstellungen.aendern(
                Katalog.felder(k), new org.json.JSONObject(), k, aus, false, s, 0L).ok());
    }

    @Test
    public void proxyAltlastNurAlterWert() throws Exception {
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt"));
        assertTrue(d.contains("127.0.0.1:8219|localhost:8219) settings delete global http_proxy;; esac"));
        assertTrue(d.contains("private fun startOptionalDaemonsFromPreferences() {\n        proxyAltlastAufraeumen()"));
    }
}
