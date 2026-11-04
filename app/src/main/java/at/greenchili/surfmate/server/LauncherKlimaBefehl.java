package at.greenchili.surfmate.server;

import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.byd.KlimaStufe;
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
 *   <li>0.1.38.6 Komfort (04.11.: „Sitzheizung, Klimaanlage und so weiter“): dazu feste Namen für Sitzheizung und
 *       Sitzlüftung je Sitz (Zyklus aus, 1, 2 aus dem gelesenen Ist-Stand), Lenkradheizung und Umluft (Umschalten aus dem
 *       gelesenen Ist-Stand) sowie Auto-Modus, Entfrosten vorn/hinten und Nur-Gebläse als {@code _an}/{@code _aus}-Paar
 *       (kein Lese-Wert vorhanden, die Antwort sagt nur „gesendet/angenommen“). Nie Hupe, Licht, Blinker, Auf-/Zusperren,
 *       „Auto finden“, Alarm, Fenster, Dach, Kofferraum, Spiegel, Sitzspeicher: {@link #AKTIONEN} ist die ganze Liste.</li>
 *   <li>nur diese Aktionen (zuerst die sechs Klima-Aktionen): {@code an}, {@code aus}, {@code temp_hoch}, {@code temp_runter} (eine Stufe am
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

    static final Set<String> KLIMA_AKTIONEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "an", "aus", "temp_hoch", "temp_runter", "luft_hoch", "luft_runter")));

    /** 0.1.38.6: Komfort-Aktionen (Sitze, Lenkrad, Umluft: aus dem Ist-Stand; der Rest ohne Lese-Wert als an/aus-Paar). */
    static final Set<String> KOMFORT_AKTIONEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "sitz_fahrer", "sitz_beifahrer", "lueftung_fahrer", "lueftung_beifahrer", "lenkrad", "umluft",
            "auto_an", "auto_aus", "entfrosten_vorn_an", "entfrosten_vorn_aus",
            "entfrosten_hinten_an", "entfrosten_hinten_aus", "nur_geblaese_an", "nur_geblaese_aus")));

    static final Set<String> AKTIONEN;
    static {
        Set<String> alle = new HashSet<>(KLIMA_AKTIONEN);
        alle.addAll(KOMFORT_AKTIONEN);
        AKTIONEN = Collections.unmodifiableSet(alle);
    }

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
        /** 0.1.38.5: roher Fahrer-Sollwert des SDK (beim Surf die Stufe + 16, 17..33) oder null. */
        default Integer sollRoh() { return null; }
        /** 0.1.38.5: Klima in festen Stufen statt Grad (Dolphin Surf, {@link KlimaStufe#stufenModell()}). */
        default boolean stufenModell() { return false; }
        /** 0.1.38.6: wie die Temperatur bedient wird, {@link KlimaStufe#ART_STUFEN}/{@code ART_GRAD}/{@code ART_UNBEKANNT}. */
        default String tempArt() { return stufenModell() ? KlimaStufe.ART_STUFEN : KlimaStufe.ART_GRAD; }
        /** 0.1.38.6: aktueller Fahrzeugstand (Sitze, Lenkrad, Umluft) oder null, wenn nicht lesbar. */
        default BydVehicleData daten() { return null; }
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
        return befehl(aktion, luft, sollC, null, false, grund);
    }

    /**
     * Wie oben. {@code stufen} = das Auto stellt die Temperatur in festen Stufen (Surf): dann setzt {@code temp_*} die
     * Stufe ± 1 absolut über {@code ClimateSetTempCommand(1, …)} – genau der Weg, den Panoptes selbst nutzt
     * (vehicle-control {@code set_temp}, Zone 1, Wert = Stufe + 16). Der relative Dreh-Befehl verweigert am Surf still.
     */
    static VehicleCommand befehl(String aktion, Integer luft, Double sollC, Integer sollRoh, boolean stufen, String[] grund) {
        if (aktion == null || !KLIMA_AKTIONEN.contains(aktion)) { grund[0] = "aktion"; return null; }
        if (stufen && ("temp_hoch".equals(aktion) || "temp_runter".equals(aktion))) {
            int stufe = sollRoh == null ? -1 : KlimaStufe.ausSdk(sollRoh);
            if (!KlimaStufe.gueltig(stufe)) { grund[0] = "stufe_unbekannt"; return null; }
            int ziel = stufe + ("temp_hoch".equals(aktion) ? 1 : -1);
            if (!KlimaStufe.gueltig(ziel)) { grund[0] = "grenze"; return null; }
            return new VehicleCommandRouter.ClimateSetTempCommand(1, KlimaStufe.zuSdk(ziel));
        }
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

    /** Sitzstufe 0..2 oder -1, wenn der Sitz-Stand nicht frisch und vollständig lesbar ist. */
    static int sitzStufe(BydVehicleData d, boolean heizung, int idx) {
        if (!VehicleCommandRouter.hasFreshCompleteSeatState(d)) return -1;
        int v = (heizung ? d.seatHeat : d.seatCool)[idx];
        return v >= 0 && v <= 2 ? v : -1;
    }

    /** Lenkradheizung: 1 an, 0 aus, -1 nicht lesbar (SDK: 2 = an, 1 = aus). */
    static int lenkradStand(BydVehicleData d) {
        if (d == null) return -1;
        return d.steeringWheelHeat == 2 ? 1 : d.steeringWheelHeat == 1 ? 0 : -1;
    }

    /** Umluft: 1 Umluft, 0 Frischluft, -1 nicht lesbar (SDK AC_CYCLE_MODE: 0 Frischluft, 1 Umluft). */
    static int umluftStand(BydVehicleData d) {
        if (d == null) return -1;
        return d.acCycleMode == 1 ? 1 : d.acCycleMode == 0 ? 0 : -1;
    }

    /**
     * 0.1.38.6: Router-Befehl zur Komfort-Aktion; null = nicht möglich ({@code grund[0]}: {@code aktion},
     * {@code stufe_unbekannt}). Sitze/Lenkrad/Umluft stellen immer den Gegenzustand des GELESENEN Ist-Stands ein; ist der
     * nicht lesbar, wird nichts gesendet. Die Sitzbefehle tragen den Stand der übrigen Zonen wie im HA-Katalog mit.
     */
    static VehicleCommand komfortBefehl(String aktion, BydVehicleData d, String[] grund) {
        if (aktion == null || !KOMFORT_AKTIONEN.contains(aktion)) { grund[0] = "aktion"; return null; }
        switch (aktion) {
            case "sitz_fahrer": case "sitz_beifahrer": case "lueftung_fahrer": case "lueftung_beifahrer": {
                boolean heizung = aktion.startsWith("sitz_");
                int idx = aktion.endsWith("_fahrer") ? 0 : 1;
                int jetzt = sitzStufe(d, heizung, idx);
                if (jetzt < 0) { grund[0] = "stufe_unbekannt"; return null; }
                int ziel = (jetzt + 1) % 3;
                int dh = d.seatHeat[0], ph = d.seatHeat[1], dv = d.seatCool[0], pv = d.seatCool[1];
                if (heizung) { if (idx == 0) dh = ziel; else ph = ziel; }
                else { if (idx == 0) dv = ziel; else pv = ziel; }
                long stand = d.seatClimateAtMs;
                return heizung
                        ? new VehicleCommandRouter.SeatHeatCommand(idx + 1, ziel, dh, dv, ph, pv, true, stand)
                        : new VehicleCommandRouter.SeatVentCommand(idx + 1, ziel, dh, dv, ph, pv, true, stand);
            }
            case "lenkrad": {
                int jetzt = lenkradStand(d);
                if (jetzt < 0) { grund[0] = "stufe_unbekannt"; return null; }
                return new VehicleCommandRouter.SteeringWheelHeatCommand(jetzt == 0);
            }
            case "umluft": {
                int jetzt = umluftStand(d);
                if (jetzt < 0) { grund[0] = "stufe_unbekannt"; return null; }
                return new VehicleCommandRouter.AcRecirculationCommand(jetzt == 0);
            }
            case "auto_an": return new VehicleCommandRouter.AcAutoModeCommand(true);
            case "auto_aus": return new VehicleCommandRouter.AcAutoModeCommand(false);
            case "entfrosten_vorn_an": return new VehicleCommandRouter.FrontDefrostCommand(true);
            case "entfrosten_vorn_aus": return new VehicleCommandRouter.FrontDefrostCommand(false);
            case "entfrosten_hinten_an": return new VehicleCommandRouter.RearDefrostCommand(true);
            case "entfrosten_hinten_aus": return new VehicleCommandRouter.RearDefrostCommand(false);
            case "nur_geblaese_an": return new VehicleCommandRouter.FanOnlyModeCommand(true);
            default: return new VehicleCommandRouter.FanOnlyModeCommand(false);   // nur_geblaese_aus
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
        // 0.1.38.6: Modell weder als Stufen- noch als Grad-Modell bestätigt: keine Temperatur-Bedienung, nie raten (der
        // relative Dreh-Befehl verweigert am Surf still und würde bei Stufen-Autos eine erfundene Gradzahl vortäuschen)
        if (("temp_hoch".equals(aktion) || "temp_runter".equals(aktion)) && KlimaStufe.ART_UNBEKANNT.equals(a.tempArt())) {
            BefehlsLog.grund("temperatur_unbekannt");
            BefehlsLog.ereignis("launcher.klima." + aktion, "abgelehnt", null, null);
            return new Object[]{409, o.put("grund", "temperatur_unbekannt")};
        }
        VehicleCommand c = KOMFORT_AKTIONEN.contains(aktion)
                ? komfortBefehl(aktion, a.daten(), grund)
                : befehl(aktion, a.luft(), a.sollC(), a.sollRoh(), a.stufenModell(), grund);
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
        public BydVehicleData daten() {
            try {
                return BydDataCollector.getInstance().getData();
            } catch (Throwable t) {
                return null;
            }
        }

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
        public Integer sollRoh() {
            try {
                BydVehicleData d = BydDataCollector.getInstance().getData();
                return d == null || d.acSetpointDriver == BydVehicleData.UNAVAILABLE ? null : d.acSetpointDriver;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean stufenModell() {
            return KlimaStufe.stufenModell();
        }

        @Override
        public String tempArt() {
            return KlimaStufe.temperaturArt();
        }

        @Override
        public Ergebnis ausfuehren(VehicleCommand c) {
            VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance().execute(c);
            return new Ergebnis(r.outcome.name().toLowerCase(java.util.Locale.ROOT),
                    r.path == null ? "none" : r.path.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
