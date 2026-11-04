package at.greenchili.surfmate.daemon.telegram;

import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import java.util.Locale;
import java.util.function.Function;

/**
 * {@code /erreichbar}, {@code /erreichbar an}, {@code /erreichbar aus} (0.1.38.8). Schalter „Auch bei abgestelltem Auto
 * erreichbar“ ({@code telegram.autoStartAccOff}, Standard aus): ohne ihn schläft der Bot, sobald das Auto abgestellt ist,
 * und beantwortet dann keine Nachrichten mehr. Die Frage kommt einmal direkt nach der Kopplung (Knöpfe), sonst ändert
 * sich nichts von selbst. Kostet etwas Strom der 12-V-Batterie.
 */
public class ErreichbarCommandHandler implements TelegramCommandHandler {

    @Override
    public boolean canHandle(String command) {
        return "/erreichbar".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String was = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (was(was)) {
            case "an":
                ctx.sendMessage(chatId, ctx.tr(UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_AUTO_START, true)
                        ? "erreichbar.an" : "erreichbar.fehler"));
                return;
            case "aus":
                ctx.sendMessage(chatId, ctx.tr(UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_AUTO_START, false)
                        ? "erreichbar.aus" : "erreichbar.fehler"));
                return;
            case "nein":
                ctx.sendMessage(chatId, ctx.tr("erreichbar.nein"));
                return;
            default:
                boolean an = UnifiedTelegramConfig.shouldStartOnAccOff();
                if (an) {
                    ctx.sendMessage(chatId, ctx.tr("erreichbar.stand_an"));
                } else {
                    ctx.sendMessageWithButtons(chatId, ctx.tr("erreichbar.frage"), knoepfe(ctx::tr));
                }
        }
    }

    /** an | aus | nein | status. */
    public static String was(String s) {
        switch (s == null ? "" : s) {
            case "an": case "ein": case "ja": case "on": case "yes":
                return "an";
            case "aus": case "off":
                return "aus";
            case "nein": case "no": case "später": case "spaeter":
                return "nein";
            default:
                return "status";
        }
    }

    /** Knöpfe der Frage: „Ja, auch abgestellt“ / „Nein, danke“. */
    public static String[][][] knoepfe(Function<String, String> tr) {
        return new String[][][]{{{tr.apply("erreichbar.btn_ja"), "cmd:/erreichbar an"},
                {tr.apply("erreichbar.btn_nein"), "cmd:/erreichbar nein"}}};
    }
}
