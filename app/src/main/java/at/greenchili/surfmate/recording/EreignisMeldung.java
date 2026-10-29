package at.greenchili.surfmate.recording;

import at.greenchili.surfmate.notifications.NotificationEvent;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 0.1.28 P1 „Nachricht nach Ereignis“: Texte und Push-Ereignis nach einem gesicherten Ereignis-Clip.
 *
 * <p>Eigene Kategorie „Ereignisse“ – nicht mehr „CRITICAL“: wer „Abschreckung und Alarme“ ausschaltet,
 * bekommt die Ereignis-Meldung trotzdem. Telegram: Kategorie {@link #TELEGRAM_KATEGORIE}, Schalter
 * {@code recording.ereignisTelegram} (Standard aus). Handy: Push-Kategorie {@link #PUSH_KATEGORIE}
 * (Standard an, je Gerät abschaltbar).
 *
 * <p>Nur Text mit Uhrzeit: kein Video, kein Bild, kein Ort, kein Dateiname (Rechtslage Nr. 8, Ereignis-Clips
 * nie nach außen).
 */
public final class EreignisMeldung {

    /** Telegram-Kategorie (TelegramNotifier.Category / TelegramBotDaemon). */
    public static final String TELEGRAM_KATEGORIE = "EREIGNIS";
    /** Push-Kategorie in {@code notifications-categories.json}. */
    public static final String PUSH_KATEGORIE = "ereignis.gesichert";
    /** Tippen öffnet die Wächter-Seite mit der Frage „Behalten?“. */
    public static final String ZIEL_URL = "/local/waechter.html";
    /** Feld {@code data.ziel} für die Handy-App (Tippen → zum Ereignis). */
    public static final String ZIEL = "ereignis";

    private EreignisMeldung() {}

    static String uhrzeit(long ereignisMs) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ereignisMs));
    }

    /** Telegram-Text in der Nutzersprache (Rückfall Englisch). */
    public static String telegramText(long ereignisMs) {
        return at.greenchili.surfmate.server.Messages.text("telegram.ereignis.gesichert",
                "Panoptes: event at {0} saved (stored in the car only).", uhrzeit(ereignisMs));
    }

    /** Push fürs Handy. Schwere WARN: in der Ruhezeit des Geräts still, sonst normal. */
    public static NotificationEvent pushEreignis(long ereignisMs) {
        String zeit = uhrzeit(ereignisMs);
        JSONObject data = new JSONObject();
        try {
            data.put("ziel", ZIEL);
            data.put("zeit", ereignisMs);
        } catch (Exception ignored) {
        }
        return new NotificationEvent(PUSH_KATEGORIE, NotificationEvent.Severity.WARN,
                at.greenchili.surfmate.server.Messages.text("notifications.ereignis_title",
                        "Event at {0} saved", zeit),
                at.greenchili.surfmate.server.Messages.text("notifications.ereignis_body",
                        "Hard braking or a bump. The video is stored in the car only. Keep it?"),
                "ereignis-" + ereignisMs, ZIEL_URL, data);
    }
}
