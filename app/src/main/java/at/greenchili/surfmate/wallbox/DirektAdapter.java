package at.greenchili.surfmate.wallbox;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Ein direkter Wallbox-Adapter (WALLBOX_VERTRAG §0, Stufe 2): spricht genau ein Gerät im Heimnetz an. Sicherheit,
 * Freigabe, Bestätigung, Protokoll und Fahrt-Sperre liegen im {@link WallboxDienst} darüber; der Adapter liest und
 * schreibt nur. Fähigkeiten und Belege je Adapter stehen als Daten in {@code web/shared/wallbox-adapter.json}.
 */
public interface DirektAdapter {

    /** Ladepunkt im Vertragsformat §3 ({@code id} und {@code titel} setzt die {@link DirektQuelle}). */
    JSONObject lies() throws WallboxFehler, JSONException;

    /** Aktion „modus“ (aus/sofort) bzw. „strom“ (A); Zurücklesen macht die {@link DirektQuelle}. */
    void setze(String aktion, Object wert) throws WallboxFehler, JSONException;

    /** Bekannte Adapter (Kennung → Name). Neue Adapter hier und in der Datentabelle eintragen. */
    String[] KENNUNGEN = {"goe", "shelly", "keba", "modbus", "openwb", "easee", "zaptec", "bruecke"};

    /** Hersteller-Cloud (eigene Einwilligung, keine Adresse, Geräte-ID + Konto). */
    static boolean cloud(String adapter) {
        return "easee".equals(adapter) || "zaptec".equals(adapter);
    }

    /** Pausen vor dem 1., 2., 3. Zurücklesen (ms); Clouds melden verzögert. */
    default long[] pausen() {
        return new long[]{500, 1000, 1000};
    }

    /** Adapter, deren Adresse ein Host/IP ist (TCP/UDP); alle anderen erwarten eine http(s)-URL. */
    static boolean hostAdresse(String adapter) {
        return "keba".equals(adapter) || "modbus".equals(adapter) || "openwb".equals(adapter) || "bruecke".equals(adapter);
    }

    /** Leerer Ladepunkt mit allen Vertragsfeldern auf null. */
    static JSONObject leer() throws JSONException {
        return new JSONObject().put("art", "wallbox").put("verbunden", JSONObject.NULL).put("laedt", JSONObject.NULL)
                .put("leistungW", JSONObject.NULL).put("modus", JSONObject.NULL).put("modusRoh", JSONObject.NULL)
                .put("zielSoc", JSONObject.NULL).put("zielMin", 20).put("zielMax", 100).put("stromA", JSONObject.NULL)
                .put("fahrzeug", JSONObject.NULL).put("fahrzeugSoc", JSONObject.NULL).put("plan", JSONObject.NULL);
    }
}
