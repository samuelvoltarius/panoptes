package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.recording.ClipExport;
import at.greenchili.surfmate.recording.EreignisClip;
import at.greenchili.surfmate.storage.Aufbewahrung;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.1.28 P5 „Dashcam-Clips am Handy“ – Auto-Seite (CLIP_VERTRAG_0128.md).
 *
 * <ul>
 *   <li>{@code GET /api/handy/v1/clips?seite&anzahl}: Liste für das gekoppelte Handy (Art {@code handy}, Recht
 *       „kamera“), ohne Pfade und ohne Koordinaten. Normale Aufnahmen laden wie bisher über {@code /video/id/…}
 *       (Range); Ereignis-Clips tragen {@code weg:"bruecke_medien"}.</li>
 *   <li>{@code POST /api/handy/v1/clips/behalten {id, behalten}}: im Auto behalten bzw. wieder der normalen Frist
 *       überlassen; beantwortet die „Behalten?“-Frage. Gelöscht wird vom Handy aus nichts im Auto.</li>
 *   <li>{@code …/freigabe} und {@code …/datei/…} gibt es NUR im Brücken-Medien-Kanal (im Brücken-Prozess selbst,
 *       {@code BrueckeClipWeg}); über HTTP – auch Tailscale – immer 403 {@code nur_bruecke_medien}. Der HTTP-Server
 *       liefert Ereignis-Clips nach außen weiterhin nie aus (Sperre aus 0.1.27 bleibt).</li>
 *   <li>{@code GET /api/handy/v1/clips/protokoll}: Export-Protokoll, nur am Auto bzw. mit Login (nie für Kopplungen).</li>
 * </ul>
 */
public final class HandyClipsApiHandler {

    public static final String PFAD = "/api/handy/v1/clips";
    static final int MAX_ANZAHL = 50;

    private HandyClipsApiHandler() {}

    /** Index, Kopplungen und Uhr – im Test ersetzt. */
    interface Umgebung {
        List<JSONObject> zeilen(int limit, int offset);
        /** Dateiname der Aufnahme mit dieser id oder null. */
        String dateiname(String id);
        boolean istHandy(String kid);
        boolean hatKamera(String kid);
        /** Schalter „Unfall-Clips aufs Handy erlauben“ (Recht „ereignisclip“). */
        default boolean ereignisErlaubt(String kid) { return false; }
        boolean istBehalten(String datei);
        Set<String> offeneFragen();
        boolean behalten(String stamm, boolean behalten);
        JSONArray protokoll(int n);
        boolean extern();
    }

    static volatile Umgebung umgebung = new Echt();

    public static boolean handle(String method, String path, String body, OutputStream out, String kid) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.equals(PFAD) && !p.startsWith(PFAD + "/")) return false;
        Umgebung u = umgebung;
        if (p.equals(PFAD + "/protokoll")) {
            if (!"GET".equals(method)) return antwort(out, 405, fehler("methode"));
            if (u.extern() || kid != null) return antwort(out, 403, fehler("nur_am_auto"));
            return antwort(out, 200, new JSONObject().put("ok", true).put("protokoll", u.protokoll(200)));
        }
        if (p.equals(PFAD + "/freigabe") || p.startsWith(PFAD + "/datei")) {
            // nur im Brücken-Medien-Kanal (BrueckeClipWeg); hier kommt nur an, was NICHT durch ihn ging
            return antwort(out, 403, fehler("nur_bruecke_medien"));
        }
        if (kid == null || !u.istHandy(kid)) return antwort(out, 403, fehler("nur_handy"));
        if (!u.hatKamera(kid)) return antwort(out, 403, fehler("nicht_erlaubt").put("recht", Kopplungen.RECHT_KAMERA));
        if (p.equals(PFAD)) {
            if (!"GET".equals(method)) return antwort(out, 405, fehler("methode"));
            String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            int seite = Math.max(0, zahl(q, "seite", 0));
            int anzahl = Math.max(1, Math.min(MAX_ANZAHL, zahl(q, "anzahl", 20)));
            return antwort(out, 200, liste(u, seite, anzahl).put("ereignisErlaubt", u.ereignisErlaubt(kid)));
        }
        if (p.equals(PFAD + "/behalten")) {
            if (!"POST".equals(method)) return antwort(out, 405, fehler("methode"));
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return antwort(out, 400, fehler("json"));
            }
            String id = req.optString("id", "");
            Object b = req.opt("behalten");
            if (!(b instanceof Boolean)) return antwort(out, 400, fehler("werte"));
            String datei = id.matches("[0-9a-f]{32}") ? u.dateiname(id) : null;
            if (datei == null) return antwort(out, 404, fehler("nicht_gefunden"));
            String stamm = Aufbewahrung.stamm(datei);
            boolean ok = u.behalten(stamm, (Boolean) b);
            return antwort(out, ok ? 200 : 500, ok
                    ? new JSONObject().put("ok", true).put("id", id).put("behalten", b)
                    : fehler("nicht_gespeichert"));
        }
        return antwort(out, 404, fehler("unbekannt"));
    }

    /** Eine Seite der Liste (neueste zuerst). */
    static JSONObject liste(Umgebung u, int seite, int anzahl) throws Exception {
        List<JSONObject> rows = u.zeilen(anzahl + 1, seite * anzahl);
        Set<String> offen = u.offeneFragen();
        JSONArray clips = new JSONArray();
        for (int i = 0; i < rows.size() && i < anzahl; i++) {
            JSONObject c = zeile(rows.get(i), offen, u.istBehalten(rows.get(i).optString("filename")));
            if (c != null) clips.put(c);
        }
        return new JSONObject().put("ok", true).put("v", 1).put("seite", seite)
                .put("mehr", rows.size() > anzahl).put("clips", clips)
                .put("scheinMinuten", ClipExport.SCHEIN_MS / 60_000L);
    }

    /** Index-Zeile → Handy-Zeile; nur erlaubte Felder (keine Pfade, keine Koordinaten, keine Orte). */
    static JSONObject zeile(JSONObject r, Set<String> offeneFragen, boolean behalten) throws Exception {
        String id = r.optString("id", "");
        String datei = r.optString("filename", "");
        if (!id.matches("[0-9a-f]{32}") || datei.isEmpty() || !r.optBoolean("available", true)) return null;
        boolean ereignis = EreignisClip.TYP.equals(r.optString("type")) || EreignisClip.istEreignis(datei);
        JSONObject c = new JSONObject();
        c.put("id", id);
        c.put("typ", ereignis ? "ereignis" : "aufnahme");
        c.put("art", r.optString("type", ""));
        c.put("zeit", r.optLong("timestamp", 0));
        c.put("groesse", r.optLong("size", 0));
        c.put("behalten", behalten);
        if (ereignis) {
            c.put("frageOffen", offeneFragen.contains(Aufbewahrung.stamm(datei)));
            c.put("weg", "bruecke_medien");
        } else {
            c.put("weg", "direkt");
            c.put("videoUrl", "/video/id/" + id);
            c.put("vorschauUrl", "/thumb/id/" + id);
        }
        return c;
    }

    /**
     * Für den Brücken-Medien-Kanal: Datei des Ereignis-Clips mit dieser id, nur wenn sie im erlaubten Aufnahme-Ordner
     * liegt, lesbar ist und wirklich ein Ereignis-Clip ist; sonst null.
     */
    public static File ereignisDatei(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return null;
        try {
            RecordingsIndex.RecordingRef ref = RecordingsIndex.getInstance().resolveById(id);
            if (ref == null) return null;
            File f = ref.file();
            if (!RecordingsApiHandler.indexPathAllowed(f) || !EreignisClip.istEreignisPfad(f.getPath())) return null;
            if (!EreignisClip.istEreignis(f.getName()) || !f.isFile() || !f.canRead()) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int zahl(String query, String name, int standard) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                try {
                    return Integer.parseInt(kv.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return standard;
                }
            }
        }
        return standard;
    }

    static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }

    private static boolean antwort(OutputStream out, int status, JSONObject j) throws Exception {
        HttpResponse.sendJson(out, status, j.toString());
        return true;
    }

    static final class Echt implements Umgebung {
        @Override
        public List<JSONObject> zeilen(int limit, int offset) {
            return RecordingsIndex.getInstance().queryRecordings(new RecordingsIndex.Filter(), limit, offset);
        }

        @Override
        public String dateiname(String id) {
            RecordingsIndex.RecordingRef ref = RecordingsIndex.getInstance().resolveById(id);
            return ref == null ? null : ref.filename;
        }

        @Override
        public boolean istHandy(String kid) {
            return Kopplungen.get().istHandy(kid);
        }

        @Override
        public boolean hatKamera(String kid) {
            return Kopplungen.get().hatRecht(kid, Kopplungen.RECHT_KAMERA);
        }

        @Override
        public boolean ereignisErlaubt(String kid) {
            return Kopplungen.get().hatRecht(kid, Kopplungen.RECHT_EREIGNISCLIP);
        }

        @Override
        public boolean istBehalten(String datei) {
            return Aufbewahrung.istBehalten(datei);
        }

        @Override
        public Set<String> offeneFragen() {
            Set<String> s = new HashSet<>();
            try {
                JSONArray a = EreignisClip.offen(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) s.add(o.optString("datei"));
                }
            } catch (Throwable ignored) { }
            return s;
        }

        @Override
        public boolean behalten(String stamm, boolean behalten) {
            boolean ok = behalten ? Aufbewahrung.behalten(stamm, System.currentTimeMillis()) : Aufbewahrung.freigeben(stamm);
            // beide Antworten erledigen die „Behalten?“-Frage (wie am Display: „Behalten“ / „Nein danke“)
            if (ok) EreignisClip.frageErledigt(stamm);
            return ok;
        }

        @Override
        public JSONArray protokoll(int n) {
            return ClipExport.get().protokoll(n);
        }

        @Override
        public boolean extern() {
            return AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest();
        }
    }
}
