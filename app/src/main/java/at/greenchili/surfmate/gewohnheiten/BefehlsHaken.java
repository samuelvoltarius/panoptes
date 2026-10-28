package at.greenchili.surfmate.gewohnheiten;

import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2/§2a): Haken in {@code VehicleCommandRouter.execute} – der einen Engstelle für Klima,
 * Sitze und Lenkradheizung, durch die Display, Handy, HA/MQTT, Automationen, AcAutoOffTimer, Camping-Heizweg und die
 * Cloud-Abschreckung gehen.
 *
 * <ul>
 *   <li>{@link #vor}: JEDER Befehl (egal welche Herkunft) trägt seine Wirkung in die Erwartungen ein – damit die
 *   Zustandsänderung danach nicht als „am Auto“ zählt.</li>
 *   <li>{@link #nach}: nur bei Erfolg wird die Handlung mit der Herkunft des Threads mitgeschrieben (Automationen haben
 *   keine gesetzte Herkunft → zählen nicht).</li>
 * </ul>
 * Alles andere (Aufsperren, Licht, Hupe, Fenster …) wird weder erwartet noch mitgeschrieben.
 */
public final class BefehlsHaken {

    private BefehlsHaken() {}

    public static void vor(VehicleCommandRouter.VehicleCommand cmd) {
        try {
            if (cmd instanceof VehicleCommandRouter.ClimateOnCommand) {
                Gewohnheiten.erwarte(AutoBeobachter.KLIMA, 1);
                Gewohnheiten.erwarte(AutoBeobachter.TEMP_FAHRER, (int) Math.round(((VehicleCommandRouter.ClimateOnCommand) cmd).tempCelsius));
            } else if (cmd instanceof VehicleCommandRouter.ClimateRemoteOnCommand) {
                Gewohnheiten.erwarte(AutoBeobachter.KLIMA, 1);
                Gewohnheiten.erwarte(AutoBeobachter.TEMP_FAHRER, ((VehicleCommandRouter.ClimateRemoteOnCommand) cmd).tempCelsius);
            } else if (cmd instanceof VehicleCommandRouter.ClimateOffCommand || cmd instanceof VehicleCommandRouter.ClimateRemoteOffCommand) {
                Gewohnheiten.erwarte(AutoBeobachter.KLIMA, 0);
            } else if (cmd instanceof VehicleCommandRouter.ClimateSetTempCommand) {
                VehicleCommandRouter.ClimateSetTempCommand c = (VehicleCommandRouter.ClimateSetTempCommand) cmd;
                int ziel = (int) Math.round(c.tempCelsius);
                if (c.zone != 2) Gewohnheiten.erwarte(AutoBeobachter.TEMP_FAHRER, ziel);
                if (c.zone != 1) Gewohnheiten.erwarte(AutoBeobachter.TEMP_BEIFAHRER, ziel);
            } else if (cmd instanceof VehicleCommandRouter.SeatHeatCommand) {
                VehicleCommandRouter.SeatHeatCommand c = (VehicleCommandRouter.SeatHeatCommand) cmd;
                Gewohnheiten.erwarte(c.position == 1 ? AutoBeobachter.HEIZ_FAHRER : AutoBeobachter.HEIZ_BEIFAHRER, c.level);
            } else if (cmd instanceof VehicleCommandRouter.SeatVentCommand) {
                VehicleCommandRouter.SeatVentCommand c = (VehicleCommandRouter.SeatVentCommand) cmd;
                Gewohnheiten.erwarte(c.position == 1 ? AutoBeobachter.LUEFT_FAHRER : AutoBeobachter.LUEFT_BEIFAHRER, c.level);
            } else if (cmd instanceof VehicleCommandRouter.SteeringWheelHeatCommand) {
                Gewohnheiten.erwarte(AutoBeobachter.LENKRAD, ((VehicleCommandRouter.SteeringWheelHeatCommand) cmd).on ? 1 : 0);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void nach(VehicleCommandRouter.VehicleCommand cmd, VehicleCommandRouter.CommandResult r) {
        try {
            if (r == null || r.outcome != VehicleCommandRouter.Outcome.SUCCESS) return;
            String q = Herkunft.aktuell();
            if (cmd instanceof VehicleCommandRouter.ClimateOnCommand) {
                Gewohnheiten.melde(Aktionen.KLIMA_AN, new JSONObject().put("temp",
                        Gewohnheiten.temp(((VehicleCommandRouter.ClimateOnCommand) cmd).tempCelsius)), q);
            } else if (cmd instanceof VehicleCommandRouter.ClimateRemoteOnCommand) {
                Gewohnheiten.melde(Aktionen.KLIMA_AN, new JSONObject().put("temp",
                        ((VehicleCommandRouter.ClimateRemoteOnCommand) cmd).tempCelsius), q);
            } else if (cmd instanceof VehicleCommandRouter.ClimateOffCommand || cmd instanceof VehicleCommandRouter.ClimateRemoteOffCommand) {
                Gewohnheiten.melde(Aktionen.KLIMA_AUS, null, q);
            } else if (cmd instanceof VehicleCommandRouter.ClimateSetTempCommand) {
                VehicleCommandRouter.ClimateSetTempCommand c = (VehicleCommandRouter.ClimateSetTempCommand) cmd;
                Gewohnheiten.melde(Aktionen.KLIMA_TEMP, new JSONObject().put("zone", c.zone == 2 ? "beifahrer" : "fahrer")
                        .put("temp", Gewohnheiten.temp(c.tempCelsius)), q);
            } else if (cmd instanceof VehicleCommandRouter.ClimateStepTempCommand) {
                VehicleCommandRouter.ClimateStepTempCommand c = (VehicleCommandRouter.ClimateStepTempCommand) cmd;
                if (c.resultSetpoint == at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE) return;
                String s = c.area == 2 ? AutoBeobachter.TEMP_BEIFAHRER : AutoBeobachter.TEMP_FAHRER;
                Gewohnheiten.erwarte(s, c.resultSetpoint);   // Ziel erst jetzt bekannt
                Gewohnheiten.melde(Aktionen.KLIMA_TEMP, new JSONObject().put("zone", c.area == 2 ? "beifahrer" : "fahrer")
                        .put("temp", c.resultSetpoint), q);
            } else if (cmd instanceof VehicleCommandRouter.SeatHeatCommand) {
                VehicleCommandRouter.SeatHeatCommand c = (VehicleCommandRouter.SeatHeatCommand) cmd;
                Gewohnheiten.melde(Aktionen.SITZHEIZUNG, new JSONObject().put("sitz", c.position == 1 ? "fahrer" : "beifahrer")
                        .put("stufe", c.level), q);
            } else if (cmd instanceof VehicleCommandRouter.SeatVentCommand) {
                VehicleCommandRouter.SeatVentCommand c = (VehicleCommandRouter.SeatVentCommand) cmd;
                Gewohnheiten.melde(Aktionen.SITZLUEFTUNG, new JSONObject().put("sitz", c.position == 1 ? "fahrer" : "beifahrer")
                        .put("stufe", c.level), q);
            } else if (cmd instanceof VehicleCommandRouter.SteeringWheelHeatCommand) {
                Gewohnheiten.melde(Aktionen.LENKRADHEIZUNG, new JSONObject().put("an",
                        ((VehicleCommandRouter.SteeringWheelHeatCommand) cmd).on), q);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Wallbox ({@code WallboxDienst.steuern}, nicht der Ladeplan-Weg): Start, Stopp, Strom. */
    public static void wallbox(String aktion, Object wert) {
        try {
            if ("modus".equals(aktion)) {
                if ("sofort".equals(wert)) Gewohnheiten.melde(Aktionen.WALLBOX_START, null);
                else if ("aus".equals(wert)) Gewohnheiten.melde(Aktionen.WALLBOX_STOPP, null);
            } else if ("strom".equals(aktion) && wert instanceof Number) {
                Gewohnheiten.melde(Aktionen.WALLBOX_STROM, new JSONObject().put("a", ((Number) wert).intValue()));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Camping: Quelle aus dem Aufruf („web“/„tipp“ = Herkunft des Threads, „ha“, „telegram“; sonst Automatik). */
    public static void camping(boolean an, String quelle) {
        String q;
        if ("ha".equals(quelle)) q = Herkunft.HA;
        else if ("telegram".equals(quelle)) q = Herkunft.TELEGRAM;
        else if ("web".equals(quelle) || "tipp".equals(quelle)) q = Herkunft.aktuell();
        else q = Herkunft.AUTOMATION;
        Gewohnheiten.melde(an ? Aktionen.CAMPING_AN : Aktionen.CAMPING_AUS, null, q);
    }
}
