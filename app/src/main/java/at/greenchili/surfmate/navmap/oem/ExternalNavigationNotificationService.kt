package at.greenchili.surfmate.navmap.oem

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Opt-in bridge from Google Maps/Waze notifications to the already gated OEM
 * navigation publisher. Package allow-list and parsing happen before any frame;
 * daemon-side platform capability checks remain authoritative.
 */
class ExternalNavigationNotificationService : NotificationListenerService() {
    @Volatile private var activeKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val frame = ExternalNavigationNotificationParser.parse(
            sbn.packageName,
            listOf(
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            )
        ) ?: return
        activeKey = sbn.key
        OemNavigationClient.publishExternal(frame.maneuverType, frame.distanceMeters, frame.roundaboutExit)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.key != null && sbn.key == activeKey) {
            activeKey = null
            OemNavigationClient.stop()
        }
    }

    override fun onListenerDisconnected() {
        activeKey = null
        OemNavigationClient.stop()
    }
}
