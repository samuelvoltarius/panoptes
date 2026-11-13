package at.greenchili.surfmate.server;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M1 (13.11.2026): Titel und Interpret der laufenden Musik für die Launcher-Musikkarte, NUR LESEN.
 *
 * <p>Der Launcher sieht fremde Mediensitzungen nur mit Benachrichtigungszugriff. Am BYD lässt sich die Android-Seite
 * dafür nicht öffnen („IVI-System unterstützt diesen Vorgang nicht“), und erteilt wird der Zugriff nie per Befehl.
 * Der Kern läuft als shell und darf {@code dumpsys media_session} lesen (am Surf belegt, Befund CARPLAY_MUSIK_0141).
 * CarPlay legt bei verbundenem iPhone die Sitzung {@code com.byd.carplay.ui/MediaManager} mit TITLE/ARTIST an.
 *
 * <p>Aus dem Abbild werden nur Paket, aktiv, Wiedergabezustand und die Kurzbeschreibung der Metadaten
 * ({@code metadata: size=N, description=Titel, Interpret, Album}) gelesen. Nichts wird gesteuert, gespeichert,
 * protokolliert oder gesendet; die Antwort bekommt nur die Launcher-APK über Loopback. Parser und Wahl sind rein und
 * im Unit-Test belegt; das Abbild bei laufender CarPlay-Musik ist am Auto noch ungeprüft.
 */
public final class LauncherMusik {

    private LauncherMusik() {}

    public static final String PFAD = "/api/launcher/v1/musik";
    /** Zwischenspeicher: der Launcher fragt im Sekundentakt, dumpsys läuft höchstens so oft. */
    public static final long CACHE_MS = 1_500L;
    static final int MAX_BYTES = 512 * 1024;
    static final int STATE_PLAYING = 3;
    /** Wie {@code MedienWahl} im Launcher: Projektion vor dem Rest, Telecom nie. */
    static final List<String> PROJEKTION = Collections.unmodifiableList(Arrays.asList("com.byd.carplay.ui", "com.byd.androidauto"));
    static final String TELECOM = "com.android.server.telecom";

    /** Eine Sitzung aus dem Abbild. */
    static final class Sitzung {
        String paket;
        boolean aktiv;
        int zustand = -1;
        String beschreibung;
    }

    /** Sitzungen aus {@code dumpsys media_session}. Jede beginnt mit der Zeile {@code ownerPid=…}. */
    static List<Sitzung> parse(String abbild) {
        List<Sitzung> liste = new ArrayList<>();
        if (abbild == null) return liste;
        Sitzung s = null;
        for (String roh : abbild.split("\n")) {
            String z = roh.trim();
            if (z.startsWith("ownerPid=")) {
                s = new Sitzung();
                liste.add(s);
            } else if (s == null) {
                continue;
            } else if (z.startsWith("package=")) {
                s.paket = z.substring(8).trim();
            } else if (z.startsWith("active=")) {
                s.aktiv = "true".equals(z.substring(7).trim());
            } else if (z.startsWith("state=")) {
                s.zustand = zustand(z);
            } else if (z.startsWith("metadata:") || z.startsWith("metadata=")) {
                int i = z.indexOf("description=");
                s.beschreibung = i < 0 ? null : z.substring(i + 12);
            }
        }
        return liste;
    }

    private static int zustand(String z) {
        int i = z.indexOf("state=", 6);   // „state=PlaybackState {state=3, …“
        if (i < 0) return -1;
        int a = i + 6, e = a;
        while (e < z.length() && Character.isDigit(z.charAt(e))) e++;
        try {
            return e > a ? Integer.parseInt(z.substring(a, e)) : -1;
        } catch (NumberFormatException x) {
            return -1;
        }
    }

    /** Wahl wie im Launcher: spielend > CarPlay/Android Auto > zuletzt genutztes Paket > erste. Nur aktive, nie Telecom. */
    static Sitzung waehle(List<Sitzung> alle, String fokusPaket) {
        List<Sitzung> b = new ArrayList<>();
        for (Sitzung s : alle) if (s.aktiv && s.paket != null && !s.paket.isEmpty() && !TELECOM.equals(s.paket)) b.add(s);
        for (Sitzung s : b) if (s.zustand == STATE_PLAYING) return s;
        for (String p : PROJEKTION) for (Sitzung s : b) if (p.equals(s.paket)) return s;
        if (fokusPaket != null) for (Sitzung s : b) if (fokusPaket.equals(s.paket)) return s;
        return b.isEmpty() ? null : b.get(0);
    }

    /**
     * Titel und Interpret aus {@code MediaDescription.toString()} = „Titel, Untertitel, Beschreibung“ (Android füllt
     * die drei Felder der Reihe nach aus TITLE, ARTIST, ALBUM …). Genau drei Teile: eindeutig. Mehr Teile (ein Komma
     * im Titel oder Namen): nicht raten, die ganze Zeile als Titel, kein Interpret.
     */
    static String[] titelKuenstler(String beschreibung) {
        if (beschreibung == null) return new String[]{null, null};
        String[] t = beschreibung.split(", ", -1);
        if (t.length == 3) return new String[]{wert(t[0]), wert(t[1])};
        List<String> rest = new ArrayList<>(Arrays.asList(t));
        while (!rest.isEmpty() && wert(rest.get(rest.size() - 1)) == null) rest.remove(rest.size() - 1);
        return new String[]{wert(join(rest)), null};
    }

    private static String join(List<String> t) {
        StringBuilder b = new StringBuilder();
        for (String s : t) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.toString();
    }

    private static String wert(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || "null".equals(t) ? null : t;
    }

    /** Ist das Abbild echt (Dienst geantwortet, keine Rechteverweigerung)? */
    static boolean lesbar(String abbild) {
        return abbild != null && !abbild.contains("Permission Denial")
                && (abbild.contains("MEDIA SESSION SERVICE") || abbild.contains("Sessions Stack"));
    }

    /** Antwort {@code {ok, lesbar, paket, titel, kuenstler, spielt}}; ohne Sitzung paket = null. */
    static JSONObject antwort(String abbild, String fokusPaket) throws JSONException {
        JSONObject o = new JSONObject().put("ok", true);
        if (!lesbar(abbild)) return o.put("lesbar", false);
        o.put("lesbar", true);
        Sitzung s = waehle(parse(abbild), fokusPaket);
        if (s == null) return o.put("paket", JSONObject.NULL);
        String[] tk = titelKuenstler(s.beschreibung);
        return o.put("paket", s.paket)
                .put("titel", tk[0] == null ? JSONObject.NULL : tk[0])
                .put("kuenstler", tk[1] == null ? JSONObject.NULL : tk[1])
                .put("spielt", s.zustand == STATE_PLAYING);
    }

    private static long cacheZeit;
    private static String cacheAbbild;

    /** Mit Zwischenspeicher; dumpsys läuft nie öfter als alle {@link #CACHE_MS}. Nie schreibend. */
    public static synchronized JSONObject lesen(long jetztMs, String fokusPaket) throws JSONException {
        if (cacheAbbild == null || jetztMs - cacheZeit >= CACHE_MS || jetztMs < cacheZeit) {
            cacheZeit = jetztMs;
            cacheAbbild = abbild();
        }
        return antwort(cacheAbbild, fokusPaket);
    }

    private static String abbild() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            InputStream in = p.getInputStream();
            long ende = System.currentTimeMillis() + 3_000L;
            int n;
            while ((n = in.read(b)) > 0) {
                if (out.size() < MAX_BYTES) out.write(b, 0, Math.min(n, MAX_BYTES - out.size()));
                if (System.currentTimeMillis() > ende) break;
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) { }
        }
    }
}
