package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.action.Action;
import at.greenchili.surfmate.automation.action.Actions;
import at.greenchili.surfmate.automation.action.ApiAction;
import at.greenchili.surfmate.genai.KiSperre;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.1.37 Integration: Keine neue Aktion aus Tacho, Assistenz, Display und Launcher ist für den KI-Assistenten oder
 * eine Automation erreichbar. Die Erlaubnisliste ist gegenüber 0.1.36.1 nicht gewachsen.
 */
public class KiSperre0137Test {

    /** Alle neuen schreibenden oder neuen lesenden Routen der vier 0.1.37-Zweige. */
    static final List<String> NEUE_ROUTEN = new ArrayList<>(Arrays.asList(
            // Tacho
            "/api/tacho/ansicht", "/api/tacho/navi/hinweis", "/api/tacho/rueckmeldung", "/api/tacho/normal",
            "/api/tacho/navi/fahrt", "/api/tacho/navi/fahrt/hinweis", "/api/tacho/auswahl",
            // Assistenz
            "/api/assistenz/hinweis",
            // Launcher
            "/api/launcher/v1/summary", "/api/launcher/v1/hintergrund/bild", "/api/launcher/v1/hintergrund/video",
            "/api/launcher/v1/vehicle/flash", "/api/launcher/v1/vehicle/find", "/api/launcher/v1/vehicle/climate"));

    static {
        // Display-Werkzeuge (Liste des Zweigs, damit beide Tests dieselben Wege meinen)
        NEUE_ROUTEN.addAll(Arrays.asList(DisplayWerkzeugeRechteTest.NEUE_AKTIONEN));
    }

    /** Stand 0.1.36.1: genau diese Typen darf die KI auslösen. */
    static final Set<String> ERLAUBT_01361 = new HashSet<>(Arrays.asList(
            "acauto", "acsync", "acautooff", "fanonly", "steeringheat", "frontdefrost", "reardefrost",
            "recirculation", "setac", "setactemp", "stepactemp", "setacfan", "remoteclimateschedule",
            "remoteclimatescheduleupdate", "remoteclimatescheduledelete",
            "seat", "sunshade",
            "showtoast", "showdialog", "notification", "genaiinsight",
            "pause", "waituntil", "waituntilstate", "setvariable", "incrementvariable", "computevariable",
            "capturevariable", "loop", "if", "actiongroup", "automationcontrol"));

    @Test
    public void automationenErreichenKeineNeueRoute() throws Exception {
        Method erlaubt = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        erlaubt.setAccessible(true);
        for (String p : NEUE_ROUTEN) {
            assertFalse("Automation/KI erreicht " + p, (Boolean) erlaubt.invoke(null, p));
            assertFalse("Automation/KI erreicht " + p + "?x", (Boolean) erlaubt.invoke(null, p + "?x=1"));
        }
        for (String p : new String[]{"/api/tacho", "/api/assistenz", "/api/launcher", "/api/display"}) {
            assertFalse(p, (Boolean) erlaubt.invoke(null, p));
            assertFalse(p + "/", (Boolean) erlaubt.invoke(null, p + "/"));
        }
    }

    @Test
    public void erlaubnislisteIstNichtGewachsen() throws Exception {
        Field f = KiSperre.class.getDeclaredField("ERLAUBT");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> jetzt = (Set<String>) f.get(null);
        assertEquals(ERLAUBT_01361, new HashSet<>(jetzt));
        for (String typ : new String[]{"api", "tachoansicht", "tachorueckmeldung", "assistenzhinweis", "wallpaper",
                "hintergrund", "valet", "displaylock", "appdisable", "launcher", "launcherclimate", "files"}) {
            assertFalse(typ, KiSperre.erlaubterTyp(typ));
            assertNotNull(typ, KiSperre.regelGesperrt(regel(typ)));
        }
    }

    /** Kommt eine Katalog-Aktion an einen neuen Weg, ist sie für die KI gesperrt (auch in Wenn und Gruppe). */
    @Test
    @SuppressWarnings("unchecked")
    public void katalogAktionenAufNeueWegeSindFuerDieKiGesperrt() throws Exception {
        Field af = Actions.class.getDeclaredField("actions");
        af.setAccessible(true);
        Field pf = ApiAction.class.getDeclaredField("path");
        pf.setAccessible(true);
        Map<String, Action> alle = (Map<String, Action>) af.get(new Actions());
        for (Action a : alle.values()) {
            if (!(a instanceof ApiAction)) continue;
            String path = (String) pf.get(a);
            if (path == null) continue;
            for (String p : NEUE_ROUTEN) {
                String wurzel = p.substring(0, p.indexOf('/', 5) + 1);
                if (!path.startsWith(wurzel)) continue;
                String id = a.getLabel().getId().toLowerCase(Locale.ROOT);
                assertFalse(id + " → " + path, KiSperre.erlaubterTyp(id));
                assertNotNull(id, KiSperre.regelGesperrt(regel(id)));
            }
        }
        JSONObject wenn = new JSONObject().put("type", "if").put("variables", new JSONObject())
                .put("childActions", new JSONArray().put(aktion("showToast")))
                .put("elseActions", new JSONArray().put(aktion("api")));
        assertNotNull(KiSperre.regelGesperrt(new JSONObject().put("actions", new JSONArray().put(wenn))));
    }

    private static JSONObject aktion(String typ) throws Exception {
        return new JSONObject().put("type", typ).put("variables", new JSONObject());
    }

    private static JSONObject regel(String typ) throws Exception {
        return new JSONObject().put("actions", new JSONArray().put(aktion(typ)));
    }
}
