package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.10 Doku-Abgleich: Den Bereich „System“ gibt es in der Leiste nicht mehr (seit 0.1.37 heißt er „Einstellungen“).
 * Nutzertexte (Web, Server/Telegram, Android-Strings, alle Sprachen) dürfen deshalb keinen Menüweg „System → …“ mehr
 * nennen. Die Bereichsnamen kommen aus SECTIONS in app-shell.js (eine Quelle): Gibt es dort wieder einen Bereich
 * „System“, ist der Weg erlaubt. Ausnahmen: der Versionsverlauf (alte Versionen hießen so) und XML-Kommentare.
 */
public class MenuewegeTest {

    /** System in den fünf Sprachen, gefolgt von einem Menüpfeil. */
    private static final Pattern SYSTEM_WEG = Pattern.compile("(System|Système|Sistema|Systeem)\\s*(→|&rarr;|->)");

    /** Dateien, die alte Wege bewusst behalten. */
    private static final List<String> AUSNAHMEN = Arrays.asList(
            "web/shared/neuigkeiten.json");   // „Was ist neu“ früherer Versionen, damals hieß der Bereich so

    private static File basis() {
        return new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> dateien(File dir, String... endungen) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.addAll(dateien(f, endungen));
            else for (String e : endungen) if (f.getName().endsWith(e)) out.add(f);
        }
        return out;
    }

    private static List<File> nutzertexte() {
        File b = basis();
        List<File> l = new ArrayList<>();
        l.addAll(dateien(new File(b, "src/main/assets/web"), ".json", ".html", ".js"));
        l.addAll(dateien(new File(b, "src/main/assets/server-i18n"), ".json"));
        for (File res : new File[] {new File(b, "src/main/res"), new File(b, "../panoptes-launcher/src/main/res")}) {
            File[] werte = res.listFiles((d, n) -> n.equals("values") || n.startsWith("values-"));
            if (werte != null) for (File w : werte) l.addAll(dateien(w, ".xml"));
        }
        // Ersatztexte im Code (Messages.text(..., "…")): nur String-Literale zählen, Kommentare nicht
        l.addAll(dateien(new File(b, "src/main/java"), ".java", ".kt"));
        return l;
    }

    /** Nur die String-Literale einer Quelldatei, je Zeile (Zeilennummern bleiben gleich). */
    private static String literale(String quelle) {
        StringBuilder out = new StringBuilder();
        Pattern literal = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
        for (String z : quelle.split("\n", -1)) {
            Matcher m = literal.matcher(z);
            StringBuilder zeile = new StringBuilder();
            while (m.find()) zeile.append(m.group()).append(' ');
            out.append(zeile).append('\n');
        }
        return out.toString();
    }

    private static boolean bereichSystemGibtEs() throws Exception {
        String js = lies(new File(basis(), "src/main/assets/web/shared/app-shell.js"));
        int a = js.indexOf("var SECTIONS = [");
        int e = js.indexOf("var TEILE = {", a);
        assertTrue("SECTIONS gefunden", a >= 0 && e > a);
        Matcher m = Pattern.compile("label: '([^']+)', i18n: 'shell\\.sec_").matcher(js.substring(a, e));
        while (m.find()) if (m.group(1).equals("System")) return true;
        return false;
    }

    @Test
    public void keinMenuewegUeberSystem() throws Exception {
        if (bereichSystemGibtEs()) return;
        List<File> l = nutzertexte();
        assertTrue("Nutzertexte gefunden", l.size() > 20);
        String wurzel = new File(basis(), "src/main/assets").getCanonicalPath().replace('\\', '/') + "/";
        List<String> funde = new ArrayList<>();
        for (File f : l) {
            String pfad = f.getCanonicalPath().replace('\\', '/');
            if (AUSNAHMEN.contains(pfad.replace(wurzel, ""))) continue;
            String t = lies(f);
            if (f.getName().endsWith(".xml")) t = t.replaceAll("(?s)<!--.*?-->", "");
            if (f.getName().endsWith(".java") || f.getName().endsWith(".kt")) t = literale(t);
            String[] zeilen = t.split("\n", -1);
            for (int i = 0; i < zeilen.length; i++) {
                Matcher m = SYSTEM_WEG.matcher(zeilen[i]);
                if (m.find()) funde.add(f.getName() + ":" + (i + 1) + "  …" + zeilen[i].substring(Math.max(0, m.start() - 40),
                        Math.min(zeilen[i].length(), m.end() + 30)).trim());
            }
        }
        assertTrue("Menüweg „System → …“ gibt es nicht mehr, bitte über „Einstellungen → Alle Einstellungen → …“:\n"
                + String.join("\n", funde), funde.isEmpty());
    }

    @Test
    public void musterErkenntAlleSprachenUndNurMitPfeil() {
        for (String s : new String[] {"unter System → App-Updates", "sous Système → Source", "in Sistema → Fonte",
                "onder Systeem → Kaartbron", "System &rarr; X", "System -> X"}) {
            assertTrue(s, SYSTEM_WEG.matcher(s).find());
        }
        for (String s : new String[] {"System", "« Système »", "Systemeingriffe", "Tab System"}) {
            assertTrue(s, !SYSTEM_WEG.matcher(s).find());
        }
    }
}
