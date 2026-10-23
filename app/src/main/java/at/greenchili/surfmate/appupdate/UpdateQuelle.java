package at.greenchili.surfmate.appupdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Woher der In-App-Updater liest (Koordination 0.1.15): eine Einstellung statt hart verdrahtet.
 *
 * <ul>
 *   <li>{@code update.quelle = oeffentlich} (Standard für alle): {@link #STANDARD_REPO}, {@code releases/latest},
 *       ohne Token. Ein gesetzter Token wird hier nie mitgeschickt.</li>
 *   <li>{@code update.quelle = eigen}: Repo aus {@code update.repo} (owner/name), optional mit Lese-Token
 *       {@code update.token}. Mit Token geht alles über die GitHub-API (private Repos), sonst über die öffentlichen
 *       Download-Adressen.</li>
 * </ul>
 *
 * <p>Die Prüfung von sha256 und Signaturzertifikat ({@link Freigabe}, {@link AppUpdateDienst#ausfuehren}) gilt für
 * jede Quelle gleich.
 */
public final class UpdateQuelle {

    public static final String OEFFENTLICH = "oeffentlich";
    public static final String EIGEN = "eigen";
    /** Öffentliches Panoptes-Repo (Quellcode + Releases). */
    public static final String STANDARD_REPO = "samuelvoltarius/panoptes";
    /** Bis 0.1.14 fest eingebaute Quelle; nur für die Migration bestehender Installationen. */
    static final String ALTE_REPO = "samuelvoltarius/panoptes-releases";

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");
    private static final String API = "https://api.github.com/repos/";
    private static final String WEB = "https://github.com/";

    public final String art;
    public final String repo;
    /** Lese-Token oder null; nur bei {@link #EIGEN}. */
    public final String token;

    private UpdateQuelle(String art, String repo, String token) {
        this.art = art;
        this.repo = repo;
        this.token = token;
    }

    /** Aus den Config-Werten {@code update.quelle}, {@code update.repo}, {@code update.token}. */
    public static UpdateQuelle aus(String quelle, String repo, String token) {
        if (EIGEN.equals(quelle) && gueltigesRepo(repo)) {
            String t = token == null || token.trim().isEmpty() ? null : token.trim();
            return new UpdateQuelle(EIGEN, repo.trim(), t);
        }
        return new UpdateQuelle(OEFFENTLICH, STANDARD_REPO, null);
    }

    public static boolean gueltigesRepo(String repo) {
        return repo != null && REPO.matcher(repo.trim()).matches() && !repo.contains("..");
    }

    public boolean mitToken() {
        return token != null;
    }

    /** Kennung für den Stand (wechselt die Quelle, gilt ein früher gefundenes Update nicht mehr). Ohne Token. */
    public String kennung() {
        return art + ":" + repo + (mitToken() ? ":token" : "");
    }

    // ------------------------------------------------------------------ Adressen

    /** Ohne Token: version.json der neuesten Nicht-Vorabversion. */
    String latestVersionJson() {
        return WEB + repo + "/releases/latest/download/version.json";
    }

    /** Mit Token: Release-Beschreibung der neuesten Nicht-Vorabversion (API). */
    String latestRelease() {
        return API + repo + "/releases/latest";
    }

    /** Liste der letzten Releases (neueste zuerst), auch Vorabversionen. */
    String releasesListe() {
        return API + repo + "/releases?per_page=5";
    }

    /** Mit Token: Release-Beschreibung zu einem Tag (API). */
    String releaseZuTag(String tag) {
        return API + repo + "/releases/tags/" + tag;
    }

    /** Ohne Token: Datei eines Releases. */
    String download(String tag, String datei) {
        return WEB + repo + "/releases/download/" + tag + "/" + datei;
    }

    /** API-Adresse eines Assets nach Name aus einer Release-Beschreibung, sonst null. */
    static String asset(String releaseJson, String name) {
        try {
            JSONArray a = new JSONObject(releaseJson).optJSONArray("assets");
            if (a == null) return null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (name.equals(x.optString("name"))) {
                    String u = x.optString("url", "");
                    return u.startsWith(API) ? u : null;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Darf diese APK-Adresse (aus dem gespeicherten Stand) benutzt werden? Nur GitHub über https. */
    static boolean erlaubteAdresse(String url) {
        return url != null && (url.startsWith(WEB) || url.startsWith(API)) && !url.contains("..");
    }

    // ------------------------------------------------------------------ Migration

    /**
     * Einmalige Migration beim ersten Start ab 0.1.15. Rückgabe: was in den Abschnitt {@code update} geschrieben
     * werden soll (leer = nichts, Standard „öffentlich“ gilt). Ein vorhandener Token wird nie angefasst.
     *
     * @param quelle  {@code update.quelle}, wie gesetzt (null = nie gesetzt)
     * @param repo    {@code update.repo}, wie gesetzt
     * @param token   {@code update.token}, wie gesetzt
     * @param bestand lief der Updater hier schon vor 0.1.15 (Stand-Datei vorhanden)?
     */
    public static Map<String, Object> migration(String quelle, String repo, String token, boolean bestand) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (quelle != null && !quelle.isEmpty()) return m;          // schon entschieden
        boolean hatToken = token != null && !token.trim().isEmpty();
        boolean hatRepo = gueltigesRepo(repo);
        if (!hatToken && !hatRepo && !bestand) return m;            // neue Installation: öffentlich
        m.put("quelle", EIGEN);
        if (!hatRepo) m.put("repo", ALTE_REPO);                     // alte, bisher fest eingebaute Quelle
        return m;
    }
}
