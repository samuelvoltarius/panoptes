package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** 0.1.38.11 W1: EIN Wächter-Zustand mit Grund (Befund Seal U: bei Zündung an „an“, aber nicht scharf, ohne Grund). */
public class WaechterGrundTest {

    static Map<String, Object> s(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void zuendungAnIstBereitMitGrundNichtAus() {
        Map<String, Object> st = s("enabled", true, "active", false, "zustand", "bereit");
        assertEquals(WaechterGrund.ZUENDUNG_AN, WaechterGrund.grund(st, true));
        assertEquals(WaechterGrund.BEREIT, WaechterGrund.grund(st, false));
        assertEquals("Zündung unbekannt: nie raten", WaechterGrund.BEREIT, WaechterGrund.grund(st, null));
    }

    /** 0.1.43 (S2): bei Zündung an ist enabled immer false – eingeschaltet heißt die Einstellung (gewollt). */
    @Test
    public void gewolltZaehltBeiZuendungAn() {
        assertEquals(WaechterGrund.ZUENDUNG_AN, WaechterGrund.grund(s("enabled", false, "gewollt", true), true));
        assertEquals(WaechterGrund.BEREIT, WaechterGrund.grund(s("enabled", false, "gewollt", true), false));
        assertEquals("in den Einstellungen aus = aus", WaechterGrund.AUS,
                WaechterGrund.grund(s("enabled", false, "gewollt", false), true));
    }

    @Test
    public void reihenfolge() {
        assertEquals(WaechterGrund.AUS, WaechterGrund.grund(s("enabled", false), true));
        assertEquals(WaechterGrund.PASST_AUF, WaechterGrund.grund(s("enabled", true, "active", true), false));
        assertEquals(WaechterGrund.RUHEZONE, WaechterGrund.grund(s("enabled", true, "active", true, "inSafeZone", true), false));
        assertEquals(WaechterGrund.GESTOPPT, WaechterGrund.grund(
                s("enabled", true, "guardStopActive", true, "zustand", "unterdrueckt"), false));
        assertEquals("Stopp gilt nur, wenn der Wächter an ist", WaechterGrund.AUS,
                WaechterGrund.grund(s("enabled", false, "guardStopActive", true), false));
        assertEquals(WaechterGrund.STARTET, WaechterGrund.grund(s("enabled", true, "zustand", "startet"), true));
        assertEquals(WaechterGrund.PAUSIERT, WaechterGrund.grund(s("enabled", true, "zustand", "unterdrueckt"), false));
    }

    @Test
    public void zeileFuerFehlerMeldenOhneOrt() {
        String z = WaechterGrund.zeile(s("enabled", true, "active", false, "zustand", "bereit",
                "guardStopActive", false, "safeZoneName", "Zuhause"), true, false);
        assertEquals("WAECHTER_ZUSTAND grund=zuendung_an zustand=bereit an=true scharf=false zuendung=an verriegelt=nein", z);
        assertFalse("kein Ortsname", z.contains("Zuhause"));
        String g = WaechterGrund.zeile(s("enabled", true, "guardStopActive", true, "guardStopReason", "v12"), null, null);
        assertTrue(g, g.startsWith("WAECHTER_ZUSTAND grund=gestoppt stopp=v12 "));
        assertTrue(g, g.endsWith("zuendung=unbekannt verriegelt=unbekannt"));
    }
}
