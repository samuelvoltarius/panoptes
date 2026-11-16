package at.greenchili.surfmate.tacho;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.1.43.9 (T2) Element „App-Fenster“ im freien Tacho-Editor: eine gewaehlte App laeuft in einem Kasten der
 * Panoptes-Tacho-Ansicht, das eigene Design bleibt drumherum sichtbar.
 *
 * <p><b>Weg</b> (Ablauf je Takt der Ansicht, alle 250 ms):
 * <ol>
 *   <li>Die Ansicht schickt die Lage ihrer Seite ({@code flaeche}) und, sobald sie eine hat, die Kennung ihrer eigenen
 *       Flaeche ({@code appDisplay}: privates VirtualDisplay auf einer SurfaceView im Kasten).</li>
 *   <li>Der Kern rechnet das Rechteck selbst aus dem gespeicherten Design, prueft die Regeln und antwortet mit
 *       {@code rechteck} und {@code flaeche: true}, wenn die Ansicht die Flaeche anlegen soll.</li>
 *   <li>Mit Flaeche startet {@code launcher.TachoAppFensterStart} die App darauf ({@code am start --display}).</li>
 * </ol>
 * Die App zeichnet nur in den Kasten; das Tempo kann sie nicht verdecken (zusaetzlich geprueft: Format
 * {@link TachoFrei#appPruefen} und hier in Pixeln).
 *
 * <p>Regeln (Test {@code TachoAppFensterTest}):
 * <ul>
 *   <li>Nur solange die Panoptes-Tacho-Ansicht die aktive Projektion ist; endet sie, kommt die App weg.</li>
 *   <li>Gleiche Freigabe wie „App auf dem Tacho“ ({@link TachoNaviApp}): installiert und freigegeben.</li>
 *   <li>Beim Fahren nur Apps der Navi-Liste ({@link TachoNaviApp#istNavi}); alle anderen nur im Stand und beim
 *       Losfahren sofort weg.</li>
 *   <li>Ob es auf diesem Auto geht, ist erst belegt, wenn die App einmal auf der Flaeche lag ({@code appFenster=ja});
 *       lehnt das Auto den Start ab, steht {@code nein} und der naechste Versuch kommt erst nach einer Pause.</li>
 *   <li>Geht es nicht, bleibt der Kasten leer, mit Grund. „App auf dem ganzen Tacho“ waehlt der Nutzer selbst
 *       ({@code PUT /api/tacho/navi}); hier wird nie automatisch umgeschaltet.</li>
 * </ul>
 */
public final class TachoAppFenster {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TachoAppFenster");

    public static final String AUS = "aus", FENSTER = "fenster", LEER = "leer";
    /** Gespeichert in {@code tachoNavi}: ja (lag auf der Flaeche), nein (abgelehnt), sonst ungeprueft. */
    static final String BELEGT = "appFenster";
    static final String BELEGT_ZEIT = "appFensterZeit";
    /** Nach einem Ende durch die App selbst (geschlossen, abgestuerzt) erst so spaet neu starten. */
    static final long NEUSTART_PAUSE_MS = 30_000L;
    /** Nach einem abgelehnten Start erst so spaet wieder versuchen. */
    static final long ABGELEHNT_PAUSE_MS = 5 * 60_000L;
    /** So oft pruefen, ob die App noch auf der Flaeche laeuft. */
    static final long LAUF_PRUEFUNG_MS = 5_000L;

    private static final ExecutorService arbeit = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TachoAppFenster");
        t.setDaemon(true);
        return t;
    });

    // Ist-Zustand (nur unter der Klassensperre)
    private static String istPaket;
    private static int istDisplay = -1;
    private static boolean arbeitLaeuft;
    private static long neustartAb;
    private static long laufGeprueft;
    private static String zustand = AUS, grund = "", paket = "";

    private TachoAppFenster() {}

    // ------------------------------------------------------------ rein

    /** Das App-Element eines (geprueften) Designs der Version 2 oder null. */
    static JSONObject appElement(JSONObject eigen) {
        return element(eigen, "typ", "app");
    }

    /** Das Tempo-Element oder null. */
    static JSONObject tempoElement(JSONObject eigen) {
        return element(eigen, "wert", "tempo");
    }

    private static JSONObject element(JSONObject eigen, String feld, String wert) {
        JSONArray el = eigen == null ? null : eigen.optJSONArray("elemente");
        if (el == null) return null;
        for (int i = 0; i < el.length(); i++) {
            JSONObject e = el.optJSONObject(i);
            if (e != null && wert.equals(e.optString(feld))) return e;
        }
        return null;
    }

    /**
     * Element (Einheiten der Flaeche {@link TachoFrei#BREITE} x {@link TachoFrei#HOEHE}) in Pixel des Fahrerdisplays
     * {@code [links, oben, rechts, unten]}, innen am Rahmen. {@code flaeche} = Lage der Tacho-Seite {@code [x, y, w, h]};
     * gezeichnet wird wie im Renderer mittig mit {@code s = min(w / BREITE, h / HOEHE)}. null bei ungueltiger Flaeche.
     */
    static int[] rechteck(JSONObject e, int[] flaeche) {
        if (e == null || flaeche == null || flaeche.length != 4 || flaeche[2] <= 0 || flaeche[3] <= 0) return null;
        double s = Math.min((double) flaeche[2] / TachoFrei.BREITE, (double) flaeche[3] / TachoFrei.HOEHE);
        double ox = flaeche[0] + (flaeche[2] - TachoFrei.BREITE * s) / 2, oy = flaeche[1] + (flaeche[3] - TachoFrei.HOEHE * s) / 2;
        int r0 = Math.max(0, e.optInt("rahmen", 0));
        int l = (int) Math.round(ox + (e.optInt("x") + r0) * s), t = (int) Math.round(oy + (e.optInt("y") + r0) * s);
        int r = (int) Math.round(ox + (e.optInt("x") + e.optInt("b") - r0) * s);
        int b = (int) Math.round(oy + (e.optInt("y") + e.optInt("h") - r0) * s);
        return r > l && b > t ? new int[] {l, t, r, b} : null;
    }

    static boolean ueberlappt(int[] a, int[] b) {
        return a != null && b != null && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** Lage der Tacho-Seite aus dem Takt der Ansicht {@code [x, y, w, h]} (Pixel), sonst null. */
    static int[] flaeche(JSONArray a) {
        if (a == null || a.length() != 4) return null;
        int[] f = new int[4];
        for (int i = 0; i < 4; i++) {
            double v = a.optDouble(i, Double.NaN);
            if (Double.isNaN(v) || v < -10000 || v > 10000) return null;
            f[i] = (int) Math.round(v);
        }
        return f[2] > 0 && f[3] > 0 ? f : null;
    }

    /**
     * Darf die App laufen? Ergebnis {@code null} = ja, sonst der Grund fuer „leer“. Reihenfolge = was der Nutzer
     * zuerst beheben muss.
     *
     * @param bewegungsGrund {@code null} nur im Stand (P, 0 km/h laut DrivingSafetyGuard), sonst ein Grund
     */
    static String sperre(String paket, boolean startbar, boolean freigegeben, boolean navi, String bewegungsGrund,
                         int[] app, int[] tempo) {
        if (paket == null || paket.isEmpty()) return "keine_app";
        if (!startbar) return "nicht_installiert";
        if (!freigegeben) return "nicht_freigegeben";
        if (bewegungsGrund != null && !navi) return "nur_im_stand";
        if (app == null) return "ansicht_unbekannt";
        if (tempo == null || ueberlappt(app, tempo)) return "app_tempo";
        return null;
    }

    // ------------------------------------------------------------ Laufzeit

    /**
     * Ein Takt der Panoptes-Tacho-Ansicht (aus {@link TachoAnsicht#daten}). Blockiert nie: Starten und Wegnehmen laufen
     * auf einem eigenen Faden, immer nur eine Arbeit zugleich.
     *
     * @return {@code {zustand, grund, paket}}; bei erlaubter App dazu {@code flaeche: true} und {@code rechteck}
     *         (Pixel des Fahrerdisplays), damit die Ansicht ihre Flaeche genau dort anlegt
     */
    public static JSONObject schritt(boolean aktiv, JSONObject eigen, JSONArray flaecheRoh, int appDisplay) {
        JSONObject app = aktiv ? appElement(eigen) : null;
        if (app == null) {
            wegnehmen();
            return setze(AUS, "", "", null);
        }
        String p = app.optString("paket", "");
        String sp;
        int[] rechteck = null;
        try {
            int[] f = flaeche(flaecheRoh);
            rechteck = rechteck(app, f);
            int[] tempo = rechteck(tempoElement(eigen), f);
            boolean startbar = !p.isEmpty() && !TachoAnsicht.istAnsicht(p)
                    && at.greenchili.surfmate.launcher.AppLauncher.isLaunchable(p);
            sp = sperre(p, startbar, TachoNaviApp.istFreigegeben(p), TachoNaviApp.istNavi(p), bewegungsGrund(), rechteck, tempo);
        } catch (Throwable t) {
            sp = "ansicht_unbekannt";
        }
        if (sp != null) {
            wegnehmen();
            return setze(LEER, sp, p, null);
        }
        if (appDisplay <= 0) return setze(LEER, "startet", p, rechteck);   // Flaeche entsteht gerade
        anwenden(p, appDisplay);
        synchronized (TachoAppFenster.class) {
            if (p.equals(istPaket) && istDisplay == appDisplay) return setze(FENSTER, "", p, rechteck);
            String g = "kein_fenster".equals(grund) && System.currentTimeMillis() < neustartAb ? "kein_fenster" : "startet";
            return setze(LEER, g, p, rechteck);
        }
    }

    /** Ansicht pausiert oder beendet: App weg. */
    public static void ende() {
        wegnehmen();
        setze(AUS, "", "", null);
    }

    /** Fuer die Oberflaeche ({@code GET /api/tacho/appfenster}): letzter Zustand und ob es auf diesem Auto schon ging. */
    public static synchronized JSONObject status() throws Exception {
        return new JSONObject().put("zustand", zustand).put("grund", grund).put("paket", paket).put("belegt", belegt());
    }

    private static synchronized JSONObject setze(String z, String g, String p, int[] rechteck) {
        zustand = z; grund = g; paket = p;
        try {
            JSONObject o = new JSONObject().put("zustand", z).put("grund", g).put("paket", p);
            if (rechteck != null) {
                o.put("flaeche", true).put("rechteck", new JSONArray().put(rechteck[0]).put(rechteck[1]).put(rechteck[2]).put(rechteck[3]));
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void anwenden(final String p, final int display) {
        final String alt;
        final boolean neu;
        synchronized (TachoAppFenster.class) {
            long jetzt = System.currentTimeMillis();
            if (arbeitLaeuft) return;
            neu = !p.equals(istPaket) || display != istDisplay;
            if (neu && jetzt < neustartAb) return;
            if (!neu && jetzt - laufGeprueft < LAUF_PRUEFUNG_MS) return;
            alt = neu && istPaket != null && !istPaket.equals(p) ? istPaket : null;
            arbeitLaeuft = true;
            laufGeprueft = jetzt;
        }
        arbeit.execute(() -> {
            try {
                if (alt != null) at.greenchili.surfmate.launcher.TachoAppFensterStart.weg(alt);
                if (neu) {
                    boolean ok = at.greenchili.surfmate.launcher.TachoAppFensterStart.zeige(p, display);
                    merkeBelegt(ok ? "ja" : "nein");
                    synchronized (TachoAppFenster.class) {
                        istPaket = ok ? p : null; istDisplay = ok ? display : -1;
                        if (!ok) { grund = "kein_fenster"; neustartAb = System.currentTimeMillis() + ABGELEHNT_PAUSE_MS; }
                    }
                    logger.info("App-Fenster " + p + " auf Flaeche " + display + (ok ? " gezeigt" : " abgelehnt"));
                } else if (!at.greenchili.surfmate.launcher.TachoAppFensterStart.laeuft(p, display)) {
                    // App geschlossen oder abgestuerzt: nicht sofort wieder hochreissen
                    synchronized (TachoAppFenster.class) {
                        istPaket = null; istDisplay = -1;
                        neustartAb = System.currentTimeMillis() + NEUSTART_PAUSE_MS;
                    }
                }
            } catch (Throwable t) {
                logger.warn("App-Fenster: " + t.getMessage());
            } finally {
                synchronized (TachoAppFenster.class) { arbeitLaeuft = false; }
            }
        });
    }

    private static void wegnehmen() {
        final String p;
        synchronized (TachoAppFenster.class) {
            p = istPaket;
            if (p == null) return;
            istPaket = null; istDisplay = -1;
        }
        // eigener Faden: nicht hinter einer laufenden Arbeit anstellen, sofort
        Thread t = new Thread(() -> at.greenchili.surfmate.launcher.TachoAppFensterStart.weg(p), "TachoAppFenster-weg");
        t.setDaemon(true);
        t.start();
        logger.info("App-Fenster " + p + " weggenommen");
    }

    static String belegt() {
        String b = TachoNaviApp.sektion().optString(BELEGT, "");
        return "ja".equals(b) || "nein".equals(b) ? b : "";
    }

    private static void merkeBelegt(String wert) {
        if (wert.equals(belegt())) return;
        try {
            Map<String, Object> w = new HashMap<>();
            w.put(BELEGT, wert);
            w.put(BELEGT_ZEIT, System.currentTimeMillis());
            at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(TachoNaviApp.SEKTION, w);
        } catch (Throwable t) {
            logger.warn("App-Fenster merken: " + t.getMessage());
        }
    }

    private static String bewegungsGrund() {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) {
            return "state_unknown";
        }
    }
}
