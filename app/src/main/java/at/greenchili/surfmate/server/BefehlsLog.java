package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 0.1.34 Befehls-Log (Anlass 30.10. 20:26, Entriegeln ohne klare Herkunft): WER hat WAS WANN gemacht – dauerhaft, je
 * Kopplung. Eine JSON-Zeile je schreibender Anfrage auf {@code /api/*} nach der Anmeldung (auch abgelehnte) und je
 * Fahrzeug- oder Kopplungs-Ereignis ohne Anfrage (Telegram, Automation, MQTT).
 *
 * <p>Inhalt: Zeit, Kopplungs-id + Name (oder {@code display}/{@code login}), Art, Weg (lokal | direkt | bruecke), Pfad
 * (wie {@link SchreibProtokoll}, Werte zu {@code {x}}), Aktion aus einer festen Liste, Ziel-Kopplung und Rechte,
 * Namen geänderter Einstellungen, ausgeführte Fahrzeugbefehle, Ergebnis (ok | abgelehnt | fehler) mit Grund.
 * <b>Nie</b>: Rumpf, Werte, Schlüssel, Tokens, Passkey-Daten, Koordinaten oder Orte – aus dem Rumpf wird nur gelesen,
 * was auf einer Erlaubnisliste steht.
 *
 * <p>Ablage: zwei Ringdateien je Klasse ({@code pflicht} = Fahrzeug, Kopplung, Rechte, Einstellungen, Update,
 * Ablehnungen; {@code andere} = übrige Schreibzugriffe), je {@link #MAX_BYTES} plus eine Vorgängerdatei, Einträge
 * älter als {@link #TAGE} Tage fallen beim Lesen und Rotieren weg. Viel Kleinkram kann so nie die Pflicht-Einträge
 * verdrängen. Lesbar über {@code GET /api/system/befehle} (nur am Auto oder mit Login, nie für Kopplungen).
 */
public final class BefehlsLog {

    static final int TAGE = 30;
    static final long TAG_MS = 86_400_000L;
    static final long MAX_BYTES = 256 * 1024;
    static final String PFLICHT = "pflicht", ANDERE = "andere";
    /** Höchstens so viele Einträge je Abruf vom Handy (Koordination 03.11.). */
    public static final int MAX_HANDY = 200;

    public static final String OK = "ok", ABGELEHNT = "abgelehnt", FEHLER = "fehler";

    /** Aktionen von {@code /api/handy/v1/aktion} (HandyApiHandler §3); alles andere wird {@code unbekannt}. */
    static final Set<String> AKTIONEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "aufsperren", "zusperren", "klima_an", "klima_aus", "sitzheizung", "akku_heizen", "heizen_stoppen",
            "wallbox", "route", "route_stand", "plan_vorschau", "plan_vorschau_stand", "update_starten", "tiermodus", "tiermodus_stand")));

    /** Pfade, deren Schreibzugriffe immer zu {@link #PFLICHT} gehören. */
    static final String[] PFLICHT_PFADE = {
            "/api/handy/v1/aktion", "/api/vehicle", "/api/bydcloud", "/api/quick-controls", "/api/keymap",
            "/api/kopplung", "/api/einstellungen", "/api/appupdate/", "/api/update/", "/api/ha/",
            "/api/telegram-anmeldung/", "/api/cloud-uebergabe", "/api/notzugang", "/api/sitzung/", "/api/bruecke",
            "/api/wireguard", "/api/tailscale", "/api/backup/",
            "/api/tiermodus",   // 0.1.36: Tiermodus schaltet das Klima
            "/api/display/", "/api/apps/verwaltung/", "/api/dateien/wechsel/",   // 0.1.37 Display-Werkzeuge
            "/api/tacho/navi/fahrt", "/api/tacho/normal",   // 0.1.37 Tacho: auch beim Fahren, wieder normal
            "/api/launcher/v1/klima", "/api/launcher/v1/update", "/api/launcher/v1/tacho/",   // 0.1.37: Launcher-Klima und -Updates
            "/api/tacho/auswahl",   // 0.1.37 „Was zeigt der Tacho?“
            "/api/tacho/groesse",   // 0.1.38.11 Größe im Fahrerdisplay
            "/api/launcher/v1/layout"};   // 0.1.38: Launcher-Layout (ohne Inhalt)

    /** Gründe, die ein technischer Fehler sind (sonst gilt ok:false als Ablehnung). */
    static final Set<String> FEHLER_GRUENDE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "fehlgeschlagen", "cloud_nicht_erreichbar", "server_nicht_erreichbar", "nicht_gespeichert", "intern")));

    /** Ein Grund ist nur ein kurzes Kennwort, nie Freitext (der könnte Werte enthalten). */
    private static final Pattern KENNWORT = Pattern.compile("[a-z][a-z0-9_.-]{0,39}");
    /** Kopplungs-ids und Namen von Einstellungen: kurz, ohne Leer- und Sonderzeichen. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern FELD = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    /** Feldnamen, die nach Ort aussehen, tauchen nicht einmal als Name auf. */
    private static final Pattern ORTSNAME = Pattern.compile(
            "(?i)(.*(^|[._-])(lat|lon|lng|latitude|longitude)([._-]|$).*)|(.*(koordinat|standort|adresse|position).*)");

    private BefehlsLog() {}

    // ------------------------------------------------------------------ Anfrage-Kontext (je Thread)

    /** Eine laufende, mitzuschreibende Anfrage. */
    static final class Anfrage {
        final long t;
        final String methode, pfad, wer, name, art, weg;
        String aktion, ziel, zielName, grund;
        List<String> rechte, felder;
        final List<String> ereignisse = new ArrayList<>();
        boolean fehler;
        Mitschnitt antwort;

        Anfrage(long t, String methode, String pfad, String wer, String name, String art, String weg) {
            this.t = t;
            this.methode = methode;
            this.pfad = pfad;
            this.wer = wer;
            this.name = name;
            this.art = art;
            this.weg = weg;
        }
    }

    private static final ThreadLocal<Anfrage> LAUFEND = new ThreadLocal<>();

    /**
     * Nach der Anmeldung, vor ExternGuard: Anfrage merken, wenn sie schreibt ({@code POST/PUT/PATCH/DELETE} auf
     * {@code /api/*}). Aus dem Rumpf werden nur Felder der Erlaubnisliste gelesen ({@link #ausRumpf}).
     *
     * @param kid       Kopplungs-id (Kopplungs-JWT oder Web-Sitzung dahinter), sonst null; Art ({@code handy},
     *                  {@code dienst}, {@code homeassistant}, {@code cockpit}) und Name kommen aus der Kopplungsliste
     * @param web       Web-Sitzung der Handy-App (Art {@code web})
     * @param direkt    Autobildschirm selbst (Loopback, kein Tunnel)
     * @param bruecke   kam über die Panoptes-Brücke ({@code Forwarded: for=panoptes-bruecke})
     * @param out       Antwort-Strom; ein {@link Mitschnitt} liefert Status und Grund fürs Ergebnis
     */
    public static void beginne(String methode, String path, String body, String kid, boolean web, boolean direkt,
                               boolean bruecke, OutputStream out, long nowMs) {
        LAUFEND.remove();
        if (SchreibProtokoll.pfadOhneWerte(methode, path) == null) return;
        try {
            at.greenchili.surfmate.auth.Kopplungen k = at.greenchili.surfmate.auth.Kopplungen.get();
            beginne(methode, path, body, kid, kid == null ? null : web ? "web" : k.art(kid),
                    kid == null ? null : k.anzeigeName(kid), direkt, bruecke, out, nowMs);
            Anfrage r = LAUFEND.get();
            if (r != null && r.ziel != null) r.zielName = sauber(k.anzeigeName(r.ziel));
        } catch (Throwable ignored) {
        }
    }

    /** Wie oben, Art und Name schon bekannt (Tests ohne Kopplungsliste). */
    static void beginne(String methode, String path, String body, String kid, String art, String name,
                        boolean direkt, boolean bruecke, OutputStream out, long nowMs) {
        LAUFEND.remove();
        try {
            String pfad = SchreibProtokoll.pfadOhneWerte(methode, path);
            if (pfad == null) return;
            String wer = kid != null ? kid : direkt ? "display" : "login";
            String a = kid != null ? (art == null || art.isEmpty() ? "kopplung" : art) : direkt ? "display" : "login";
            String weg = bruecke ? "bruecke" : direkt ? "lokal" : "direkt";
            Anfrage r = new Anfrage(nowMs, methode.toUpperCase(Locale.ROOT), pfad, wer, kid == null ? null : sauber(name),
                    a, weg);
            ausRumpf(r, pfad, body);
            if (out instanceof Mitschnitt) r.antwort = (Mitschnitt) out;
            LAUFEND.set(r);
        } catch (Throwable ignored) {
            // Mitschreiben darf nie eine Anfrage stören
        }
    }

    /** Aktion der laufenden Anfrage (für die SCHREIBEN-Zeile im cam_daemon.log), sonst null. */
    public static String aktion() {
        Anfrage r = LAUFEND.get();
        return r == null ? null : r.aktion;
    }

    /** Grund einer Ablehnung durch einen Wächter (vor dem Handler), z. B. {@code nicht_fuer_kopplung}. */
    public static void grund(String grund) {
        Anfrage r = LAUFEND.get();
        if (r != null && grund != null && KENNWORT.matcher(grund).matches()) r.grund = grund;
    }

    /**
     * Fahrzeugbefehl oder Kopplungs-Ereignis: hängt an der laufenden Anfrage, sonst eigener Eintrag mit Herkunft des
     * Threads ({@code telegram}, {@code automation}, {@code ha} …). {@code was} und {@code ergebnis} sind Kennwörter.
     */
    public static void ereignis(String was, String ergebnis, String ziel, String zielName) {
        try {
            if (was == null) return;
            String e = was + (ergebnis == null ? "" : ":" + ergebnis);
            Anfrage r = LAUFEND.get();
            if (r != null) {
                if (r.ereignisse.size() < 8) r.ereignisse.add(e);
                if (ziel != null && r.ziel == null && ID.matcher(ziel).matches()) r.ziel = ziel;
                if (ziel != null && ziel.equals(r.ziel) && r.zielName == null) r.zielName = sauber(zielName);
                if (FEHLER.equals(ergebnisKlasse(ergebnis))) r.fehler = true;
                return;
            }
            JSONObject z = new JSONObject();
            z.put("t", System.currentTimeMillis());
            String q = at.greenchili.surfmate.gewohnheiten.Herkunft.aktuell();
            z.put("wer", q).put("art", "intern").put("weg", "lokal");
            z.put("ereignisse", new JSONArray().put(e));
            if (ziel != null && ID.matcher(ziel).matches()) {
                z.put("ziel", ziel);
                if (zielName != null) z.put("zielName", sauber(zielName));
            }
            z.put("ergebnis", ergebnisKlasse(ergebnis));
            schreibe(PFLICHT, z);
        } catch (Throwable ignored) {
        }
    }

    /** Ergebnis eines Fahrzeugbefehls (Router) als Kennwort. */
    public static void fahrzeug(String befehl, String outcome, String weg) {
        String o = outcome == null ? "null" : outcome.toLowerCase(Locale.ROOT);
        String w = weg == null ? null : weg.toLowerCase(Locale.ROOT);
        ereignis("fahrzeug." + (befehl == null ? "?" : befehl.replaceAll("[^a-z0-9_-]", "")),
                o + (w == null || "none".equals(w) ? "" : "@" + w), null, null);
    }

    /** Herkünfte einer Assistenz-Abschaltung (sonst {@code automation}). */
    static final Set<String> HERKUENFTE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "nutzer", "handy", "automation", "ha", "telegram")));

    /**
     * 0.1.37: Abschaltung eines Assistenzsystems (auch abgelehnte) als eigene Pflicht-Zeile mit Herkunft. In einer
     * laufenden Anfrage übernimmt die Zeile Gerät und Weg der Anfrage, sonst {@code intern} mit der Herkunft als
     * {@code wer}. Nur Kennwörter: System-Schlüssel, Herkunft, Ausgang, Grund – keine Werte.
     */
    public static void assistenzAus(String system, String herkunft, String outcome, String grund) {
        try {
            String sys = system == null ? "?" : system.replaceAll("[^a-z0-9_]", "");
            if (sys.isEmpty() || sys.length() > 40) sys = "?";
            String h = herkunft != null && HERKUENFTE.contains(herkunft) ? herkunft : "automation";
            String o = outcome == null ? "null" : outcome.toLowerCase(Locale.ROOT);
            JSONObject z = new JSONObject();
            Anfrage r = LAUFEND.get();
            if (r != null) {
                z.put("t", System.currentTimeMillis()).put("wer", r.wer);
                if (r.name != null) z.put("name", r.name);
                z.put("art", r.art).put("weg", r.weg);
            } else {
                z.put("t", System.currentTimeMillis()).put("wer", h).put("art", "intern").put("weg", "lokal");
            }
            z.put("herkunft", h).put("system", sys);
            z.put("ereignisse", new JSONArray().put("assistenz_aus." + sys + ":" + o));
            String e = ergebnisKlasse(o);
            z.put("ergebnis", e);
            if (!OK.equals(e)) z.put("grund", grund != null && KENNWORT.matcher(grund).matches() ? grund : o);
            schreibe(PFLICHT, z);
        } catch (Throwable ignored) {
        }
    }

    /** {@code success}/{@code ok} → ok, Fehler-Ausgänge → fehler, sonst abgelehnt. */
    static String ergebnisKlasse(String ergebnis) {
        if (ergebnis == null) return OK;
        String e = ergebnis.contains("@") ? ergebnis.substring(0, ergebnis.indexOf('@')) : ergebnis;
        switch (e) {
            case "success":
            case "ok":
            case "erlaubt":
            case "angelegt":
            case "widerrufen":
            case "gesetzt":
            case "gestartet":
                return OK;
            case "failed":
            case "vehicle_unreachable":
            case "null":
            case "fehler":
            case "nur_im_speicher":
                return FEHLER;
            default:
                return ABGELEHNT;
        }
    }

    /** Im {@code finally} der Anfrage: Status und Anfang der Antwort auswerten, Zeile schreiben. */
    public static void abschliessen() {
        Anfrage r = LAUFEND.get();
        LAUFEND.remove();
        if (r == null) return;
        try {
            int status = r.antwort == null ? 0 : r.antwort.status();
            String koerper = r.antwort == null ? null : r.antwort.koerper();
            schreibe(r.ereignisse.isEmpty() && !istPflicht(r, status) ? ANDERE : PFLICHT, eintrag(r, status, koerper));
        } catch (Throwable ignored) {
        }
    }

    static boolean istPflicht(Anfrage r, int status) {
        if (r.aktion != null || status == 401 || status == 403) return true;
        for (String p : PFLICHT_PFADE) if (r.pfad.startsWith(p)) return true;
        return false;
    }

    static JSONObject eintrag(Anfrage r, int status, String koerper) throws Exception {
        JSONObject z = new JSONObject();
        z.put("t", r.t).put("wer", r.wer);
        if (r.name != null) z.put("name", r.name);
        z.put("art", r.art).put("weg", r.weg).put("methode", r.methode).put("pfad", r.pfad);
        if (r.aktion != null) z.put("aktion", r.aktion);
        if (r.ziel != null) z.put("ziel", r.ziel);
        if (r.zielName != null) z.put("zielName", r.zielName);
        if (r.rechte != null) z.put("rechte", new JSONArray(r.rechte));
        if (r.felder != null) z.put("felder", new JSONArray(r.felder));
        if (!r.ereignisse.isEmpty()) z.put("ereignisse", new JSONArray(r.ereignisse));
        if (status > 0) z.put("status", status);
        String grund = r.grund;
        Boolean ok = null;
        if (koerper != null) {
            String k = koerper.trim();
            if (k.startsWith("{")) {
                try {
                    JSONObject j = new JSONObject(k);
                    if (j.has("ok")) ok = j.optBoolean("ok", true);
                    else if (j.has("success")) ok = j.optBoolean("success", true);
                    if (grund == null) {
                        for (String f : new String[]{"grund", "error", "fehler"}) {
                            Object v = j.opt(f);
                            if (v instanceof String && KENNWORT.matcher((String) v).matches()) {
                                grund = (String) v;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        String ergebnis;
        if (status >= 500 || r.fehler) ergebnis = FEHLER;
        else if (status >= 400) ergebnis = ABGELEHNT;
        else if (Boolean.FALSE.equals(ok)) ergebnis = grund != null && FEHLER_GRUENDE.contains(grund) ? FEHLER : ABGELEHNT;
        else if (status == 0) ergebnis = FEHLER;   // keine Antwort geschrieben (Ausnahme im Handler)
        else ergebnis = OK;
        if (!OK.equals(ergebnis) && grund == null) grund = status > 0 ? "http_" + status : "keine_antwort";
        z.put("ergebnis", ergebnis);
        if (!OK.equals(ergebnis)) z.put("grund", grund);
        return z;
    }

    /** Nur Erlaubtes aus dem Rumpf: Aktion, Ziel-Kopplung, Rechte-Namen, Namen geänderter Einstellungen. */
    static void ausRumpf(Anfrage r, String pfad, String body) {
        boolean aktion = pfad.equals("/api/handy/v1/aktion");
        boolean rechte = pfad.equals("/api/kopplung/rechte");
        boolean widerruf = pfad.equals("/api/kopplung/widerrufen");
        boolean anfrage = pfad.startsWith("/api/kopplung/anfrage/") && pfad.endsWith("/erlauben");
        boolean einstellungen = pfad.equals("/api/einstellungen");
        if (!(aktion || rechte || widerruf || anfrage || einstellungen) || body == null || body.isEmpty()) return;
        JSONObject b;
        try {
            b = new JSONObject(body);
        } catch (Exception e) {
            return;
        }
        if (aktion) {
            String a = b.optString("aktion", "");
            r.aktion = AKTIONEN.contains(a) ? a : "unbekannt";
        }
        if (rechte || widerruf) {
            String id = b.optString("id", "");
            if (ID.matcher(id).matches()) r.ziel = id;
        }
        if (rechte) {
            List<String> l = new ArrayList<>();
            JSONArray a = b.optJSONArray("rechte");
            for (int i = 0; a != null && i < a.length(); i++) {
                String x = a.optString(i);
                if (at.greenchili.surfmate.auth.Kopplungen.RECHTE.contains(x) && !l.contains(x)) l.add(x);
            }
            r.rechte = l;
        }
        if (anfrage) {
            List<String> l = new ArrayList<>();
            for (String x : new String[]{"aufsperren", "wallbox", "update"}) if (b.optBoolean(x, false)) l.add(x);
            if (!l.isEmpty()) r.rechte = l;
        }
        if (einstellungen) {
            JSONObject w = b.optJSONObject("werte");
            if (w == null) return;
            List<String> l = new ArrayList<>();
            java.util.Iterator<String> it = w.keys();
            while (it.hasNext() && l.size() < 20) {
                String k = it.next();
                if (!FELD.matcher(k).matches()) l.add("{x}");
                else if (ORTSNAME.matcher(k).matches()) l.add("{ort}");
                else l.add(k);
            }
            Collections.sort(l);
            r.felder = l;
        }
    }

    /** Anzeigename: höchstens 40 Zeichen, ohne Steuerzeichen. */
    static String sauber(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    // ------------------------------------------------------------------ Antwort mitschneiden

    /** Merkt sich Status und die ersten Bytes der Antwort; reicht alles unverändert weiter. */
    public static final class Mitschnitt extends FilterOutputStream {
        private static final int MAX = 2048;
        private final byte[] anfang = new byte[MAX];
        private int n;

        public Mitschnitt(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws java.io.IOException {
            if (n < MAX) anfang[n++] = (byte) b;
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            int k = Math.min(len, MAX - n);
            if (k > 0) {
                System.arraycopy(b, off, anfang, n, k);
                n += k;
            }
            out.write(b, off, len);
        }

        String text() {
            return new String(anfang, 0, n, StandardCharsets.UTF_8);
        }

        int status() {
            String s = text();
            if (!s.startsWith("HTTP/")) return 0;
            int a = s.indexOf(' ');
            if (a < 0 || s.length() < a + 4) return 0;
            try {
                return Integer.parseInt(s.substring(a + 1, a + 4));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Rumpf, nur wenn unkomprimiertes JSON. */
        String koerper() {
            String s = text();
            int k = s.indexOf("\r\n\r\n");
            if (k < 0) return null;
            String kopf = s.substring(0, k).toLowerCase(Locale.ROOT);
            if (!kopf.contains("application/json") || kopf.contains("content-encoding")) return null;
            return s.substring(k + 4);
        }
    }

    // ------------------------------------------------------------------ Ablage

    private static volatile File ordner;
    private static final Object SPERRE = new Object();

    /** Im Daemon beim Start; ohne Aufruf (Unit-Tests ohne eigenen Ordner) wird nichts geschrieben. */
    public static void starten(File dir) {
        ordner = dir;
    }

    static File ordner() {
        return ordner;
    }

    static void schreibe(String klasse, JSONObject eintrag) {
        File dir = ordner;
        if (dir == null) return;
        synchronized (SPERRE) {
            try {
                if (!dir.isDirectory()) dir.mkdirs();
                File f = new File(dir, klasse + ".jsonl");
                if (f.length() >= MAX_BYTES) rotiere(f);
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(eintrag.toString().replace('\n', ' '));
                    w.write('\n');
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Aktuelle Datei wird zur Vorgängerdatei (die ältere fällt weg). */
    private static void rotiere(File f) {
        File alt = new File(f.getPath() + ".1");
        alt.delete();
        if (!f.renameTo(alt)) f.delete();
    }

    /** Einträge beider Klassen, jüngste zuerst, höchstens {@code max}, nur der letzten {@link #TAGE} Tage. */
    public static JSONObject alsJson(int max, long nowMs) throws Exception {
        List<JSONObject> alle = new ArrayList<>();
        File dir = ordner;
        long grenze = nowMs - TAGE * TAG_MS;
        if (dir != null) {
            synchronized (SPERRE) {
                for (String k : new String[]{PFLICHT, ANDERE}) {
                    File f = new File(dir, k + ".jsonl");
                    File alt = new File(f.getPath() + ".1");
                    // Vorgängerdatei ganz außerhalb der Frist: weg damit (Datenschutz, nicht nur ausblenden)
                    if (alt.isFile() && alt.lastModified() < grenze) alt.delete();
                    lies(alt, k, grenze, alle);
                    lies(f, k, grenze, alle);
                }
            }
        }
        Collections.sort(alle, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        JSONArray a = new JSONArray();
        for (int i = 0; i < alle.size() && i < max; i++) a.put(alle.get(i));
        return new JSONObject().put("success", true).put("tage", TAGE).put("anzahl", alle.size()).put("eintraege", a);
    }

    /** Arten ohne Kopplung dahinter: deren {@code wer} ist keine Kopplungs-id. */
    private static final Set<String> OHNE_KOPPLUNG = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "display", "login", "intern")));

    /**
     * 0.1.34 Abruf für ein gekoppeltes Handy (und die Web-Sitzung dahinter). Ohne Recht „protokoll“ nur die eigenen
     * Einträge, ohne Ziel- und Rechte-Felder. Mit Recht alle; fremde Kopplungs-ids werden durch {@code anderes_geraet}
     * ersetzt (Name und Art bleiben), Ziel-ids fallen weg. Höchstens {@link #MAX_HANDY}.
     */
    public static JSONObject fuerKopplung(String kid, boolean alle, int max, long nowMs) throws Exception {
        int n = Math.max(1, Math.min(MAX_HANDY, max));
        JSONArray roh = alsJson(Integer.MAX_VALUE, nowMs).getJSONArray("eintraege");
        JSONArray a = new JSONArray();
        for (int i = 0; i < roh.length() && a.length() < n; i++) {
            JSONObject e = roh.getJSONObject(i);
            boolean eigen = kid != null && kid.equals(e.optString("wer")) && !OHNE_KOPPLUNG.contains(e.optString("art"));
            if (!eigen && !alle) continue;
            JSONObject z = new JSONObject(e.toString());
            z.remove("ziel");
            if (!alle) {
                z.remove("zielName");
                z.remove("rechte");
                z.remove("felder");
            }
            if (!eigen && !OHNE_KOPPLUNG.contains(z.optString("art"))) z.put("wer", "anderes_geraet");
            z.put("eigen", eigen);
            a.put(z);
        }
        return new JSONObject().put("success", true).put("tage", TAGE).put("nurEigene", !alle).put("eintraege", a);
    }

    private static void lies(File f, String klasse, long grenze, List<JSONObject> ziel) {
        if (!f.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String z;
            while ((z = r.readLine()) != null) {
                z = z.trim();
                if (z.isEmpty()) continue;
                try {
                    JSONObject e = new JSONObject(z);
                    if (e.optLong("t") < grenze) continue;
                    ziel.add(e.put("klasse", klasse));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Für Tests: Kontext der laufenden Anfrage verwerfen. */
    static void vergessenFuerTest() {
        LAUFEND.remove();
    }
}
