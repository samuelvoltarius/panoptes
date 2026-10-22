package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Eine Wallbox-Quelle (evcc oder Home Assistant), WALLBOX_VERTRAG §3/§4. */
public interface WallboxQuelle {

    String MODUS_AUS = "aus";
    String MODUS_SOFORT = "sofort";
    String MODUS_MINPV = "minpv";
    String MODUS_PV = "pv";
    String[] MODI = {MODUS_AUS, MODUS_SOFORT, MODUS_MINPV, MODUS_PV};

    /** Kopf-Felder für den Status (z. B. evccVersion); nie null. */
    JSONObject info() throws JSONException;

    /** Ladepunkte im Vertragsformat, ohne {@code steuernFrei} (setzt der Dienst). */
    JSONArray ladepunkte() throws WallboxFehler, JSONException;

    /**
     * Führt genau eine Aktion aus und liest zurück.
     * @return {@code {uebernommen: bool, ladepunkt: {…danach}}}
     */
    JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException;

    /** Kurze Pause zwischen Befehl und Zurücklesen; in Tests 0. */
    interface Schlaf {
        void ms(long ms);
    }

    Schlaf ECHT = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
}
