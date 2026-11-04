package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LauncherHomeTest {

    private static final String OHNE_ALIAS = "2 activities found:\n  Activity #0:\n    priority=0 preferredOrder=0 match=0x108000 "
            + "specificIndex=-1 isDefault=true\n    com.android.launcher3/.home.MainActivity\n  Activity #1:\n    priority=-1000 "
            + "preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
            + "    com.byd.carsettings/com.byd.systemsettings.fallbackhome.FallbackHome\n";
    private static final String MIT_ALIAS = "3 activities found:\n  Activity #0:\n    priority=0 preferredOrder=0\n"
            + "    at.greenchili.panoptes.launcher/.HomeAlias\n  Activity #1:\n    priority=0 preferredOrder=0\n"
            + "    com.android.launcher3/.home.MainActivity\n  Activity #2:\n    priority=-1000\n"
            + "    com.byd.carsettings/com.byd.systemsettings.fallbackhome.FallbackHome\n";
    private static final String WIR = "priority=0\nat.greenchili.panoptes.launcher/.HomeAlias\n";
    private static final String BYD = "priority=0\ncom.android.launcher3/.home.MainActivity\n";

    private static LauncherPaket.Geraet geraet(String abfrage, String aufloesen, List<String> befehle) {
        return (b, s) -> {
            befehle.add(b);
            if (b.equals(LauncherHome.ABFRAGE)) return abfrage;
            if (b.equals(LauncherHome.AUFLOESEN)) return aufloesen;
            return "";
        };
    }

    @Test
    public void ohneAliasWirdNichtsAngeboten() {
        LauncherHome.Stand s = LauncherHome.lesen(geraet(OHNE_ALIAS, BYD, new ArrayList<>()));
        assertFalse(s.aliasAn);
        assertFalse(s.standard);
        assertNotNull(s.original);
        assertEquals("com.android.launcher3/com.android.launcher3.home.MainActivity", s.original.komponente());
    }

    @Test
    public void originalIstNieFallbackHomeNieWirSelbstNieAuswahl() {
        LauncherHome.Stand s = LauncherHome.lesen(geraet(MIT_ALIAS, WIR, new ArrayList<>()));
        assertTrue(s.aliasAn);
        assertTrue(s.standard);
        assertEquals("com.android.launcher3", s.original.paket);
        String nurFremdkram = "x\n  at.greenchili.panoptes.launcher/.HomeAlias\n"
                + "  android/com.android.internal.app.ResolverActivity\n  com.android.settings/.FallbackHome\n";
        assertNull(LauncherHome.lesen(geraet(nurFremdkram, "", new ArrayList<>())).original);
    }

    @Test
    public void standardUndRueckwegNurMitFestenBefehlen() {
        List<String> b = new ArrayList<>();
        final boolean[] zurueck = {false};
        LauncherPaket.Geraet g = (c, s) -> {
            b.add(c);
            if (c.equals(LauncherHome.ABFRAGE)) return MIT_ALIAS;
            if (c.equals(LauncherHome.AUFLOESEN)) return zurueck[0] ? BYD : WIR;
            if (c.startsWith("cmd package set-home-activity --user 0 com.android.launcher3")) {
                zurueck[0] = true;
                return "Success\n";
            }
            return "";
        };
        assertNull(LauncherHome.alsStandardSetzen(g));
        LauncherHome.Stand s = LauncherHome.lesen(g);
        assertTrue(s.standard);
        b.clear();
        assertNull(LauncherHome.zurueckZumOriginal(g, s));
        assertEquals("cmd package set-home-activity --user 0 com.android.launcher3", b.get(0));
        assertTrue(b.get(b.size() - 1).startsWith("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                + "com.android.launcher3/"));
    }

    @Test
    public void rueckwegMeldetFehlerWennNochStandardOderKeinOriginal() {
        LauncherHome.Stand s = LauncherHome.lesen((c, x) -> c.equals(LauncherHome.ABFRAGE) ? MIT_ALIAS : "");
        assertEquals("zurueck:noch_standard", LauncherHome.zurueckZumOriginal((c, x) -> {
            if (c.equals(LauncherHome.ABFRAGE)) return MIT_ALIAS;
            if (c.equals(LauncherHome.AUFLOESEN)) return WIR;
            return "Success\n";
        }, s));
        assertTrue(LauncherHome.zurueckZumOriginal((c, x) -> "Error: Failed", s).startsWith("zurueck:"));
        assertEquals("kein_original", LauncherHome.zurueckZumOriginal((c, x) -> "", null));
    }

    @Test
    public void paketAusAnNurDiesesPaket() {
        List<String> b = new ArrayList<>();
        LauncherPaket.Geraet g = (c, s) -> {
            b.add(c);
            return c.startsWith("pm disable-user") ? "Package at.greenchili.panoptes.launcher new state: disabled-user\n"
                    : "Package at.greenchili.panoptes.launcher new state: enabled\n";
        };
        assertNull(LauncherHome.paketAus(g));
        assertNull(LauncherHome.paketAn(g));
        assertEquals("pm disable-user --user 0 at.greenchili.panoptes.launcher", b.get(0));
        assertEquals("pm enable at.greenchili.panoptes.launcher", b.get(1));
        assertTrue(LauncherHome.paketAus((c, x) -> "Error").startsWith("paket_aus:"));
    }

    @Test
    public void originalOeffnenStartetNurFremdesHome() {
        List<String> b = new ArrayList<>();
        LauncherHome.Stand s = LauncherHome.lesen(geraet(MIT_ALIAS, "", b));
        b.clear();
        assertNull(LauncherHome.originalOeffnen(geraet(MIT_ALIAS, "", b), s));
        assertEquals("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                + "com.android.launcher3/com.android.launcher3.home.MainActivity", b.get(0));
        assertEquals("kein_original", LauncherHome.originalOeffnen((x, y) -> "", null));
    }

    @Test
    public void alleBefehleFestOhneShellZeichen() {
        List<String> b = new ArrayList<>();
        LauncherPaket.Geraet g = (c, s) -> {
            b.add(c);
            return c.equals(LauncherHome.ABFRAGE) ? MIT_ALIAS : c.equals(LauncherHome.AUFLOESEN) ? WIR
                    : "Success disabled enabled\n";
        };
        LauncherHome.Stand s = LauncherHome.lesen(g);
        LauncherHome.alsStandardSetzen(g);
        LauncherHome.zurueckZumOriginal(g, s);
        LauncherHome.originalOeffnen(g, s);
        LauncherHome.paketAus(g);
        LauncherHome.paketAn(g);
        assertFalse(b.isEmpty());
        for (String c : b) {
            assertTrue(c, c.equals("pm enable at.greenchili.panoptes.launcher")
                    || c.equals("pm disable-user --user 0 at.greenchili.panoptes.launcher")
                    || c.equals(LauncherHome.ABFRAGE) || c.equals(LauncherHome.AUFLOESEN)
                    || c.equals("cmd package set-home-activity --user 0 at.greenchili.panoptes.launcher")
                    || c.equals("cmd package set-home-activity --user 0 com.android.launcher3")
                    || c.startsWith("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "));
            for (char z : new char[]{';', '&', '|', '$', '`', '\n', '>', '<'}) {
                assertFalse(c, c.indexOf(z) >= 0);
            }
        }
    }

    @Test
    public void paketDeaktiviertErkenntNurExakteZeile() {
        assertTrue(LauncherHome.paketDeaktiviert((c, x) -> "package:com.foo\npackage:at.greenchili.panoptes.launcher\n"));
        assertFalse(LauncherHome.paketDeaktiviert((c, x) -> "package:com.foo\npackage:at.greenchili.panoptes.launcher.extra\n"));
        assertFalse(LauncherHome.paketDeaktiviert((c, x) -> ""));
        assertFalse(LauncherHome.paketDeaktiviert((c, x) -> null));
        List<String> b = new ArrayList<>();
        LauncherHome.paketDeaktiviert((c, x) -> { b.add(c); return ""; });
        assertEquals("pm list packages -d", b.get(0));
    }
}
