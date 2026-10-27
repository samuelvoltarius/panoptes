package at.greenchili.surfmate.server;

/**
 * 0.1.20 (Regel 2): Darf eine Kopplung die Position lesen? Gleiche Freigabe wie /api/ha/v1/zustand?position=1
 * (ha.positionTeilen). Als eigene Klasse, damit Tests die Entscheidung ohne Daemon prüfen können.
 */
final class GpsFreigabe {

    private GpsFreigabe() {}

    /** Für Tests ersetzbar. */
    static volatile java.util.function.BooleanSupplier kopplungQuelle = AuthMiddleware::isExternRequest;
    static volatile java.util.function.BooleanSupplier freigabeQuelle = HaApiHandler::positionErlaubt;

    static boolean kopplung() {
        try { return kopplungQuelle.getAsBoolean(); } catch (Throwable t) { return true; }   // im Zweifel wie Kopplung
    }

    static boolean erlaubt() {
        try { return freigabeQuelle.getAsBoolean(); } catch (Throwable t) { return false; }
    }
}
