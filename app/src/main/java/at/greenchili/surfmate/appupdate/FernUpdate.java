package at.greenchili.surfmate.appupdate;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.26.1 Update von außen (Handy-App, EV-Cockpit, Web-Sitzung der Handy-App), HANDY_API_VERTRAG §8.
 *
 * <p>Es gibt keinen eigenen Installationsweg: gestartet wird nur {@link AppUpdateDienst#installieren} mit genau der
 * Version, die der Updater selbst als freigegeben kennt (Quelle, sha256, Signatur und Lineage wie immer, danach
 * Selbstbestätigung oder Rückfall). Keine APK und keine Adresse vom Gerät. Zusätzlich zu den Regeln des Updaters
 * (nie in Fahrt; bei Zündung an seit 0.1.38.5 nur sicher in P, {@link UpdateRegel#nichtWeilGetippt}): Gang P bzw. Auto
 * aus laut Fahrsperre und eine ausdrückliche Bestätigung
 * ({@code bestaetigt:true}). Jeder Start meldet Telegram mit dem Gerätenamen.
 *
 * <p>Fortschritt: {@code zustand} = {@code laeuft} | {@code fertig} | {@code fehler} (oder null). Weil der Daemon
 * beim Update neu startet, merkt sich die Marke {@code .appupdate-fern} das Ziel.
 */
public final class FernUpdate {

    /** Was diese Klasse vom Auto braucht (im Test ersetzt). */
    public interface Umgebung {
        /** Status wie {@code GET /api/appupdate/status} oder null (Updater nicht bereit). */
        JSONObject status(String sprache);
        /** {@link AppUpdateDienst#installieren}: null = gestartet, sonst Grund. */
        String installieren(int versionCode);
        /** 0.1.32.1: wie oben; {@code mobil} = Einwilligung für diesen einen Download ohne WLAN. */
        default String installieren(int versionCode, boolean mobil) { return installieren(versionCode); }
        /** Fahrsperre: null = steht in P bzw. Auto aus; sonst Grund (DrivingSafetyGuard). */
        String fahrsperre();
        void telegram(String version, String geraet);
        File basis();
        long jetzt();
        /** 0.1.38.2: Stand wie {@code GET /api/appupdate/launcher} oder null (Updater nicht bereit). */
        default JSONObject launcherStatus() { return null; }
        /** 0.1.38.2: {@link AppUpdateDienst#launcherInstallieren}: null = gestartet, sonst Grund. */
        default String launcherInstallieren(int versionCode, boolean mobil) { return "nicht_bereit"; }
        /** 0.1.38.2: Launcher-Update gestartet (Telegram mit Gerätenamen). */
        default void telegramLauncher(String version, String geraet) { }
    }

    /** Ohne Phase und ohne neue Version so lange noch „läuft“ (Start des Hintergrund-Ablaufs). */
    static final long ANLAUF_MS = 90_000L;
    /** So lange wird „fertig“ bzw. „fehler“ gemeldet, dann vergessen. */
    static final long MELDEN_MS = 24 * 3_600_000L;
    static final String MARKE = ".appupdate-fern";

    private final Umgebung env;

    public FernUpdate(Umgebung env) {
        this.env = env;
    }

    private static volatile FernUpdate instanz;

    public static FernUpdate get() {
        FernUpdate f = instanz;
        if (f == null) {
            synchronized (FernUpdate.class) {
                if (instanz == null) instanz = new FernUpdate(new Echt());
                f = instanz;
            }
        }
        return f;
    }

    /** {@code GET /api/handy/v1/update}: Stand in einfachen Worten; {@code darf} = Recht „update“. */
    public JSONObject stand(String sprache, boolean darf) throws Exception {
        JSONObject r = new JSONObject().put("ok", true).put("darf", darf);
        JSONObject s = env.status(sprache);
        if (s == null) {
            return r.put("installiert", JSONObject.NULL).put("neueste", JSONObject.NULL).put("moeglich", false)
                    .put("grund", "nicht_bereit").put("zustand", JSONObject.NULL)
                    .put("text", text(sprache, "nicht_bereit", null, null));
        }
        JSONObject akt = s.optJSONObject("aktuell");
        String installiert = akt == null ? "" : akt.optString("version", "");
        int installiertVc = akt == null ? 0 : akt.optInt("versionCode", 0);
        r.put("installiert", installiert).put("installiertVc", installiertVc);
        JSONObject v = s.optJSONObject("verfuegbar");
        if (v != null) {
            JSONObject n = new JSONObject().put("version", v.optString("version"))
                    .put("versionCode", v.optInt("versionCode"))
                    .put("groesseBytes", v.opt("groesseBytes") == null ? JSONObject.NULL : v.opt("groesseBytes"));
            if (v.has("notizen")) n.put("notizen", v.get("notizen"));
            // 0.1.38.5: Art ("fix" | "funktion" | "beides"), ihr Text und die Kurzfassung (≤ 3 Stichpunkte)
            for (String k : new String[]{"art", "artText", "kurz"}) if (v.has(k)) n.put(k, v.get(k));
            r.put("neueste", n);
        } else {
            r.put("neueste", JSONObject.NULL);
        }
        String zustand = zustand(s, installiertVc);
        r.put("zustand", zustand == null ? JSONObject.NULL : zustand);
        boolean laeuft = "laeuft".equals(zustand);
        String grund = laeuft ? null : hindernis(s, darf);   // nach „fehler“ darf man es noch einmal versuchen
        r.put("moeglich", !laeuft && grund == null);
        r.put("grund", grund == null ? JSONObject.NULL : grund);
        // 0.1.38.2: ohne WLAN geht es nach einer Rückfrage mit Größe ({@code mobil:true} beim Starten)
        r.put("mobilFrage", UpdateRegel.WARTET_WLAN.equals(grund) && s.optBoolean("mobilFrage", false));
        String ziel = zielVersion();
        String fuerText = zustand != null ? zustand : grund != null ? grund : "bereit";
        String t = text(sprache, fuerText, v != null ? v.optString("version") : null,
                "fertig".equals(zustand) && ziel != null ? ziel : installiert);
        if (v != null && zustand == null) t = mitWasNeu(t, v);   // Handy, Web, Cockpit sehen es ohne extra Tipp
        r.put("text", t);
        return r;
    }

    /** 0.1.38.5: Satz plus Zeile Art plus Kurzfassung aus dem Status des Updaters (schon in der Sprache). */
    static String mitWasNeu(String satz, JSONObject v) {
        String a = v.optString("artText", "").trim();
        String k = v.optString("kurz", "").trim();
        StringBuilder b = new StringBuilder(satz);
        if (!a.isEmpty()) b.append("\n").append(a);
        if (!k.isEmpty()) b.append("\n").append(k);
        return b.toString();
    }

    /**
     * {@code update_starten {version, bestaetigt:true}}: null-sicher; Antwort {@code {ok:true, zustand:"laeuft",
     * version}} oder {@code {ok:false, grund, text}}. {@code darf} prüft der Aufrufer (Recht „update“).
     */
    public JSONObject starten(String version, boolean bestaetigt, String geraet, String sprache) throws Exception {
        return starten(version, bestaetigt, false, geraet, sprache);
    }

    /**
     * 0.1.32.1: {@code mobil} = Einwilligung für diesen einen Download ohne WLAN. Fehlt sie und das Auto darf fragen,
     * kommt {@code {ok:false, grund:"wartet_wlan", mobilFrage:true, groesseBytes, text}}; das Gerät fragt und ruft neu.
     */
    public JSONObject starten(String version, boolean bestaetigt, boolean mobil, String geraet, String sprache)
            throws Exception {
        if (!bestaetigt) return fehler("bestaetigung_fehlt", sprache, null);
        JSONObject s = env.status(sprache);
        if (s == null) return fehler("nicht_bereit", sprache, null);
        JSONObject akt = s.optJSONObject("aktuell");
        int installiertVc = akt == null ? 0 : akt.optInt("versionCode", 0);
        if ("laeuft".equals(zustand(s, installiertVc))) return fehler("laeuft_schon", sprache, null);
        JSONObject v = s.optJSONObject("verfuegbar");
        if (v == null) return fehler("kein_update", sprache, null);
        // nur genau die Version, die der Updater freigegeben gefunden hat – nie eine andere vom Gerät
        if (version == null || !version.equals(v.optString("version"))) {
            return fehler("andere_version", sprache, v.optString("version"));
        }
        String grund = hindernis(s, true);
        boolean frage = UpdateRegel.WARTET_WLAN.equals(grund) && s.optBoolean("mobilFrage", false);
        if (frage && !mobil) {
            long g = v.optLong("groesseBytes", 0);
            return new JSONObject().put("ok", false).put("grund", grund).put("mobilFrage", true)
                    .put("groesseBytes", g > 0 ? g : JSONObject.NULL)
                    .put("text", text(sprache, "mobil_frage", UpdateRegel.groesseText(
                            g > 0 ? g : UpdateHinweis.MOBIL_GROESSE_RUECKFALL), null));
        }
        if (grund != null && !frage) return fehler(grund, sprache, v.optString("version"));
        int vc = v.optInt("versionCode");
        String g = frage ? env.installieren(vc, true) : env.installieren(vc);
        if (g != null) return fehler(g, sprache, v.optString("version"));
        merken(vc, version, geraet);
        try {
            env.telegram(version, geraet == null || geraet.isEmpty() ? "?" : geraet);
        } catch (Throwable ignored) { }
        return new JSONObject().put("ok", true).put("zustand", "laeuft").put("version", version)
                .put("text", text(sprache, "laeuft", version, null));
    }

    // ------------------------------------------------------------------ 0.1.38.2 Launcher von außen

    /**
     * Stand des Panoptes Launchers in einfachen Worten, gleiche Felder wie {@link #stand}. Nur Updates: ist der Launcher
     * nicht installiert, gibt es von außen nichts zu tun (Einrichten nur am Auto). Kein Vormerken: der Launcher braucht
     * nicht „Auto aus“, nur „nicht während der Fahrt“ ({@link LauncherRegel}).
     */
    public JSONObject launcherStand(String sprache, boolean darf) throws Exception {
        JSONObject r = new JSONObject().put("ok", true).put("darf", darf);
        JSONObject s = env.launcherStatus();
        if (s == null) {
            return r.put("installiert", JSONObject.NULL).put("neueste", JSONObject.NULL).put("moeglich", false)
                    .put("grund", "nicht_bereit").put("zustand", JSONObject.NULL).put("mobilFrage", false)
                    .put("text", text(sprache, "nicht_bereit", null, null));
        }
        boolean installiert = !s.isNull("installiert") && s.has("installiert");
        r.put("installiertVc", installiert ? s.optInt("installiert") : JSONObject.NULL);
        JSONObject v = s.optJSONObject("verfuegbar");
        boolean neuer = installiert && v != null && s.optBoolean("neuer", false);
        r.put("neueste", neuer ? new JSONObject().put("version", v.optString("version"))
                .put("versionCode", v.optInt("versionCode"))
                .put("groesseBytes", v.opt("groesseBytes") == null ? JSONObject.NULL : v.opt("groesseBytes"))
                : JSONObject.NULL);
        String zustand = !s.isNull("phase") && s.has("phase") ? "laeuft"
                : !s.isNull("fehler") && s.has("fehler") && neuer ? "fehler" : null;
        r.put("zustand", zustand == null ? JSONObject.NULL : zustand);
        String grund = "laeuft".equals(zustand) ? null : launcherHindernis(s, installiert, neuer, darf);
        boolean frage = LauncherRegel.WARTET_WLAN.equals(grund);
        r.put("moeglich", !"laeuft".equals(zustand) && (grund == null || frage));
        r.put("grund", grund == null ? JSONObject.NULL : grund);
        r.put("mobilFrage", frage);
        String fall = zustand != null ? "launcher_" + zustand : grund == null || frage ? "launcher_bereit"
                : launcherFall(grund);
        r.put("text", text(sprache, fall, neuer ? v.optString("version") : null, null));
        return r;
    }

    /**
     * Launcher-Update starten: nur genau die freigegebene Version, nur nach Bestätigung, nicht während der Fahrt; ohne
     * WLAN erst nach der Rückfrage mit Größe ({@code mobil:true}). Antwort wie {@link #starten}.
     */
    public JSONObject launcherStarten(String version, boolean bestaetigt, boolean mobil, String geraet, String sprache)
            throws Exception {
        if (!bestaetigt) return fehler("bestaetigung_fehlt", sprache, null);
        JSONObject s = env.launcherStatus();
        if (s == null) return fehler("nicht_bereit", sprache, null);
        boolean installiert = !s.isNull("installiert") && s.has("installiert");
        JSONObject v = s.optJSONObject("verfuegbar");
        boolean neuer = installiert && v != null && s.optBoolean("neuer", false);
        if (!s.isNull("phase") && s.has("phase")) return fehler("launcher_laeuft_schon", sprache, null);
        String grund = launcherHindernis(s, installiert, neuer, true);
        if (grund != null && !LauncherRegel.WARTET_WLAN.equals(grund)) {
            return fehlerText(grund, launcherFall(grund), sprache, null);
        }
        if (version == null || !version.equals(v.optString("version"))) {
            return fehler("andere_version", sprache, v.optString("version"));
        }
        if (LauncherRegel.WARTET_WLAN.equals(grund) && !mobil) {
            long g = v.optLong("groesseBytes", 0);
            return new JSONObject().put("ok", false).put("grund", grund).put("mobilFrage", true)
                    .put("groesseBytes", g > 0 ? g : JSONObject.NULL)
                    .put("text", text(sprache, "launcher_mobil_frage", UpdateRegel.groesseText(
                            g > 0 ? g : LAUNCHER_GROESSE_RUECKFALL), null));
        }
        String g = env.launcherInstallieren(v.optInt("versionCode"), mobil);
        if (g != null) {
            String gr = LauncherRegel.LAEUFT_SCHON.equals(g) ? "launcher_laeuft_schon" : g;
            return fehlerText(gr, launcherFall(gr), sprache, version);
        }
        try {
            env.telegramLauncher(version, geraet == null || geraet.isEmpty() ? "?" : geraet);
        } catch (Throwable ignored) { }
        return new JSONObject().put("ok", true).put("zustand", "laeuft").put("version", version)
                .put("text", text(sprache, "launcher_laeuft", version, null));
    }

    /** Text-Fall zu einem Launcher-Grund: Fahrt und Gang klingen anders als beim Panoptes-Update (kein „aus“ nötig). */
    static String launcherFall(String grund) {
        if (LauncherRegel.FAEHRT.equals(grund) || LauncherRegel.NICHT_IN_P.equals(grund)) return "launcher_faehrt";
        if ("kein_update".equals(grund)) return "launcher_kein_update";
        return grund;
    }

    /** Ohne bekannte Größe nennt die Rückfrage diese Größe (der Launcher ist klein, aufgerundet). */
    static final long LAUNCHER_GROESSE_RUECKFALL = 20L * 1024 * 1024;

    /** Warum der Launcher gerade nicht (oder null); {@code wartet_wlan} heißt: nach Rückfrage möglich. */
    static String launcherHindernis(JSONObject s, boolean installiert, boolean neuer, boolean darf) {
        if (!installiert) return "nicht_installiert";
        if (!neuer) return "kein_update";
        if (!darf) return "nicht_erlaubt";
        String n = s.isNull("nichtWeil") ? "" : s.optString("nichtWeil", "");
        if (LauncherRegel.LAEUFT_SCHON.equals(n)) return "launcher_laeuft_schon";
        if (LauncherRegel.KEIN_LAUNCHER.equals(n)) return "kein_update";
        return n.isEmpty() ? null : n;
    }

    /** Warum gerade nicht (oder null): Recht, Fahrsperre, dann die Regeln des Updaters. */
    String hindernis(JSONObject s, boolean darf) {
        if (s.optJSONObject("verfuegbar") == null) return "kein_update";
        if (!darf) return "nicht_erlaubt";
        String sperre;
        try {
            sperre = env.fahrsperre();
        } catch (Throwable t) {
            sperre = "state_unknown";
        }
        if (sperre != null) {
            if ("moving".equals(sperre) || "speed_unknown".equals(sperre)) return "faehrt";
            if ("not_park".equals(sperre) || "gear_unknown".equals(sperre)) return "nicht_in_p";
            return "zustand_unbekannt";
        }
        if (s.optBoolean("installierbar", false)) return null;
        String n = s.optString("nichtWeil", "");
        return n.isEmpty() ? "zustand_unbekannt" : n;
    }

    /** laeuft | fertig | fehler | null, aus Phase des Updaters und der Marke. */
    String zustand(JSONObject s, int installiertVc) {
        String phase = s.isNull("phase") ? "" : s.optString("phase", "");
        boolean laeuft = "prueft".equals(phase) || "laedt".equals(phase) || "installiert".equals(phase)
                || "beobachtet".equals(phase);
        long[] m = marke();
        if (m == null) return laeuft ? "laeuft" : null;
        long alter = env.jetzt() - m[1];
        if (installiertVc >= m[0]) {
            if (laeuft && "beobachtet".equals(phase)) return "laeuft";   // neue Version, Selbstprüfung läuft noch
            return alter < MELDEN_MS ? "fertig" : vergessen();
        }
        if (laeuft) return "laeuft";
        if (alter >= MELDEN_MS) return vergessen();
        if ("rueckfall".equals(phase) || "fehler".equals(s.optString("ergebnis"))) return "fehler";
        return alter < ANLAUF_MS ? "laeuft" : "fehler";
    }

    private String vergessen() {
        new File(env.basis(), MARKE).delete();
        return null;
    }

    private void merken(int vc, String version, String geraet) {
        try {
            Files.write(new File(env.basis(), MARKE).toPath(), (vc + " " + env.jetzt() + " " + version + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** {versionCode, zeit} oder null. */
    private long[] marke() {
        File f = new File(env.basis(), MARKE);
        if (!f.isFile()) return null;
        try {
            String[] t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            return new long[]{Long.parseLong(t[0]), Long.parseLong(t[1])};
        } catch (Exception e) {
            return null;
        }
    }

    private String zielVersion() {
        File f = new File(env.basis(), MARKE);
        try {
            String[] t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            return t.length >= 3 ? t[2] : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject fehler(String grund, String sprache, String version) throws Exception {
        return fehlerText(grund, grund, sprache, version);
    }

    private static JSONObject fehlerText(String grund, String fall, String sprache, String version) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund).put("text", text(sprache, fall, version, null));
    }

    /** Ein einfacher Satz je Lage (server-i18n {@code update_fern.*}), Fallback Deutsch. */
    public static String text(String sprache, String fall, String neu, String installiert) {
        String key = "update_fern." + fall;
        String fb = DEUTSCH.get(fall);
        if (fb == null) {
            key = "update_fern.nicht_moeglich";
            fb = DEUTSCH.get("nicht_moeglich");
        }
        final String k = key, f = fb;
        Object[] a = {neu == null ? "" : neu, installiert == null ? "" : installiert};
        try {
            return at.greenchili.surfmate.server.Messages.withLocale(sprache,
                    () -> at.greenchili.surfmate.server.Messages.text(k, f, a));
        } catch (Throwable t) {
            return f.replace("{0}", String.valueOf(a[0])).replace("{1}", String.valueOf(a[1]));
        }
    }

    static final java.util.Map<String, String> DEUTSCH = new java.util.HashMap<>();
    static {
        DEUTSCH.put("bereit", "Neue Version {0} ist da. Das Update dauert etwa 3–8 Minuten. Panoptes startet dabei kurz neu, das Display kann flackern. Nur im Stand.");
        DEUTSCH.put("kein_update", "Panoptes ist auf dem neuesten Stand ({1}).");
        DEUTSCH.put("laeuft", "Panoptes wird aktualisiert und kommt gleich wieder. Das dauert etwa 3–8 Minuten.");
        DEUTSCH.put("laeuft_schon", "Panoptes wird gerade schon aktualisiert.");
        DEUTSCH.put("fertig", "Fertig. Panoptes läuft jetzt mit {1}.");
        DEUTSCH.put("fehler", "Das Update hat nicht geklappt. Panoptes läuft weiter mit der bisherigen Version.");
        DEUTSCH.put("nicht_erlaubt", "Dieses Gerät darf keine Updates starten. Einschalten am Auto unter „Gekoppelte Geräte“.");
        DEUTSCH.put("faehrt", "Während der Fahrt geht kein Update. Bitte anhalten und in P stellen.");
        DEUTSCH.put("nicht_in_p", "Update geht nur, wenn das Auto in P steht. Bitte in P stellen und noch einmal versuchen.");
        DEUTSCH.put("zuendung_an", "Panoptes weiß gerade nicht sicher, ob das Auto in P steht. Bitte in P stellen und noch einmal versuchen.");
        DEUTSCH.put("waechter_aktiv", "Der Wächter nimmt gerade etwas auf. Bitte warten, bis er fertig ist, und noch einmal versuchen.");
        DEUTSCH.put("zustand_unbekannt", "Panoptes weiß gerade nicht sicher, ob das Auto steht. Bitte gleich noch einmal versuchen.");
        DEUTSCH.put("wartet_wlan", "Updates sind auf „nur WLAN“ eingestellt, und das Auto hat gerade kein WLAN.");
        DEUTSCH.put("mobil_frage", "Das Auto hat gerade kein WLAN. Das Update braucht etwa {0} mobile Daten. Nur für diese Version laden?");
        DEUTSCH.put("zu_alt", "Für dieses Update braucht Panoptes zuerst eine neuere Version. Bitte am Auto aktualisieren.");
        DEUTSCH.put("andere_version", "Diese Version ist nicht freigegeben. Freigegeben ist {0}.");
        DEUTSCH.put("bestaetigung_fehlt", "Bitte das Update zuerst bestätigen.");
        DEUTSCH.put("nicht_bereit", "Panoptes startet gerade. Bitte in einer Minute noch einmal versuchen.");
        DEUTSCH.put("nicht_moeglich", "Das Update geht gerade nicht. Bitte später noch einmal versuchen.");
        // 0.1.38.2 Launcher
        DEUTSCH.put("launcher_bereit", "Neuer Launcher {0} ist da. Das Update dauert etwa eine Minute.");
        DEUTSCH.put("launcher_kein_update", "Der Launcher ist auf dem neuesten Stand.");
        DEUTSCH.put("launcher_laeuft", "Der Launcher wird aktualisiert. Das dauert etwa eine Minute.");
        DEUTSCH.put("launcher_laeuft_schon", "Der Launcher wird gerade schon aktualisiert.");
        DEUTSCH.put("launcher_fehler", "Das Launcher-Update hat nicht geklappt. Der bisherige Launcher bleibt. Bitte später noch einmal versuchen.");
        DEUTSCH.put("launcher_mobil_frage", "Das Auto hat gerade kein WLAN. Der Launcher braucht etwa {0} mobile Daten. Nur für diese Version laden?");
        DEUTSCH.put("launcher_faehrt", "Während der Fahrt geht das nicht. Bitte in P stellen und noch einmal tippen.");
        DEUTSCH.put("nicht_installiert", "Der Launcher ist auf diesem Auto nicht eingerichtet. Das geht nur am Auto.");
    }

    // ------------------------------------------------------------------ echte Umgebung

    static final class Echt implements Umgebung {
        public JSONObject status(String sprache) {
            AppUpdateDienst d = AppUpdateDienst.get();
            return d == null ? null : d.status(sprache);
        }

        public String installieren(int versionCode) {
            AppUpdateDienst d = AppUpdateDienst.get();
            return d == null ? "nicht_bereit" : d.installieren(versionCode);
        }

        public String installieren(int versionCode, boolean mobil) {
            AppUpdateDienst d = AppUpdateDienst.get();
            return d == null ? "nicht_bereit" : d.installieren(versionCode, mobil);
        }

        public String fahrsperre() {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        }

        public void telegram(String version, String geraet) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get("handy.update_gestartet", version, geraet),
                        "CRITICAL");
            } catch (Throwable ignored) { }
        }

        public JSONObject launcherStatus() {
            AppUpdateDienst d = AppUpdateDienst.get();
            return d == null ? null : d.launcherStatus();
        }

        public String launcherInstallieren(int versionCode, boolean mobil) {
            AppUpdateDienst d = AppUpdateDienst.get();
            return d == null ? "nicht_bereit" : d.launcherInstallieren(versionCode, mobil);
        }

        public void telegramLauncher(String version, String geraet) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                        at.greenchili.surfmate.telegram.TelegramMessages.get("handy.launcher_update_gestartet", version, geraet),
                        "CRITICAL");
            } catch (Throwable ignored) { }
        }

        public File basis() {
            return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(AppUpdateDienst.NEU));
        }

        public long jetzt() {
            return System.currentTimeMillis();
        }
    }
}
