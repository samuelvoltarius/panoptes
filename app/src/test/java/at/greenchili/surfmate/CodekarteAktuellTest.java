package at.greenchili.surfmate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 0.1.31 Codekarte („das muss aktuell gehalten werden“): docs/CODEKARTE.md bleibt die erste Anlaufstelle jeder
 * Sitzung. Dieser Test bricht, sobald Karte und Code auseinanderlaufen: Paket, Handler, API-Pfad oder Web-Seite fehlt,
 * eine genannte Datei ist weg, die Stand-Zeile passt nicht zur Version, oder es steht etwas Privates drin.
 *
 * Im öffentlichen Snapshot fehlen docs/ und deploy/server (export_public); dort setzt der Test aus, statt rot zu sein.
 */
public class CodekarteAktuellTest {

    private static final String QUELLE = "app/src/main/java/at/greenchili/surfmate";
    private static final String ASSETS = "app/src/main/assets";
    private static final int MAX_ZEILEN = 450;

    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");
    /** Mindestens ein Kleinbuchstabe: schließt ENTWICKLUNG, GET und RECHT_* aus. */
    private static final Pattern KLASSE = Pattern.compile("[A-Z][a-z0-9]+[A-Za-z0-9]*");
    private static final Pattern DATEI = Pattern.compile("[A-Za-z0-9_./-]+\\.(java|kt|sh|py|ps1|md|json|html|js|css|cpp|txt|kts|yml|lineage)");
    private static final Pattern ROUTE = Pattern.compile("path\\.(?:startsWith|equals)\\(\"(/api/[^\"]*)\"\\)");
    private static final Pattern STAND = Pattern.compile("\\*\\*Stand: Version (\\d[0-9.]*), Commit ([0-9a-f]{8})\\*\\*");

    /**
     * Wie VERBOTEN in export_public, dazu Adressen, Koordinaten und Zeilennummern. Die Wörter sind getrennt geschrieben,
     * weil diese Datei selbst in den Snapshot geht und die Prüfung dort sonst hier anschlagen würde.
     */
    private static final Pattern[] VERBOTEN = {
            Pattern.compile("\\b[1-9]\\d{0,2}(\\.\\d{1,3}){3}\\b"),   // IPv4; Versionen beginnen mit 0
            Pattern.compile("\\b(g" + "x10[-\\w]*|tgbr" + "utus|VM ?1" + "30|n" + "s[12]|Spark|serveone)\\b"),
            Pattern.compile("\\b[A-Z]:[\\\\/](Users|surfmate|os|tools|panoptes|byd)"),
            Pattern.compile("/home/[a-z]"),
            Pattern.compile("\\bAl" + "fred\\b|\\balf" + "_a\\b"),
            Pattern.compile("\\b\\d{1,3}[.,]\\d{4,}\\b"),              // Koordinaten
            Pattern.compile("\\.(java|kt|sh|py|js|html)[:#]L?\\d+"),   // Zeilennummern veralten
    };

    private File wurzel;
    private String karte;

    @Before
    public void lade() throws IOException {
        File start = new File("").getAbsoluteFile();
        wurzel = new File(start, "settings.gradle.kts").isFile() ? start : start.getParentFile();
        Assume.assumeTrue("nur im Arbeits-Repo, nicht im öffentlichen Snapshot",
                new File(wurzel, "deploy/server").isDirectory());
        File f = new File(wurzel, "docs/CODEKARTE.md");
        assertTrue("docs/CODEKARTE.md fehlt", f.isFile());
        karte = lies(f);
    }

    private static String lies(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void keineFehler(String was, Set<String> fehlend) {
        assertTrue(was + " (docs/CODEKARTE.md nachziehen): " + fehlend, fehlend.isEmpty());
    }

    private List<Path> quellDateien() throws IOException {
        List<Path> r = new ArrayList<>();
        try (Stream<Path> s = Files.walk(new File(wurzel, QUELLE).toPath())) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".java") || n.endsWith(".kt")) r.add(p);
            });
        }
        return r;
    }

    private static String ohneEndung(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    @Test
    public void standZeilePasstZurVersion() throws IOException {
        Matcher m = STAND.matcher(karte);
        assertTrue("Stand-Zeile „**Stand: Version x, Commit 8 Zeichen**“ fehlt", m.find());
        String gradle = lies(new File(wurzel, "app/build.gradle.kts"));
        Matcher v = Pattern.compile("surfmateVersionName\"\\) as\\? String\\) \\?: \"([^\"]+)\"").matcher(gradle);
        assertTrue("versionName in app/build.gradle.kts nicht gefunden", v.find());
        assertEquals("Stand-Zeile der Karte beim Versionssprung nachziehen", v.group(1), m.group(1));
    }

    @Test
    public void allePaketeStehenInDerKarte() throws IOException {
        Path basis = new File(wurzel, QUELLE).toPath();
        Set<String> fehlend = new TreeSet<>();
        for (Path p : quellDateien()) {
            Path ordner = basis.relativize(p.getParent());
            String paket = ordner.toString().replace('\\', '/');
            if (paket.isEmpty()) continue;
            if (!karte.contains("`" + paket + "/`")) fehlend.add(paket + "/");
        }
        keineFehler("Pakete fehlen", fehlend);
    }

    @Test
    public void alleHandlerStehenInDerKarte() throws IOException {
        Set<String> fehlend = new TreeSet<>();
        for (Path p : quellDateien()) {
            String n = ohneEndung(p.getFileName().toString());
            if (n.endsWith("ApiHandler") && !karte.contains("`" + n + "`")) fehlend.add(n);
        }
        keineFehler("Handler fehlen", fehlend);
    }

    @Test
    public void alleRoutenStehenInDerKarte() throws IOException {
        Matcher m = ROUTE.matcher(lies(new File(wurzel, QUELLE + "/server/HttpServer.java")));
        Set<String> gefunden = new TreeSet<>();
        while (m.find()) gefunden.add(m.group(1).replaceAll("[/?]+$", ""));
        assertTrue("Routen in HttpServer.java nicht erkannt, Muster prüfen", gefunden.size() > 50);
        Set<String> fehlend = new TreeSet<>();
        for (String r : gefunden) if (!karte.contains("`" + r)) fehlend.add(r);
        keineFehler("API-Pfade fehlen", fehlend);
    }

    @Test
    public void alleWebSeitenStehenInDerKarte() {
        Set<String> fehlend = new TreeSet<>();
        File[] seiten = new File(wurzel, ASSETS + "/web/local").listFiles((d, n) -> n.endsWith(".html"));
        assertTrue(seiten != null && seiten.length > 0);
        for (File s : seiten) if (!karte.contains("`" + s.getName() + "`")) fehlend.add(s.getName());
        keineFehler("Web-Seiten fehlen", fehlend);
    }

    /** Alle Dateinamen im Repo; Build-Ausgaben zählen nicht als Beleg. */
    private Set<String> dateinamen() throws IOException {
        Set<String> r = new HashSet<>();
        Set<String> aus = new HashSet<>(java.util.Arrays.asList("build", ".git", ".gradle", ".claude", ".cxx"));
        Files.walkFileTree(wurzel.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                return !d.equals(wurzel.toPath()) && aus.contains(d.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                r.add(f.getFileName().toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return r;
    }

    private boolean pfadDa(String pfad) {
        return new File(wurzel, pfad).exists()
                || new File(wurzel, QUELLE + "/" + pfad).exists()
                || new File(wurzel, ASSETS + "/" + pfad).exists()
                || new File(wurzel, "app/src/main/" + pfad).exists();
    }

    @Test
    public void genannteDateienUndKlassenExistieren() throws IOException {
        Set<String> namen = dateinamen();
        Set<String> fehlend = new TreeSet<>();
        Matcher m = BACKTICK.matcher(karte);
        while (m.find()) {
            String t = m.group(1);
            if (t.startsWith("/") || t.contains("<") || t.contains("*") || t.contains(" ")) continue;
            if (t.endsWith("/")) {
                if (!pfadDa(t)) fehlend.add(t);
            } else if (DATEI.matcher(t).matches()) {
                if (t.contains("/") ? !pfadDa(t) : !namen.contains(t)) fehlend.add(t);
            } else if (KLASSE.matcher(t).matches()) {
                if (!namen.contains(t + ".java") && !namen.contains(t + ".kt")) fehlend.add(t);
            }
        }
        keineFehler("Genanntes existiert nicht", fehlend);
    }

    @Test
    public void nichtsPrivatesUndKeineZeilennummern() throws IOException {
        for (String name : new String[]{"docs/CODEKARTE.md", "CLAUDE.md"}) {
            String text = lies(new File(wurzel, name));
            for (Pattern p : VERBOTEN) {
                Matcher m = p.matcher(text);
                assertFalse(name + " enthält Verbotenes: " + (m.find() ? m.group() : ""), p.matcher(text).find());
            }
        }
    }

    @Test
    public void karteBleibtKnapp() {
        int zeilen = Collections.frequency(java.util.Arrays.asList(karte.split("")), "\n");
        assertTrue("Karte hat " + zeilen + " Zeilen, Grenze " + MAX_ZEILEN, zeilen <= MAX_ZEILEN);
    }

    @Test
    public void claudeMdVerweistAufDieKarte() throws IOException {
        assertTrue(lies(new File(wurzel, "CLAUDE.md")).contains("docs/CODEKARTE.md"));
    }
}
