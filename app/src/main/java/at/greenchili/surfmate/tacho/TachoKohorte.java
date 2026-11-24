package at.greenchili.surfmate.tacho;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Kohorten-Gate für alle Befehle, die den Tacho verändern (0.1.43.20, P3), plus Größen-Sperre (P2).
 *
 * <p>Herkunft: Idee aus DashCast 1.9.8x (MIT-Lizenz, „exact cohort gate“: Hash von libBydCluster.so und fissiond,
 * Firmware, Panel 1920×720). Code hier eigen geschrieben, Hashes NICHT übernommen.
 *
 * <ul>
 *   <li>Öffnende AutoContainer-Befehle (16, 35, Größe 29/30/31) gehen nur, wenn der genaue Hardware-Schlüssel dieses
 *       Autos in {@link TachoKohortenListe} steht oder ein Tester sein Auto ausdrücklich freigeschaltet hat.</li>
 *   <li>Vor der ersten Bestätigung „Warnleuchten und Tempo sichtbar“ ({@link TachoSicht}) nur im Stand. Danach fragt
 *       Panoptes; „nein“ oder keine Antwort sperrt diesen Schlüssel.</li>
 *   <li>18 und 0 (Projektion schließen, Instrumente zurück) gehen IMMER. 2/3 (Warnleuchten) nie.</li>
 * </ul>
 * Alles wird nur gelesen (getprop, Dateien hashen, dumpsys display). Nichts an Server.
 */
public final class TachoKohorte {

    public static final String FREI = "frei", PROBE = "probe", GESPERRT = "gesperrt";

    static final String LIB_CLUSTER = "/system/lib64/libBydCluster.so";
    static final String[] FISSIOND = {"/system/bin/fissiond", "/vendor/bin/fissiond"};

    private TachoKohorte() {}

    /** Genau ein Auto-Typ: Modell, Tacho-Firmware, Head-Unit-Build, API, zwei Datei-Hashes, Panel. */
    public static final class Schluessel {
        public String modell = "", firmware = "", build = "", libBydCluster = "", fissiond = "", panel = "";
        public int api;

        /** Kurze Kennung ohne Panel (Panel ist vor der ersten Projektion oft noch nicht messbar). */
        public String id() {
            return sha256Hex(("v1|" + modell + "|" + firmware + "|" + build + "|" + api + "|"
                    + libBydCluster + "|" + fissiond).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        }

        public JSONObject alsJson() throws Exception {
            return new JSONObject().put("id", id()).put("modell", modell).put("firmware", firmware)
                    .put("build", build).put("api", api).put("libBydCluster", libBydCluster)
                    .put("fissiond", fissiond).put("panel", panel);
        }
    }

    /** Ergebnis des Gates: status frei|probe|gesperrt, code, grund (ein verständlicher Satz). */
    public static final class Urteil {
        public final String status, code, grund;

        Urteil(String status, String code, String grund) {
            this.status = status;
            this.code = code;
            this.grund = grund;
        }

        public boolean oeffnenErlaubt() { return FREI.equals(status) || PROBE.equals(status); }

        public JSONObject alsJson() throws Exception {
            return new JSONObject().put("status", status).put("code", code).put("grund", grund);
        }
    }

    // ------------------------------------------------------------------ reine Auswertung

    static boolean inListe(Schluessel s, JSONObject liste) {
        if (s == null || liste == null) return false;
        JSONArray e = liste.optJSONArray("eintraege");
        if (e == null) return false;
        for (int i = 0; i < e.length(); i++) {
            JSONObject x = e.optJSONObject(i);
            if (x == null) continue;
            if (!genau(x, "modell", s.modell) || !genau(x, "firmware", s.firmware)
                    || !genau(x, "build", s.build) || !genau(x, "libBydCluster", s.libBydCluster)
                    || !genau(x, "fissiond", s.fissiond)) continue;
            if (x.optInt("api", -1) != s.api || s.api <= 0) continue;
            String panel = x.optString("panel", "");
            if (panel.isEmpty()) continue;   // ohne Panel-Angabe kein gültiger Eintrag
            if (!s.panel.isEmpty() && !panel.equals(s.panel)) continue;
            return true;
        }
        return false;
    }

    private static boolean genau(JSONObject x, String feld, String wert) {
        String v = x.optString(feld, "");
        return !v.isEmpty() && v.equals(wert == null ? "" : wert);
    }

    /**
     * @param antwort gespeicherte Antwort auf „Warnleuchten und Tempo sichtbar?“ für genau diesen Schlüssel:
     *                {@link TachoSicht#JA}, {@link TachoSicht#NEIN}, {@link TachoSicht#UNBEKANNT} oder "" (nie gefragt)
     * @param tester  Tester hat die Projektion für dieses Auto ausdrücklich freigeschaltet
     * @param imStand Auto steht sicher (P), sonst null-Grund aus DrivingSafetyGuard
     */
    static Urteil urteil(Schluessel s, JSONObject liste, String antwort, boolean tester, boolean imStand) {
        String a = antwort == null ? "" : antwort;
        if (TachoSicht.NEIN.equals(a)) {
            return new Urteil(GESPERRT, "sicht_nein",
                    "Du hast gemeldet, dass Warnleuchten oder Tempo verdeckt waren. Deshalb bleibt der Tacho aus.");
        }
        if (TachoSicht.UNBEKANNT.equals(a)) {
            return new Urteil(GESPERRT, "sicht_unbekannt",
                    "Die Frage „Warnleuchten und Tempo sichtbar?“ blieb ohne Antwort. Deshalb bleibt der Tacho aus. "
                            + "Du kannst die Prüfung im Stand neu starten.");
        }
        boolean bekannt = inListe(s, liste) || tester;
        if (!bekannt) {
            return new Urteil(GESPERRT, "hardware_unbekannt",
                    "Dieses Auto ist für Bilder am Tacho noch nicht geprüft. Damit Warnleuchten und Tempo sicher "
                            + "sichtbar bleiben, bleibt die Funktion aus. Schick uns gern den Tacho-Test.");
        }
        if (TachoSicht.JA.equals(a)) {
            return new Urteil(FREI, "bestaetigt", "Geprüft: Warnleuchten und Tempo bleiben sichtbar.");
        }
        if (!imStand) {
            return new Urteil(GESPERRT, "erst_im_stand_pruefen",
                    "Beim ersten Mal nur im Stand: Panoptes fragt dann, ob Warnleuchten und Tempo sichtbar sind.");
        }
        return new Urteil(PROBE, "erste_pruefung",
                "Erstes Mal: Panoptes fragt gleich, ob Warnleuchten und Tempo sichtbar sind.");
    }

    /** 18 = Projektion schließen, 0 = Instrumente zurück: dürfen IMMER. */
    public static boolean istRueckweg(int op) {
        return op == 18 || op == 0;
    }

    /** Öffnende/verändernde Befehle, die das Gate brauchen. */
    public static boolean istOeffnend(int op) {
        return op == 16 || op == 35 || op == 29 || op == 30 || op == 31;
    }

    // ------------------------------------------------------------------ Größen-Sperre (P2)

    /** Modell, wie es das Gate exakt erkennt. Grobe Namensteile (dmi, song, han …) zählen nicht mehr. */
    enum Modell { SEAL_EU, ATTO3, ANDERES }

    static Modell modell(String roProductModel) {
        String n = roProductModel == null ? "" : roProductModel.trim().toLowerCase(Locale.US)
                .replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
        if (n.startsWith("byd ")) n = n.substring(4);
        if (n.equals("seal")) return Modell.SEAL_EU;
        if (n.equals("atto 3") || n.equals("atto3") || n.equals("yuan plus")) return Modell.ATTO3;
        return Modell.ANDERES;
    }

    /**
     * Vorschlag fürs Größenprofil (nur Anzeige, nie automatisch gesendet): 0 = keiner. 31 nur bei exakt erkanntem
     * Seal EU, 29 nur bei exakt erkanntem Atto 3, und nur, wenn ein schon gemessenes Panel nicht widerspricht.
     * 30 wird nie vorgeschlagen; für den Seal EU gibt es ihn als ADAS-Alternative ({@link #adasAlternative}).
     */
    public static int vorschlag(String roProductModel, String panel) {
        Modell m = modell(roProductModel);
        int v = m == Modell.SEAL_EU ? 31 : m == Modell.ATTO3 ? 29 : 0;
        return v != 0 && sperrGrund(v, roProductModel, panel) == null ? v : 0;
    }

    /** DashCast-README: am Seal EU behebt 30 die gestreckte ADAS-Anzeige. Nur dort, sonst 0. */
    public static int adasAlternative(String roProductModel, String panel) {
        return modell(roProductModel) == Modell.SEAL_EU && sperrGrund(30, roProductModel, panel) == null ? 30 : 0;
    }

    /**
     * Passt das Profil zu Modell und Display? null = ja. Gesperrt wird bei belegtem Widerspruch:
     * 8,8″-Panel (1280×480) oder Atto 3 → kein 30/31; 1920×720-Panel oder Seal EU → kein 29.
     */
    static String sperrGrund(int profil, String roProductModel, String panel) {
        if (profil == 0) return null;
        if (profil != 29 && profil != 30 && profil != 31) return "profil_ungueltig";
        Modell m = modell(roProductModel);
        String p = panel == null ? "" : panel.trim();
        boolean klein = "1280x480".equals(p);
        boolean gross = "1920x720".equals(p);
        if (profil == 29) {
            if (gross) return "panel_1920x720";
            if (m == Modell.SEAL_EU) return "modell_seal";
        } else {
            if (klein) return "panel_1280x480";
            if (m == Modell.ATTO3) return "modell_atto3";
        }
        return null;
    }

    // ------------------------------------------------------------------ live (nur lesen)

    /** Testbare Quellen. */
    interface Quelle {
        Schluessel schluessel();
        String antwort(String id);
        boolean tester(String id);
        boolean imStand();
    }

    static volatile Quelle quelle = new LiveQuelle();
    private static volatile Schluessel cache;
    private static volatile long cacheBis;

    /** Aktueller Schlüssel (10 min gemerkt, Hash von 30 MB nicht bei jedem Befehl). */
    public static Schluessel schluessel() {
        long now = System.currentTimeMillis();
        Schluessel s = cache;
        if (s != null && now < cacheBis) return s;
        s = quelle.schluessel();
        cache = s;
        cacheBis = now + 10 * 60_000L;
        // Nur lokal ins Log, damit Tester die Kennwerte melden können (nichts wird gesendet).
        try { log("Tacho-Hardware-Schlüssel: " + s.alsJson()); } catch (Throwable ignored) { }
        return s;
    }

    static void vergiss() { cache = null; cacheBis = 0; zulassungBis = 0; }

    /** Nach einer Antwort oder Freigabe: das gemerkte Zulassungs-Urteil sofort verwerfen. */
    public static void urteilNeu() { zulassungBis = 0; }

    /** Steht dieses Auto in der ausgelieferten Erlaubt-Liste? */
    public static boolean inListe() {
        try {
            return inListe(schluessel(), new JSONObject(TachoKohortenListe.JSON));
        } catch (Throwable t) { return false; }
    }

    /**
     * Tester-Knopf zeigen? Nur wenn das Auto überhaupt eine Tacho-Projektion anbietet (AutoContainer oder
     * DiLink-5-Fläche, kein Single-OS, Shell-Rechte) und NICHT in der Liste steht.
     */
    static boolean testerAngebot(JSONObject plattform, boolean inListe) {
        if (plattform == null || inListe) return false;
        if (plattform.optBoolean("singleOs") && !plattform.optBoolean("sharedFission")) return false;
        if (!plattform.optBoolean("shellRechte")) return false;
        return plattform.optBoolean("autoContainer") || plattform.optBoolean("sharedFission");
    }

    public static boolean testerAngebot(JSONObject plattform) {
        return testerAngebot(plattform, inListe());
    }

    private static volatile boolean zulassung;
    private static volatile long zulassungBis;

    /**
     * Für die Zulassung VOR dem Öffnen (Totwinkel-Schleife alle 250 ms): dasselbe Urteil, 2 s gemerkt. So geht bei
     * gesperrter Hardware gar nichts hinaus, auch kein überflüssiges 18/0 nach einem abgewiesenen Öffnen.
     */
    public static boolean zulassungMoeglich() {
        long now = System.currentTimeMillis();
        if (now < zulassungBis) return zulassung;
        boolean z = urteil().oeffnenErlaubt();
        zulassung = z;
        zulassungBis = now + 2_000L;
        return z;
    }

    public static Urteil urteil() {
        try {
            Schluessel s = schluessel();
            JSONObject liste = new JSONObject(TachoKohortenListe.JSON);
            return urteil(s, liste, quelle.antwort(s.id()), quelle.tester(s.id()), quelle.imStand());
        } catch (Throwable t) {
            return new Urteil(GESPERRT, "pruefung_fehlgeschlagen",
                    "Panoptes konnte die Hardware nicht prüfen. Deshalb bleibt der Tacho aus.");
        }
    }

    /**
     * DAS Gate vor jedem AutoContainer-Befehl (Typ 1000). 18/0 immer ja. Öffnende nur bei frei/probe; bei probe
     * startet es die Pflichtfrage {@link TachoSicht}.
     */
    public static boolean darfSenden(int op) {
        if (op == 2 || op == 3) return false;
        if (istRueckweg(op)) return true;
        if (!istOeffnend(op)) return false;
        Urteil u = urteil();
        if (!u.oeffnenErlaubt()) {
            log("Tacho-Gate: Befehl " + op + " abgewiesen (" + u.code + ")");
            return false;
        }
        if (PROBE.equals(u.status)) {
            try { TachoSicht.oeffnungGesendet(schluessel().id()); } catch (Throwable ignored) { }
        }
        return true;
    }

    /** Größenprofil nach Sperre: das Profil oder 0 (kein Größenschritt). */
    public static int profilNachSperre(int profil) {
        if (profil == 0) return 0;
        try {
            Schluessel s = schluessel();
            String g = sperrGrund(profil, s.modell, s.panel);
            if (g == null) return profil;
            log("Tacho-Größe " + profil + " gesperrt (" + g + ") – kein Größenschritt");
        } catch (Throwable ignored) { }
        return 0;
    }

    /** Für Fähigkeitstest und Größen-Seite. */
    public static JSONObject stand() {
        JSONObject o = new JSONObject();
        try {
            Schluessel s = schluessel();
            Urteil u = urteil();
            o.put("schluessel", s.alsJson()).put("urteil", u.alsJson())
                    .put("vorschlag", vorschlag(s.modell, s.panel))
                    .put("adasAlternative", adasAlternative(s.modell, s.panel));
        } catch (Throwable ignored) { }
        return o;
    }

    private static void log(String m) {
        try {
            at.greenchili.surfmate.logging.DaemonLogger.getInstance("TachoKohorte").warn(m);
        } catch (Throwable ignored) { }
    }

    static final class LiveQuelle implements Quelle {
        @Override public Schluessel schluessel() {
            Schluessel s = new Schluessel();
            s.modell = prop("ro.product.model");
            s.firmware = prop("persist.sys.gbClientVersion");
            s.build = prop("ro.build.display.id");
            try { s.api = Integer.parseInt(prop("ro.build.version.sdk")); } catch (Exception ignored) { }
            s.libBydCluster = dateiHash(LIB_CLUSTER);
            String f = "fehlt";
            for (String pfad : FISSIOND) {
                String h = dateiHash(pfad);
                if (!"fehlt".equals(h)) { f = h; break; }
            }
            s.fissiond = f;
            s.panel = panelLive();
            return s;
        }

        @Override public String antwort(String id) { return TachoSicht.antwort(id); }

        @Override public boolean tester(String id) { return TachoSicht.testerFreigabe(id); }

        @Override public boolean imStand() {
            try {
                return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason() == null;
            } catch (Throwable t) { return false; }
        }
    }

    private static String prop(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, name, "");
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** sha256 einer Datei; "fehlt" wenn nicht da, "unlesbar" wenn kein Leserecht. Nur lesen. */
    static String dateiHash(String pfad) {
        File f = new File(pfad);
        if (!f.exists()) return "fehlt";
        if (!f.canRead()) return "unlesbar";
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable t) {
            return "unlesbar";
        }
    }

    private static String panelLive() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "dumpsys display 2>/dev/null | grep -E 'mDisplayId=|mName=|DisplayInfo\\{'")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 200_000) sb.append(line).append('\n');
            }
            p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
            p.destroy();
            JSONObject d = TachoFaehigkeit.tachoDisplay(sb.toString());
            return d == null ? "" : d.optString("groesse", "");
        } catch (Throwable t) {
            return "";
        }
    }

    static String sha256Hex(byte[] b) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
