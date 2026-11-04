package at.greenchili.surfmate.tacho;

import at.greenchili.surfmate.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paketbezogene Nutzerfreigabe fuer Apps auf dem Fahrerdisplay.
 *
 * <p>Jede installierte App mit Launcher-Activity kann ausgewaehlt werden. Fahrt, ACC und
 * Automation bleiben jedoch gesperrt, bis der Nutzer genau dieses Paket in P auf dem echten
 * Fahrerdisplay geprueft und freie Tempo-/Warnleuchten bestaetigt hat. Alle Startwege laufen
 * weiter ueber {@code ClusterCast}; {@link #fahrtSperre} ist ihre gemeinsame Policy.
 *
 * <p>0.1.36.1 (Vorgabe „Kein Video waehrend der Fahrt“): Ausserhalb von P laufen nur bekannte
 * Navigations-Apps ({@link #NAVI_PAKETE}), ab 0.1.38.1 auch Musik-/Radio-/Podcast-Apps ({@link #istAudio}).
 * Jede andere App (Video, Spiele, unbekannt) startet nur in P
 * und wird beim Verlassen von P sofort gestoppt ({@link #beiGangwechsel} → {@code ClusterCast.stop()},
 * zurueck zur Instrumentenanzeige). Gilt fuer alle Startwege (Display, Web, Tastenbelegung,
 * Automation, ACC-Autostart), weil alle durch {@link #fahrtSperre} laufen.
 *
 * <p>0.1.37: Die eigene {@link TachoAnsicht} ist ein zusaetzliches Ziel derselben Appwahl (gleiche
 * Probe, gleiche Freigabe, gleiche Sperre). Vor der ersten Probe bzw. dem ersten Start muss der
 * Nutzer einmal den Hinweis „neu, eigenes Risiko, Warnleuchten muessen sichtbar bleiben“ mit
 * „Verstanden“ bestaetigen ({@link #hinweisVerstanden}).
 *
 * <p>0.1.37 („wir wollen keine Probe, das verstehen die nicht“): Freigeben heisst App waehlen und
 * einmal den Tacho-Hinweis bestaetigen; die 20-s-Probe ist keine Pflicht mehr. Die Faehigkeitspruefung
 * (Surf/DiLink 5/unbekannt gesperrt) bleibt. „Tacho wieder normal“ ({@link #tachoNormal}) stoppt jede Projektion.
 *
 * <p>0.1.37 („das sollen die selbst entscheiden“): je freigegebener App eine Option „auch beim Fahren“
 * ({@link #FAHRT}). Ohne Eintrag gilt: Navi-Apps der festen Liste ja, alles andere nein (wie 0.1.36.1).
 * Einschalten nur mit Login (nie Kopplung, KI oder Automation) und nach dem eigenen Haftungshinweis
 * ({@link #FAHRT_HINWEIS_VERSION}); jede Aenderung steht im Befehls-Log.
 *
 * <p>0.1.38.1 („am Tacho alle Apps, es ist die eigene Entscheidung des Users“): Nach dem EINMALIGEN
 * Haftungshinweis (Version + Zeit, {@link #fahrtHinweisBestaetigt()}) darf jede freigegebene App auch beim Fahren
 * laufen, ohne Navi-Liste und ohne Audio/Video-Unterscheidung als Sperre; keine Nachfrage je App mehr. Vor dem
 * Hinweis gilt ab Werk: Navi und Musik/Radio/Podcast ({@link #fahrtStandard}). Ein ausdrueckliches „aus“ je App
 * bleibt die Entscheidung des Nutzers. KI und Automationen koennen den Hinweis nie bestaetigen.
 */
public final class TachoNaviApp {
    @FunctionalInterface
    interface FreigabeSchreiber { boolean speichere(Map<String, Object> werte); }
    private static final DaemonLogger logger = DaemonLogger.getInstance("TachoNaviApp");
    private static final String SELF_PACKAGE = "at.greenchili.surfmate";
    static final String SEKTION = "tachoNavi";
    static final String FREIGABEN = "freigaben";
    static final long PROBE_MS = 20_000L;
    /** Je Paket „auch beim Fahren“: true/false ausdruecklich, fehlt = {@link #istNavi}. */
    static final String FAHRT = "fahrt";
    static final String FAHRT_HINWEIS = "fahrtHinweis";
    /** Neue Textversion = neu bestaetigen (Muster Tiermodus/AssistenzHinweis). */
    // 0.1.38.1: neue Fassung (gilt jetzt fuer alle freigegebenen Apps) → einmal neu bestaetigen
    public static final String FAHRT_HINWEIS_VERSION = "2026-11-04";

    private static volatile long probeBis;
    private static volatile String probePaket;
    /** Nur waehrend des synchronen Probe-Starts gesetzt. */
    private static volatile String probeStartPaket;
    /** Ein fehlgeschlagener Persistenzschreibzug darf einen Widerruf nie wieder oeffnen. */
    private static final Set<String> widerrufen = ConcurrentHashMap.newKeySet();

    /**
     * Pakete, die als Navigation gelten und auch beim Fahren am Fahrerdisplay bleiben duerfen.
     * Nur bekannte Pakete; alles andere gilt als „nur in P“.
     */
    static final Set<String> NAVI_PAKETE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.google.android.apps.maps",          // Google Maps
            "com.google.android.apps.mapslite",      // Google Maps Go
            "com.waze",                               // Waze
            "net.osmand",                             // OsmAnd
            "net.osmand.plus",                        // OsmAnd+
            "com.iternio.abrpapp",                    // A Better Routeplanner
            "com.here.app.maps",                      // HERE WeGo
            "com.sygic.aura",                         // Sygic
            "com.generalmagic.magicearth",            // Magic Earth
            SELF_PACKAGE                              // Panoptes selbst
    )));

    /**
     * Bekannte Video- und Streaming-Apps: der Fahrt-Hinweis zeigt dann deutlich „Video-App“. Keine Sperre.
     * Paketnamen aus dem Gedaechtnis, nicht gegen den Play Store geprueft.
     */
    static final Set<String> VIDEO_PAKETE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "tv.twitch.android.app",
            "org.videolan.vlc", "org.xbmc.kodi", "com.mxtech.videoplayer.ad", "com.plexapp.android",
            "org.schabi.newpipe", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "tv.danmaku.bili",
            "com.tencent.qqlive", "com.youku.phone", "com.qiyi.video", "com.wbd.stream",
            "de.zdf.android.mediathek", "de.swr.avp.ard", "de.prosiebensat1digital.seventv", "com.dazn",
            "com.vimeo.android.videoapp", "com.dailymotion.dailymotion")));

    /**
     * 0.1.38.1 („warum laeuft Spotify nur in P?“): bekannte Musik-, Radio-, Podcast- und Hoerbuch-Apps.
     * Sie zeigen kein Video und duerfen ab Werk auch beim Fahren laufen, wie Navi-Apps und ohne eigenen
     * „auch beim Fahren“-Hinweis. Paketnamen aus dem Gedaechtnis, nicht gegen den Play Store geprueft; dazu kommt
     * die Erkennung ueber {@link #audioErkennung}. Eine Video-App ({@link #istVideo}) ist nie Audio.
     */
    static final Set<String> AUDIO_PAKETE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.spotify.music", "com.spotify.lite", "deezer.android.app", "com.aspiro.tidal", "com.amazon.mp3",
            "com.google.android.apps.youtube.music", "com.apple.android.music", "com.audible.application",
            "au.com.shiftyjelly.pocketcasts", "com.google.android.apps.podcasts", "fm.castbox.audiobook.radio.podcast",
            "tunein.player", "de.radio.android", "com.soundcloud.android", "com.qobuz.music", "com.bambuna.podcastaddict",
            "com.storytel", "de.bookbeat.android")));

    /**
     * Erkennung installierter Audio-Apps: Android-Kategorie {@code CATEGORY_AUDIO} oder ein
     * {@code MediaBrowserService} (Medien-Apps fuer Android Auto), nie bei Kategorie Video. Austauschbar fuer Tests.
     */
    static volatile java.util.function.Predicate<String> audioErkennung = p -> {
        try { return at.greenchili.surfmate.launcher.AppLauncher.istAudioApp(p); } catch (Throwable t) { return false; }
    };

    private TachoNaviApp() {}

    /** Musik/Radio/Podcast/Hoerbuch (feste Liste oder erkannt), aber nie eine bekannte Video-App. */
    public static boolean istAudio(String paket) {
        if (paket == null) return false;
        String p = paket.trim();
        if (p.isEmpty() || istVideo(p)) return false;
        if (AUDIO_PAKETE.contains(p)) return true;
        java.util.function.Predicate<String> e = audioErkennung;
        if (e == null) return false;
        // Erkennung kostet je App einen PackageManager-Aufruf; 10 min merken (Status listet alle Apps).
        long jetzt = System.currentTimeMillis();
        Object[] m = audioMerker.get(p);
        if (m != null && jetzt - (Long) m[1] < AUDIO_MERKEN_MS && m[2] == e) return (Boolean) m[0];
        boolean a = e.test(p);
        audioMerker.put(p, new Object[] {a, jetzt, e});
        return a;
    }

    static final long AUDIO_MERKEN_MS = 10 * 60_000L;
    private static final Map<String, Object[]> audioMerker = new ConcurrentHashMap<>();

    /** Ab Werk auch beim Fahren: Navi oder Audio. Video und alles Unbekannte nur in P bzw. nach eigenem Hinweis. */
    static boolean fahrtStandard(String paket) {
        return istNavi(paket) || istAudio(paket);
    }

    /** Bekannte Video-/Streaming-App (nur fuer den Hinweis, keine harte Sperre)? */
    public static boolean istVideo(String paket) {
        return paket != null && VIDEO_PAKETE.contains(paket.trim());
    }

    /** Darf diese App auch ausserhalb von P laufen? Ausdruecklicher Eintrag, sonst Navi oder Audio ({@link #fahrtStandard}). */
    static boolean fahrtErlaubt(String paket, JSONObject fahrt) {
        return fahrtErlaubt(paket, fahrt, false);
    }

    /**
     * Wie oben, mit dem einmaligen Haftungshinweis: bestaetigt → jede App (ausser ausdruecklich „aus“).
     * Ob die App freigegeben ist, prueft {@link #fahrtSperre} getrennt.
     */
    static boolean fahrtErlaubt(String paket, JSONObject fahrt, boolean hinweisBestaetigt) {
        if (paket == null) return false;
        String p = paket.trim();
        if (fahrt != null && fahrt.has(p)) return fahrt.optBoolean(p, false);
        return hinweisBestaetigt || fahrtStandard(p);
    }

    public static boolean fahrtErlaubt(String paket) {
        return fahrtErlaubt(paket, fahrtListe(), fahrtHinweisBestaetigt());
    }

    private static JSONObject fahrtListe() {
        JSONObject f = sektion().optJSONObject(FAHRT);
        return f == null ? new JSONObject() : f;
    }

    /** Eigener Haftungshinweis fuer „auch beim Fahren“ in der aktuellen Textversion bestaetigt? */
    public static boolean fahrtHinweisBestaetigt() {
        return fahrtHinweisBestaetigt(sektion().optJSONObject(FAHRT_HINWEIS));
    }

    static boolean fahrtHinweisBestaetigt(JSONObject h) {
        return h != null && FAHRT_HINWEIS_VERSION.equals(h.optString("version")) && h.optLong("zeit", 0L) > 0L;
    }

    /** Bestaetigt den Fahrt-Hinweis; nur die aktuelle Version wird angenommen. Speichert nur Version und Zeit. */
    public static synchronized JSONObject fahrtHinweisBestaetigen(String version) throws Exception {
        if (!FAHRT_HINWEIS_VERSION.equals(version == null ? "" : version.trim())) {
            return new JSONObject().put("ok", false).put("grund", "version");
        }
        Map<String, Object> w = new HashMap<>();
        w.put(FAHRT_HINWEIS, new JSONObject().put("version", FAHRT_HINWEIS_VERSION)
                .put("zeit", System.currentTimeMillis()));
        boolean ok = speichere(w);
        logFahrt("hinweis", ok ? "ok" : "fehlgeschlagen", null);
        return new JSONObject().put("ok", ok);
    }

    /** Grund, warum „auch beim Fahren“ nicht gesetzt werden darf, sonst null. Ausschalten geht immer. */
    static String fahrtSperreSetzen(String paket, boolean an, boolean freigegeben, boolean hinweisBestaetigt) {
        if (!gueltigesZiel(paket)) return "ungueltiges_paket";
        if (!an) return null;
        if (!freigegeben) return "nicht_freigegeben";
        if (!hinweisBestaetigt) return "fahrt_hinweis_noetig";
        return null;
    }

    /**
     * Option „auch beim Fahren“ fuer ein Paket setzen. Nur ueber {@code TachoApiHandler} (Login, keine Kopplung,
     * keine Automation, keine KI). Ausschalten ausserhalb von P stoppt die App sofort.
     */
    public static synchronized JSONObject setzeFahrt(String paket, boolean an) throws Exception {
        String p = paket == null ? "" : paket.trim();
        // Navi und Audio sind ab Werk fahrtauglich: Wieder-Einschalten braucht keinen Haftungshinweis.
        String sperre = fahrtSperreSetzen(p, an, istFreigegeben(p), fahrtHinweisBestaetigt() || fahrtStandard(p));
        if (sperre != null) {
            logFahrt(an ? "an" : "aus", sperre, p);
            return new JSONObject().put("ok", false).put("grund", sperre);
        }
        JSONObject f = new JSONObject(fahrtListe().toString());
        f.put(p, an);
        Map<String, Object> w = new HashMap<>();
        w.put(FAHRT, f);
        boolean ok = speichere(w);
        logFahrt(an ? "an" : "aus", ok ? "ok" : "fehlgeschlagen", p);
        if (!an && bewegungsGrund() != null) stoppePaket(p);
        return new JSONObject().put("ok", ok).put("paket", p).put("fahrt", ok ? an : fahrtErlaubt(p));
    }

    private static void logFahrt(String was, String ergebnis, String paket) {
        try {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("tacho.fahrt." + was, ergebnis, null, null);
        } catch (Throwable ignored) { }
        logger.info("Tacho auch beim Fahren " + was + ": " + ergebnis + (paket == null ? "" : " (" + paket + ")"));
    }

    /** Ein Stopp-Schritt (Tests ersetzen sie). */
    interface Stopper { boolean stoppe() throws Exception; }

    /** Echte Stopps: App-Projektion, Spiegel/Totwinkel-Projektion, Panoptes-Karte am Tacho. */
    static volatile Stopper[] stopper = {
            () -> at.greenchili.surfmate.launcher.ClusterCast.stop(),
            () -> { at.greenchili.surfmate.surveillance.ClusterMirrorController.getInstance().stop(); return true; },
            () -> at.greenchili.surfmate.navmap.ClusterMapProjector.stop()
    };

    /**
     * „Tacho wieder normal“: stoppt jede Projektion und kehrt zur Instrumentenanzeige zurueck; der ACC-Autostart
     * der gewaehlten App ist danach aus. Freigaben bleiben. Jede Stufe laeuft, auch wenn eine vorige scheitert.
     */
    public static synchronized JSONObject tachoNormal() throws Exception {
        probeBis = 0; probePaket = null; probeStartPaket = null;
        Map<String, Object> w = new HashMap<>();
        w.put("an", false);
        boolean gespeichert = speichere(w);
        boolean alle = true;
        for (Stopper s : stopper) {
            try { alle &= s.stoppe(); }
            catch (Throwable t) { alle = false; logger.warn("Tacho wieder normal: " + t.getMessage()); }
        }
        try {
            at.greenchili.surfmate.server.BefehlsLog.ereignis("tacho.normal", alle ? "ok" : "unvollstaendig", null, null);
        } catch (Throwable ignored) { }
        logger.info("Tacho wieder normal (" + (alle ? "ok" : "unvollstaendig") + ")");
        return new JSONObject().put("ok", alle).put("success", alle).put("gespeichert", gespeichert);
    }

    /** Gilt dieses Paket als Navigation (darf auch ausserhalb von P laufen)? */
    public static boolean istNavi(String paket) {
        return paket != null && NAVI_PAKETE.contains(paket.trim());
    }

    /** Muss die aktive App beim Gangwechsel weg? Ausserhalb von P: jede nicht freigegebene und jede ohne „auch beim Fahren“. */
    static boolean mussBeiGangStoppen(int gang, String aktivPaket, boolean freigegeben) {
        return mussBeiGangStoppen(gang, aktivPaket, freigegeben, fahrtStandard(aktivPaket));
    }

    static boolean mussBeiGangStoppen(int gang, String aktivPaket, boolean freigegeben, boolean fahrt) {
        if (gang == at.greenchili.surfmate.monitor.GearMonitor.GEAR_P) return false;
        if (aktivPaket == null || aktivPaket.trim().isEmpty()) return false;
        return !freigegeben || !fahrt;
    }

    static boolean gueltigesFremdpaket(String paket) {
        if (paket == null) return false;
        String p = paket.trim();
        if (p.isEmpty() || p.length() > 255 || SELF_PACKAGE.equals(p)
                || p.startsWith(SELF_PACKAGE + ".")) return false;
        if (p.startsWith(".") || p.endsWith(".") || p.indexOf('.') < 1 || p.contains("..")) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '.') return false;
        }
        return true;
    }

    /**
     * Ziel der Appwahl: jede gueltige Fremd-App oder die eigene Panoptes-Tacho-Ansicht
     * ({@link TachoAnsicht}). Panoptes selbst ist sonst nie waehlbar.
     */
    static boolean gueltigesZiel(String paket) {
        return gueltigesFremdpaket(paket) || TachoAnsicht.istAnsicht(paket);
    }

    static final String HINWEIS = "hinweisVerstanden";

    /** Einmaliger Hinweis „neu, eigenes Risiko, Warnleuchten muessen sichtbar bleiben“ bestaetigt? */
    public static boolean hinweisVerstanden() { return sektion().optLong(HINWEIS, 0L) > 0L; }

    public static synchronized JSONObject hinweisBestaetigen() throws Exception {
        Map<String, Object> w = new HashMap<>();
        w.put(HINWEIS, System.currentTimeMillis());
        return new JSONObject().put("ok", speichere(w));
    }

    static boolean istFreigegeben(String paket, JSONObject freigaben) {
        if (!gueltigesZiel(paket) || freigaben == null) return false;
        Object wert = freigaben.opt(paket.trim());
        return Boolean.TRUE.equals(wert) || wert instanceof Number;
    }

    public static boolean istFreigegeben(String paket) {
        String p = paket == null ? "" : paket.trim();
        return !widerrufen.contains(p) && istFreigegeben(p, freigaben());
    }

    private static boolean istStartbar(String paket) {
        if (TachoAnsicht.istAnsicht(paket)) return true; // eigene Activity, immer vorhanden
        return gueltigesFremdpaket(paket)
                && at.greenchili.surfmate.launcher.AppLauncher.isLaunchable(paket.trim());
    }

    /** Gemeinsame Zulassung vor jedem Cluster-Start. Nur die Probe hat eine enge P-Ausnahme. */
    public static String fahrtSperre(String paket, String bewegungsGrund) {
        String p = paket == null ? "" : paket.trim();
        return fahrtSperreAuswertung(p, andereApps(p), bewegungsGrund,
                istStartbar(p), istFreigegeben(p), p.equals(probeStartPaket), fahrtErlaubt(p));
    }

    static String fahrtSperreAuswertung(String paket, JSONObject andereApps, String bewegungsGrund,
                                        boolean startbar, boolean freigegeben, boolean probe) {
        return fahrtSperreAuswertung(paket, andereApps, bewegungsGrund, startbar, freigegeben, probe,
                istNavi(paket));
    }

    static String fahrtSperreAuswertung(String paket, JSONObject andereApps, String bewegungsGrund,
                                        boolean startbar, boolean freigegeben, boolean probe, boolean fahrt) {
        if (!gueltigesZiel(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        String status = andereApps == null ? "" : andereApps.optString("status");
        String code = andereApps == null ? "" : andereApps.optString("code");
        if (probe) {
            if (bewegungsGrund != null) return "nur_im_stand";
            return code.startsWith("warnleuchten_") ? null : "auf_diesem_auto_nicht_angeboten";
        }
        if (!freigegeben) return "nicht_freigegeben";
        // 0.1.36.1: ausserhalb von P (oder Zustand unbekannt) nur Navigation; 0.1.37: oder „auch beim Fahren“.
        if (bewegungsGrund != null && !fahrt) return "nur_im_stand";
        return TachoFaehigkeit.JA.equals(status) ? null : "auf_diesem_auto_nicht_angeboten";
    }

    static String einschaltSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                                  boolean startbar, boolean freigegeben, boolean hinweisVerstanden) {
        if (!hinweisVerstanden) return "hinweis_noetig";
        return einschaltSperre(paket, andereApps, bewegungsGrund, startbar, freigegeben);
    }

    static String einschaltSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                                  boolean startbar, boolean freigegeben) {
        if (!gueltigesZiel(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        if (!freigegeben) return "nicht_freigegeben";
        if (bewegungsGrund != null) return "nur_im_stand";
        if (andereApps == null || !TachoFaehigkeit.JA.equals(andereApps.optString("status"))) {
            return "auf_diesem_auto_nicht_angeboten";
        }
        return null;
    }

    /**
     * 0.1.37: Freigabe ohne Probe. Noetig: Tacho-Hinweis bestaetigt, App startbar und Plattform angeboten
     * (Faehigkeitspruefung: Surf, DiLink 5 und Unbekanntes bleiben gesperrt).
     */
    static String freigabeSperre(String paket, JSONObject andereApps, boolean startbar, boolean hinweisVerstanden) {
        if (!gueltigesZiel(paket)) return "ungueltiges_paket";
        if (!hinweisVerstanden) return "hinweis_noetig";
        if (!startbar) return "nicht_installiert";
        String code = andereApps == null ? "" : andereApps.optString("code");
        String status = andereApps == null ? "" : andereApps.optString("status");
        if (!code.startsWith("warnleuchten_") && !TachoFaehigkeit.JA.equals(status)) {
            return "auf_diesem_auto_nicht_angeboten";
        }
        return null;
    }

    static String probeSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                              boolean startbar, boolean hinweisVerstanden) {
        if (!hinweisVerstanden) return "hinweis_noetig";
        return probeSperre(paket, andereApps, bewegungsGrund, startbar);
    }

    static String probeSperre(String paket, JSONObject andereApps, String bewegungsGrund,
                              boolean startbar) {
        if (!gueltigesZiel(paket)) return "ungueltiges_paket";
        if (!startbar) return "nicht_installiert";
        if (bewegungsGrund != null) return "nur_im_stand";
        String code = andereApps == null ? "" : andereApps.optString("code");
        if (!code.startsWith("warnleuchten_")) return "auf_diesem_auto_nicht_angeboten";
        return null;
    }

    static JSONObject sektion() {
        try {
            JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject(SEKTION);
            return s == null ? new JSONObject() : s;
        } catch (Throwable t) { return new JSONObject(); }
    }

    private static JSONObject freigaben() {
        JSONObject f = sektion().optJSONObject(FREIGABEN);
        return f == null ? new JSONObject() : f;
    }

    public static boolean istAn() { return sektion().optBoolean("an", false); }
    public static String paket() { return sektion().optString("paket", ""); }
    /** Kompatibilitaetswert: ja nur fuer das aktuell gewaehlte, freigegebene Paket. */
    public static String warnleuchtenFrei() {
        return warnleuchtenFrei(paket(), freigaben());
    }

    static String warnleuchtenFrei(String paket, JSONObject freigaben) {
        return istFreigegeben(paket, freigaben) ? TachoFaehigkeit.JA : "";
    }

    static JSONObject kopiereFreigaben(JSONObject original) throws Exception {
        return original == null ? new JSONObject() : new JSONObject(original.toString());
    }

    static boolean commitFreigabe(String frei, String p, JSONObject liveFreigaben,
                                   String aktuellesPaket, FreigabeSchreiber schreiber) throws Exception {
        JSONObject f = TachoFaehigkeit.JA.equals(frei)
                ? kopiereFreigaben(liveFreigaben) : liveFreigaben;
        if (f == null) f = new JSONObject();
        if (TachoFaehigkeit.JA.equals(frei)) f.put(p, System.currentTimeMillis()); else f.remove(p);
        Map<String, Object> w = new HashMap<>();
        w.put(FREIGABEN, f);
        if (TachoFaehigkeit.JA.equals(frei)) w.put("paket", p);
        if (TachoFaehigkeit.NEIN.equals(frei) && p.equals(aktuellesPaket)) w.put("an", false);
        return schreiber.speichere(w);
    }

    private static boolean speichere(Map<String, Object> werte) {
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(SEKTION, werte);
        } catch (Throwable t) {
            logger.warn("Fahrerdisplay-App speichern fehlgeschlagen: " + t.getMessage());
            return false;
        }
    }

    private static String bewegungsGrund() {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) { return "state_unknown"; }
    }

    private static JSONObject andereApps(String paket) {
        try {
            TachoFaehigkeit.Eingaben e = TachoFaehigkeit.erhebe();
            e.warnleuchtenFrei = istFreigegeben(paket) ? TachoFaehigkeit.JA : "";
            return TachoFaehigkeit.bewerte(e).optJSONObject("andereApps");
        } catch (Throwable t) { return null; }
    }

    private static JSONObject plattformUrteil(boolean bestaetigt) {
        try {
            TachoFaehigkeit.Eingaben e = TachoFaehigkeit.erhebe();
            e.warnleuchtenFrei = bestaetigt ? TachoFaehigkeit.JA : "";
            return TachoFaehigkeit.bewerte(e);
        } catch (Throwable t) { return null; }
    }

    /** Status plus dieselbe Liste startbarer Apps wie die generische Projektion, ohne SurfMate. */
    public static JSONObject status() throws Exception {
        JSONObject r = new JSONObject();
        r.put("an", istAn()).put("paket", paket()).put("warnleuchtenFrei", warnleuchtenFrei());
        String aktiv = null;
        try {
            if (at.greenchili.surfmate.launcher.ClusterCast.isActive())
                aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
        } catch (Throwable ignored) { }
        r.put("aktiv", aktiv == null ? JSONObject.NULL : aktiv);
        r.put("probeLaeuft", probeBis > System.currentTimeMillis());
        r.put("probePaket", probePaket == null ? JSONObject.NULL : probePaket);
        JSONObject probeUrteil = plattformUrteil(false), bestaetigtUrteil = plattformUrteil(true);
        JSONObject probeApps = probeUrteil == null ? null : probeUrteil.optJSONObject("andereApps");
        JSONObject bestaetigtApps = bestaetigtUrteil == null ? null : bestaetigtUrteil.optJSONObject("andereApps");
        boolean angeboten = probeApps != null && probeApps.optString("code").startsWith("warnleuchten_");
        boolean startPlattform = bestaetigtApps != null && TachoFaehigkeit.JA.equals(bestaetigtApps.optString("status"));
        r.put("angeboten", angeboten);
        r.put("grund", angeboten ? "" : (probeApps == null ? "faehigkeit_unbekannt" : probeApps.optString("code")));
        r.put("plattform", probeUrteil == null ? JSONObject.NULL : probeUrteil.opt("plattform"));
        r.put("hinweisVerstanden", hinweisVerstanden());
        r.put("fahrtHinweis", new JSONObject().put("version", FAHRT_HINWEIS_VERSION)
                .put("bestaetigt", fahrtHinweisBestaetigt()));
        JSONObject fahrt = fahrtListe();
        boolean fahrtHinweis = fahrtHinweisBestaetigt();
        r.put("ansicht", new JSONObject().put("paket", TachoAnsicht.PAKET)
                .put("design", TachoAnsicht.design()).put("designs", new JSONArray(TachoAnsicht.DESIGNS)));
        JSONArray liste = new JSONArray();
        JSONObject f = freigaben();
        // Eigene Panoptes-Tacho-Ansicht zuerst; gleiche Freigabe-Regeln wie jede App.
        boolean ansichtFrei = !widerrufen.contains(TachoAnsicht.PAKET) && istFreigegeben(TachoAnsicht.PAKET, f);
        liste.put(new JSONObject().put("paket", TachoAnsicht.PAKET).put("name", TachoAnsicht.NAME)
                .put("installiert", true).put("panoptes", true)
                .put("freigegeben", ansichtFrei && startPlattform)
                .put("gespeichertFreigegeben", ansichtFrei)
                .put("navi", true).put("video", false)
                .put("fahrt", fahrtErlaubt(TachoAnsicht.PAKET, fahrt, fahrtHinweis)));
        JSONArray apps = at.greenchili.surfmate.launcher.AppLauncher.listLaunchableApps();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            String p = app.optString("package", "");
            if (TachoAnsicht.istAnsicht(p) || !istStartbar(p)) continue;
            boolean gespeichertFrei = !widerrufen.contains(p) && istFreigegeben(p, f);
            liste.put(new JSONObject().put("paket", p).put("name", app.optString("label", p))
                    .put("installiert", true)
                    .put("freigegeben", gespeichertFrei && startPlattform)
                    .put("gespeichertFreigegeben", gespeichertFrei)
                    .put("navi", istNavi(p)).put("video", istVideo(p)).put("audio", istAudio(p))
                    .put("fahrt", fahrtErlaubt(p, fahrt, fahrtHinweis)));
        }
        r.put("erlaubt", liste); // Altes Feld bleibt fuer bereits ausgelieferte Web-Dateien.
        r.put("apps", liste);
        return r;
    }

    public static synchronized JSONObject setze(boolean an, String paket) throws Exception {
        JSONObject r = new JSONObject();
        if (!an) {
            Map<String, Object> w = new HashMap<>();
            w.put("an", false);
            boolean ok = speichere(w);
            stoppeEigene();
            return r.put("ok", ok);
        }
        String p = paket == null ? "" : paket.trim();
        String sperre = einschaltSperre(p, andereApps(p), bewegungsGrund(),
                istStartbar(p), istFreigegeben(p), hinweisVerstanden());
        if (sperre != null) return r.put("ok", false).put("grund", sperre);
        Map<String, Object> w = new HashMap<>();
        w.put("an", true); w.put("paket", p);
        boolean gespeichert = speichere(w);
        boolean gestartet = gespeichert && at.greenchili.surfmate.launcher.ClusterCast.start(p);
        if (gespeichert && !gestartet) {
            Map<String, Object> rollback = new HashMap<>();
            rollback.put("an", false);
            speichere(rollback);
        }
        return r.put("ok", gespeichert && gestartet).put("gestartet", gestartet);
    }

    /** Zeitlich begrenzte, nicht freigegebene Ausnahme ausschliesslich fuer die Probe in P. */
    public static synchronized JSONObject probe(String paket) throws Exception {
        JSONObject r = new JSONObject();
        String p = paket == null ? "" : paket.trim();
        String sperre = probeSperre(p, andereApps(p), bewegungsGrund(), istStartbar(p),
                hinweisVerstanden());
        if (sperre != null) return r.put("ok", false).put("grund", sperre);
        probeStartPaket = p;
        boolean gestartet;
        try { gestartet = at.greenchili.surfmate.launcher.ClusterCast.start(p); }
        finally { probeStartPaket = null; }
        if (!gestartet) return r.put("ok", false).put("grund", "start_fehlgeschlagen");
        final long bis = System.currentTimeMillis() + PROBE_MS;
        probeBis = bis; probePaket = p;
        Thread t = new Thread(() -> {
            try { Thread.sleep(PROBE_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            synchronized (TachoNaviApp.class) { if (probeBis == bis) beendeProbe("zeit_um"); }
        }, "TachoAppProbe");
        t.setDaemon(true); t.start();
        return r.put("ok", true).put("sekunden", PROBE_MS / 1000).put("paket", p);
    }

    /** Bestaetigt die laufende Probe oder widerruft eine bestehende Paketfreigabe. */
    public static synchronized JSONObject bestaetige(String frei, String paket) throws Exception {
        JSONObject r = new JSONObject();
        if (!TachoFaehigkeit.JA.equals(frei) && !TachoFaehigkeit.NEIN.equals(frei))
            return r.put("ok", false).put("grund", "ja_oder_nein");
        String p = paket == null ? "" : paket.trim();
        if (p.isEmpty()) p = probePaket == null ? "" : probePaket;
        if (!gueltigesZiel(p)) return r.put("ok", false).put("grund", "ungueltiges_paket");
        if (TachoFaehigkeit.JA.equals(frei)) {
            // 0.1.37: keine Probe mehr noetig – Hinweis bestaetigt und Plattform angeboten genuegen.
            String sperre = freigabeSperre(p, andereApps(p), istStartbar(p), hinweisVerstanden());
            if (sperre != null) return r.put("ok", false).put("grund", sperre);
        }
        // getConfig()/optJSONObject liefert den gecachten Live-Baum. Bei JA deshalb nie
        // vor dem dauerhaften Commit darin mutieren: ein Schreibfehler darf keine
        // In-Memory-Freigabe hinterlassen. NEIN bleibt zusaetzlich ueber widerrufen
        // fail-closed, selbst wenn das Speichern scheitert.
        if (TachoFaehigkeit.NEIN.equals(frei)) widerrufen.add(p);
        // Die bestaetigte App wird zugleich zur gewaehlten App. Dadurch bewertet
        // TachoFaehigkeit beim allerersten Start genau diese Freigabe und nicht ein
        // altes/leeres Paket als "warnleuchten_ungeprueft".
        boolean ok = commitFreigabe(frei, p, freigaben(), paket(), TachoNaviApp::speichere);
        if (ok && TachoFaehigkeit.JA.equals(frei)) widerrufen.remove(p);
        if (TachoFaehigkeit.NEIN.equals(frei)) stoppePaket(p); else beendeProbe("bestaetigt");
        return r.put("ok", ok).put("paket", p)
                .put("freigegeben", ok && TachoFaehigkeit.JA.equals(frei));
    }

    public static synchronized JSONObject bestaetige(String frei) throws Exception {
        return bestaetige(frei, "");
    }

    public static boolean sollBeiAccStarten() {
        String p = paket();
        return istAn() && fahrtSperre(p, null) == null;
    }

    /**
     * Verlassen von P beendet jede Probe, jede nicht freigegebene aktive App und (0.1.36.1) jede
     * App, die nicht als Navigation gilt (Video, Spiele, unbekannt) – sofort, zurueck zum Tacho.
     */
    public static void beiGangwechsel(int gang) {
        if (gang == at.greenchili.surfmate.monitor.GearMonitor.GEAR_P) return;
        try {
            synchronized (TachoNaviApp.class) { if (probeBis > 0) beendeProbe("gang_" + gang); }
            if (!at.greenchili.surfmate.launcher.ClusterCast.isActive()) return;
            String p = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (mussBeiGangStoppen(gang, p, p != null && istFreigegeben(p), p != null && fahrtErlaubt(p))) {
                logger.info("Gang " + gang + ": " + p + (fahrtErlaubt(p) ? " nicht freigegeben" : " nicht fuer die Fahrt freigegeben, nur in P")
                        + " - Tacho zurueck");
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            }
        } catch (Throwable t) { logger.warn("beiGangwechsel: " + t.getMessage()); }
    }

    private static void beendeProbe(String grund) {
        String p = probePaket;
        probeBis = 0; probePaket = null; probeStartPaket = null;
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            boolean dauerhaft = istAn() && p != null && p.equals(paket()) && istFreigegeben(p);
            if (p != null && p.equals(aktiv) && !dauerhaft)
                at.greenchili.surfmate.launcher.ClusterCast.stop();
            logger.info("Fahrerdisplay-Probe beendet (" + grund + ")");
        } catch (Throwable t) { logger.warn("Probe beenden: " + t.getMessage()); }
    }

    private static void stoppePaket(String p) {
        try {
            String aktiv = at.greenchili.surfmate.launcher.ClusterCast.getCastPackage();
            if (p != null && p.equals(aktiv)) at.greenchili.surfmate.launcher.ClusterCast.stop();
        } catch (Throwable t) { logger.warn("stoppen: " + t.getMessage()); }
        if (p != null && p.equals(probePaket)) {
            probeBis = 0; probePaket = null; probeStartPaket = null;
        }
    }

    private static void stoppeEigene() { stoppePaket(paket()); }
}
