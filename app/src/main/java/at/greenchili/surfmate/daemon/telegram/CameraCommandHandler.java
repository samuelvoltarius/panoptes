package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

/**
 * {@code /kamera} – schickt ein aktuelles Standbild aller Kameras. Geht nur,
 * wenn die Kameras gerade laufen (Wächter wach oder Auto an); sonst ein
 * Hinweis statt eines alten Bildes.
 */
public class CameraCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/kamera".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        JSONObject r;
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "TELEGRAM_STILL");
            r = ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd, 10_000);
        } catch (Exception e) {
            r = null;
        }
        if (r == null) {
            ctx.sendMessage(chatId, ctx.tr("common.camera_service_down"));
            return;
        }
        String path = r.optString("path", "");
        if (!r.optBoolean("success", false) || path.isEmpty()) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("camera.off"), new String[][][]{
                    {{ctx.tr("buttons.guard_on"), "cmd:/waechter an"},
                            {ctx.tr("buttons.events"), "cmd:/aufnahmen"}}});
            return;
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "HH:mm", SurveillanceCommandHandler.locale());
        String caption = ctx.tr("camera.caption", sdf.format(new java.util.Date()));
        if (!ctx.sendPhoto(chatId, path, caption)) {
            ctx.sendMessage(chatId, ctx.tr("camera.send_failed"));
        }
    }
}
