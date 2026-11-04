package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.byd.MessageOverlayController;
import at.greenchili.surfmate.notifications.NotificationBus;
import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.server.Messages;
import at.greenchili.surfmate.telegram.TelegramNotifier;

import org.json.JSONObject;

/**
 * 0.1.38.8 Die eine Frage „Soll der Wächter an sein?“ über drei Kanäle (Versand, nicht testbar; die Texte und die Verdrahtung
 * prüft der Quellvertrag). Jeder Kanal ist für sich abgesichert: fällt einer aus, gehen die anderen.
 * <ul>
 *   <li>Telegram: Text mit Rechtshinweis und Knopf „Wächter einschalten“ ({@code /waechter standard});</li>
 *   <li>Handy und Verlauf: Meldung {@value #KATEGORIE} mit Sprung auf die Wächter-Seite, dort steht dieselbe Frage mit Knopf;</li>
 *   <li>Display: Hinweis am Bildschirm mit Verweis auf dieselbe Seite (der Overlay-Dienst kennt keinen eigenen Knopf für diese Frage).</li>
 * </ul>
 */
public final class WaechterStandardKanaele {

    /** Push-Kategorie; der TelegramSink lässt sie aus, Telegram geht direkt. */
    public static final String KATEGORIE = "system.waechter";
    public static final String SEITE = "/local/waechter.html";

    private WaechterStandardKanaele() {}

    public static void senden() {
        try {
            TelegramNotifier.notifyWaechterStandard();
        } catch (Throwable ignored) {
        }
        try {
            NotificationBus.get().publish(new NotificationEvent(KATEGORIE, NotificationEvent.Severity.INFO,
                    Messages.get("notifications.waechter_standard_titel"),
                    Messages.get("notifications.waechter_standard_text"),
                    "waechter-standard-frage", SEITE, new JSONObject().put("art", "waechter_standard_frage")));
        } catch (Throwable ignored) {
        }
        try {
            MessageOverlayController.showDialog(Messages.get("notifications.waechter_standard_titel"),
                    Messages.get("notifications.waechter_standard_display"), "OK", "info", 60);
        } catch (Throwable ignored) {
        }
    }
}
