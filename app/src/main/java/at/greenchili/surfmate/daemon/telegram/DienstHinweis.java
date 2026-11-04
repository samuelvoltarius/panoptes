package at.greenchili.surfmate.daemon.telegram;

/**
 * 0.1.38.8 (Community-Meldung 04.11.2026): Was der Bot sagt, wenn der Panoptes-Hauptdienst im Auto (Kamera-Daemon, der
 * auch Weboberfläche, Handy-Zugang und Wächter trägt) auf eine Frage nicht antwortet.
 *
 * <ul>
 *   <li>In den ersten {@link #ANLAUF_MS} nach Start des Bots oder nach der Kopplung: „startet noch“ – kein Alarm, frisch
 *       eingerichtete Autos brauchen dafür ein paar Minuten.</li>
 *   <li>Dienst vom Nutzer bewusst gestoppt (Merker {@code camera_daemon.disabled}): „ausgeschaltet“ – kein Ausfall.</li>
 *   <li>Sonst wirklich ausgefallen: einfacher Satz, was zu tun ist.</li>
 * </ul>
 * Rein bis auf {@link #text}: die Uhr und der Merker kommen von außen (JVM-testbar).
 */
public final class DienstHinweis {

    public static final long ANLAUF_MS = 5 * 60_000L;

    public static final String STARTET = "dienst.startet", AUS = "dienst.aus", AUSFALL = "dienst.antwortet_nicht";

    private static volatile long botStartMs = System.currentTimeMillis();
    private static volatile long gekoppeltMs = 0L;

    private static final String MERKER = "/data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled";

    private DienstHinweis() {}

    /** Bot-Daemon gestartet (TelegramBotDaemon). */
    public static void botGestartet(long jetztMs) {
        botStartMs = jetztMs;
    }

    /** Besitzer gerade gekoppelt (TelegramBotDaemon.handlePairCommand). */
    public static void gekoppelt(long jetztMs) {
        gekoppeltMs = jetztMs;
    }

    /** Katalog-Schlüssel ({@code telegram.} davor) für die Lage. */
    public static String schluessel(long jetztMs, long botStart, long gekoppelt, boolean bewusstAus) {
        if (bewusstAus) return AUS;
        long seit = Math.min(jetztMs - botStart, gekoppelt > 0 ? jetztMs - gekoppelt : Long.MAX_VALUE);
        if (seit >= 0 && seit < ANLAUF_MS) return STARTET;
        return AUSFALL;
    }

    /** Text für den Chat: Lage aus Uhr, Start, Kopplung und Merker. */
    public static String text(CommandContext ctx) {
        boolean aus = false;
        try {
            aus = new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase(MERKER)).exists();
        } catch (Throwable ignored) {
        }
        return ctx.tr(schluessel(System.currentTimeMillis(), botStartMs, gekoppeltMs, aus));
    }
}
