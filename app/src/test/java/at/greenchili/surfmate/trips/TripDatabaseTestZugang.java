package at.greenchili.surfmate.trips;

/** Testzugang auf paketinterne TripDatabase-Helfer. */
public final class TripDatabaseTestZugang {
    private TripDatabaseTestZugang() {}

    public static String praefix(String pfad) {
        return TripDatabase.praefix(pfad);
    }
}
