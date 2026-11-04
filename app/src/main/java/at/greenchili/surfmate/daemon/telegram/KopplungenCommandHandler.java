package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.8 verlorenes Handy (Koordination 04.11.): {@code /kopplungen} listet die gekoppelten Geräte, ein Tipp fragt
 * nach, „Ja, entfernen“ widerruft die Kopplung. Nur im Besitzer-Chat (Router/Callback prüfen Absender und Chat).
 *
 * <p>Die Knöpfe tragen nie die Kopplungs-id, sondern einen Einmal-Griff (16 hex, 24 h) vom Kamera-Daemon
 * ({@code GET /api/kopplung/telegram-liste}, nur Login/lokal). Das Entfernen läuft über den bestehenden Knopf
 * {@code ke:<ref>} ({@code POST /api/kopplung/entfernen/<ref>}). Über die Brücke/vom Handy geht das nicht.
 */
public class KopplungenCommandHandler implements TelegramCommandHandler {

    static final String LISTE = "/api/kopplung/telegram-liste";

    @Override
    public boolean canHandle(String command) {
        return "/kopplungen".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length >= 4 && "entfernen".equals(args[1]) && args[2].matches("[0-9a-f]{16}")) {
            frage(chatId, args[2], args[3], ctx);
            return;
        }
        JSONObject r = ctx.daemonHttp("GET", LISTE, null);
        JSONArray l = r == null ? null : r.optJSONArray("geraete");
        if (l == null) {
            ctx.sendMessage(chatId, ctx.tr("kopplungen.fehler"));
            return;
        }
        if (l.length() == 0) {
            ctx.sendMessage(chatId, ctx.tr("kopplungen.leer"));
            return;
        }
        ctx.sendMessageWithButtons(chatId, text(l, ctx), knoepfe(l, ctx));
    }

    static String text(JSONArray l, CommandContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.tr("kopplungen.titel")).append("\n");
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            if (g == null) continue;
            sb.append("\n• ").append(name(g)).append(" (").append(g.optString("art", "?")).append(")");
            if (g.optBoolean("besitzer", false)) sb.append(" – ").append(ctx.tr("kopplungen.besitzer"));
        }
        return sb.append("\n\n").append(ctx.tr("kopplungen.hinweis")).toString();
    }

    /** Ein Knopf je Gerät: {@code cmd:/kopplungen entfernen <ref> <nr>} (fragt erst nach). */
    static String[][][] knoepfe(JSONArray l, CommandContext ctx) {
        List<String[][]> rows = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            JSONObject g = l.optJSONObject(i);
            String ref = g == null ? "" : g.optString("ref", "");
            if (!ref.matches("[0-9a-f]{16}")) continue;
            rows.add(new String[][]{{ctx.tr("kopplungen.btn_entfernen", name(g)),
                    "cmd:/kopplungen entfernen " + ref + " " + (i + 1)}});
        }
        return rows.toArray(new String[0][][]);
    }

    /** Rückfrage: „Ja, entfernen“ = bestehender Knopf {@code ke:<ref>}, „Abbrechen“ zeigt die Liste neu. */
    static void frage(long chatId, String ref, String nr, CommandContext ctx) {
        ctx.sendMessageWithButtons(chatId, ctx.tr("kopplungen.frage", nr.matches("\\d{1,2}") ? nr : "?"),
                new String[][][]{{{ctx.tr("kopplungen.btn_ja"), at.greenchili.surfmate.telegram.EntfernenKnopf.daten(ref)},
                        {ctx.tr("kopplungen.btn_nein"), "cmd:/kopplungen"}}});
    }

    /** Name ohne Markdown-Sonderzeichen, gekürzt. */
    static String name(JSONObject g) {
        String n = g.optString("name", "?").replaceAll("[\\r\\n\\t*_`\\[\\]]", " ").trim();
        if (n.isEmpty()) n = "?";
        return n.length() > 24 ? n.substring(0, 24) + "…" : n;
    }
}
