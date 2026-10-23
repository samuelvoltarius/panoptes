package at.greenchili.surfmate.daemon.telegram;

import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * {@code /camping [an|aus|kalt|passt|warm]} (0.1.16). Camping läuft im Kamera-Dienst; der Befehl geht über
 * dieselbe IPC wie {@code /waechter}. Ein/Aus braucht die Freigabe „Wächter“, weil Camping den Wächter auf
 * „bewohnt“ stellt. In Stufe 2a heizt Panoptes nicht (Probelauf) – die Antwort sagt das.
 */
public class CampingCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/camping".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String was = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String rueck = rueckmeldung(was);
        if (rueck != null) {
            JSONObject r = ipc("rueckmeldung", rueck, ctx);
            if (r == null || !r.optBoolean("success", false)) {
                ctx.sendMessage(chatId, ctx.tr("camping.failed"));
            } else {
                ctx.sendMessage(chatId, ctx.tr(r.optBoolean("uebernommen", false) ? "camping.feedback_ok" : "camping.feedback_wait"));
            }
            return;
        }
        Boolean an = SurveillanceCommandHandler.parseOnOff(was);
        if (an != null) {
            if (!UnifiedTelegramConfig.isRemoteAllowed(UnifiedTelegramConfig.REMOTE_GUARD)) {
                ctx.sendMessage(chatId, ctx.tr("router.not_allowed"));
                return;
            }
            JSONObject r = ipc(an ? "an" : "aus", null, ctx);
            if (r == null || !r.optBoolean("success", false)) {
                String f = r == null ? "" : r.optString("fehler", "");
                ctx.sendMessage(chatId, ctx.tr("nicht_geparkt".equals(f) ? "camping.not_parked"
                        : "fahrzustand_unbekannt".equals(f) ? "camping.state_unknown" : "camping.failed"));
                return;
            }
        }
        zeigen(chatId, ctx);
    }

    /** "kalt"/"zu_kalt" → zu_kalt, "passt"/"gut" → passt, "warm"/"zu_warm" → zu_warm, sonst null. */
    public static String rueckmeldung(String s) {
        switch (s == null ? "" : s) {
            case "kalt": case "zu_kalt": case "cold": return "zu_kalt";
            case "passt": case "gut": case "ok": return "passt";
            case "warm": case "zu_warm": case "hot": return "zu_warm";
            default: return null;
        }
    }

    private void zeigen(long chatId, CommandContext ctx) {
        JSONObject r = ipc("status", null, ctx);
        JSONObject s = r == null ? null : r.optJSONObject("data");
        if (s == null) {
            ctx.sendMessage(chatId, ctx.tr("camping.failed"));
            return;
        }
        boolean an = s.optBoolean("aktiv", false);
        StringBuilder sb = new StringBuilder(ctx.tr(an ? "camping.status_on" : "camping.status_off"));
        if (an) {
            if (s.has("soc") && !s.isNull("soc")) {
                sb.append("\n").append(ctx.tr("camping.battery", Math.round(s.optDouble("soc")), s.optInt("socGrenze")));
            }
            JSONObject z = s.optJSONObject("tabelle"), b = s.optJSONObject("budget");
            if (z != null) {
                int iv = b != null ? b.optInt("intervallMin") : z.optInt("intervallMin");
                int du = b != null ? b.optInt("dauerMin") : z.optInt("dauerMin");
                sb.append("\n").append(ctx.tr("camping.interval", iv, du, z.optInt("stufe")));
            }
            if (b != null && b.optLong("reichtBisMs", 0) > 0) {
                sb.append("\n").append(ctx.tr("camping.lasts", uhr(b.optLong("reichtBisMs"))));
            }
            sb.append("\n\n").append(ctx.tr("camping.trial"));
        }
        ctx.sendMessageWithButtons(chatId, sb.toString(), an
                ? new String[][][]{{{ctx.tr("camping.btn_cold"), "cmd:/camping kalt"},
                        {ctx.tr("camping.btn_fine"), "cmd:/camping passt"},
                        {ctx.tr("camping.btn_warm"), "cmd:/camping warm"}},
                        {{ctx.tr("camping.btn_off"), "cmd:/camping aus"}}}
                : new String[][][]{{{ctx.tr("camping.btn_on"), "cmd:/camping an"}}});
    }

    static String uhr(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private JSONObject ipc(String aktion, String art, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "CAMPING");
            cmd.put("aktion", aktion);
            if (art != null) cmd.put("art", art);
            return ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
}
