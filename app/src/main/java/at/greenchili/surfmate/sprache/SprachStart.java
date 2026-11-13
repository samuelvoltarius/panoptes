package at.greenchili.surfmate.sprache;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 0.1.44 (P2) Einstieg in die Sprachbefehle, im App-Prozess und ohne eigene Ansicht. Gestartet von der Launcher-Kachel
 * „Sprechen“, vom Weckwort ({@link SprachWeckDienst}) oder aus {@link SprachActivity} (Aufträge, die in den
 * App-Prozess gehören).
 *
 * <p>Hier wird geprüft, was nur der App-Prozess weiß: Schalter, Sprache und ob Panoptes selbst gerade Ton aufnimmt
 * (Dashcam-Ton, Mithören). Dann startet die Ansicht im eigenen Prozess {@code :sprache} (Vosk ist nativer Code; stürzt
 * er ab, trifft es nur diesen Prozess). Will Panoptes während eines Sprachbefehls Ton aufnehmen, endet das Zuhören sofort.
 *
 * <p>Aufträge: {@link #AKTION_NAVI_HEIM} (Navi nach Hause: Vorschau, geführt wird erst nach dem Tipp auf Start),
 * {@link #AKTION_WECK_AN}, {@link #AKTION_WECK_AUS}.
 */
public final class SprachStart extends Activity {

    public static final String AKTION_NAVI_HEIM = "at.greenchili.surfmate.sprache.NAVI_HEIM";
    public static final String AKTION_WECK_AN = "at.greenchili.surfmate.sprache.WECK_AN";
    public static final String AKTION_WECK_AUS = "at.greenchili.surfmate.sprache.WECK_AUS";
    static final String EXTRA_SPRACHE = "sprache";
    static final String EXTRA_WECK = "weck";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            String aktion = getIntent() == null ? null : getIntent().getAction();
            if (AKTION_NAVI_HEIM.equals(aktion)) naviHeim();
            else if (AKTION_WECK_AN.equals(aktion)) weckAn();
            else if (AKTION_WECK_AUS.equals(aktion)) SprachWeckDienst.stoppen(this, "nutzer");
            else zuhoeren();
        } catch (Throwable t) {
            // nie die App stören
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void zuhoeren() {
        String nein = SprachRegeln.startVerweigert(schalterAn(SprachBefehle.SCHALTER), panoptesTon());
        if (nein != null) { hinweis(nein); return; }
        // Panoptes will Ton: Sprachbefehl und Weckwort geben das Mikrofon sofort frei.
        at.greenchili.surfmate.audio.AppAudioCaptureController.setSprachTonHorcher(() -> {
            sendBroadcast(new Intent(SprachActivity.AKTION_ENDE).setPackage(getPackageName()));
            SprachWeckDienst.stoppen(getApplicationContext(), "panoptes_ton_aktiv");
        });
        boolean weck = getIntent() != null && getIntent().getBooleanExtra(EXTRA_WECK, false);
        startActivity(new Intent(this, SprachActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SPRACHE, sprache()).putExtra(EXTRA_WECK, weck));
    }

    private void weckAn() {
        String nein = SprachRegeln.weckVerweigert(schalterAn(SprachBefehle.SCHALTER), schalterAn(SprachBefehle.SCHALTER_WECKWORT),
                panoptesTon(), SprachBefehle.WECKWORT.containsKey(sprache()));
        if (nein != null) { hinweis(nein); return; }
        at.greenchili.surfmate.audio.AppAudioCaptureController.setSprachTonHorcher(() ->
                SprachWeckDienst.stoppen(getApplicationContext(), "panoptes_ton_aktiv"));
        SprachWeckDienst.starten(this, sprache());
    }

    /** Navi nach Hause: Ziel „Zuhause“ aus den gespeicherten Orten; die Karte zeigt die Vorschau (A+: Start per Tipp). */
    private void naviHeim() {
        at.greenchili.surfmate.navmap.nav.SearchResult heim = at.greenchili.surfmate.navmap.SavedPlacesStore.INSTANCE.getHome(this);
        if (heim == null) { Toast.makeText(this, getString(at.greenchili.surfmate.R.string.sprache_kein_zuhause), Toast.LENGTH_LONG).show(); return; }
        java.util.List<at.greenchili.surfmate.navmap.nav.SearchResult> stopps = new java.util.ArrayList<>();
        stopps.add(heim);
        at.greenchili.surfmate.navmap.NavTripStore.INSTANCE.save(this, stopps, false);
        startActivity(new Intent(this, at.greenchili.surfmate.navmap.RoadSenseMapActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(at.greenchili.surfmate.navmap.Fuehrung.EXTRA_NEU, true));
    }

    private void hinweis(String grund) {
        Toast.makeText(this, getString(SprachRegeln.textZuGrund(grund)), Toast.LENGTH_LONG).show();
    }

    private static boolean panoptesTon() {
        try {
            return at.greenchili.surfmate.audio.AppAudioCaptureController.isAnyCaptureRunning();
        } catch (Throwable t) {
            return true;   // unklar = Mikrofon nicht nehmen
        }
    }

    static boolean schalterAn(String schluessel) {
        try {
            org.json.JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(SprachBefehle.ABSCHNITT);
            return s != null && s.optBoolean(schluessel.substring(schluessel.indexOf('.') + 1), false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sprache() {
        try {
            return SprachBefehle.sprache(at.greenchili.surfmate.server.LocaleManager.get());
        } catch (Throwable t) {
            return SprachBefehle.sprache(java.util.Locale.getDefault().getLanguage());
        }
    }
}
