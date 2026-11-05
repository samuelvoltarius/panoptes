package at.greenchili.surfmate.telegram.config;

import org.json.JSONObject;

/**
 * 0.1.38.11 (05.11.: „Wächter-Bilder immer automatisch an Telegram, ohne erst aktivieren“): Bilder und Videos an
 * Telegram ({@code videoUploads}) sind Standard AN, sobald Telegram gekoppelt ist. Eine Quelle für den Standard.
 *
 * <p>{@code videoUploads} schreibt nur ein bewusster Schalter (Web, Kategorien, alte Einstellungen). Steht dort {@code false},
 * hat jemand ausgeschaltet: das bleibt aus. Fehlt der Schlüssel, gilt der neue Standard.
 *
 * <p>Bestand bekommt EINMAL eine Telegram-Nachricht, wenn es dadurch neu an ist ({@link #entscheide}). Der Merker
 * {@link #K_HINWEIS} wird gesetzt, sobald entschieden ist (still oder nach erfolgreichem Versand), danach nie wieder.
 * Wer erst nach dieser Version koppelt, war beim ersten Durchgang nicht gekoppelt und bekommt keine Nachricht.
 */
public final class MedienStandard {

    private MedienStandard() {}

    /** Standard für {@code videoUploads}, wenn der Schlüssel fehlt. */
    public static final boolean STANDARD = true;

    /** Merker: einmalige Nachricht erledigt (gesendet oder nicht nötig). */
    public static final String K_HINWEIS = "_medienStandardHinweis";

    public static final String NICHTS = "nichts";    // schon erledigt
    public static final String STILL = "still";      // erledigt, ohne Nachricht (Merker setzen)
    public static final String HINWEIS = "hinweis";  // einmal Nachricht schicken, dann Merker setzen

    /** Ein/Aus aus dem Telegram-Abschnitt (null = Standard). */
    public static boolean an(JSONObject telegram) {
        return telegram == null ? STANDARD : telegram.optBoolean(UnifiedTelegramConfig.K_VIDEO_UPLOADS, STANDARD);
    }

    /** Was beim ersten Durchgang auf dieser Version mit dem Telegram-Abschnitt passiert. */
    public static String entscheide(JSONObject telegram) {
        if (telegram == null || telegram.length() == 0) return NICHTS;    // nicht lesbar: nichts entscheiden
        if (telegram.optBoolean(K_HINWEIS, false)) return NICHTS;
        if (telegram.optLong(UnifiedTelegramConfig.K_OWNER_CHAT_ID, -1L) <= 0) return STILL;  // nicht gekoppelt
        if (telegram.has(UnifiedTelegramConfig.K_VIDEO_UPLOADS)) return STILL;               // bewusst gewählt
        return HINWEIS;                                                                       // dadurch neu an
    }
}
