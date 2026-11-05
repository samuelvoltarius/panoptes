package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.util.Collections;

/**
 * 0.1.38.11 („dass sie selbst die Größe auch einstellen können“): „Größe im Fahrerdisplay“ auf der Tacho-Seite.
 *
 * <p>EINE Quelle: {@code blindspot.clusterSizeProfile} – dieselbe Einstellung, die Totwinkel, Karte und App-Projektion
 * lesen und mit der Panoptes beim Schließen wiederherstellt. 0 = „Automatisch (vom Auto)“: Panoptes sendet keine Größe.
 * 29 / 31 / 30 = klein 8,8" / mittel 10,25" / groß 12,3". Ein Tipp speichert und zeigt die Größe sofort
 * ({@code ClusterProjectionController.groesseAnwenden}); „Zurück zum vorherigen“ ist derselbe Aufruf mit dem alten Wert.
 * Nur im Stand und nur mit Login/am Display (TachoApiHandler).
 */
public final class TachoGroesse {

    public static final int AUTOMATISCH = 0, KLEIN = 29, MITTEL = 31, GROSS = 30;

    private TachoGroesse() {}

    /** Speichern und Anwenden; Tests ersetzen beide. */
    interface Speicher { boolean setze(int profil) throws Exception; }
    interface Anwender { String wende() throws Exception; }

    static volatile Speicher speicher = profil -> at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(
            "blindspot", Collections.<String, Object>singletonMap("clusterSizeProfile", profil));
    static volatile Anwender anwender =
            () -> at.greenchili.surfmate.surveillance.ClusterProjectionController.groesseAnwenden();

    public static boolean gueltig(int profil) {
        return profil == AUTOMATISCH || profil == KLEIN || profil == MITTEL || profil == GROSS;
    }

    /** {profil, vorschlag, geraet}: die gespeicherte Größe (0 = Automatisch) und der Vorschlag nach Modell. */
    public static JSONObject stand() throws Exception {
        JSONObject s = at.greenchili.surfmate.surveillance.ClusterProjectionController.profilStand();
        return new JSONObject().put("ok", true).put("profil", s.optInt("profil", 0))
                .put("vorschlag", s.optInt("vorschlag", 0)).put("geraet", s.optString("geraet", ""));
    }

    /** Größe setzen und sofort zeigen. {ok, profil, angewendet} oder {ok:false, grund}. */
    public static synchronized JSONObject setze(int profil) throws Exception {
        if (!gueltig(profil)) return new JSONObject().put("ok", false).put("grund", "ungueltige_groesse");
        if (!speicher.setze(profil)) return new JSONObject().put("ok", false).put("grund", "nicht_gespeichert");
        String angewendet;
        try { angewendet = anwender.wende(); }
        catch (Throwable t) { angewendet = "fehler"; }
        return new JSONObject().put("ok", true).put("profil", profil).put("angewendet", angewendet);
    }
}
