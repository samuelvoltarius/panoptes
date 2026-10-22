package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.community.CommunityZugang;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import kotlin.Pair;

/** Koordination 0.1.9.1 (DOPPELUNGEN.md): F2, F3, F4, F16. */
public class Fix0191Test {

    // ------------------------------------------------------------- F2 Pflichttext E serverseitig

    @Test
    public void f2_einschaltenOhneQuittungAbgelehnt() throws Exception {
        assertTrue(TelegramApiHandler.mediaBestaetigungFehlt(true, false, 0));    // aus → an ohne Quittung
        assertFalse(TelegramApiHandler.mediaBestaetigungFehlt(true, false, 1L));  // mit Quittung ok
        assertFalse(TelegramApiHandler.mediaBestaetigungFehlt(true, true, 0));    // Bestand schon an: unberührt
        assertFalse(TelegramApiHandler.mediaBestaetigungFehlt(false, true, 0));   // ausschalten geht immer
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/TelegramApiHandler.java"));
        int pruef = h.indexOf("mediaBestaetigungFehlt(req.has(\"videoUploads\")");
        int schreib = h.indexOf("UnifiedTelegramConfig.setBoolean(unifiedKey", pruef);
        assertTrue(pruef > 0 && schreib > pruef);                                 // vor jedem Schreiben
    }

    // ------------------------------------------------------------- F3 ein Planer-Zugang

    @Test
    public void f3_geraeteSchluesselZuerstMitRueckfall() throws Exception {
        String g = "ab".repeat(32);
        JSONObject root = new JSONObject().put("planner", new JSONObject().put("memberToken", "0123456789abcdef0123456789abcdef"));
        List<Pair<String, String>> k = CommunityZugang.INSTANCE.kandidaten(root, g);
        assertEquals(2, k.size());
        assertEquals("X-Geraete-Token", k.get(0).getFirst());
        assertEquals("X-Member-Token", k.get(1).getFirst());
        // nur per Geräte-Fluss verbunden (früher: kein Community-Zugang)
        assertEquals(1, CommunityZugang.INSTANCE.kandidaten(new JSONObject(), g).size());
        assertEquals(0, CommunityZugang.INSTANCE.kandidaten(new JSONObject(), null).size());
        for (String f : new String[] {"community/sync/CommunitySyncProvider.kt", "roadsense/sync/CloudflareEdgeSyncProvider.kt"}) {
            String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/" + f));
            assertTrue(f, q.contains("if (resp.code != 401) return resp"));
        }
    }

    // ------------------------------------------------------------- F4 eine Akku-Schwelle

    @Test
    public void f4_eineSchwelleOhneStilleAenderung() throws Exception {
        java.lang.reflect.Method w = Class.forName("at.greenchili.surfmate.power.SocCutoffMonitor")
                .getMethod("wirksameSchwelle", JSONObject.class);
        // Bestand mit Standardwerten: 10 bleibt 10
        assertEquals(10, w.invoke(null, new JSONObject().put("power", new JSONObject().put("lowSocCutoffPercent", 10))));
        // ausdrücklich abweichend gesetzter Altwert gewinnt (keine stille Änderung)
        assertEquals(15, w.invoke(null, new JSONObject().put("power", new JSONObject().put("lowSocCutoffPercent", 15))));
        // Nutzerschwelle guardStop.socBelow unter 10: Abschaltung nie oberhalb des Wächter-Stopps
        assertEquals(6, w.invoke(null, new JSONObject().put("guardStop", new JSONObject().put("socBelow", 6))));
        assertEquals(10, w.invoke(null, new JSONObject().put("guardStop", new JSONObject().put("socBelow", 20))));
        assertEquals(10, w.invoke(null, new JSONObject()));
    }

    // ------------------------------------------------------------- F16 kein alter Update-Weg mehr

    @Test
    public void f16_keinNetzwegImAltenUpdater() throws Exception {
        String a = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/updater/AppUpdater.java"));
        for (String m : new String[] {"public void checkForUpdate(", "public void downloadAndInstall(",
                "public void installCompanionApk(", "public void listVersions(", "public void fetchLatestReleaseVersion(",
                "public String prepareInstall(", "private void doStreamingDownload(", "newCall("}) {
            assertFalse(m, a.contains(m));
        }
        assertFalse(Files.exists(Paths.get("src/main/java/at/greenchili/surfmate/updater/UpdateDialog.java")));
    }

    // ------------------------------------------------------------- Planer-Vorlieben aus dem Konto

    @Test
    public void planerFilterLeerWirdNieMitgeschickt() throws Exception {
        JSONObject b = new JSONObject(NaviApiHandler.planBody(
                "{\"start\":[1,2],\"filter\":{\"bevorzugt\":[]}}"));
        assertFalse(b.has("filter"));                                    // dann gilt die Konto-Einstellung
        JSONObject c = new JSONObject(NaviApiHandler.planBody(
                "{\"filter\":{\"bevorzugt\":[],\"nur_offen\":true}}"));
        assertFalse(c.getJSONObject("filter").has("bevorzugt"));
        assertTrue(c.getJSONObject("filter").getBoolean("nur_offen"));
        JSONObject d = new JSONObject(NaviApiHandler.planBody("{\"filter\":{\"bevorzugt\":[\"ionity\"]}}"));
        assertEquals("ionity", d.getJSONObject("filter").getJSONArray("bevorzugt").getString(0));
        String n = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java"));
        assertTrue(n.contains("if (code == 401 && kopf != null)"));     // ohne Schlüssel weiter wie bisher
        String nu = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/reichweite/Neuplanung.java"));
        assertFalse(nu.contains("\"filter\""));                       // eigene Neuplanung schickt nie filter
    }

    // ------------------------------------------------------------- SETUP_WIZARD Teil A

    @Test
    public void teilA_frischeKonfigNurErschuetterung_gespeicherteWahlBleibt() throws Exception {
        for (at.greenchili.surfmate.einstellungen.Laenderprofil.Land land : at.greenchili.surfmate.einstellungen.Laenderprofil.Land.values()) {
            assertEquals("shock", at.greenchili.surfmate.einstellungen.TriggerModus.wirksam(null, land, false));
            assertFalse(at.greenchili.surfmate.einstellungen.TriggerModus.kiErlaubt(null, land, null, 0, 0, false));
        }
        assertEquals("nicht_gewaehlt", at.greenchili.surfmate.einstellungen.TriggerModus.status(null));
        assertEquals("ai_everywhere", at.greenchili.surfmate.einstellungen.TriggerModus.status("ai_everywhere"));
        String api = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java"));
        assertTrue(api.contains("statusJson.put(\"waechterAusloeser\""));
    }
}
