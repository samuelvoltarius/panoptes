package at.greenchili.surfmate.gewohnheiten;

import at.greenchili.surfmate.automation.Automation;

import org.json.JSONObject;

/**
 * 0.1.23 Gewohnheiten E4 (KONZEPT §5): Eine Regel im Probelauf hat ausgelöst – statt der Aktion nur melden
 * („Jetzt hätte ich … Passt das?“). Die Meldung geht über den NotificationBus (Handy, Cockpit, Telegram, je nach
 * Einstellung) und als Rückfrage auf die Karte; nichts Sichtbares oder Hörbares am Auto aus der Ferne (A+): die
 * Karte am Display erscheint nur, wenn dort ohnehin jemand die Startseite offen hat.
 */
public final class Probelauf {

    public static final String KATEGORIE = "gewohnheit.probe";

    private Probelauf() {}

    /** Aus {@code Automations} (statt der Aktionen). Wirft nie. */
    public static void ausgeloest(String id, Automation a) {
        try {
            if (!Gewohnheiten.get().angebot().ausgeloest(id)) return;
            JSONObject gw = a == null ? null : a.getGewohnheit();
            JSONObject data = new JSONObject().put("automation", id).put("art", "probelauf");
            if (gw != null) data.put("aktion", gw.optString("aktion")).put("wert", gw.optJSONObject("wert"));
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(new at.greenchili.surfmate.notifications.NotificationEvent(
                    KATEGORIE, at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO,
                    at.greenchili.surfmate.server.Messages.get("notifications.gewohnheit_probelauf"), "",
                    "gewohnheit-probe-" + id, "/", data));
        } catch (Throwable ignored) {
            // ein Probelauf darf nie eine Automation oder die Queue stören
        }
    }
}
