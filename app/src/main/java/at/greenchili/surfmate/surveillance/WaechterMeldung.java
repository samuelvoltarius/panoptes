package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.notifications.NotificationBus;
import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.server.Messages;

/** Meldungen des Wächters an den Nutzer (NotificationBus). */
final class WaechterMeldung {

    static final String CATEGORY_KI_AUS = "surveillance.ai_off";

    private WaechterMeldung() {}

    /** „KI aus, Wächter nimmt bei Bewegung auf“ (fail-open, Koordination 21.10.2026). */
    static void kiAus() {
        try {
            NotificationBus.get().publish(new NotificationEvent(CATEGORY_KI_AUS,
                    NotificationEvent.Severity.WARN,
                    Messages.get("notifications.ai_off_title"),
                    Messages.get("notifications.ai_off_body"),
                    "ai-off", "/local/waechter.html", null));
        } catch (Throwable ignored) {
            // Eine Meldung darf den Wächter nie aufhalten.
        }
    }
}
