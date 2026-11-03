package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Stream;

/**
 * 0.1.38 Launcher-Layout: Recht „launcher“ (Standard aus, nur Handy und Cockpit), nie Automationen, KI oder Telegram,
 * Pflicht-Ringdatei im Befehls-Log ohne Inhalt.
 */
public class LauncherLayoutRechteTest {

    private static final String L = "/api/launcher/v1/layout";

    @Test
    public void nurHandyUndCockpitUndNurDieseWege() {
        for (String art : new String[] {"handy", "cockpit"}) {
            assertFalse(art, ExternGuard.blocks("GET", L, art));
            assertFalse(art, ExternGuard.blocks("GET", L + "/apps", art));
            assertFalse(art, ExternGuard.blocks("PUT", L, art));
            assertFalse(art, ExternGuard.blocks("POST", L + "/standard", art));
            assertTrue(art, ExternGuard.blocks("POST", L, art));
            assertTrue(art, ExternGuard.blocks("PUT", L + "/standard", art));
            assertTrue(art, ExternGuard.blocks("DELETE", L, art));
            assertTrue(art, ExternGuard.blocks("GET", L + "/../klima", art));
            assertTrue(art, ExternGuard.blocks("POST", "/api/launcher/v1/klima", art));
        }
        for (String art : new String[] {"homeassistant", "dienst", "web", null}) {
            assertTrue(String.valueOf(art), ExternGuard.blocks("GET", L, art));
            assertTrue(String.valueOf(art), ExternGuard.blocks("PUT", L, art));
        }
        assertTrue(ExternGuard.blocks("GET", L));   // ohne Art: zu
    }

    /** 0.1.38 Koordination: Launcher-Stand lesen und „Öffnen“ aus der Ferne, nur mit Recht launcher. */
    @Test
    public void launcherStandUndOeffnenAusDerFerne() {
        String s = "/api/appupdate/launcher";
        for (String art : new String[] {"handy", "cockpit"}) {
            assertFalse(art, ExternGuard.blocks("GET", s, art));
            assertFalse(art, ExternGuard.blocks("POST", s, art));
            assertTrue(art, ExternGuard.blocks("POST", "/api/appupdate/installieren", art));
            assertTrue(art, ExternGuard.blocks("POST", "/api/appupdate/hinweis", art));
        }
        assertTrue(ExternGuard.blocks("GET", s, "homeassistant"));
        assertTrue(ExternGuard.blocks("POST", s, "dienst"));
        assertEquals("launcher", ExternGuard.noetigesRecht(s));
        assertTrue(WebHandyGuard.blocks("GET", s));
        assertTrue(WebHandyGuard.launcherErlaubt("GET", s, true));
        assertFalse(WebHandyGuard.launcherErlaubt("POST", s, false));
        // Handler: aus der Ferne genau „oeffnen“, installieren/entfernen nie
        assertTrue(AppUpdateApiHandler.nurOeffnen("{\"aktion\":\"oeffnen\"}"));
        assertFalse(AppUpdateApiHandler.nurOeffnen("{\"aktion\":\"installieren\"}"));
        assertFalse(AppUpdateApiHandler.nurOeffnen("{\"aktion\":\"entfernen\"}"));
        assertFalse(AppUpdateApiHandler.nurOeffnen("kaputt"));
        assertFalse(AppUpdateApiHandler.nurOeffnen(null));
    }

    @Test
    public void rechtLauncherNoetigUndFehlerText() throws Exception {
        assertEquals("launcher", ExternGuard.noetigesRecht(L));
        assertEquals("launcher", ExternGuard.noetigesRecht(L + "/apps"));
        assertEquals("launcher", ExternGuard.noetigesRecht(L + "/standard?x=1"));
        assertFalse(ExternGuard.rechtReicht("launcher", r -> false));
        assertFalse(ExternGuard.rechtReicht("launcher", r -> r.equals("web")));
        assertTrue(ExternGuard.rechtReicht("launcher", r -> r.equals("launcher")));
        assertEquals("launcher", ExternGuard.rechtFehltAntwort("launcher").getString("recht"));
        assertEquals(null, ExternGuard.noetigesRecht("/api/launcher/v1/summary"));
    }

    @Test
    public void webSitzungDerHandyAppNurMitRecht() {
        assertTrue(WebHandyGuard.blocks("GET", L));
        assertTrue(WebHandyGuard.blocks("PUT", L));
        assertTrue(WebHandyGuard.blocks("GET", L + "/apps"));
        assertFalse(WebHandyGuard.launcherErlaubt("PUT", L, false));
        assertTrue(WebHandyGuard.launcherErlaubt("PUT", L, true));
        assertTrue(WebHandyGuard.launcherErlaubt("GET", L + "/apps", true));
        assertFalse(WebHandyGuard.launcherErlaubt("POST", "/api/launcher/v1/klima", true));
        // Die Seite selbst bleibt sichtbar, ohne Recht kommt der Satz „… unter Gekoppelte Geräte einschalten“
        assertFalse(WebHandyGuard.blocks("GET", "/local/launcher.html"));
    }

    @Test
    public void automationenUndDamitKiNie() throws Exception {
        Method erlaubt = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        erlaubt.setAccessible(true);
        for (String p : new String[] {L, L + "/apps", L + "/standard"}) assertFalse(p, (Boolean) erlaubt.invoke(null, p));
        // Auch wenn ein Automationsweg den Handler direkt erreichte: 403
        Field f = HttpServer.class.getDeclaredField("AUTOMATION_REQUEST_DEPTH");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Integer> tiefe = (ThreadLocal<Integer>) f.get(null);
        tiefe.set(1);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LauncherApiHandler.handle("PUT", L, "{}", out);
            String antwort = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(antwort, antwort.contains("403"));
            assertTrue(antwort, antwort.contains("automation"));
        } finally {
            tiefe.remove();
        }
    }

    @Test
    public void keinTelegramUndKeineKiWege() throws Exception {
        File main = new File("src/main/java/at/greenchili/surfmate");
        for (String teil : new String[] {"telegram", "genai", "automation"}) {
            try (Stream<java.nio.file.Path> s = Files.walk(new File(main, teil).toPath())) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String q = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        assertFalse(p.toString(), q.contains("launcher/v1/layout") || q.contains("LauncherLayoutSpeicher"));
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                });
            }
        }
    }

    @Test
    public void befehlsLogPflichtOhneInhalt() throws Exception {
        boolean drin = false;
        for (String p : BefehlsLog.PFLICHT_PFADE) drin |= L.startsWith(p) || p.startsWith(L);
        assertTrue(drin);
        String q = new String(Files.readAllBytes(new File(
                "src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java").toPath()), StandardCharsets.UTF_8);
        // Ereignis nur mit Quelle und Ergebnis, nie mit Paket- oder Ordnernamen
        assertTrue(q.contains("BefehlsLog.ereignis(\"launcher.layout.\" + quelle, ergebnis, null, null)"));
    }
}
