package at.greenchili.surfmate.server;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 0.1.42 (Fix nach T3): Ein camview-show aus dem Panoptes-Tacho ({@code persist=0}) legt die Lage nur fuer die
 * Sitzung an (Override) und veraendert {@code camview.geometryCluster} des Nutzers NICHT. Nach hide (Override weg)
 * gilt wieder die gespeicherte Nutzerlage.
 */
public class CamViewTachoGeometrieTest {

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String quelle(String rel) throws Exception {
        return at.greenchili.surfmate.tacho.TachoFaehigkeitTestZugang.lies(rel);
    }

    @Test
    public void nurDerTachoSpeichertNichts() throws Exception {
        assertTrue(StreamingApiHandler.nurSitzung(q("geometry", "1/2/300/200", "persist", "0", "target", "cluster")));
        assertFalse("ohne persist=0 wie bisher gespeichert", StreamingApiHandler.nurSitzung(q("geometry", "1/2/300/200")));
        assertFalse(StreamingApiHandler.nurSitzung(q("geometry", "1/2/300/200", "persist", "1")));
        assertTrue("persist=0 gilt auch ohne geometry", StreamingApiHandler.nurSitzung(q("persist", "0")));
        assertFalse(StreamingApiHandler.nurSitzung(null));
        assertNull("preset bei persist=0: keine Lage, gespeicherte gilt", StreamingApiHandler.sitzungsGeometrie(q("preset", "50/center", "persist", "0")));
        JSONObject g = StreamingApiHandler.sitzungsGeometrie(q("geometry", "10/20/300/200"));
        assertEquals(10, g.getInt("x")); assertEquals(20, g.getInt("y"));
        assertEquals(300, g.getInt("w")); assertEquals(200, g.getInt("h"));
        assertNull(StreamingApiHandler.sitzungsGeometrie(q("geometry", "10/20/x/200")));
        assertNull(StreamingApiHandler.sitzungsGeometrie(q("geometry", "10/20/300")));
    }

    /** Ein Tacho-show/hide veraendert KEINEN gespeicherten camview-Wert (Lage, enabled, mode, target, autoHideSec). */
    @Test
    public void tachoShowUndHideSpeichernNichts() throws Exception {
        String s = quelle("app/src/main/java/at/greenchili/surfmate/server/StreamingApiHandler.java");
        String cv = s.substring(s.indexOf("private static boolean handleCamView("), s.indexOf("HttpResponse.sendJsonError(out, \"unknown camview endpoint\");"));
        // show: einziger Absichts-Schreiber steht hinter der Weiche
        String show = cv.substring(cv.indexOf("if (clean.equals(\"/api/camview/show\"))"));
        assertEquals("genau ein setCamViewValues im show", 1, show.split("setCamViewValues\\(", -1).length - 1);
        int weiche = show.indexOf("if (!nurSitzung(p)) try {");
        assertTrue(weiche > 0 && weiche < show.indexOf("setCamViewValues("));
        assertTrue(show.contains("failedGeo = nurSitzung(p) ? sitzungsGeometrie(p) : persistCamViewGeometry(p, target);"));
        // hide: bei persist=0 Rueckkehr VOR dem enabled=false-Schreiben
        String hide = cv.substring(cv.indexOf("if (clean.equals(\"/api/camview/hide\"))"), cv.indexOf("if (clean.equals(\"/api/camview/show\"))"));
        int frueh = hide.indexOf("if (nurSitzung(parseQuery(query))) {");
        assertTrue(frueh > hide.indexOf("pipeline.hideCamView();"));
        assertTrue("Tacho-hide schreibt nichts", frueh < hide.indexOf("setCamViewValues("));
        // Der Tacho ruft show UND hide nur mit persist=0
        String t = quelle("app/src/main/java/at/greenchili/surfmate/tacho/TachoKameraAnsicht.java");
        assertTrue(t.contains("camview(\"POST\", \"/api/camview/hide?persist=0\");"));
        assertFalse(t.contains("\"/api/camview/hide\")"));
    }

    @Test
    public void tachoShowVeraendertGeometryClusterNicht() throws Exception {
        String s = quelle("app/src/main/java/at/greenchili/surfmate/server/StreamingApiHandler.java");
        // Weiche: persist=0 → kein persistCamViewGeometry (das einzige, was geometry/geometryCluster schreibt)
        assertTrue(s.contains("failedGeo = nurSitzung(p) ? sitzungsGeometrie(p) : persistCamViewGeometry(p, target);"));
        // Sitzungs-Lage wird als Override installiert und bei hide/Uebernahme wieder entfernt
        assertTrue(s.contains("pipeline.setCamViewGeometryOverride(target, failedGeo);"));
        String sitzung = s.substring(s.indexOf("static JSONObject sitzungsGeometrie("), s.indexOf("private static JSONObject persistCamViewGeometry("));
        for (String v : new String[] {"UnifiedConfigManager", "setCamViewValues", "geometryCluster", "updateValues"})
            assertFalse("Sitzungs-Lage schreibt nichts: " + v, sitzung.contains(v));
        String pipe = quelle("app/src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java");
        String ende = pipe.substring(pipe.indexOf("void endCamViewSessionLocked"));
        assertTrue("hide → Nutzerlage gilt wieder", ende.substring(0, Math.min(ende.length(), 1500)).contains("clearCamViewGeometryOverride();"));
        // Der Tacho setzt persist=0 bei jedem show
        String t = quelle("app/src/main/java/at/greenchili/surfmate/tacho/TachoKameraAnsicht.java");
        assertTrue(t.contains("\"&persist=0&autoHide=0\""));
        assertTrue(at.greenchili.surfmate.tacho.TachoFaehigkeitTestZugang.showPfad().contains("&persist=0&"));
    }
}
