package at.greenchili.surfmate.recording;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 0.1.28 P5 (Projektinhaber 29.10. „ja“): Ereignis-Clip aufs EIGENE Handy – nur auf Knopfdruck, nur durch die Brücke
 * (Medien-Kanal, Ende-zu-Ende), nie automatisch, nie auf einen Server, nie an Dritte. CLIP_VERTRAG_0128.md.
 *
 * <ul>
 *   <li>{@link #freigeben}: ein Knopfdruck am gekoppelten Handy = eine Freigabe für genau einen Clip und genau diese
 *       Kopplung. Ergebnis ist ein Einmal-Schein (128 bit, {@link #SCHEIN_MS} gültig, zum Fortsetzen nach Abbruch).</li>
 *   <li>{@link #einloesen}: jede Byte-Anfrage braucht den Schein derselben Kopplung und desselben Clips.</li>
 *   <li>Jede Freigabe, jede Ablehnung und jede fertige Übertragung steht im Export-Protokoll (nur im Auto, ohne Orte).</li>
 * </ul>
 * Rein (Uhr und Speicher von außen), im Test ohne Android.
 */
public final class ClipExport {

    /** So lange gilt ein Schein (Fortsetzen nach Funkloch); danach braucht es einen neuen Knopfdruck. */
    public static final long SCHEIN_MS = 30 * 60_000L;
    /** Höchstens so viele Freigaben je Kopplung und Stunde (Schutz gegen eine fehlerhafte App-Schleife). */
    public static final int MAX_FREIGABEN_STUNDE = 10;
    /** So viele Protokollzeilen bleiben im Auto. */
    public static final int MAX_PROTOKOLL = 500;
    static final int MAX_SCHEINE = 16;

    /** Protokoll ablegen/lesen (im Auto eine JSON-Datei im Daemon-Ordner). */
    public interface Speicher {
        JSONArray lade();
        void speichere(JSONArray zeilen);
    }

    /** Ein gültiger Schein. */
    static final class Schein {
        final String kid, clip, datei;
        final long bis;
        long hoechstesByte = -1;
        boolean fertigGemeldet;

        Schein(String kid, String clip, String datei, long bis) {
            this.kid = kid;
            this.clip = clip;
            this.datei = datei;
            this.bis = bis;
        }
    }

    /** Ergebnis einer Freigabe: {@code schein} oder {@code grund}. */
    public static final class Freigabe {
        public final String schein, grund;
        public final long gueltigBis;

        Freigabe(String schein, String grund, long gueltigBis) {
            this.schein = schein;
            this.grund = grund;
            this.gueltigBis = gueltigBis;
        }
    }

    private final Speicher speicher;
    private final SecureRandom zufall = new SecureRandom();
    private final Map<String, Schein> scheine = new HashMap<>();
    private final Map<String, Deque<Long>> freigaben = new HashMap<>();

    public ClipExport(Speicher speicher) {
        this.speicher = speicher;
    }

    /**
     * Knopfdruck am Handy. {@code knopf} muss true sein (die App schickt es nur nach dem Tippen auf „Aufs Handy laden“
     * und der Rückfrage); {@code datei} ist der Dateiname des Ereignis-Clips (null = kein Ereignis-Clip / nicht da).
     */
    public synchronized Freigabe freigeben(String kid, String geraet, String clipId, String datei, boolean knopf, long jetzt) {
        aufraeumen(jetzt);
        String grund = null;
        if (kid == null || kid.isEmpty()) grund = "kopplung";
        else if (!knopf) grund = "nur_auf_knopfdruck";
        else if (datei == null || !EreignisClip.istEreignis(datei)) grund = "kein_ereignis_clip";
        else if (!rateOk(kid, jetzt)) grund = "zu_oft";
        if (grund != null) {
            protokoll(jetzt, "abgelehnt", kid, geraet, datei, 0, grund);
            return new Freigabe(null, grund, 0);
        }
        // pro Kopplung und Clip nur ein gültiger Schein: ein neuer Knopfdruck ersetzt den alten
        for (Iterator<Map.Entry<String, Schein>> it = scheine.entrySet().iterator(); it.hasNext(); ) {
            Schein s = it.next().getValue();
            if (s.kid.equals(kid) && s.clip.equals(clipId)) it.remove();
        }
        if (scheine.size() >= MAX_SCHEINE) aeltestenWeg();
        byte[] b = new byte[16];
        zufall.nextBytes(b);
        String schein = hex(b);
        long bis = jetzt + SCHEIN_MS;
        scheine.put(schein, new Schein(kid, clipId, datei, bis));
        freigaben.computeIfAbsent(kid, k -> new ArrayDeque<>()).addLast(jetzt);
        protokoll(jetzt, "freigabe", kid, geraet, datei, 0, null);
        return new Freigabe(schein, null, bis);
    }

    /** Ablehnung vor der Freigabe-Prüfung (z. B. Schalter aus) – steht ebenfalls im Protokoll. */
    public synchronized void abgelehnt(String kid, String geraet, String grund, long jetzt) {
        protokoll(jetzt, "abgelehnt", kid, geraet, null, 0, grund);
    }

    /** Schein prüfen: Dateiname des freigegebenen Clips oder null (falsch, fremd, abgelaufen). */
    public synchronized String einloesen(String kid, String schein, String clipId, long jetzt) {
        aufraeumen(jetzt);
        if (kid == null || schein == null || clipId == null) return null;
        Schein s = null;
        for (Map.Entry<String, Schein> e : scheine.entrySet()) {
            if (gleich(e.getKey(), schein)) s = e.getValue();
        }
        if (s == null || !s.kid.equals(kid) || !s.clip.equals(clipId)) return null;
        return s.datei;
    }

    /**
     * Ein Bereich {@code [von, bis]} einer Datei mit {@code laenge} Byte ging hinaus. Erreicht er das letzte Byte zum
     * ersten Mal, steht „übertragen“ im Protokoll (einmal je Schein).
     */
    public synchronized void bereichGesendet(String schein, String geraet, long von, long bis, long laenge, long jetzt) {
        for (Map.Entry<String, Schein> e : scheine.entrySet()) {
            if (!gleich(e.getKey(), schein)) continue;
            Schein s = e.getValue();
            s.hoechstesByte = Math.max(s.hoechstesByte, bis);
            if (!s.fertigGemeldet && laenge > 0 && bis >= laenge - 1) {
                s.fertigGemeldet = true;
                protokoll(jetzt, "uebertragen", s.kid, geraet, s.datei, laenge, null);
            }
        }
    }

    /** Neueste zuerst, höchstens {@code n} Zeilen. */
    public synchronized JSONArray protokoll(int n) {
        JSONArray alle = speicher.lade();
        JSONArray r = new JSONArray();
        for (int i = alle.length() - 1; i >= 0 && r.length() < n; i--) r.put(alle.opt(i));
        return r;
    }

    private boolean rateOk(String kid, long jetzt) {
        Deque<Long> d = freigaben.get(kid);
        if (d == null) return true;
        while (!d.isEmpty() && d.peekFirst() < jetzt - 3_600_000L) d.removeFirst();
        return d.size() < MAX_FREIGABEN_STUNDE;
    }

    private void aufraeumen(long jetzt) {
        scheine.values().removeIf(s -> s.bis < jetzt);
    }

    private void aeltestenWeg() {
        String weg = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<String, Schein> e : scheine.entrySet()) {
            if (e.getValue().bis < min) {
                min = e.getValue().bis;
                weg = e.getKey();
            }
        }
        if (weg != null) scheine.remove(weg);
    }

    private void protokoll(long jetzt, String was, String kid, String geraet, String datei, long bytes, String grund) {
        try {
            JSONObject z = new JSONObject();
            z.put("zeit", jetzt);
            z.put("was", was);
            if (kid != null) z.put("kopplung", kid);
            if (geraet != null && !geraet.isEmpty()) z.put("geraet", geraet);
            if (datei != null) z.put("datei", datei);
            if (bytes > 0) z.put("bytes", bytes);
            if (grund != null) z.put("grund", grund);
            JSONArray alle = speicher.lade();
            List<Object> l = new ArrayList<>();
            for (int i = Math.max(0, alle.length() - MAX_PROTOKOLL + 1); i < alle.length(); i++) l.add(alle.opt(i));
            l.add(z);
            speicher.speichere(new JSONArray(l));
        } catch (Exception ignored) {
            // Protokoll darf die Antwort nie verhindern; Freigabe ohne Protokoll gibt es trotzdem nicht,
            // weil der Daemon-Log (Aufrufer) jede Freigabe zusätzlich schreibt.
        }
    }

    static boolean gleich(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile ClipExport instanz;

    /** Im Daemon: Protokoll als JSON-Datei {@code clip_export.json} im Daemon-Ordner. */
    public static ClipExport get() {
        ClipExport i = instanz;
        if (i == null) {
            synchronized (ClipExport.class) {
                if (instanz == null) instanz = new ClipExport(new DateiSpeicher());
                i = instanz;
            }
        }
        return i;
    }

    static final class DateiSpeicher implements Speicher {
        private final java.io.File datei = new java.io.File(
                at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate"),
                "clip_export.json");

        @Override
        public JSONArray lade() {
            try {
                if (!datei.isFile()) return new JSONArray();
                byte[] b = java.nio.file.Files.readAllBytes(datei.toPath());
                return new JSONArray(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        @Override
        public void speichere(JSONArray zeilen) {
            try {
                java.io.File tmp = new java.io.File(datei.getPath() + ".tmp");
                java.nio.file.Files.write(tmp.toPath(), zeilen.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!tmp.renameTo(datei)) {
                    java.nio.file.Files.move(tmp.toPath(), datei.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
