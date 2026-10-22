package at.greenchili.surfmate.onboarding;

import at.greenchili.surfmate.config.VehicleModelSelection;

/**
 * Pure onboarding decisions, kept Android-free so fresh-install and restore
 * behavior can be covered by local unit tests.
 */
public final class OnboardingConfigPolicy {
    public enum Step {
        AUTHORIZE,
        MODE,
        VEHICLE,
        CAMERA,
        DASHBOARD,
        DONE
    }

    /**
     * 0.1.10 (Einrichtung, Regel 8): Pflichtwahlen und Einwilligungen ohne Vorauswahl. Die Oberfläche
     * belegt diese Schlüssel nie still vor (früher: Betriebsart „Immer an“, Updates, Mobilfunk); bis zur
     * Antwort gilt Karte „keine“ und sparsamer Betrieb (Teil C).
     */
    public static final java.util.Set<String> OHNE_VORAUSWAHL = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "surveillance.operatingMode", "karte.quelle", "update.pruefen", "update.mobilfunk",
                    "update.modus", "surveillance.triggerMode", "hinweise.version")));

    private OnboardingConfigPolicy() {
    }

    public static boolean darfVorbelegen(String key) {
        return key != null && !OHNE_VORAUSWAHL.contains(key);
    }

    /**
     * Startet der Assistent? Grundlage ist allein {@code aktuell} aus {@code GET /api/einrichtung}: der Kern
     * entscheidet dort Neuinstallation (alle Schritte) vs. Bestand (nur fehlende Pflicht- bzw. neue Schritte).
     * Ohne Antwort des Dienstes (null) startet er, damit die Freigabe (usb_freigabe) erklärt werden kann.
     */
    public static boolean assistentStarten(String aktuell) {
        return aktuell == null || !"fertig".equals(aktuell);
    }

    /**
     * Vehicle selection intentionally precedes camera setup: camera profiles
     * may depend on the selected physical model.
     */
    public static Step nextStep(boolean daemonAuthorized,
                                boolean modeChosen,
                                boolean vehicleConfigured,
                                boolean cameraConfigured,
                                boolean dashboardTourDone) {
        if (!daemonAuthorized) return Step.AUTHORIZE;
        if (!modeChosen) return Step.MODE;
        if (!vehicleConfigured) return Step.VEHICLE;
        if (!cameraConfigured) return Step.CAMERA;
        if (!dashboardTourDone) return Step.DASHBOARD;
        return Step.DONE;
    }

    public static boolean hasConfiguredVehicle(String modelId, String modelSource) {
        return VehicleModelSelection.isResolvedSelection(modelId, modelSource);
    }

    /**
     * A restored camera selection is enough to skip the destructive/redundant
     * first-run preview walk. Profile defaults alone still show the wizard so
     * a genuinely new vehicle gets visual verification.
     */
    public static boolean hasConfiguredCamera(int cameraId,
                                              boolean manualOverride,
                                              boolean probedAndValidated) {
        return cameraId >= 0 && cameraId <= 5
                && (manualOverride || probedAndValidated);
    }
}
