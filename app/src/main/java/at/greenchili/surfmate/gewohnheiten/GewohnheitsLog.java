package at.greenchili.surfmate.gewohnheiten;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2): das Log im Auto – eine JSON-Zeile je Bedienhandlung, nur lokal (Auto ist die
 * Quelle). Aufbewahrung in Tagen plus Obergrenze an Zeilen; „Alles vergessen“ löscht die Datei. Kaputte Zeilen werden
 * beim Lesen übersprungen und beim Kürzen verworfen.
 */
public final class GewohnheitsLog {

    public static final int AUFBEWAHRUNG_TAGE = 60, TAGE_MIN = 14, TAGE_MAX = 180;
    public static final int MAX_ZEILEN = 5000;
    static final long TAG_MS = 86_400_000L;

    private final File datei;

    public GewohnheitsLog(File datei) {
        this.datei = datei;
    }

    public synchronized void schreibe(JSONObject eintrag) {
        File dir = datei.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(datei, true), StandardCharsets.UTF_8)) {
            w.write(eintrag.toString().replace('\n', ' '));
            w.write('\n');
        } catch (Exception ignored) {
            // Mitschreiben darf nie eine Bedienung stören
        }
    }

    /** Alle lesbaren Einträge, älteste zuerst. */
    public synchronized List<JSONObject> lies() {
        List<JSONObject> l = new ArrayList<>();
        if (!datei.isFile()) return l;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(datei), StandardCharsets.UTF_8))) {
            String z;
            while ((z = r.readLine()) != null) {
                z = z.trim();
                if (z.isEmpty()) continue;
                try {
                    l.add(new JSONObject(z));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    /** Älter als {@code tage} und über {@code maxZeilen} hinaus verwerfen; liefert die Zahl der verworfenen Zeilen. */
    public synchronized int kuerze(long jetzt, int tage, int maxZeilen) {
        if (!datei.isFile()) return 0;
        List<JSONObject> alle = lies();
        long grenze = jetzt - Math.max(TAGE_MIN, Math.min(TAGE_MAX, tage)) * TAG_MS;
        List<JSONObject> bleibt = new ArrayList<>();
        for (JSONObject e : alle) if (e.optLong("t", 0) >= grenze) bleibt.add(e);
        if (bleibt.size() > maxZeilen) bleibt = new ArrayList<>(bleibt.subList(bleibt.size() - maxZeilen, bleibt.size()));
        int weg = zeilen() - bleibt.size();
        if (weg <= 0) return 0;
        File neu = new File(datei.getPath() + ".neu");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(neu, false), StandardCharsets.UTF_8)) {
            for (JSONObject e : bleibt) {
                w.write(e.toString());
                w.write('\n');
            }
        } catch (Exception e) {
            neu.delete();
            return 0;
        }
        if (!neu.renameTo(datei)) {
            datei.delete();
            neu.renameTo(datei);
        }
        return weg;
    }

    public synchronized void loesche() {
        datei.delete();
        new File(datei.getPath() + ".neu").delete();
    }

    private int zeilen() {
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(datei), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) n++;
        } catch (Exception ignored) {
        }
        return n;
    }
}
