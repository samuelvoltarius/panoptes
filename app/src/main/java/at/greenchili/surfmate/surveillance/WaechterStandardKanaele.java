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
 *   <li>Display: Hinweis am Bildschirm mit Verweis auf dieselbe Seite (der Overlay-Dienst kennt keinen eigenen Knopf für diese Frage).
 *       0.1.43.13: nur im sicheren Stand, mit Quittung.</li>
 * </ul>
 */
public final class WaechterStandardKanaele {

    /** Push-Kategorie; der TelegramSink lässt sie aus, Telegram geht direkt. */
    public static final String KATEGORIE = "system.waechter";
    public static final String SEITE = "/local/waechter.html";

    private WaechterStandardKanaele() {}

    /**
     * 0.1.43.13: Telegram und Handy. Erst nach {@link #zeigenDisplay()} (oder dessen endgültigem Fehlschlag) senden,
     * damit die Frage je versionCode nur einmal über alle Kanäle geht.
     */
    public static void sendenFern() {
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
    }

    /** 0.1.43.16 (E2E 0.1.43.13): so lange steht die Frage, bevor sie von selbst geht (vorher 60 s). */
    public static final int DISPLAY_SEK = 180;

    /**
     * 0.1.43.13: Display, mit Quittung des Overlay-Dienstes. Nur im sicheren Stand aufrufen
     * ({@link WaechterStandardHinweis#standFuerFrage}); das prüft der Takt im {@code AppUpdateDienst}.
     * 0.1.43.16: wartet, bis die Frage zu ist, und liefert, wie sie ausging; nur {@code OK} heißt gelesen.
     * Blockiert bis {@value #DISPLAY_SEK} s plus Spielraum (der Takt hat seinen eigenen Faden).
     */
    public static MessageOverlayController.DialogAusgang zeigenDisplay() {
        try {
            MessageOverlayController.DialogAusgang a = MessageOverlayController.showDialogMitAusgang(
                    Messages.get("notifications.waechter_standard_titel"),
                    Messages.get("notifications.waechter_standard_display"), "OK", "info", DISPLAY_SEK,
                    at.greenchili.surfmate.appupdate.AppUpdateDienst::sichererStandJetzt);   // 0.1.43.17: losgefahren → zu
            return a == null ? MessageOverlayController.DialogAusgang.NICHT_GEZEIGT : a;
        } catch (Throwable ignored) {
            return MessageOverlayController.DialogAusgang.NICHT_GEZEIGT;
        }
    }
}
