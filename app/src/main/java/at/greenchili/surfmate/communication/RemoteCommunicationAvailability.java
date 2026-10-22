package at.greenchili.surfmate.communication;

/**
 * Android-free availability policy shared by HTTP status, PTT, messages, and
 * unit tests. The first matching result is the user-visible root cause.
 */
public final class RemoteCommunicationAvailability {

    public static final String SETTINGS_PATH =
            "Settings > Status overlay > Remote communication";

    public static final class Result {
        public final boolean ready;
        public final String code;
        public final String reason;
        public final String guidance;

        private Result(boolean ready, String code, String reason, String guidance) {
            this.ready = ready;
            this.code = code;
            this.reason = reason;
            this.guidance = guidance;
        }
    }

    private RemoteCommunicationAvailability() {}

    public static Result voice(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy,
            Boolean overlayPermission) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.voiceEnabled) {
            return unavailable(
                    "voice_disabled",
                    t("voice_disabled", "Remote voice is disabled in the car settings"),
                    inCar("voice_disabled", "enable Remote voice."));
        }
        if (busy) {
            return unavailable(
                    "busy",
                    t("busy", "Another talk session is already active"),
                    t("busy_hint", "Release the other talk session before starting a new one."));
        }
        if (Boolean.FALSE.equals(overlayPermission)) return overlayPermission();
        return ready();
    }

    public static Result messages(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            Boolean overlayPermission) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.messagesEnabled) {
            return unavailable(
                    "messages_disabled",
                    t("messages_disabled", "Remote messages are disabled in the car settings"),
                    inCar("messages_disabled", "enable Remote messages."));
        }
        if (Boolean.FALSE.equals(overlayPermission)) return overlayPermission();
        return ready();
    }

    public static Result listener(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.listenerEnabled) {
            return unavailable(
                    "listener_disabled",
                    t("listener_disabled", "Cabin listening is disabled in the car settings"),
                    inCar("listener_disabled", "enable Cabin listener."));
        }
        if (busy) {
            return unavailable(
                    "listener_busy",
                    t("listener_busy", "Another cabin listener is already active"),
                    t("listener_busy_hint", "Stop the other listener before starting a new one."));
        }
        return ready();
    }

    public static boolean shouldCheckVoiceOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy) {
        return !carKnownOff
                && !settings.emergencyDisabled
                && settings.voiceEnabled
                && !busy;
    }

    public static boolean shouldCheckMessageOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings) {
        return !carKnownOff
                && !settings.emergencyDisabled
                && settings.messagesEnabled;
    }

    public static boolean shouldCheckAnyOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean voiceBusy) {
        return shouldCheckVoiceOverlay(carKnownOff, settings, voiceBusy)
                || shouldCheckMessageOverlay(carKnownOff, settings);
    }

    private static Result ready() {
        return new Result(true, "ready", "", "");
    }

    private static Result carOff() {
        return unavailable(
                "car_off",
                t("car_off", "The car is off, so remote communication is unavailable"),
                t("car_off_hint", "Turn the car on and wait for the infotainment system to finish starting."));
    }

    private static Result emergencyDisabled() {
        return unavailable(
                "emergency_disabled",
                t("emergency_disabled", "Remote communication is emergency-disabled in the car"),
                inCar("emergency_disabled", "turn off Emergency disable."));
    }

    private static Result overlayPermission() {
        return unavailable(
                "overlay_permission",
                t("overlay_permission", "Display-over-other-apps permission is not granted in the car"),
                inCar("overlay_permission", "tap Overlay permission, then allow Display over other apps."));
    }

    private static Result unavailable(
            String code, String reason, String guidance) {
        return new Result(false, code, reason, guidance);
    }

    /** Sprachkatalog server-i18n errors.remote.* (Durchsage), englischer Rückfall ohne Katalog. */
    private static String t(String code, String fallback) {
        return at.greenchili.surfmate.server.Messages.text("errors.remote." + code, fallback);
    }

    private static String inCar(String code, String action) {
        return at.greenchili.surfmate.server.Messages.text("errors.remote." + code + "_hint", "In the car app, open {0}, then {1}",
                SETTINGS_PATH, action);
    }
}
