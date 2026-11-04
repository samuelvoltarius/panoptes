package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

import java.util.Locale;

/**
 * {@code /aktionen}, {@code /aktionen aus}, {@code /aktionen automatik aus} (0.1.38.8 Fahrzeug-Aktionen). Zeigt, welche
 * außen sichtbaren Aktionen (Auto finden, Lichthupe, Warnblinker, Aufsperren, Aufsperren beim Annähern) eingeschaltet
 * sind, und schaltet sie sofort ab. <b>Auslösen oder Einschalten geht hier nie</b> – nur am Autobildschirm bzw. mit
 * Login (Schalter) und in der Handy-App (Tipp + Rückfrage). Der Befehl geht über dieselbe IPC wie {@code /tier}.
 */
public class AktionenCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/aktionen".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String aktion = aktion(args);
        JSONObject r = ipc(aktion, ctx);
        if (r == null || !r.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("aktionen.failed"));
            return;
        }
        JSONObject s = r.optJSONObject("schalter");
        StringBuilder sb = new StringBuilder();
        if ("aus".equals(aktion)) sb.append(ctx.tr("aktionen.aus_ok")).append("\n\n");
        if ("automatik_aus".equals(aktion)) sb.append(ctx.tr("aktionen.automatik_aus_ok")).append("\n\n");
        if ("zusperren".equals(aktion)) {
            sb.append(ctx.tr(r.optBoolean("zugesperrt", false) ? "aktionen.zusperren_ok" : "aktionen.zusperren_fehler"))
                    .append("\n\n");
        }
        sb.append(ctx.tr("aktionen.status"));
        boolean irgendwas = false;
        for (String f : new String[] {"autoFinden", "licht", "warnblinker", "aufsperren", "annaeherung"}) {
            boolean an = s != null && s.optBoolean(f, false);
            irgendwas |= an;
            sb.append("\n").append(an ? "🟢 " : "⚪ ").append(ctx.tr("aktionen.name_" + f));
        }
        sb.append("\n\n").append(ctx.tr("aktionen.nur_auto"));
        // Knopf „Zusperren“ immer (z. B. nach „Auto ist offen“ beim Annähern), Ausschalten nur, wenn etwas an ist
        String[] zu = {ctx.tr("aktionen.btn_zusperren"), "cmd:/aktionen zusperren"};
        if (!irgendwas) {
            ctx.sendMessageWithButtons(chatId, sb.toString(), new String[][][]{{zu}});
            return;
        }
        ctx.sendMessageWithButtons(chatId, sb.toString(), s != null && s.optBoolean("annaeherung", false)
                ? new String[][][]{{{ctx.tr("aktionen.btn_automatik_aus"), "cmd:/aktionen automatik aus"}},
                        {{ctx.tr("aktionen.btn_aus"), "cmd:/aktionen aus"}}, {zu}}
                : new String[][][]{{{ctx.tr("aktionen.btn_aus"), "cmd:/aktionen aus"}}, {zu}});
    }

    /** {@code status} | {@code aus} | {@code automatik_aus} | {@code zusperren}. Bewusst kein „an“, kein Aufsperren. */
    public static String aktion(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; args != null && i < args.length; i++) sb.append(i > 1 ? " " : "").append(args[i]);
        String a = sb.toString().toLowerCase(Locale.ROOT).trim();
        switch (a) {
            case "aus": case "off": case "stopp": case "stop": case "alle aus": case "all off":
                return "aus";
            case "automatik aus": case "automatik": case "annaeherung aus": case "annäherung aus": case "auto off":
                return "automatik_aus";
            case "zusperren": case "zu": case "lock":
                return "zusperren";
            default:
                return "status";
        }
    }

    private JSONObject ipc(String aktion, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "FAHRZEUGAKTIONEN");
            cmd.put("aktion", aktion);
            return ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
}
