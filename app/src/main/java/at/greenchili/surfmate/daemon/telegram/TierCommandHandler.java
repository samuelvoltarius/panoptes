package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * {@code /tier} und {@code /tier ok} (Tiermodus, 03.11.2026). Zeigt den Stand und bestätigt eine Meldung („Gesehen“),
 * damit die Wiederholung aufhört. <b>Einschalten oder Ausschalten geht hier nicht</b> – nur am Autobildschirm oder in
 * der Handy-App mit Recht {@code klima}. Der Befehl geht über dieselbe IPC wie {@code /camping}.
 */
public class TierCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/tier".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String was = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean gesehen = istGesehen(was);
        JSONObject r = ipc(gesehen ? "quittieren" : "status", ctx);
        if (r == null || !r.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("tiermodus_aus".equals(r == null ? "" : r.optString("fehler"))
                    ? "tier.status_off" : "tier.failed"));
            return;
        }
        JSONObject s = r.optJSONObject("data");
        if (s == null) {
            ctx.sendMessage(chatId, ctx.tr("tier.failed"));
            return;
        }
        boolean an = s.optBoolean("aktiv", false);
        StringBuilder sb = new StringBuilder();
        if (gesehen) sb.append(ctx.tr("tier.seen")).append("\n\n");
        if (!an) {
            sb.append(ctx.tr("tier.status_off"));
            ctx.sendMessage(chatId, sb.toString());
            return;
        }
        sb.append(ctx.tr("ok".equals(s.optString("anzeige")) ? "tier.status_ok" : "tier.status_check"));
        JSONArray p = s.optJSONArray("probleme");
        if (p != null && p.length() > 0) {
            for (int i = 0; i < p.length(); i++) sb.append("\n• ").append(ctx.tr("tier.problem_" + p.optString(i)));
        }
        if (!s.isNull("innenC") && s.has("innenC")) {
            sb.append("\n").append(ctx.tr("tier.inside", Math.round(s.optDouble("innenC"))));
        }
        sb.append("\n\n").append(ctx.tr("tier.only_app"));
        ctx.sendMessageWithButtons(chatId, sb.toString(), p != null && p.length() > 0
                ? new String[][][]{{{ctx.tr("tier.btn_seen"), "cmd:/tier ok"}}}
                : new String[][][]{{{ctx.tr("tier.btn_refresh"), "cmd:/tier"}}});
    }

    /** „ok“, „gesehen“, „seen“ = bestätigen; alles andere = nur Stand. Kein an/aus. */
    public static boolean istGesehen(String s) {
        switch (s == null ? "" : s) {
            case "ok": case "gesehen": case "seen": case "quittieren":
                return true;
            default:
                return false;
        }
    }

    private JSONObject ipc(String aktion, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "TIERMODUS");
            cmd.put("aktion", aktion);
            return ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
}
