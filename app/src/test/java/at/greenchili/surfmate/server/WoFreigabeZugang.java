package at.greenchili.surfmate.server;

import org.json.JSONObject;

/** Testzugang zu {@link HaApiHandler#woFreigabe(JSONObject, boolean, java.util.function.Supplier)} aus anderen Paketen. */
public final class WoFreigabeZugang {

    private WoFreigabeZugang() {}

    public static void anwenden(JSONObject r, boolean frei, JSONObject position) throws Exception {
        HaApiHandler.woFreigabe(r, frei, () -> position == null ? JSONObject.NULL : position);
    }
}
