package at.greenchili.surfmate.ha;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.1.43.21 MQTT-Discovery nur, wenn keine Home-Assistant-Integration gekoppelt ist (Befund der HA-Session: das
 * retained Discovery-Bündel erzeugt neben der Integration rund 230 doppelte Entitäten).
 *
 * <p>Schalter {@code ha.mqttDiscovery}: {@code automatisch} (Standard) = Discovery nur ohne gültige Kopplung der Art
 * {@code homeassistant}; {@code an} = immer wie bisher; {@code aus} = nie. Wird Discovery unterdrückt, leert Panoptes
 * genau sein eigenes Config-Topic einmal (leere retained Nachricht); kommt die Erlaubnis zurück (Kopplung entfernt oder
 * abgelaufen, Schalter an), wird das Bündel wieder angekündigt. Rein; die Ausführung macht {@code MqttPublisherService}.
 */
public final class HaMqttDiscovery {

    private HaMqttDiscovery() {}

    public static final String SCHLUESSEL = "ha.mqttDiscovery";
    public static final String AUTOMATISCH = "automatisch", AN = "an", AUS = "aus";

    /** Darf Panoptes das Discovery-Bündel senden? Unbekannter Modus = automatisch. */
    public static boolean senden(String modus, boolean haGekoppelt) {
        if (AN.equals(modus)) return true;
        if (AUS.equals(modus)) return false;
        return !haGekoppelt;
    }

    /** Modus aus der Config ({@code ha.mqttDiscovery}), Standard automatisch. */
    public static String modus(JSONObject root) {
        JSONObject ha = root == null ? null : root.optJSONObject("ha");
        String m = ha == null ? "" : ha.optString("mqttDiscovery", "");
        return AN.equals(m) || AUS.equals(m) ? m : AUTOMATISCH;
    }

    /** Gültige (nicht abgelaufene) Kopplung der Art {@code homeassistant} in {@code Kopplungen.alle}. */
    public static boolean haGekoppelt(JSONArray kopplungen) {
        for (int i = 0; kopplungen != null && i < kopplungen.length(); i++) {
            JSONObject k = kopplungen.optJSONObject(i);
            if (k != null && "homeassistant".equals(k.optString("art")) && !k.optBoolean("abgelaufen", false)) {
                return true;
            }
        }
        return false;
    }

    /** Was beim nächsten Veröffentlichen zu tun ist. */
    public enum Schritt {
        /** wie bisher: Bündel senden (falls noch nicht angekündigt). */
        SENDEN,
        /** Erlaubnis ist zurück: Bündel neu ankündigen. */
        WIEDER_SENDEN,
        /** unterdrückt, eigenes Config-Topic jetzt einmal leeren. */
        LEEREN,
        /** unterdrückt, schon geleert: nichts. */
        NICHTS
    }

    /** Merkt sich den letzten Stand; je Wechsel genau ein LEEREN bzw. WIEDER_SENDEN (und eine Protokollzeile). */
    public static final class Verlauf {
        private Boolean letzteErlaubnis;
        private boolean geleert;

        public synchronized Schritt schritt(boolean erlaubt) {
            Boolean vorher = letzteErlaubnis;
            letzteErlaubnis = erlaubt;
            if (erlaubt) {
                geleert = false;
                return Boolean.FALSE.equals(vorher) ? Schritt.WIEDER_SENDEN : Schritt.SENDEN;
            }
            if (geleert) return Schritt.NICHTS;
            geleert = true;
            return Schritt.LEEREN;
        }

        /** Leeren ist misslungen (keine Verbindung): beim nächsten Mal noch einmal versuchen. */
        public synchronized void leerenFehlgeschlagen() { geleert = false; }

        /** Hat sich die Entscheidung mit dem letzten Schritt geändert (für die Protokollzeile)? */
        public static boolean neu(Schritt s) {
            return s == Schritt.LEEREN || s == Schritt.WIEDER_SENDEN;
        }
    }

    /** Protokollzeile ohne Namen oder Kennungen. */
    public static String protokoll(String modus, boolean haGekoppelt, boolean erlaubt) {
        return "HA_MQTT_DISCOVERY " + (erlaubt ? "senden" : "aus") + " modus=" + modus
                + " haGekoppelt=" + haGekoppelt;
    }
}
