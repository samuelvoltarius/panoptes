package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Apps deaktivieren: keine System-, BYD- oder Panoptes-App lässt sich deaktivieren, nur nach Prüfung, mit Rücklesen. */
public class AppSperreTest {

    /** Ein Gerät, auf dem auch BYD- und Systemteile fälschlich als Nutzer-App gemeldet werden. */
    private static final Set<String> NUTZER = new LinkedHashSet<>(Arrays.asList(
            "com.spotify.music", "org.videolan.vlc", "com.byd.carlauncher", "com.android.chrome",
            "com.google.android.apps.maps", "android.ext.services", "at.greenchili.surfmate",
            "at.greenchili.panoptes.launcher", "com.tailscale.ipn", "moe.shizuku.privileged.api",
            "com.bydauto.radio", "com.example.home", "com.example.keyboard"));

    @Test
    public void keineSystemOderBydAppLaesstSichDeaktivieren() {
        for (String p : new String[]{"com.byd.carlauncher", "com.byd.wallpaperhome", "com.bydauto.radio",
                "com.android.chrome", "com.android.settings", "com.google.android.apps.maps",
                "com.google.android.gms", "android", "android.ext.services", "at.greenchili.surfmate",
                "at.greenchili.panoptes.launcher", "com.tailscale.ipn", "moe.shizuku.privileged.api",
                "COM.BYD.Upper"}) {
            assertTrue(p, AppSperre.hartGesperrt(p));
            // auch wenn das Gerät sie als Nutzer-App meldet: nie erlaubt
            String g = AppSperre.grundGegenDeaktivieren(p, set(p), null, null);
            assertTrue(p + " -> " + g, "gesperrt".equals(g) || "name_ungueltig".equals(g));
        }
    }

    @Test
    public void nurEchteNutzerAppsAberNichtStartseiteOderTastatur() {
        assertNull(AppSperre.grundGegenDeaktivieren("com.spotify.music", NUTZER, "com.example.home", "com.example.keyboard"));
        assertEquals("keine_nutzer_app", AppSperre.grundGegenDeaktivieren("com.whatsapp", NUTZER, null, null));
        assertEquals("startseite", AppSperre.grundGegenDeaktivieren("com.example.home", NUTZER, "com.example.home", null));
        assertEquals("tastatur", AppSperre.grundGegenDeaktivieren("com.example.keyboard", NUTZER, null, "com.example.keyboard"));
        assertEquals("gesperrt", AppSperre.grundGegenDeaktivieren("com.byd.carlauncher", NUTZER, null, null));
    }

    @Test
    public void namenMitShellZeichenWerdenNieAngenommen() {
        for (String p : new String[]{null, "", "spotify", "com.x;reboot", "com.x && rm", "com.x/../y", "com x.y",
                "com.x\nandroid", "-com.x", "com.x'", "com.x$(id)"}) {
            assertFalse(String.valueOf(p), AppSperre.gueltigerName(p));
            assertEquals("name_ungueltig", AppSperre.grundGegenDeaktivieren(p, NUTZER, null, null));
        }
        try {
            AppSperre.befehlDeaktivieren("com.byd.carlauncher");
            throw new AssertionError("Befehl für BYD-App gebaut");
        } catch (IllegalArgumentException erwartet) {
            // gut
        }
        assertEquals(Arrays.asList("pm", "disable-user", "--user", "0", "com.spotify.music"),
                AppSperre.befehlDeaktivieren("com.spotify.music"));
        assertEquals(Arrays.asList("pm", "enable", "--user", "0", "com.spotify.music"),
                AppSperre.befehlAktivieren("com.spotify.music"));
    }

    @Test
    public void ausgabeVonPmUndResolveWirdSauberGelesen() {
        Set<String> p = AppSperre.pakete("package:com.a.b\r\npackage:/data/app/x.apk=org.c.d\nWARNING x\npackage:bad name\n");
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.a.b", "org.c.d")), p);
        assertEquals("com.byd.launcher", AppSperre.paketAusKomponente("priority=0 preferredOrder=0\ncom.byd.launcher/.Home"));
        assertEquals("com.ex.ime", AppSperre.paketAusKomponente("com.ex.ime/.Service\n"));
        assertNull(AppSperre.paketAusKomponente("null"));
    }

    /** Gerät nachgebildet: Liste, Startseite, Tastatur, disable/enable. */
    private static final class FalscheShell implements AppSperre.Shell {
        final Set<String> nutzer = new LinkedHashSet<>(NUTZER);
        final Set<String> aus = new HashSet<>();
        final List<List<String>> befehle = new ArrayList<>();
        boolean disableWirkt = true;

        @Override
        public String run(List<String> argv) {
            befehle.add(argv);
            String a = String.join(" ", argv);
            if (a.equals("pm list packages -3")) return liste(nutzer);
            if (a.equals("pm list packages -d -3")) return liste(aus);
            if (a.startsWith("cmd package resolve-activity")) return "com.byd.launcher/.Home";
            if (a.startsWith("settings get secure default_input_method")) return "com.example.keyboard/.Ime";
            if (a.startsWith("pm disable-user --user 0 ")) {
                if (disableWirkt) aus.add(argv.get(4));
                return "Package " + argv.get(4) + " new state: disabled-user";
            }
            if (a.startsWith("pm enable --user 0 ")) {
                aus.remove(argv.get(4));
                return "Package " + argv.get(4) + " new state: enabled";
            }
            return null;
        }

        private static String liste(Set<String> s) {
            StringBuilder b = new StringBuilder();
            for (String p : s) b.append("package:").append(p).append('\n');
            return b.toString();
        }
    }

    @Test
    public void deaktivierenUndWiederAktivierenMitRuecklesen() {
        FalscheShell sh = new FalscheShell();
        assertNull(AppSperre.deaktivieren(sh, "com.spotify.music"));
        assertTrue(sh.aus.contains("com.spotify.music"));
        assertNull(AppSperre.aktivieren(sh, "com.spotify.music"));
        assertFalse(sh.aus.contains("com.spotify.music"));
        assertEquals("nicht_deaktiviert", AppSperre.aktivieren(sh, "com.spotify.music"));
        sh.disableWirkt = false;
        assertEquals("nicht_bestaetigt", AppSperre.deaktivieren(sh, "org.videolan.vlc"));
    }

    @Test
    public void fuerGesperrteAppsWirdNieEinPmBefehlAbgesetzt() {
        FalscheShell sh = new FalscheShell();
        for (String p : NUTZER) {
            if (!AppSperre.hartGesperrt(p) && !p.equals("com.example.keyboard")) continue;
            assertTrue(p, AppSperre.deaktivieren(sh, p) != null);
        }
        for (List<String> b : sh.befehle) assertFalse(String.join(" ", b), b.contains("disable-user"));
        assertTrue(sh.aus.isEmpty());
    }

    private static Set<String> set(String p) {
        return new HashSet<>(Arrays.asList(p));
    }
}
