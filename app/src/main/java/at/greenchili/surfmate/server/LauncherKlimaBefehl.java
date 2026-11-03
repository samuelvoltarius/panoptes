package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.37 Stufe 4: der einzige Fahrzeugweg des Launchers, {@code POST /api/launcher/v1/klima {aktion}} (03.11.:
 * „Klimasteuerung muss gehen“). Bewusst eng:
 *
 * <ul>
 *   <li>nur sechs Aktionen: {@code an}, {@code aus}, {@code temp_hoch}, {@code temp_runter} (eine Stufe am
 *       Fahrer-Drehrad, beide Zonen wie das BYD-Drehrad), {@code luft_hoch}, {@code luft_runter} (Gebläse ±1,
 *       1..7). Keine Zahlen von außen, keine anderen Klimaaktionen, sonst nichts am Auto;</li>
 *   <li>ausgeführt über die vorhandenen Klimabefehle des {@link VehicleCommandRouter}: dort gelten alle bestehenden
 *       Sperren (Fahrsperren, {@code CloudPowerGate}, Cloud nur bei Auto aus, {@code localOnlyWhenAwake}), und jeder
 *       Befehl landet mit Ausgang und Weg im Befehls-Log;</li>
 *   <li>Ratenlimit: höchstens ein Befehl je {@value #MIN_ABSTAND_MS} ms und {@value #MAX_IM_FENSTER} je
 *       {@value #FENSTER_MS} ms;</li>
 *   <li>wer aufrufen darf, prüft {@link LauncherAufrufer} vorher (nur die Launcher-APK mit Panoptes-Signatur).</li>
 * </ul>
 */
final class LauncherKlimaBefehl {

    static final Set<String> AKTIONEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "an", "aus", "temp_hoch", "temp_runter", "luft_hoch", "luft_runter")));

    static final long MIN_ABSTAND_MS = 250L;
    static final long FENSTER_MS = 30_000L;
    static final int MAX_IM_FENSTER = 20;
    static final int LUFT_MIN = 1, LUFT_MAX = 7;
    /** Sollwert beim Einschalten, wenn keiner lesbar ist (wie {@code power_on} ohne Angabe). */
    static final double SOLL_STANDARD_C = 22;

    /** Ergebnis eines Router-Befehls (nur Kennwörter). */
    static final class Ergebnis {
        final String ausgang, weg;
        Ergebnis(String ausgang, String weg) { this.ausgang = ausgang; this.weg = weg; }
    }

    /** Auto (im Test nachgebaut). */
    interface Auto {
        /** Gebläsestufe 1..7 oder null, wenn nicht lesbar. */
        Integer luft();
        /** Fahrer-Sollwert in °C oder null. */
        Double sollC();
        Ergebnis ausfuehren(VehicleCommand c);
    }

    static volatile Auto auto = new Echt();

    private static final Deque<Long> ZEITEN = new ArrayDeque<>();

    private LauncherKlimaBefehl() {}

    /**
     * Router-Befehl zur Aktion; null = nichts zu tun bzw. nicht möglich ({@code grund[0]} sagt warum: {@code aktion},
     * {@code stufe_unbekannt}, {@code grenze}).
     */
    static VehicleCommand befehl(String aktion, Integer luft, Double sollC, String[] grund) {
        if (aktion == null || !AKTIONEN.contains(aktion)) { grund[0] = "aktion"; return null; }
        switch (aktion) {
            case "an":
                return new VehicleCommandRouter.ClimateOnCommand(sollC != null ? sollC : SOLL_STANDARD_C, 20);
            case "aus":
                return new VehicleCommandRouter.ClimateOffCommand();
            case "temp_hoch":
            case "temp_runter":
                return new VehicleCommandRouter.ClimateStepTempCommand(0, BydDataCollector.AC_TEMP_AREA_DRIVER,
                        "temp_hoch".equals(aktion) ? 1 : -1);
            default: {
                if (luft == null || luft < LUFT_MIN || luft > LUFT_MAX) { grund[0] = "stufe_unbekannt"; return null; }
                int ziel = luft + ("luft_hoch".equals(aktion) ? 1 : -1);
                if (ziel < LUFT_MIN || ziel > LUFT_MAX) { grund[0] = "grenze"; return null; }
                return new VehicleCommandRouter.ClimateSetFanCommand(ziel);
            }
        }
    }

    /** true = darf jetzt (und zählt mit), false = Ratenlimit. */
    static synchronized boolean ratenlimit(long jetzt) {
        while (!ZEITEN.isEmpty() && jetzt - ZEITEN.peekFirst() >= FENSTER_MS) ZEITEN.pollFirst();
        Long letzte = ZEITEN.peekLast();
        if (letzte != null && jetzt - letzte < MIN_ABSTAND_MS) return false;
        if (ZEITEN.size() >= MAX_IM_FENSTER) return false;
        ZEITEN.addLast(jetzt);
        return true;
    }

    /** Für Tests. */
    static synchronized void ratenlimitLeeren() {
        ZEITEN.clear();
    }

    /**
     * Antwort {@code {status, json}}: 200 mit {@code ok, aktion, ausgang, weg}, 400 bei unbekannter Aktion, 409 wenn
     * nicht möglich, 429 beim Ratenlimit. Ablehnungen kommen mit Grund ins Befehls-Log.
     */
    static Object[] ausfuehren(String body, long jetzt) throws Exception {
        String aktion;
        try {
            aktion = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("aktion", "");
        } catch (Exception e) {
            aktion = "";
        }
        JSONObject o = new JSONObject().put("ok", false);
        if (!AKTIONEN.contains(aktion)) {
            BefehlsLog.grund("aktion");
            return new Object[]{400, o.put("grund", "aktion")};
        }
        o.put("aktion", aktion);
        if (!ratenlimit(jetzt)) {
            BefehlsLog.grund("ratenlimit");
            BefehlsLog.ereignis("launcher.klima." + aktion, "abgelehnt", null, null);
            return new Object[]{429, o.put("grund", "ratenlimit")};
        }
        Auto a = auto;
        String[] grund = {null};
        VehicleCommand c = befehl(aktion, a.luft(), a.sollC(), grund);
        if (c == null) {
            BefehlsLog.grund(grund[0]);
            BefehlsLog.ereignis("launcher.klima." + aktion, "abgelehnt", null, null);
            return new Object[]{409, o.put("grund", grund[0])};
        }
        BefehlsLog.ereignis("launcher.klima." + aktion, "ok", null, null);
        Ergebnis e = a.ausfuehren(c);
        boolean ok = "success".equals(e.ausgang);
        o.put("ok", ok).put("ausgang", e.ausgang).put("weg", e.weg);
        if (!ok) o.put("grund", e.ausgang);
        return new Object[]{200, o};
    }

    private static final class Echt implements Auto {
        @Override
        public Integer luft() {
            try {
                BydVehicleData d = BydDataCollector.getInstance().getData();
                return d == null || d.acFanLevel == BydVehicleData.UNAVAILABLE ? null : d.acFanLevel;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Double sollC() {
            try {
                BydVehicleData d = BydDataCollector.getInstance().getData();
                if (d == null || d.acSetpointDriver == BydVehicleData.UNAVAILABLE) return null;
                double c = VehicleControlApiHandler.climateSetpointToCelsius(d.acSetpointDriver);
                return c >= BydDataCollector.AC_SETPOINT_MIN_C && c <= BydDataCollector.AC_SETPOINT_MAX_C ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Ergebnis ausfuehren(VehicleCommand c) {
            VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance().execute(c);
            return new Ergebnis(r.outcome.name().toLowerCase(java.util.Locale.ROOT),
                    r.path == null ? "none" : r.path.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
