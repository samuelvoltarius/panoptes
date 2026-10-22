package at.greenchili.surfmate.auth;

/**
 * Meldet neue Kopplungs-Anfragen: Telegram (Hauptweg, Besitzer-Chat mit Knöpfen) und Auto-Display
 * (Zusatz, nur im Stand, ohne Ton). Panoptes-Web fragt selbst ab ({@code GET /api/kopplung/anfragen}).
 */
final class KopplungsMelder implements KopplungsAnfragen.Beobachter {

    @Override
    public void neu(String ref, String geraet, String pruefzahl) {
        neu(ref, geraet, pruefzahl, null);
    }

    /** Texte je Art: Home Assistant bekommt „Home Assistant ‚Name‘ will sich verbinden“. */
    static String titelKey(String art) {
        return "homeassistant".equals(art) ? "notifications.kopplung_titel_ha" : "notifications.kopplung_titel";
    }

    @Override
    public void neu(String ref, String geraet, String pruefzahl, String art) {
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyKopplungsAnfrage(ref, geraet, pruefzahl, art);
        } catch (Throwable ignored) {
        }
        try {
            if (!at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.isMovementBlocked()) {
                at.greenchili.surfmate.byd.MessageOverlayController.showKopplung(ref,
                        at.greenchili.surfmate.server.Messages.get(titelKey(art), geraet),
                        at.greenchili.surfmate.server.Messages.get("notifications.kopplung_text", pruefzahl),
                        at.greenchili.surfmate.server.Messages.get("notifications.kopplung_erlauben"),
                        at.greenchili.surfmate.server.Messages.get("notifications.kopplung_ablehnen"),
                        (int) (KopplungsAnfragen.GUELTIG_MS / 1000));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void entschieden(String ref, boolean erlaubt) {
        try {
            at.greenchili.surfmate.byd.MessageOverlayController.dismissKopplung(ref);
        } catch (Throwable ignored) {
        }
    }
}
