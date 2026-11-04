package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 0.1.38.7 {@code /startbildschirm}: Rückweg vom Panoptes Launcher zum BYD-Startbildschirm. {@code original} ist für den
 * gekoppelten Besitzer immer erlaubt (es geht nur zurück zu dem, was BYD mitliefert); Einschalten gibt es nur am Display.
 */
public class HomeCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/startbildschirm".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String was = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean original = "original".equals(was) || "byd".equals(was) || "zurueck".equals(was) || "zurück".equals(was);
        boolean paketAn = "launcher".equals(was);
        JSONObject r = ipc(original ? "original" : paketAn ? "paketan" : "status", ctx);
        if (r == null) {
            ctx.sendMessage(chatId, ctx.tr("common.camera_service_down"));
            return;
        }
        if (original) {
            ctx.sendMessage(chatId, ctx.tr(r.optBoolean("success", false) ? "home.original_ok" : "home.original_fehler"));
            return;
        }
        if (paketAn) {
            ctx.sendMessage(chatId, ctx.tr(r.optBoolean("success", false) ? "home.paket_an_ok" : "home.paket_an_fehler"));
            return;
        }
        JSONObject d = r.optJSONObject("data");
        boolean an = d != null && d.optBoolean("gewuenscht", false);
        ctx.sendMessage(chatId, ctx.tr(an ? "home.status_an" : "home.status_aus"));
    }

    private JSONObject ipc(String aktion, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "LAUNCHER_HOME");
            cmd.put("aktion", aktion);
            return ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
}
