package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

/**
 * 0.1.38.14 (P3): {@code /fehler [Text]} – Fehlerbericht, auch wenn die Anzeige im Auto eingefroren ist (Seal U:
 * Hänger beim Umschalten der Kartenquelle, Tacho-Einrichtung). Derselbe Bericht wie „Fehler melden“ (Wächter-Zeile,
 * START_STUFE, Log-Auszug, Tacho-Profil, Gang, Bildschirm, Kartenquelle), über den Kamera-Daemon
 * ({@code POST /api/rueckmeldung/fehler-telegram}, nur lokal). Nur Besitzer: der Bot leitet Befehle nur aus dem
 * Besitzer-Chat an den Router weiter (TelegramBotDaemon prüft {@code chatId == ownerChatId}).
 */
public class FehlerCommandHandler implements TelegramCommandHandler {

    static final String PFAD = "/api/rueckmeldung/fehler-telegram";

    @Override
    public boolean canHandle(String command) {
        return "/fehler".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        ctx.sendMessage(chatId, ctx.tr("fehler.laeuft"));
        JSONObject body = new JSONObject();
        try {
            body.put("text", text(args));
        } catch (Exception ignored) {
            // leerer Text → der Daemon setzt „Telegram /fehler“
        }
        JSONObject r = ctx.daemonHttp("POST", PFAD, body);
        ctx.sendMessage(chatId, antwort(r, ctx));
    }

    /** Freitext nach dem Befehl ({@code /fehler Karte hängt}); leer, wenn nichts dabei steht. */
    static String text(String[] args) {
        if (args == null || args.length < 2) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    /** Antwort an den Besitzer nach dem Ergebnis des Daemons. */
    static String antwort(JSONObject r, CommandContext ctx) {
        if (r == null) return ctx.tr("fehler.nicht");
        String stand = r.optString("stand", "");
        String code = r.optString("code", "");
        if ("gesendet".equals(stand)) return code.isEmpty() ? ctx.tr("fehler.gesendet_ohne") : ctx.tr("fehler.gesendet", code);
        if ("gemerkt".equals(stand)) return ctx.tr("fehler.gemerkt");
        if ("zuviel".equals(stand)) return ctx.tr("fehler.zuviel");
        return ctx.tr("fehler.nicht");
    }
}
