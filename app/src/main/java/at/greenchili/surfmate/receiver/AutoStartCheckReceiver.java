package at.greenchili.surfmate.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import at.greenchili.surfmate.overlay.SetupGuideDialog;
import at.greenchili.surfmate.overlay.SetupNeeds;
import at.greenchili.surfmate.services.KeepAliveAccessibilityService;

/**
 * Deploy-Schritt „BYD-Autostart nach der Installation“ (deploy/autostart-check.sh).
 *
 * <p>Nur für die Shell bzw. das System erreichbar: im Manifest mit
 * {@code android:permission="android.permission.DUMP"} geschützt (adb-Shell hat DUMP,
 * normale Apps nicht).
 * <ul>
 *   <li>{@link #ACTION_STATUS}: prüft nur und meldet (Logzeile {@value #LOG_TAG}).</li>
 *   <li>{@link #ACTION_ENABLE}: legt den Schalter im BYD-Dialog über den bestehenden
 *       AutoStartEnabler um (mit Bestätigung durch erneutes Lesen) und meldet das Ergebnis.
 *       Wird nur vom Deploy-Skript ausgelöst, nie von der App selbst.</li>
 * </ul>
 * Kein Fahrzeugbefehl: betrifft nur die Autostart-Liste des Infotainments.
 */
public class AutoStartCheckReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "at.greenchili.surfmate.action.AUTOSTART_STATUS";
    public static final String ACTION_ENABLE = "at.greenchili.surfmate.action.AUTOSTART_ENABLE";
    static final String LOG_TAG = "PanoptesAutostart";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        final Context app = context.getApplicationContext();
        if (ACTION_STATUS.equals(action)) {
            boolean fehlt = report(app, "status");
            // 0.1.21: Ergebnis an „am broadcast“ zurück (Daemon: AutostartNachUpdate) – result 1 = frei
            setResult(fehlt ? 0 : 1, "autostartMissing=" + fehlt, null);
        } else if (ACTION_ENABLE.equals(action)) {
            KeepAliveAccessibilityService service = KeepAliveAccessibilityService.getInstance();
            if (service == null) {
                Log.i(LOG_TAG, "ergebnis=NO_SERVICE (Bedienungshilfe nicht verbunden)");
                report(app, "status");
                setResult(0, "ergebnis=NO_SERVICE ok=false", null);
                return;
            }
            final PendingResult pending = goAsync();
            service.runAutoStartEnabler((success, result) -> {
                try {
                    if (success) SetupGuideDialog.markAutoStartOk(app);
                    Log.i(LOG_TAG, "ergebnis=" + (result == null ? "NONE" : result.name())
                            + " ok=" + success);
                    boolean fehlt = report(app, "nachher");
                    pending.setResult(success && !fehlt ? 1 : 0,
                            "ergebnis=" + (result == null ? "NONE" : result.name()) + " ok=" + success
                                    + " autostartMissing=" + fehlt, null);
                } finally {
                    pending.finish();
                }
            });
        }
    }

    /** Loggt den Stand; liefert autostartMissing (bei Fehler true). */
    private static boolean report(Context context, String when) {
        try {
            SetupNeeds n = SetupGuideDialog.evaluateNeeds(context);
            Log.i(LOG_TAG, when + " autostartMissing=" + n.autostartMissing
                    + " overlayMissing=" + n.overlayMissing
                    + " installTime=" + SetupGuideDialog.installTime(context));
            return n.autostartMissing;
        } catch (Throwable t) {
            Log.w(LOG_TAG, when + " fehler=" + t.getClass().getSimpleName());
            return true;
        }
    }
}
