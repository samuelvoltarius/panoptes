package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.notifications.NotificationBus;
import at.greenchili.surfmate.notifications.NotificationEvent;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONObject;

/** Meldung "Wächter gestoppt: Grund" / "Wächter läuft wieder" über den NotificationBus. */
final class GuardStopNotice {

    static final String CATEGORY = "surveillance.guard_stop";

    private GuardStopNotice() {}

    static void stopped(String reason) {
        try {
            String why = Messages.get("notifications.guard_stop_reason_" + reason);
            JSONObject data = new JSONObject();
            data.put("reason", reason);
            NotificationBus.get().publish(new NotificationEvent(CATEGORY,
                    NotificationEvent.Severity.WARN,
                    Messages.get("notifications.guard_stop_title", why), "",
                    "guard-stop", "/local/waechter.html", data));
        } catch (Throwable ignored) {
            // Eine Meldung darf den Wächter-Stopp nie verhindern.
        }
    }

    static void resumed() {
        try {
            NotificationBus.get().publish(new NotificationEvent(CATEGORY,
                    NotificationEvent.Severity.INFO,
                    Messages.get("notifications.guard_stop_resumed"), "",
                    "guard-stop", "/local/waechter.html", null));
        } catch (Throwable ignored) {
        }
    }
}
