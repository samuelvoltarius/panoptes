package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Datenschutz 0.1.8 (Regel 2): Karten laden nur von der Quelle, die der Nutzer gewählt hat
 * (Standard „keine“), es gibt keinen stillen Wechsel auf Dritte, und 3D-Modelle kommen nur
 * über den Daemon (SHA-256), nie direkt aus dem Netz.
 */
public class KartenquelleDatenschutzTest {

    private static final String WEB = "app/src/main/assets/web/";

    @Test
    public void kartenquelleStandardIstKeine() throws Exception {
        String kq = read(WEB + "shared/kartenquelle.js");
        assertTrue(kq.contains("var KEY = 'pn.karte.quelle';"));
        assertTrue("unbekannt/leer → keine", kq.contains("return QUELLEN.indexOf(v) >= 0 ? v : 'keine';"));
    }

    @Test
    public void vektorkarteOhneStillenWechsel() throws Exception {
        String map = read(WEB + "shared/panoptes-map.js");
        assertFalse(map.contains("openfreemap.org"));
        assertTrue(map.contains("zeigeHinweis(el, 'nicht_erreichbar');"));
        assertTrue("ohne Wahl nichts laden", map.contains("if (q === 'keine') {"));
        assertTrue("ohne Modul gilt keine", map.contains("return KQ ? KQ.get() : 'keine';"));
    }

    @Test
    public void blitzerNurMitEinstellung() throws Exception {
        String map = read(WEB + "shared/panoptes-map.js");
        assertTrue(map.contains("api.setBlitzer(false);"));
        assertTrue(map.contains("d.werte['speedcam.enabled']"));
    }

    @Test
    public void blitzerEinschaltenNurMitRechtshinweis() throws Exception {
        String bz = read(WEB + "shared/blitzer.js");
        assertTrue(bz.contains("var FELD = 'speedcam.enabled';"));
        assertTrue("Einschalten mit Quittung", bz.contains("speichern(true, [FELD]);"));
        assertTrue("Ausschalten ohne Quittung", bz.contains("if (an) { speichern(false); return; }"));
        assertTrue(read(WEB + "local/system.html").contains("../shared/blitzer.js"));
    }

    @Test
    public void alleKartenUeberPanoptesMapKeinLeaflet() throws Exception {
        // 0.1.10: nur noch MapLibre (panoptes-map.js) – keine zweite Kachelquelle mit eigener OSM-Einwilligung
        assertFalse(read(WEB + "shared/theme.js").contains("attachMapTiles"));
        for (String seite : new String[] {"live-view", "surveillance", "navi", "trips"}) {
            String html = read(WEB + "local/" + seite + ".html");
            assertTrue(seite, html.contains("../shared/panoptes-map.js"));
            assertFalse(seite, html.contains("leaflet.js"));
        }
        assertFalse(read(WEB + "shared/map.js").contains("L.map("));
        assertFalse(read(WEB + "shared/safe-locations.js").contains("L.map("));
    }

    @Test
    public void naviFragtDenPlanerNurMitFreigabe() throws Exception {
        // 0.1.10 (Regel 2): eine Freigabe „Ortssuche und Routen“, Standard aus – vorher keine Planer-Anfrage
        String navi = read(WEB + "local/navi.html");
        assertTrue(navi.contains("KEY: 'pn.navi.freigabe'"));
        assertTrue(navi.contains("if (!ok) throw new Error(t('navi.fg_aus'"));
        for (String op : new String[] {"orte", "plan", "umkreis"}) {
            assertTrue(op, navi.contains("planer('/api/navi/" + op + "'"));
            assertFalse(op, navi.contains("post('/api/navi/" + op + "'"));
        }
    }

    @Test
    public void displayFolgtDemKernFremdeBrowserNicht() throws Exception {
        String kq = read(WEB + "shared/kartenquelle.js");
        assertTrue(kq.contains("if (!window.fetch || !istDisplay()) return Promise.resolve(null);"));
        assertTrue(kq.contains("/PanoptesDisplay/.test(navigator.userAgent || '')"));
        assertTrue("alte OSM-Zusatzeinwilligung wird gelöscht",
                kq.contains("window.localStorage.removeItem('pn.karte.osmLeaflet')"));
    }

    @Test
    public void kartenseitenLadenDasModul() throws Exception {
        for (String seite : new String[] {"index", "navi", "trips", "live-view", "surveillance", "system"}) {
            assertTrue(seite, read(WEB + "local/" + seite + ".html").contains("../shared/kartenquelle.js"));
        }
    }

    @Test
    public void dreiDModelleNurUeberDenDaemon() throws Exception {
        String card = read(WEB + "shared/ev-card-3d.js");
        assertFalse(card.contains("githubUrl("));
        assertFalse(card.contains("manifest.baseUrl"));
        assertTrue(card.contains("/api/models/download?id="));
    }

    @Test
    public void schluesselFelderNennenDasZiel() throws Exception {
        assertTrue(read(WEB + "local/assistant.html").contains("id=\"genAiZiel\""));
        assertTrue(read(WEB + "local/road-sense.html").contains("id=\"rsRoutingZiel\""));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(repositoryPath(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
