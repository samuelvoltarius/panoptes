package at.greenchili.surfmate.launcher;

import at.greenchili.surfmate.daemon.CameraDaemon;

/**
 * 0.1.38.5 (Befund am Auto 04.11., SPLIT_BEFUND_20261104): Kann eine App im geteilten Bildschirm laufen? Android 10
 * lässt Activities mit {@code resizeableActivity=false} (z. B. YouTube, Netflix, CarPlay am Surf) nicht in den Split,
 * außer die Entwickler-Einstellung {@code force_resizable_activities} ist an. Statt still zu scheitern, fragt der
 * Launcher vorher und sagt es ehrlich.
 *
 * <p>Gelesen wird: {@code ActivityInfo.resizeMode} der Start-Activity (über den PackageManager des Daemons,
 * verborgenes Feld per Reflexion, Rückfall {@code cmd package resolve-activity}) und
 * {@code settings get global force_resizable_activities}. Nichts wird gesetzt (Koordination 04.11.: kein
 * force_resizable am Auto). Regel wie BYDs {@code ScreenSplitUtils.isAppSuportSplit}: resizeMode 0, 5, 6 = nicht teilbar.
 */
public final class SplitFaehigkeit {

    public static final String JA = "ja", NEIN = "nein", UNBEKANNT = "unbekannt";

    /** {@code ActivityInfo.RESIZE_MODE_*} (Android 10). */
    static final int RESIZE_MODE_UNRESIZEABLE = 0, RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION = 1,
            RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY = 5, RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY = 6;
    /** Ab API 24 ist „resizeable“ der Standard für Apps ohne ausdrückliche Angabe. */
    static final int SDK_N = 24;

    private static volatile long erzwungenGeprueft;
    private static volatile boolean erzwungenZuletzt;

    private SplitFaehigkeit() {}

    /** Rein: Urteil aus resizeMode und targetSdk; {@code erzwungen} = force_resizable_activities an. */
    static String urteil(Integer resizeMode, int targetSdk, boolean erzwungen) {
        if (erzwungen) return JA;
        if (resizeMode == null) return UNBEKANNT;
        // wie BYD ScreenSplitUtils.isAppSuportSplit (Firmware 04.11.): 0, 5, 6 = nicht teilbar; VIA_SDK gilt als teilbar
        if (resizeMode == RESIZE_MODE_UNRESIZEABLE || resizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || resizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY) return NEIN;
        return JA;
    }

    /** Urteil für ein Paket (Start-Activity), nie null. */
    public static String fuer(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return UNBEKANNT;
        boolean erzwungen = erzwungen();
        Integer mode = null;
        int target = 0;
        try {
            android.content.Context ctx = CameraDaemon.getAppContext();
            android.content.pm.PackageManager pm = ctx == null ? null : ctx.getPackageManager();
            android.content.Intent i = pm == null ? null : pm.getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                android.content.pm.ActivityInfo ai = pm.getActivityInfo(i.getComponent(), 0);
                java.lang.reflect.Field f = android.content.pm.ActivityInfo.class.getDeclaredField("resizeMode");
                f.setAccessible(true);
                mode = f.getInt(ai);
                target = ai.applicationInfo == null ? 0 : ai.applicationInfo.targetSdkVersion;
            }
        } catch (Throwable ignored) {
            // weiter mit dem Shell-Rückfall
        }
        if (mode == null) {
            // Rückfall (am Emulator belegt, Daemon ohne nutzbaren PackageManager): ActivityInfo-Ausgabe von
            // `cmd package resolve-activity` enthält „resizeMode=RESIZE_MODE_…“ und „targetSdkVersion=…“.
            String aus = shell("cmd package resolve-activity -c android.intent.category.LAUNCHER " + pkg);
            mode = resizeModeAus(aus);
            target = targetSdkAus(aus);
        }
        return urteil(mode, target, erzwungen);
    }

    /** Rein: {@code resizeMode=RESIZE_MODE_…} aus der ActivityInfo-Ausgabe → Zahl, sonst null. */
    static Integer resizeModeAus(String aus) {
        if (aus == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("resizeMode=RESIZE_MODE_([A-Z_]+)").matcher(aus);
        if (!m.find()) return null;
        switch (m.group(1)) {
            case "UNRESIZEABLE": return RESIZE_MODE_UNRESIZEABLE;
            case "RESIZEABLE_VIA_SDK_VERSION": return RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
            case "FORCE_RESIZABLE_LANDSCAPE_ONLY": return RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
            case "FORCE_RESIZABLE_PORTRAIT_ONLY": return RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
            default: return 2;   // RESIZEABLE, FORCE_RESIZEABLE*, … = teilbar
        }
    }

    /** Rein: {@code targetSdkVersion=N} oder 30 (heutige Apps), wenn nicht angegeben. */
    static int targetSdkAus(String aus) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("targetSdkVersion=(\\d{1,3})").matcher(aus == null ? "" : aus);
        return m.find() ? Integer.parseInt(m.group(1)) : 30;
    }

    private static String shell(String befehl) {
        try {
            Process p = new ProcessBuilder("sh", "-c", befehl).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            java.io.InputStream in = p.getInputStream();
            while ((n = in.read(b)) > 0 && out.size() < 64 * 1024) out.write(b, 0, n);
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code force_resizable_activities} = 1? (60 s gemerkt, nur lesen) */
    public static boolean erzwungen() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - erzwungenGeprueft < 60_000L) return erzwungenZuletzt;
        boolean an = false;
        try {
            Process p = new ProcessBuilder("settings", "get", "global", "force_resizable_activities")
                    .redirectErrorStream(true).start();
            byte[] b = new byte[64];
            int n = p.getInputStream().read(b);
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            an = n > 0 && "1".equals(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (Throwable ignored) {
            // aus
        }
        erzwungenZuletzt = an;
        erzwungenGeprueft = jetzt;
        return an;
    }
}
