package at.greenchili.surfmate.appupdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine veröffentlichte Panoptes-Version laut {@code version.json} aus der eingestellten {@link UpdateQuelle}
 * (Format: .github/workflows/release.yml).
 *
 * <p>Alles wird streng geprüft; ein Feld außerhalb der Form → {@link Fehler}. Der Zertifikats-Fingerabdruck muss
 * eines der Panoptes-Zertifikate sein ({@link #zulaessig}) – ein anderer Schlüssel würde Android ohnehin ablehnen,
 * wir melden es vorher.
 *
 * <p>0.1.15 (Kern 8, Signatur-Rotation Kern 9): Ab 0.1.15 sind APKs mit dem Release-Schlüssel {@link #ZERTIFIKAT}
 * signiert (APK-Signaturschema v3, Lineage {@link #ZERTIFIKAT_ALT} → {@link #ZERTIFIKAT}). Installationen mit dem
 * alten Schlüssel nehmen solche Updates an; der Updater lässt beide zu, aber nie zurück von neu auf alt.
 */
public final class Freigabe {

    /** Aktuelles Signaturzertifikat der Panoptes-APKs ab 0.1.15 (Release-Schlüssel „Panoptes“, SHA-256). */
    public static final String ZERTIFIKAT = "287c31e78288a440be4d23eb59f58e415d77aca54def67e0e6bf271f1959be81";
    /** Bisheriges Zertifikat bis 0.1.14.1 (Debug-Keystore, Koordination 21.10.2026); in der Lineage enthalten. */
    public static final String ZERTIFIKAT_ALT = "02d5a37bf0c1e334f56277af5669efc536fa96921fde0138d7e88dea7d7d8006";

    /** Ein Panoptes-Zertifikat (neu oder alt)? */
    public static boolean zulaessig(String cert) {
        return ZERTIFIKAT.equals(cert) || ZERTIFIKAT_ALT.equals(cert);
    }

    /**
     * Passt die geladene APK zur installierten App? null = ja, sonst Grund. Neu signiert (mit Lineage) passt auf alt
     * und neu; alt signiert nur auf alt (bzw. unbekannt) – Android lehnt den Rückweg von neu auf alt ab.
     */
    public static String signaturPasst(String apkCert, String installiert) {
        return signaturPasst(apkCert, installiert, ZERTIFIKAT, ZERTIFIKAT_ALT);
    }

    /** Wie oben, mit eigenem Paar neu/alt (Test mit Wegwerf-Schlüsseln). */
    static String signaturPasst(String apkCert, String installiert, String neu, String alt) {
        boolean apkOk = neu.equals(apkCert) || alt.equals(apkCert);
        if (!apkOk) return "Zertifikat " + apkCert;
        if (installiert == null) return null;
        if (!neu.equals(installiert) && !alt.equals(installiert)) return "installierte App hat Zertifikat " + installiert;
        if (alt.equals(apkCert) && neu.equals(installiert)) {
            return "APK mit altem Zertifikat auf neu signierter Installation";
        }
        return null;
    }

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAME = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}");
    private static final Pattern TAG = Pattern.compile("v[0-9]{1,3}(\\.[0-9]{1,3}){1,3}(-test\\.[0-9]{1,4})?");
    private static final Pattern APK = Pattern.compile("[A-Za-z0-9._-]{1,120}\\.apk");

    public final int versionCode;
    public final String versionName;
    public final String kanal;
    public final String tag;
    public final String apk;
    public final String sha256;
    public final int minVersion;
    /** Zertifikat laut version.json (zulässig). */
    public final String zertifikat;
    /** Sprache → Zeilen; leer, wenn version.json keine Notizen trägt. */
    public final JSONObject notizen;
    /** Download-Adresse der APK; setzt der Dienst je nach {@link UpdateQuelle} (nie aus version.json). */
    private final String apkAdresse;
    /** 0.1.37 Stufe 4: Launcher-APK desselben Releases (optional, sonst null). */
    public final Launcher launcher;
    /** 0.1.38.5: Art laut version.json ("fix" | "funktion" | "beides") oder null (fehlt bzw. unbekannt). */
    public final String art;

    /**
     * Zweites Paket im selben Release: {@code "launcher": {versionCode, versionName, apk, sha256}} in version.json.
     * Signiert mit demselben Schlüssel ({@link #zertifikat}); fehlt es oder ist es fehlerhaft, gilt nur Panoptes.
     */
    public static final class Launcher {
        public final int versionCode;
        public final String versionName;
        public final String apk;
        public final String sha256;
        /** Download-Adresse; setzt der Dienst je nach {@link UpdateQuelle}. */
        private final String apkAdresse;

        Launcher(int versionCode, String versionName, String apk, String sha256, String apkAdresse) {
            this.versionCode = versionCode; this.versionName = versionName; this.apk = apk; this.sha256 = sha256;
            this.apkAdresse = apkAdresse;
        }

        public String apkUrl() {
            return apkAdresse;
        }

        Launcher mitApkUrl(String url) {
            return new Launcher(versionCode, versionName, apk, sha256, url);
        }

        /** null bei fehlender oder fehlerhafter Angabe (nie ein Fehler für das Panoptes-Update). */
        static Launcher lies(JSONObject j) {
            if (j == null) return null;
            int vc = j.optInt("versionCode", -1);
            String name = j.optString("versionName", "");
            String apk = j.optString("apk", "");
            String sha = j.optString("sha256", "").toLowerCase(Locale.ROOT);
            if (vc <= 0 || vc > 1_000_000) return null;
            if (!NAME.matcher(name).matches()) return null;
            if (!APK.matcher(apk).matches() || apk.startsWith(".")) return null;
            if (!HEX64.matcher(sha).matches()) return null;
            return new Launcher(vc, name, apk, sha, null);
        }

        JSONObject alsJson() throws Exception {
            return new JSONObject().put("versionCode", versionCode).put("versionName", versionName).put("apk", apk)
                    .put("sha256", sha256);
        }
    }

    public static final class Fehler extends Exception {
        public final String grund;
        Fehler(String grund, String text) { super(text); this.grund = grund; }
    }

    private Freigabe(int vc, String name, String kanal, String tag, String apk, String sha, int minV, String cert,
                     JSONObject notizen, String apkAdresse, Launcher launcher, String art) {
        this.versionCode = vc; this.versionName = name; this.kanal = kanal; this.tag = tag; this.apk = apk;
        this.sha256 = sha; this.minVersion = minV; this.zertifikat = cert; this.notizen = notizen;
        this.apkAdresse = apkAdresse; this.launcher = launcher; this.art = art;
    }

    /** Dieselbe Freigabe mit der Download-Adresse der APK. */
    public Freigabe mitApkUrl(String url) {
        return new Freigabe(versionCode, versionName, kanal, tag, apk, sha256, minVersion, zertifikat, notizen, url,
                launcher, art);
    }

    /** Dieselbe Freigabe mit der Download-Adresse der Launcher-APK; null = Launcher nicht im Release erreichbar. */
    public Freigabe mitLauncherUrl(String url) {
        Launcher l = launcher == null || url == null ? null : launcher.mitApkUrl(url);
        return new Freigabe(versionCode, versionName, kanal, tag, apk, sha256, minVersion, zertifikat, notizen,
                apkAdresse, l, art);
    }

    public static Freigabe lies(String json) throws Fehler {
        JSONObject j;
        try {
            j = new JSONObject(json);
        } catch (Exception e) {
            throw new Fehler("nicht_erreichbar", "version.json kein JSON");
        }
        int vc = j.optInt("versionCode", -1);
        String name = j.optString("versionName", "");
        String kanal = j.optString("kanal", "");
        String tag = j.optString("tag", "");
        String apk = j.optString("apk", "");
        String sha = j.optString("sha256", "").toLowerCase(Locale.ROOT);
        String cert = j.optString("zertifikatSha256", "").toLowerCase(Locale.ROOT);
        int minV = j.optInt("minVersion", 0);
        if (vc <= 0 || vc > 1_000_000) throw new Fehler("nicht_erreichbar", "versionCode");
        if (!NAME.matcher(name).matches()) throw new Fehler("nicht_erreichbar", "versionName");
        if (!"stabil".equals(kanal) && !"test".equals(kanal)) throw new Fehler("nicht_erreichbar", "kanal");
        if (!TAG.matcher(tag).matches() || !tag.startsWith("v" + name)) throw new Fehler("nicht_erreichbar", "tag");
        if ("stabil".equals(kanal) == tag.contains("-test.")) throw new Fehler("nicht_erreichbar", "tag/kanal");
        if (!APK.matcher(apk).matches() || apk.startsWith(".")) throw new Fehler("nicht_erreichbar", "apk");
        if (!HEX64.matcher(sha).matches()) throw new Fehler("pruefsumme", "sha256");
        if (!zulaessig(cert)) throw new Fehler("signatur", "zertifikat " + cert);
        JSONObject notizen = new JSONObject();
        JSONObject n = j.optJSONObject("notizen");
        if (n != null) {
            for (String sprache : new String[]{"de", "en", "fr", "it", "nl"}) {
                JSONArray a = n.optJSONArray(sprache);
                if (a == null) continue;
                JSONArray sauber = new JSONArray();
                for (int i = 0; i < a.length() && i < 8; i++) {
                    String z = a.optString(i, "").trim();
                    if (!z.isEmpty()) sauber.put(z.length() > 200 ? z.substring(0, 200) : z);
                }
                try { notizen.put(sprache, sauber); } catch (Exception ignored) { }
            }
        }
        Launcher l = Launcher.lies(j.optJSONObject("launcher"));
        if (l != null && l.apk.equals(apk)) l = null;   // nie dieselbe Datei wie Panoptes
        String art = j.optString("art", "");
        if (!UpdateHinweis.ART_FIX.equals(art) && !UpdateHinweis.ART_FUNKTION.equals(art)
                && !UpdateHinweis.ART_BEIDES.equals(art)) art = null;
        return new Freigabe(vc, name, kanal, tag, apk, sha, Math.max(0, minV), cert, notizen, null, l, art);
    }

    /** Download-Adresse der APK (öffentlich oder API-Asset) oder null, wenn noch keine Quelle sie gesetzt hat. */
    public String apkUrl() {
        return apkAdresse;
    }

    /** Notizen in der gewünschten Sprache, sonst en, sonst leer. */
    public JSONArray notizen(String sprache) {
        JSONArray a = sprache == null ? null : notizen.optJSONArray(sprache);
        if (a == null) a = notizen.optJSONArray("en");
        return a == null ? new JSONArray() : a;
    }

    public JSONObject alsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("versionCode", versionCode).put("versionName", versionName).put("kanal", kanal).put("tag", tag)
                    .put("apk", apk).put("sha256", sha256).put("zertifikatSha256", zertifikat)
                    .put("minVersion", minVersion).put("notizen", notizen);
            if (launcher != null) o.put("launcher", launcher.alsJson());
            if (art != null) o.put("art", art);
        } catch (Exception ignored) { }
        return o;
    }
}
