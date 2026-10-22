package at.greenchili.surfmate.appupdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine veröffentlichte Panoptes-Version laut {@code version.json} im öffentlichen Repo
 * {@code samuelvoltarius/panoptes-releases} (Format: .github/workflows/release.yml).
 *
 * <p>Alles wird streng geprüft; ein Feld außerhalb der Form → {@link Fehler}. Der Zertifikats-Fingerabdruck muss
 * genau {@link #ZERTIFIKAT} sein – ein anderer Schlüssel würde Android ohnehin ablehnen, wir melden es vorher.
 */
public final class Freigabe {

    /** Signaturzertifikat aller Panoptes-APKs (SHA-256, Koordination 21.10.2026). */
    public static final String ZERTIFIKAT = "02d5a37bf0c1e334f56277af5669efc536fa96921fde0138d7e88dea7d7d8006";
    public static final String REPO = "samuelvoltarius/panoptes-releases";

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
    /** Sprache → Zeilen; leer, wenn version.json keine Notizen trägt. */
    public final JSONObject notizen;

    public static final class Fehler extends Exception {
        public final String grund;
        Fehler(String grund, String text) { super(text); this.grund = grund; }
    }

    private Freigabe(int vc, String name, String kanal, String tag, String apk, String sha, int minV, JSONObject notizen) {
        this.versionCode = vc; this.versionName = name; this.kanal = kanal; this.tag = tag; this.apk = apk;
        this.sha256 = sha; this.minVersion = minV; this.notizen = notizen;
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
        if (!ZERTIFIKAT.equals(cert)) throw new Fehler("signatur", "zertifikat " + cert);
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
        return new Freigabe(vc, name, kanal, tag, apk, sha, Math.max(0, minV), notizen);
    }

    /** Download-Adresse der APK im Release (öffentlich, ohne Token). */
    public String apkUrl() {
        return "https://github.com/" + REPO + "/releases/download/" + tag + "/" + apk;
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
                    .put("apk", apk).put("sha256", sha256).put("zertifikatSha256", ZERTIFIKAT)
                    .put("minVersion", minVersion).put("notizen", notizen);
        } catch (Exception ignored) { }
        return o;
    }
}
