package at.greenchili.surfmate.server;

import org.json.JSONObject;

import at.greenchili.surfmate.einstellungen.Katalog;

/**
 * Fixrunde E2E 0.1.43.2 (M1, G3): Was der Kern bei einer frischen Neuinstallation selbst entscheidet.
 *
 * <p>M1: Die App schrieb „einfach“ beim ersten Start, bevor der Kern seine Konfiguration angelegt hatte – der Wert
 * ging verloren, die Neuinstallation sah „Alle Funktionen“. Jetzt erkennt der Kern die frische Installation selbst und
 * schreibt „einfach“ beim ersten Abruf fest. Ein Bestand wird nie umgeschaltet: der Kern schreibt nur, solange
 * {@code anzeige.umfang} fehlt und die Installation frisch ist; einen vorhandenen Wert ändert er nie.
 *
 * <p>G3: „Neu in Panoptes …“ nur nach einem Update; eine frische Installation gilt als „schon gesehen“.
 *
 * <p>Frisch = Einrichtung „neu“ und noch nicht fertig, oder (noch keine Einrichtung) keine Spur einer früheren
 * Nutzung ({@link EinrichtungApiHandler#istBestand}). Rein, im Test belegt.
 */
public final class Erststart {

    private Erststart() {}

    public static boolean frisch(JSONObject root) {
        if (root == null) return true;
        JSONObject e = root.optJSONObject(EinrichtungApiHandler.ABSCHNITT);
        if (e == null) return !EinrichtungApiHandler.istBestand(root);
        if (!"neu".equals(e.optString("art", ""))) return false;
        JSONObject s = e.optJSONObject("schritte");
        return s == null || !EinrichtungApiHandler.ERLEDIGT.equals(s.optString("fertig", ""));
    }

    static boolean umfangGesetzt(JSONObject root) {
        JSONObject a = root == null ? null : root.optJSONObject("anzeige");
        return a != null && a.has("umfang") && !a.isNull("umfang");
    }

    /** Muss der Kern jetzt „einfach“ festschreiben? Nur ohne Wert und nur bei frischer Installation. */
    public static boolean umfangFestschreiben(JSONObject root) {
        return !umfangGesetzt(root) && frisch(root);
    }

    /** Umfang für die Oberfläche: gespeicherter Wert, sonst frisch → „einfach“, sonst „alle“. */
    public static String umfang(JSONObject root) {
        if (umfangGesetzt(root)) return Katalog.umfang(root);
        return frisch(root) ? Katalog.UMFANG_EINFACH : Katalog.UMFANG_ALLE;
    }

    /** G3: Frische Installation ohne gemerkten Stand → aktuelle Version als gesehen merken, keine „Neu in“-Karte. */
    public static boolean neuigkeitenAlsGesehen(JSONObject stand, JSONObject root) {
        boolean ohneStand = stand == null || !stand.has("gesehenVersionCode") || stand.isNull("gesehenVersionCode");
        return ohneStand && frisch(root);
    }
}
