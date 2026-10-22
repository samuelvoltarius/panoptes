package at.greenchili.surfmate.ha;

import at.greenchili.surfmate.automation.AutomationAction;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Welche Panoptes-Regeln Home Assistant auslösen darf (HA_VERTRAG.md §5c, Regel A): nur Regeln, deren Aktionen
 * – auch verschachtelt in if/loop und im sonst-Zweig – ausschließlich Panoptes-intern sind. Im Zweifel nicht
 * (Koordination 21.10.: „regel im Zweifel nicht auslösbar“). Unbekannte Aktionsarten zählen als Zweifel.
 */
public final class HaRegeln {

    private HaRegeln() {}

    /** Nur Panoptes-intern: melden, warten, Variablen, MQTT an den eigenen Broker, Clip sichern. */
    static final Set<String> SICHER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "notification", "pause", "waitUntil", "waitUntilState", "setVariable", "incrementVariable",
            "computeVariable", "captureVariable", "mqttPublish", "manualClip", "if", "loop")));

    /** Wirken aufs Auto (Klima, Schloss, Fenster, Radio, Hupe …). */
    static final Set<String> FAHRZEUG = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "vehicle-control", "radio")));

    /** null = auslösbar, sonst "fahrzeugaktion" oder "unsicher". */
    public static String grund(List<AutomationAction> aktionen, List<AutomationAction> sonst) {
        String g = pruefe(aktionen, 0);
        if (g != null) return g;
        return pruefe(sonst, 0);
    }

    private static String pruefe(List<AutomationAction> l, int tiefe) {
        if (l == null) return null;
        if (tiefe > 10) return "unsicher";
        String schlimmster = null;
        for (AutomationAction a : l) {
            if (a == null) continue;
            String t = a.getType();
            if (t != null && FAHRZEUG.contains(t)) return "fahrzeugaktion";
            if (t == null || !SICHER.contains(t)) {
                schlimmster = "unsicher";
                continue;
            }
            String k = pruefe(a.getChildActions(), tiefe + 1);
            if ("fahrzeugaktion".equals(k)) return k;
            if (k != null) schlimmster = k;
            k = pruefe(a.getElseChildActions(), tiefe + 1);
            if ("fahrzeugaktion".equals(k)) return k;
            if (k != null) schlimmster = k;
        }
        return schlimmster;
    }
}
