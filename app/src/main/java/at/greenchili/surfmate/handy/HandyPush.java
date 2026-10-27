package at.greenchili.surfmate.handy;

/**
 * 0.1.18 Handy-Vertrag §4: Brücke zum bestehenden Push-Speicher (NotificationApiHandler/SubscriptionStore).
 * Eigene Klasse, damit Kopplungen beim Widerruf das Abo entfernen kann, ohne den Server-Teil zu kennen.
 */
public final class HandyPush {

    private HandyPush() {}

    /** Abo entfernen; Fehler stillschweigend (Widerruf darf daran nie scheitern). */
    public static void entfernen(String aboId) {
        try {
            at.greenchili.surfmate.server.NotificationApiHandler.handyAboEntfernen(aboId);
        } catch (Throwable ignored) { }
    }
}
