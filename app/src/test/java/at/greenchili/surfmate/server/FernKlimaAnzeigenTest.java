package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.7 Fernklima ehrlich in allen Anzeigen (Web, Handy-API = {@code /api/vehicle/state}, Launcher, Telegram): nach einem
 * erfolgreichen Fern-Start „läuft (gestartet HH:MM, bis ca. HH:MM, Heizen Stufe 3) – laut Befehl“, danach „beendet“, ohne Beleg
 * und ohne Befehl „Zustand unbekannt (Zündung aus)“, nie „Klima aus“.
 */
public class FernKlimaAnzeigenTest {

    private static String datei(String rel) throws Exception {
        Path p = Paths.get("src/main/" + rel);
        if (!Files.exists(p)) p = Paths.get("app/src/main/" + rel);
        if (!Files.exists(p)) p = Paths.get("../" + rel.replace("app/src/main/", "panoptes-launcher/src/main/"));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String launcher(String rel) throws Exception {
        Path p = Paths.get("../panoptes-launcher/src/main/" + rel);
        if (!Files.exists(p)) p = Paths.get("panoptes-launcher/src/main/" + rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void zustandsApiLiefertFernUndLaesstAcOnBeiZuendungAusWeg() throws Exception {
        String h = datei("java/at/greenchili/surfmate/server/VehicleControlApiHandler.java");
        assertTrue(h.contains("climate.put(\"fern\", fern)"));
        assertTrue(h.contains("KlimaZustand.fernJson()"));
        assertTrue("eine Quelle fuer die Bewertung", h.contains("KlimaZustand.aktuell("));
        // acOn nur mit Beleg (Auto an bzw. DiLink 5): sonst fehlt das Feld statt „false“
        assertTrue(h.contains("data.acStartState != BydVehicleData.UNAVAILABLE && (diLink5 || vehiclePoweredOn)"));
        assertFalse(h.contains("(diLink5 || vehiclePoweredOn) && data.acStartState == 1);"));
        String l = datei("java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(l.contains("o.put(\"fern\", fern == null ? JSONObject.NULL : fern)"));
    }

    @Test
    public void webZeigtNieKlimaAusOhneBeleg() throws Exception {
        String js = datei("assets/web/shared/klima-stufe.js");
        assertTrue(js.contains("function fernText(cl)"));
        assertTrue(js.contains("fernText: fernText"));
        assertTrue("echter Zustand gewinnt", js.contains("lz === 'laeuft' || lz === 'aus' || typeof cl.acOn === 'boolean'"));
        String idx = datei("assets/web/local/index.html");
        assertTrue(idx.contains("PanoptesKlimaStufe.fernText(cl)"));
        assertTrue("Kopfzeile: Klima aus nur mit Beleg", idx.contains("lzs === 'aus' || (!lzs && typeof cl.acOn === 'boolean')"));
        assertTrue("Kachel: unbekannt zeigt Strich, nie Aus", idx.contains("setText('sClim', '—'); setCls('sClim', 'v', '');"));
        String vc = datei("assets/web/shared/vehicle-control.js");
        assertTrue(vc.contains("PanoptesKlimaStufe.fernText(data.climate)"));
        assertTrue(vc.contains("laeuftEl.textContent = fernH.text;"));
    }

    @Test
    public void telegramStatusUndLauncherNennenFernklima() throws Exception {
        String tg = datei("java/at/greenchili/surfmate/daemon/telegram/SurveillanceCommandHandler.java");
        assertTrue(tg.contains("data(\"GET_FERNKLIMA\", ctx)"));
        assertTrue(tg.contains("status.fernklima_laeuft"));
        assertTrue(datei("java/at/greenchili/surfmate/server/SurveillanceIpcServer.java").contains("case \"GET_FERNKLIMA\""));
        String m = launcher("java/at/greenchili/panoptes/launcher/MainActivity.kt");
        assertTrue(m.contains("null -> fernText(k)"));
        assertTrue("nur wenn das Auto selbst nichts meldet", m.contains("when (k?.an) { true ->"));
    }

    @Test
    public void textenInFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject web = new JSONObject(datei("assets/web/i18n/" + l + ".json")).getJSONObject("klima_laeuft");
            for (String k : new String[]{"fern_laeuft", "fern_beendet", "zuendung_aus", "art_heizen", "art_kuehlen", "art_neutral"}) {
                assertTrue(l + " web " + k, !web.optString(k, "").isEmpty());
            }
            assertTrue(l, web.getString("fern_laeuft").contains("{von}") && web.getString("fern_laeuft").contains("{bis}")
                    && web.getString("fern_laeuft").contains("{art}"));
            assertTrue(l, web.getString("art_heizen").contains("{n}"));
            JSONObject tg = new JSONObject(datei("assets/server-i18n/" + l + ".json")).getJSONObject("telegram").getJSONObject("status");
            for (String k : new String[]{"fernklima_laeuft", "fernklima_beendet", "fern_heizen", "fern_kuehlen", "fern_neutral"}) {
                assertTrue(l + " telegram " + k, !tg.optString(k, "").isEmpty());
            }
            assertTrue(l, tg.getString("fernklima_laeuft").contains("{0}") && tg.getString("fernklima_laeuft").contains("{1}")
                    && tg.getString("fernklima_laeuft").contains("{2}"));
            // Apostrophe brechen das MessageFormat (fr/it): nur typografische
            assertFalse(l, tg.getString("fernklima_laeuft").contains("'"));
            String sp = l.equals("en") ? "" : "-" + l;
            String lx = launcher("res/values" + sp + "/strings.xml");
            for (String k : new String[]{"fern_laeuft", "fern_beendet", "fern_art_heizen", "fern_art_kuehlen", "fern_art_neutral"}) {
                assertTrue(l + " launcher " + k, lx.contains("name=\"" + k + "\""));
            }
        }
    }
}
