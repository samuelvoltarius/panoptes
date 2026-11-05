package at.greenchili.surfmate.daemon.telegram;

import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import java.util.Locale;
import java.util.function.Function;

/**
 * {@code /bilder}, {@code /bilder aus}, {@code /bilder an} (0.1.38.11). Schalter „Bilder und Videos an Telegram senden“
 * ({@code telegram.videoUploads}, Standard an, {@link at.greenchili.surfmate.telegram.config.MedienStandard}).
 * Ausschalten geht immer. Einschalten im Chat nur, wenn der Hinweis zu Bildern bei Telegram (Pflichttext E) schon
 * einmal bestätigt wurde; sonst zeigt der Bot, wo man es in Panoptes einschaltet (dort steht der Hinweis).
 */
public class BilderCommandHandler implements TelegramCommandHandler {

    @Override
    public boolean canHandle(String command) {
        return "/bilder".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String was = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (ErreichbarCommandHandler.was(was)) {
            case "aus":
                ctx.sendMessage(chatId, ctx.tr(UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_VIDEO_UPLOADS, false)
                        ? "bilder.aus" : "bilder.fehler"));
                return;
            case "an":
                if (!anImChatErlaubt(UnifiedTelegramConfig.getMediaNoticeAckAt())) {
                    ctx.sendMessage(chatId, ctx.tr("bilder.an_in_panoptes"));
                    return;
                }
                ctx.sendMessage(chatId, ctx.tr(UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_VIDEO_UPLOADS, true)
                        ? "bilder.an" : "bilder.fehler"));
                return;
            default:
                if (UnifiedTelegramConfig.isVideoUploads()) {
                    ctx.sendMessageWithButtons(chatId, ctx.tr("bilder.stand_an"), knoepfe(ctx::tr));
                } else {
                    ctx.sendMessage(chatId, ctx.tr("bilder.stand_aus"));
                }
        }
    }

    /** Einschalten im Chat nur nach bestätigtem Pflichttext E (wie {@code /api/telegram/preferences}). */
    static boolean anImChatErlaubt(long quittungMs) {
        return quittungMs > 0;
    }

    /** Knopf unter der Nachricht: „Bilder abschalten“. */
    public static String[][][] knoepfe(Function<String, String> tr) {
        return new String[][][]{{{tr.apply("bilder.btn_aus"), "cmd:/bilder aus"}}};
    }
}
