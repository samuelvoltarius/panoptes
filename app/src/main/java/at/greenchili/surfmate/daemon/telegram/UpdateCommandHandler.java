package at.greenchili.surfmate.daemon.telegram;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 0.1.38.2 {@code /updates} und die Knöpfe {@code up:…}: Panoptes und Launcher per Telegram aktualisieren.
 *
 * <p>Kein eigener Installationsweg: alles läuft über {@code /api/appupdate/fern} im Kamera-Daemon (voller Login), also
 * über dieselben Regeln wie der Updater (freigegebene Version, Panoptes sicher → sofort, sonst vorgemerkt; Launcher
 * nicht während der Fahrt; ohne WLAN nur nach Rückfrage mit Größe). Vor jedem Start fragt der Bot „Wirklich
 * installieren?“. Nur der gekoppelte Besitzer-Chat erreicht diese Knöpfe ({@code TelegramBotDaemon}).</p>
 *
 * <p>Knöpfe: {@code up:s} Übersicht, {@code up:such} nachsehen, {@code up:p}/{@code up:l} Rückfrage,
 * {@code up:pj:<version>:<0|1>}/{@code up:lj:…} ja (1 = mobile Daten erlaubt), {@code up:n} nein.</p>
 */
public class UpdateCommandHandler implements TelegramCommandHandler {

    public static final String PREFIX = "up:";
    static final String FERN = "/api/appupdate/fern";
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._-]{1,24}$");
    /** Ohne bekannte Größe: lieber zu groß angeben (wie die Karte am Display). */
    private static final long GROESSE_PANOPTES = 70_000_000L, GROESSE_LAUNCHER = 20L * 1024 * 1024;

    @Override
    public boolean canHandle(String command) {
        return "/updates".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        uebersicht(chatId, ctx);
    }

    /** Ist das ein gültiger Update-Knopf? Package-sichtbar für Tests. */
    static boolean gueltig(String data) {
        if (data == null || !data.startsWith(PREFIX)) return false;
        String d = data.substring(PREFIX.length());
        if (d.equals("s") || d.equals("such") || d.equals("p") || d.equals("l") || d.equals("n")) return true;
        String[] t = d.split(":");
        return t.length == 3 && (t[0].equals("pj") || t[0].equals("lj")) && VERSION.matcher(t[1]).matches()
                && (t[2].equals("0") || t[2].equals("1"));
    }

    /** Ein Tipp auf einen Update-Knopf (schon als Besitzer geprüft). */
    public static void knopf(long chatId, String data, CommandContext ctx) {
        if (!gueltig(data)) {
            ctx.sendMessage(chatId, ctx.tr("callback.unknown_action"));
            return;
        }
        String d = data.substring(PREFIX.length());
        switch (d) {
            case "s":
                uebersicht(chatId, ctx);
                return;
            case "such":
                suchen(chatId, ctx);
                return;
            case "p":
                frage(chatId, "panoptes", ctx);
                return;
            case "l":
                frage(chatId, "launcher", ctx);
                return;
            case "n":
                ctx.sendMessageWithButtons(chatId, ctx.tr("updates.abgebrochen"), menueKnopf(ctx));
                return;
            default:
                String[] t = d.split(":");
                starten(chatId, t[0].equals("lj") ? "launcher" : "panoptes", t[1], "1".equals(t[2]), ctx);
        }
    }

    static void uebersicht(long chatId, CommandContext ctx) {
        JSONObject f = ctx.daemonHttp("GET", FERN, null);
        if (f == null || !f.optBoolean("ok", false)) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("updates.nicht_bereit"), menueKnopf(ctx));
            return;
        }
        JSONObject p = f.optJSONObject("panoptes"), l = f.optJSONObject("launcher");
        StringBuilder sb = new StringBuilder(ctx.tr("updates.title")).append("\n\n");
        List<String[][]> rows = new ArrayList<>();
        if (p != null) {
            String inst = md(p.optString("installiert", ""));
            JSONObject n = p.optJSONObject("neueste");
            if ("laeuft".equals(zustand(p))) {
                sb.append(ctx.tr("updates.panoptes_laeuft"));
            } else if (n != null) {
                sb.append(ctx.tr("updates.panoptes_neu", inst, md(n.optString("version"))));
                rows.add(new String[][]{{ctx.tr("updates.btn_panoptes", n.optString("version")), PREFIX + "p"}});
            } else {
                sb.append(ctx.tr("updates.panoptes_aktuell", inst));
            }
            sb.append("\n");
        }
        if (l != null) {
            JSONObject n = l.optJSONObject("neueste");
            if ("laeuft".equals(zustand(l))) {
                sb.append(ctx.tr("updates.launcher_laeuft"));
            } else if ("nicht_installiert".equals(l.optString("grund"))) {
                sb.append(ctx.tr("updates.launcher_keiner"));
            } else if (n != null) {
                sb.append(ctx.tr("updates.launcher_neu", md(n.optString("version"))));
                rows.add(new String[][]{{ctx.tr("updates.btn_launcher", n.optString("version")), PREFIX + "l"}});
            } else {
                sb.append(ctx.tr("updates.launcher_aktuell"));
            }
            sb.append("\n");
        }
        rows.add(new String[][]{{ctx.tr("updates.btn_suchen"), PREFIX + "such"}, {ctx.tr("menu.btn_menu"), "cmd:/menue"}});
        ctx.sendMessageWithButtons(chatId, sb.toString().trim(), rows.toArray(new String[0][][]));
    }

    private static void suchen(long chatId, CommandContext ctx) {
        JSONObject r = ctx.daemonHttp("POST", "/api/appupdate/suchen", new JSONObject());
        if (r == null) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("updates.nicht_bereit"), menueKnopf(ctx));
            return;
        }
        if ("ratenlimit".equals(r.optString("grund"))) ctx.sendMessage(chatId, ctx.tr("updates.ratenlimit"));
        uebersicht(chatId, ctx);
    }

    /** „Wirklich installieren?“ – ohne WLAN mit Größe. */
    static void frage(long chatId, String was, CommandContext ctx) {
        JSONObject f = ctx.daemonHttp("GET", FERN, null);
        JSONObject teil = f == null ? null : f.optJSONObject(was);
        if (teil == null) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("updates.nicht_bereit"), menueKnopf(ctx));
            return;
        }
        JSONObject n = teil.optJSONObject("neueste");
        if (n == null || "laeuft".equals(zustand(teil))) {
            // nichts zu tun (aktuell, nicht eingerichtet oder läuft schon): der Satz des Kerns sagt es
            ctx.sendMessageWithButtons(chatId, md(teil.optString("text", "")), menueKnopf(ctx));
            return;
        }
        boolean launcher = "launcher".equals(was);
        String version = n.optString("version", "");
        if (!VERSION.matcher(version).matches()) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("updates.nicht_bereit"), menueKnopf(ctx));
            return;
        }
        boolean mobil = teil.optBoolean("mobilFrage", false);
        StringBuilder sb = new StringBuilder(ctx.tr(launcher ? "updates.frage_launcher" : "updates.frage_panoptes",
                md(version)));
        if (mobil) {
            long g = n.optLong("groesseBytes", 0);
            sb.append("\n\n").append(ctx.tr("updates.frage_mobil", at.greenchili.surfmate.appupdate.UpdateRegel
                    .groesseText(g > 0 ? g : launcher ? GROESSE_LAUNCHER : GROESSE_PANOPTES)));
        }
        ctx.sendMessageWithButtons(chatId, sb.toString(), new String[][][]{{
                {ctx.tr("updates.btn_ja"), PREFIX + (launcher ? "lj:" : "pj:") + version + (mobil ? ":1" : ":0")},
                {ctx.tr("updates.btn_nein"), PREFIX + "n"}}});
    }

    /** Nach dem Ja: über den Kern starten (bzw. vormerken); dessen Satz zeigen. */
    static void starten(long chatId, String was, String version, boolean mobil, CommandContext ctx) {
        JSONObject body = new JSONObject();
        try {
            body.put("was", was).put("version", version).put("bestaetigt", true).put("mobil", mobil)
                    .put("geraet", "telegram");
        } catch (Exception ignored) { }
        JSONObject r = ctx.daemonHttp("POST", FERN, body);
        if (r == null) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("updates.nicht_bereit"), menueKnopf(ctx));
            return;
        }
        if (r.optBoolean("mobilFrage", false) && !mobil) {
            // inzwischen ohne WLAN: erst mit Größe fragen, nie still über mobile Daten laden
            frage(chatId, was, ctx);
            return;
        }
        String text = md(r.optString("text", ""));
        if (text.isEmpty()) text = ctx.tr(r.optBoolean("ok", false) ? "updates.gestartet" : "updates.nicht_bereit");
        ctx.sendMessageWithButtons(chatId, (r.optBoolean("ok", false) ? "✅ " : "ℹ️ ") + text, menueKnopf(ctx));
    }

    private static String zustand(JSONObject teil) {
        return teil.isNull("zustand") ? null : teil.optString("zustand", null);
    }

    static String[][][] menueKnopf(CommandContext ctx) {
        return new String[][][]{{{ctx.tr("menu.btn_menu"), "cmd:/menue"}}};
    }

    private static String md(String s) {
        return at.greenchili.surfmate.telegram.KopplungsKnopf.md(s);
    }
}
