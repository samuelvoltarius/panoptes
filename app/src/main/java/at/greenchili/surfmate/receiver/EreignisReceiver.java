package at.greenchili.surfmate.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import at.greenchili.surfmate.R;

/**
 * „Ereignis gesichert“ (Ereignis-Clip 0.1.27): kurze Meldung am Display, ohne Ton und ohne Licht
 * (REGEL A+). Sender ist der Kamera-Daemon ({@code am broadcast}, Shell-UID); nur Shell/System
 * dürfen senden (DUMP), wie bei den anderen Daemon-Empfängern.
 */
public final class EreignisReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !at.greenchili.surfmate.recording.EreignisMonitor.ACTION_GESICHERT.equals(intent.getAction())) {
            return;
        }
        try {
            Toast.makeText(context.getApplicationContext(), R.string.ereignis_gesichert, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
