package at.greenchili.surfmate.telegram.config;

import org.json.JSONObject;

/**
 * 0.1.38.12 T2: „Auch bei abgestelltem Auto erreichbar“ ({@code telegram.autoStartAccOff}) ist Standard AN, sobald
 * Telegram gekoppelt ist. Eine Quelle, gleiches Muster wie {@link MedienStandard}: Den Schlüssel schreibt nur ein
 * bewusster Schalter (Web, {@code /erreichbar}, alte Einstellungen); gespeichertes {@code false} bleibt aus. Ohne
 * Kopplung bleibt es aus, damit beim Abstellen kein Bot ohne Besitzer startet. Schutz der Starterbatterie:
 * {@link BotSchlaf12v}.
 */
public final class ErreichbarStandard {

    private ErreichbarStandard() {}

    /** Merker: einmalige Nachricht an den Bestand erledigt. */
    public static final String K_HINWEIS = "_erreichbarStandardHinweis";

    private static boolean gekoppelt(JSONObject t) {
        return t.optLong(UnifiedTelegramConfig.K_OWNER_CHAT_ID, -1L) > 0;
    }

    /** Ein/Aus aus dem Telegram-Abschnitt (null = aus). */
    public static boolean an(JSONObject telegram) {
        if (telegram == null) return false;
        if (telegram.has(UnifiedTelegramConfig.K_AUTO_START)) return telegram.optBoolean(UnifiedTelegramConfig.K_AUTO_START, false);
        return gekoppelt(telegram);
    }

    /** Wie {@link MedienStandard#entscheide}: NICHTS | STILL | HINWEIS. Neu an nur, wenn nicht schon dauerhaft an. */
    public static String entscheide(JSONObject telegram) {
        if (telegram == null || telegram.length() == 0) return MedienStandard.NICHTS;
        if (telegram.optBoolean(K_HINWEIS, false)) return MedienStandard.NICHTS;
        if (!gekoppelt(telegram)) return MedienStandard.STILL;
        if (telegram.has(UnifiedTelegramConfig.K_AUTO_START)) return MedienStandard.STILL;
        if (telegram.optBoolean(UnifiedTelegramConfig.K_DAEMON_ENABLED, false)) return MedienStandard.STILL;  // lief schon
        return MedienStandard.HINWEIS;
    }
}
