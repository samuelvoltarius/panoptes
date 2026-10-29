package at.greenchili.surfmate.bruecke;

import at.greenchili.surfmate.recording.ClipExport;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 0.1.28 P5: Ereignis-Clip aufs eigene Handy (CLIP_VERTRAG_0128.md). Läuft NUR hier, im Brücken-Prozess, auf einem
 * Medien-Kanal ({@code "art":"medien"}, v2, Ende-zu-Ende verschlüsselt) – der HTTP-Server gibt Ereignis-Clips nach
 * außen weiterhin nie heraus. {@link BrueckeWeiterleitung#strom} hat vorher geprüft: Kopplungs-JWT genau dieser
 * Kopplung, Recht „kamera“, Medien-Tagesgrenze.
 * <ul>
 *   <li>{@code POST /api/handy/v1/clips/freigabe {"id","knopf":true}} → {@code {ok, schein, gueltigBis, groesse, name}}</li>
 *   <li>{@code GET /api/handy/v1/clips/datei/<id>?schein=<hex>} mit {@code range} → 206/200 {@code video/mp4}</li>
 * </ul>
 */
final class BrueckeClipWeg {

    static final String FREIGABE = "/api/handy/v1/clips/freigabe";
    static final String DATEI = "/api/handy/v1/clips/datei/";

    /** Auto-Seite: Kopplungen und Ereignis-Clip-Dateien (im Test ersetzt). */
    interface Quelle {
        boolean istHandy(String kid);
        /** Eigener Schalter „Unfall-Clips aufs Handy erlauben“ (Recht „ereignisclip“, Standard aus). */
        boolean ereignisErlaubt(String kid);
        String geraet(String kid);
        /** Datei des Ereignis-Clips mit dieser id, nur wenn erlaubt und lesbar; sonst null. */
        File ereignisDatei(String id);
        long jetzt();
        void log(String zeile);
    }

    private final Quelle quelle;
    private final ClipExport export;

    BrueckeClipWeg(Quelle quelle, ClipExport export) {
        this.quelle = quelle;
        this.export = export;
    }

    /** Gehört der Pfad diesem Weg (und darf darum nie an den HTTP-Server)? */
    static boolean zustaendig(String pfad) {
        String q = pfad.contains("?") ? pfad.substring(0, pfad.indexOf('?')) : pfad;
        return q.equals(FREIGABE) || q.startsWith(DATEI);
    }

    BrueckeWeiterleitung.Strom oeffnen(String kid, String methode, String pfad, Map<String, String> kopf, byte[] body)
            throws Exception {
        String q = pfad.contains("?") ? pfad.substring(0, pfad.indexOf('?')) : pfad;
        if (!quelle.istHandy(kid)) return json(403, fehler("nur_handy"));
        if (!quelle.ereignisErlaubt(kid)) {
            if (q.equals(FREIGABE)) {
                export.abgelehnt(kid, quelle.geraet(kid), "ereignisclip_aus", quelle.jetzt());
                quelle.log("Ereignis-Clip aufs Handy abgelehnt (Schalter aus)");
            }
            return json(403, fehler("ereignisclip_aus").put("recht", at.greenchili.surfmate.auth.Kopplungen.RECHT_EREIGNISCLIP));
        }
        if (q.equals(FREIGABE)) {
            if (!"POST".equals(methode)) return json(405, fehler("methode"));
            return freigabe(kid, body);
        }
        if (!"GET".equals(methode)) return json(405, fehler("methode"));
        return datei(kid, q.substring(DATEI.length()), pfad, kopf.get("range"));
    }

    private BrueckeWeiterleitung.Strom freigabe(String kid, byte[] body) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(new String(body, StandardCharsets.UTF_8).isEmpty() ? "{}"
                    : new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return json(400, fehler("json"));
        }
        String id = req.optString("id", "");
        File f = quelle.ereignisDatei(id);
        String geraet = quelle.geraet(kid);
        ClipExport.Freigabe fr = export.freigeben(kid, geraet, id, f == null ? null : f.getName(),
                req.optBoolean("knopf", false), quelle.jetzt());
        if (fr.schein == null) {
            quelle.log("Ereignis-Clip aufs Handy abgelehnt (" + fr.grund + ")");
            int s = "zu_oft".equals(fr.grund) ? 429 : "kein_ereignis_clip".equals(fr.grund) ? 404 : 403;
            return json(s, fehler(fr.grund));
        }
        quelle.log("Ereignis-Clip aufs Handy freigegeben: " + f.getName() + " → " + (geraet == null ? kid : geraet));
        return json(200, new JSONObject().put("ok", true).put("id", id).put("schein", fr.schein)
                .put("gueltigBis", fr.gueltigBis).put("groesse", f.length()).put("name", f.getName()));
    }

    private BrueckeWeiterleitung.Strom datei(String kid, String id, String pfad, String range) throws Exception {
        String schein = abfrage(pfad, "schein");
        String name = export.einloesen(kid, schein, id, quelle.jetzt());
        if (name == null) return json(403, fehler("schein"));
        File f = quelle.ereignisDatei(id);
        if (f == null || !f.getName().equals(name)) return json(410, fehler("nicht_mehr_da"));
        long laenge = f.length();
        long[] b = bereich(range, laenge);
        Map<String, String> k = new HashMap<>();
        k.put("content-type", "video/mp4");
        k.put("accept-ranges", "bytes");
        k.put("cache-control", "no-store");
        if (b == null && range != null && !range.trim().isEmpty()) {
            k.put("content-range", "bytes */" + laenge);
            return new BrueckeWeiterleitung.Strom(416, k, null, null);
        }
        long von = b == null ? 0 : b[0], bis = b == null ? laenge - 1 : b[1];
        InputStream in = new FileInputStream(f);
        try {
            long rest = von;
            while (rest > 0) {
                long n = in.skip(rest);
                if (n <= 0) throw new java.io.EOFException();
                rest -= n;
            }
        } catch (Exception e) {
            in.close();
            throw e;
        }
        if (b != null) k.put("content-range", "bytes " + von + "-" + bis + "/" + laenge);
        k.put("content-length", String.valueOf(bis - von + 1));
        final long ende = bis;
        // „übertragen“ nur, wenn der Bereich ganz gelesen wurde (nicht bei Abbruch/Funkloch)
        java.util.concurrent.atomic.AtomicBoolean ganz = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable zu = () -> {
            if (ganz.get()) export.bereichGesendet(schein, quelle.geraet(kid), von, ende, laenge, quelle.jetzt());
        };
        return new BrueckeWeiterleitung.Strom(b == null ? 200 : 206, k, begrenzt(in, bis - von + 1, ganz), zu);
    }

    /** Ein Bereich {@code bytes=a-b | a- | -n} in [0, laenge); null = keiner bzw. unerfüllbar. */
    static long[] bereich(String range, long laenge) {
        if (range == null || laenge <= 0) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes=(\\d{0,15})-(\\d{0,15})\\s*$")
                .matcher(range.toLowerCase(Locale.ROOT));
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) return null;
        if (m.group(1).isEmpty()) {
            long n = Long.parseLong(m.group(2));
            if (n <= 0) return null;
            return new long[]{Math.max(0, laenge - n), laenge - 1};
        }
        long a = Long.parseLong(m.group(1));
        long e = m.group(2).isEmpty() ? laenge - 1 : Math.min(Long.parseLong(m.group(2)), laenge - 1);
        if (a >= laenge || e < a) return null;
        return new long[]{a, e};
    }

    static String abfrage(String pfad, String name) {
        int q = pfad.indexOf('?');
        if (q < 0) return null;
        for (String kv : pfad.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    private static InputStream begrenzt(InputStream in, long n, java.util.concurrent.atomic.AtomicBoolean ganz) {
        return new java.io.FilterInputStream(in) {
            long rest = n;

            @Override
            public int read() throws java.io.IOException {
                if (rest <= 0) return -1;
                int r = super.read();
                if (r >= 0 && --rest == 0) ganz.set(true);
                return r;
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (rest <= 0) return -1;
                int r = super.read(b, off, (int) Math.min(len, rest));
                if (r > 0) {
                    rest -= r;
                    if (rest == 0) ganz.set(true);
                }
                return r;
            }
        };
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }

    private static BrueckeWeiterleitung.Strom json(int status, JSONObject j) {
        Map<String, String> k = new HashMap<>();
        k.put("content-type", "application/json");
        byte[] b = j.toString().getBytes(StandardCharsets.UTF_8);
        k.put("content-length", String.valueOf(b.length));
        return new BrueckeWeiterleitung.Strom(status, k, new java.io.ByteArrayInputStream(b), null);
    }

    // ------------------------------------------------------------------ Laufzeit

    static BrueckeClipWeg imDaemon() {
        return new BrueckeClipWeg(new Quelle() {
            @Override
            public boolean istHandy(String kid) {
                return at.greenchili.surfmate.auth.Kopplungen.get().istHandy(kid);
            }

            @Override
            public boolean ereignisErlaubt(String kid) {
                return at.greenchili.surfmate.auth.Kopplungen.get().hatRecht(kid,
                        at.greenchili.surfmate.auth.Kopplungen.RECHT_EREIGNISCLIP);
            }

            @Override
            public String geraet(String kid) {
                return at.greenchili.surfmate.auth.Kopplungen.get().anzeigeName(kid);
            }

            @Override
            public File ereignisDatei(String id) {
                return at.greenchili.surfmate.server.HandyClipsApiHandler.ereignisDatei(id);
            }

            @Override
            public long jetzt() {
                return System.currentTimeMillis();
            }

            @Override
            public void log(String zeile) {
                at.greenchili.surfmate.logging.DaemonLogger.getInstance("ClipExport").info(zeile);
            }
        }, ClipExport.get());
    }
}
