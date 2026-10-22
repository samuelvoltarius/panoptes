package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

/**
 * {@code /laden} – Ladestand, Reichweite und ob gerade geladen wird. Nur
 * lesend; die Werte kommen vom Kamera-Dienst (IPC 19977).
 */
public class ChargeCommandHandler implements TelegramCommandHandler {

    @Override
    public boolean canHandle(String command) {
        return "/laden".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        JSONObject soc = SurveillanceCommandHandler.data("GET_BATTERY_SOC", ctx);
        JSONObject range = SurveillanceCommandHandler.data("GET_DRIVING_RANGE", ctx);
        JSONObject chg = SurveillanceCommandHandler.data("GET_CHARGING_STATE", ctx);
        if (soc == null && range == null && chg == null) {
            ctx.sendMessage(chatId, ctx.tr("charge.unavailable"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("charge.title")).append("\n\n");
        if (soc != null && soc.has("socPercent")) {
            sb.append(ctx.tr("charge.soc", SurveillanceCommandHandler.fmt0(soc.optDouble("socPercent")))).append("\n");
        }
        if (range != null && range.optDouble("elecRangeKm", 0) > 0) {
            sb.append(ctx.tr("charge.range", SurveillanceCommandHandler.fmt0(range.optDouble("elecRangeKm")))).append("\n");
        }
        String line = chargingLine(chg, ctx);
        sb.append(line != null ? line : ctx.tr("charge.not_plugged")).append("\n");
        ctx.sendMessageWithButtons(chatId, sb.toString().trim(), new String[][][]{
                {{ctx.tr("buttons.refresh"), "cmd:/laden"}, {ctx.tr("buttons.status"), "cmd:/status"}}});
    }

    /** Eine Zeile zum Ladevorgang, oder null, wenn nicht eingesteckt / unbekannt. */
    static String chargingLine(JSONObject chg, CommandContext ctx) {
        if (chg == null) return null;
        String status = chg.optString("status", "UNKNOWN");
        switch (status) {
            case "CHARGING": {
                double kw = chg.optDouble("chargingPowerKW", 0);
                boolean est = chg.optBoolean("isEstimated", false);
                if (kw > 0 && !est) {
                    return ctx.tr("charge.charging_kw",
                            String.format(SurveillanceCommandHandler.locale(), "%.1f", kw));
                }
                return ctx.tr("charge.charging");
            }
            case "FINISHED":    return ctx.tr("charge.finished");
            case "SCHEDULED":   return ctx.tr("charge.scheduled");
            case "READY":       return ctx.tr("charge.plugged");
            case "TERMINATED":  return ctx.tr("charge.stopped");
            case "TIMEOUT":     return ctx.tr("charge.timeout");
            case "ERROR":       return ctx.tr("charge.error");
            case "DISCHARGING": return ctx.tr("charge.discharging");
            default:            return null;
        }
    }
}
