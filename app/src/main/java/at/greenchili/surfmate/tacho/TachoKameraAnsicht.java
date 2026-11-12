package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 0.1.42 (T3): Kamera- und Video-Ansicht im Panoptes-Tacho (Fahrerdisplay).
 *
 * <p><b>Kamera</b> über den vorhandenen Panoptes-Live-Kamera-Weg ({@code /api/camview/show|hide},
 * {@code target=cluster}). Keine eigene Kamera-Anbindung. Das Bild liegt nur im freien Feld, das das Design über
 * {@code tachoInstanz.ansicht(true)} / {@code ansichtBereich()} (T2, tacho-galerie.js) frei lässt; die Leiste mit dem
 * Tempo bleibt sichtbar. Meldet die Ansicht kein Feld ({@link #bereich}), zeigt der Tacho nichts.
 * <ul>
 *   <li><b>Rückwärtsgang:</b> Heckbild nur, wenn der Nutzer bestätigt hat, dass sein Tacho kein eigenes
 *       BYD-Rückfahrbild zeigt ({@link #K_HECK_OHNE_BYD}), und die Kamera nicht an die BYD-App abgegeben ist
 *       (dann zeigt BYD selbst). Im Zweifel nichts.</li>
 *   <li><b>Blinker</b> (nur gelesen): Seitenblick links/rechts mit den Regeln des Kerns: im Rückwärtsgang nie
 *       (suppressInReverse), kein Warnblinker (beide Seiten = nichts), weg {@link #NACHLAUF_MS} nach dem Blinken.
 *       Läuft schon der Kern-Seitenblick, zeigt der Tacho gar keine Kamera (dieselbe native Ebene).</li>
 *   <li><b>Rundumsicht</b> dauerhaft nur in P und nur, wenn der vorhandene {@code DrivingSafetyGuard} Stand meldet.</li>
 * </ul>
 * <p><b>Video</b> (eine gewählte Aufnahme): nur in P bei 0 km/h und nur, wenn der {@code DrivingSafetyGuard} Stand meldet.
 * Ohne Ton. Beim Gang-Wechsel sofort weg (Kern und Ansicht prüfen beide).
 *
 * <p>Alles ab Werk aus. Die Regeln unten sind rein und im Unit-Test belegt; {@link #schritt} wendet sie an.
 */
public final class TachoKameraAnsicht {

    public static final String KEINE = "", HECK = "heck", LINKS = "links", RECHTS = "rechts", RUNDUM = "rundum";
    /** Wie {@code BS_OFF_DEBOUNCE_MS} im Kern-Seitenblick. */
    public static final long NACHLAUF_MS = 800L;
    /** Ohne Takt der Ansicht so lange → Kamera weg (fail-closed). */
    static final long OHNE_TAKT_MS = 1500L;

    static final String K_KAMERA = "kameraAn", K_HECK = "kameraHeck", K_HECK_OHNE_BYD = "kameraHeckOhneByd",
            K_SEITE = "kameraSeite", K_RUNDUM = "kameraRundum", K_VIDEO = "videoAn", K_VIDEO_ID = "videoId";
    private static final Pattern ID = Pattern.compile("^[a-fA-F0-9]{32}$");

    private TachoKameraAnsicht() {}

    // ==================== Einstellungen ====================

    /** Gespeicherte Wahl; alles ab Werk aus. */
    public static final class Wahl {
        public final boolean kamera, heck, heckOhneByd, seite, rundum, video;
        public final String videoId;

        Wahl(boolean kamera, boolean heck, boolean heckOhneByd, boolean seite, boolean rundum, boolean video, String videoId) {
            this.kamera = kamera; this.heck = heck; this.heckOhneByd = heckOhneByd; this.seite = seite;
            this.rundum = rundum; this.video = video; this.videoId = gueltigeId(videoId);
        }

        static Wahl aus(JSONObject s) {
            if (s == null) s = new JSONObject();
            return new Wahl(s.optBoolean(K_KAMERA, false), s.optBoolean(K_HECK, false), s.optBoolean(K_HECK_OHNE_BYD, false),
                    s.optBoolean(K_SEITE, false), s.optBoolean(K_RUNDUM, false), s.optBoolean(K_VIDEO, false),
                    s.optString(K_VIDEO_ID, ""));
        }

        public JSONObject json() throws Exception {
            return new JSONObject().put(K_KAMERA, kamera).put(K_HECK, heck).put(K_HECK_OHNE_BYD, heckOhneByd)
                    .put(K_SEITE, seite).put(K_RUNDUM, rundum).put(K_VIDEO, video).put(K_VIDEO_ID, videoId);
        }
    }

    static String gueltigeId(String id) {
        return id != null && ID.matcher(id.trim()).matches() ? id.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    public static Wahl wahl() {
        try { return Wahl.aus(TachoNaviApp.sektion()); } catch (Throwable t) { return Wahl.aus(null); }
    }

    /**
     * Speichern. Ausschalten geht immer; Einschalten nur im Stand ({@code imStand}), wie „Tacho gestalten“.
     * Unbekannte Schlüssel fallen weg. Rückgabe: null = gespeichert, sonst Grund.
     */
    public static String speichern(JSONObject in, boolean imStand) {
        if (in == null) return "leer";
        Wahl alt = wahl();
        Map<String, Object> w = new HashMap<>();
        String[] schalter = {K_KAMERA, K_HECK, K_HECK_OHNE_BYD, K_SEITE, K_RUNDUM, K_VIDEO};
        boolean[] vorher = {alt.kamera, alt.heck, alt.heckOhneByd, alt.seite, alt.rundum, alt.video};
        for (int i = 0; i < schalter.length; i++) {
            if (!in.has(schalter[i])) continue;
            boolean neu = in.optBoolean(schalter[i], false);
            if (neu && !vorher[i] && !imStand) return "nur_im_stand";
            w.put(schalter[i], neu);
        }
        if (in.has(K_VIDEO_ID)) {
            String id = gueltigeId(in.optString(K_VIDEO_ID, ""));
            if (!in.optString(K_VIDEO_ID, "").isEmpty() && id.isEmpty()) return "ungueltige_aufnahme";
            w.put(K_VIDEO_ID, id);
        }
        if (w.isEmpty()) return null;
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(TachoNaviApp.SEKTION, w)
                    ? null : "nicht_gespeichert";
        } catch (Throwable t) {
            return "nicht_gespeichert";
        }
    }

    // ==================== Reine Regeln ====================

    /** Blinker-Seite aus dem gepackten Wert ({@code readTurnNow}: bit0 links, bit1 rechts, -1 unbekannt). Warnblinker = keine. */
    static String blinkerSeite(int gepackt) {
        if (gepackt < 0) return KEINE;
        boolean l = (gepackt & 1) != 0, r = (gepackt & 2) != 0;
        if (l && !r) return LINKS;
        if (r && !l) return RECHTS;
        return KEINE;
    }

    /**
     * Seitenblick mit Nachlauf: Solange geblinkt wird, die Seite; danach bleibt die letzte Seite noch
     * {@link #NACHLAUF_MS}. Ein Wechsel auf die andere Seite gilt sofort.
     */
    static String mitNachlauf(String roh, String letzte, long letzteMs, long jetzt) {
        if (!KEINE.equals(roh)) return roh;
        if (!KEINE.equals(letzte) && jetzt - letzteMs < NACHLAUF_MS) return letzte;
        return KEINE;
    }

    /**
     * Welche Kamera der Tacho zeigt.
     *
     * @param gang         P/R/N/D/M/S oder null (unbekannt)
     * @param seite        Seitenblick nach {@link #mitNachlauf}
     * @param bydHatKamera true = Kamera an die BYD-App abgegeben (BYD zeigt selbst), null = unbekannt
     * @param kernSeitenblick true = der Kern-Seitenblick ist an (dann nichts)
     * @param guardGrund   {@code DrivingSafetyGuard.getMovementBlockReason()}: null = Stand
     */
    static String entscheide(Wahl w, String gang, String seite, Boolean bydHatKamera, boolean kernSeitenblick, String guardGrund) {
        // Läuft der Kern-Seitenblick, gehört die Kamera-Ebene ihm (gleiche native Ebene) – der Tacho zeigt dann nichts.
        if (w == null || !w.kamera || gang == null || kernSeitenblick) return KEINE;
        if ("R".equals(gang)) {
            // Rückwärts: nie Seitenblick (suppressInReverse); Heck nur mit Bestätigung und wenn BYD sicher nicht selbst zeigt.
            return w.heck && w.heckOhneByd && Boolean.FALSE.equals(bydHatKamera) ? HECK : KEINE;
        }
        if (w.seite && (LINKS.equals(seite) || RECHTS.equals(seite))) return seite;
        if (w.rundum && "P".equals(gang) && guardGrund == null) return RUNDUM;
        return KEINE;
    }

    /** Video nur in P, bei 0 km/h, mit Stand laut DrivingSafetyGuard, gewählter Aufnahme und ohne Kamerabild. */
    static boolean videoErlaubt(Wahl w, String gang, double kmh, String guardGrund, String kamera) {
        return w != null && w.video && !w.videoId.isEmpty() && "P".equals(gang)
                && !Double.isNaN(kmh) && kmh >= 0 && kmh < 0.5 && guardGrund == null && KEINE.equals(kamera);
    }

    /** Freies Feld {x, y, w, h} in Pixeln des Fahrerdisplays aus der Ansicht; ungültig → null (dann nichts). */
    static int[] bereich(org.json.JSONArray a) {
        if (a == null || a.length() != 4) return null;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            double v = a.optDouble(i, Double.NaN);
            if (Double.isNaN(v) || v < 0 || v > 8000) return null;
            b[i] = (int) Math.round(v);
        }
        return b[2] >= 80 && b[3] >= 60 ? b : null;
    }

    /** {@code cam=} für {@code /api/camview/show}. */
    static String cam(String ansicht) {
        switch (ansicht) {
            case HECK: return "rear";
            case LINKS: return "bs_left";
            case RECHTS: return "bs_right";
            case RUNDUM: return "all";
            default: return null;
        }
    }

    // ==================== Anwenden (Daemon) ====================

    private static final Object SPERRE = new Object();
    private static String gezeigt = KEINE;
    private static String letzteSeite = KEINE;
    private static long letzteSeiteMs = 0, letzterTaktMs = 0;
    private static java.util.concurrent.ScheduledExecutorService waechter;

    /**
     * Ein Takt der Ansicht (aus {@link TachoAnsicht#daten()}): entscheiden und über camview anwenden.
     * Rückgabe: {kamera, video, videoId} für die Ansicht.
     */
    static JSONObject schritt(boolean aktiv, String gang, double kmh, int[] feld) throws Exception {
        Wahl w = wahl();
        long jetzt = System.currentTimeMillis();
        String guard = guardGrund();
        String ziel;
        synchronized (SPERRE) {
            letzterTaktMs = jetzt;
            String roh = blinkerSeite(gepackterBlinker());
            if (!KEINE.equals(roh)) { letzteSeite = roh; letzteSeiteMs = jetzt; }
            String seite = mitNachlauf(roh, letzteSeite, letzteSeiteMs, jetzt);
            ziel = aktiv && feld != null ? entscheide(w, gang, seite, bydHatKamera(), kernSeitenblick(), guard) : KEINE;
            anwenden(ziel, feld);
        }
        if (aktiv && w.kamera) starteWaechter();
        boolean video = aktiv && feld != null && videoErlaubt(w, gang, kmh, guard, ziel);
        return new JSONObject().put("kamera", ziel).put("video", video).put("videoId", video ? w.videoId : "");
    }

    /** Ansicht beendet oder pausiert: eigenes Kamerabild sofort weg. */
    public static void ende() {
        synchronized (SPERRE) { anwenden(KEINE, null); }
    }

    private static String gezeigtFeld = "";

    private static void anwenden(String ziel, int[] feld) {
        String geo = feld == null ? "" : feld[0] + "/" + feld[1] + "/" + feld[2] + "/" + feld[3];
        if (ziel.equals(gezeigt) && (KEINE.equals(ziel) || geo.equals(gezeigtFeld))) return;
        if (KEINE.equals(ziel)) {
            // Nur ausblenden, was der Tacho selbst gezeigt hat.
            if (!KEINE.equals(gezeigt)) camview("POST", "/api/camview/hide?persist=0");
        } else {
            camview("POST", showPfad(ziel, feld));
        }
        gezeigt = ziel;
        gezeigtFeld = KEINE.equals(ziel) ? "" : geo;
    }

    /** Show-Aufruf: Kamera, Fahrerdisplay, genau das freie Feld, ohne Auto-Ausblenden (der Tacho blendet selbst aus). */
    static String showPfad(String ziel, int[] feld) {
        return "/api/camview/show?cam=" + cam(ziel) + "&target=cluster&geometry="
                + feld[0] + "/" + feld[1] + "/" + feld[2] + "/" + feld[3] + "&persist=0&autoHide=0";
    }

    private static void camview(String methode, String pfad) {
        try {
            at.greenchili.surfmate.server.StreamingApiHandler.handleBlindSpot(methode, pfad, "", new ByteArrayOutputStream());
            at.greenchili.surfmate.server.BefehlsLog.ereignis("tacho.kamera", pfad, null, null);
        } catch (Throwable ignored) { }
    }

    private static synchronized void starteWaechter() {
        if (waechter != null) return;
        waechter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tacho-kamera-waechter"); t.setDaemon(true); return t;
        });
        waechter.scheduleWithFixedDelay(() -> {
            synchronized (SPERRE) {
                if (!KEINE.equals(gezeigt) && System.currentTimeMillis() - letzterTaktMs > OHNE_TAKT_MS) anwenden(KEINE, null);
            }
        }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static int gepackterBlinker() {
        try { return at.greenchili.surfmate.byd.BydDataCollector.getInstance().readTurnNow(); }
        catch (Throwable t) { return -1; }
    }

    private static Boolean bydHatKamera() {
        try {
            at.greenchili.surfmate.surveillance.GpuSurveillancePipeline p = at.greenchili.surfmate.daemon.CameraDaemon.getGpuPipeline();
            at.greenchili.surfmate.camera.PanoramicCameraGpu c = p != null ? p.getCamera() : null;
            return c != null ? c.isCameraYielded() : null;
        } catch (Throwable t) { return null; }
    }

    private static boolean kernSeitenblick() {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.INSTANCE.getBlindSpot().optBoolean("enabled", false);
        } catch (Throwable t) { return true; }
    }

    private static String guardGrund() {
        try { return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason(); }
        catch (Throwable t) { return "state_unknown"; }
    }
}
