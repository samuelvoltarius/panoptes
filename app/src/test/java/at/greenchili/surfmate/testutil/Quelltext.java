package at.greenchili.surfmate.testutil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liest Quell- und Asset-Dateien für Vertragstests als UTF-8 und wandelt Windows-Zeilenenden
 * ({@code \r\n}) in {@code \n} um. Unter Windows mit {@code core.autocrlf=true} haben die
 * Arbeitskopien CRLF; Vertragstests, die nach mehrzeiligen Mustern mit {@code "\n"} suchen,
 * wären dort sonst rot, obwohl der Code stimmt (Koordination 21.10.: im Test normalisieren,
 * keine .gitattributes-Umnormalisierung).
 */
public final class Quelltext {

    private Quelltext() {}

    public static String lies(Path path) throws IOException {
        return normal(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    /**
     * Löst einen Git-Symlink auf, der unter Windows ohne {@code core.symlinks} als kleine Textdatei mit dem Ziel
     * ausgecheckt wird (z. B. {@code web/local/shared} enthält {@code ../shared}). Unter Linux gibt es echte Symlinks;
     * dann bleibt der Pfad unverändert. Nur Zwischenstücke des Pfads werden aufgelöst, nie die letzte Datei.
     */
    public static Path aufloesen(Path pfad) {
        Path voll = pfad.toAbsolutePath().normalize();
        Path aktuell = voll.getRoot();
        int n = voll.getNameCount();
        for (int i = 0; i < n; i++) {
            Path naechster = aktuell.resolve(voll.getName(i).toString());
            if (i < n - 1 && Files.isRegularFile(naechster)) {
                try {
                    if (Files.size(naechster) < 1024) {
                        String ziel = new String(Files.readAllBytes(naechster), StandardCharsets.UTF_8).trim();
                        if (!ziel.isEmpty() && ziel.indexOf('\n') < 0 && ziel.indexOf('\0') < 0) {
                            naechster = at.greenchili.surfmate.testutil.RepoWurzel.hoch(naechster).resolve(ziel).normalize();
                        }
                    }
                } catch (IOException ignored) {
                    // bleibt der Pfad, wie er ist
                }
            }
            aktuell = naechster;
        }
        return aktuell;
    }

    public static String normal(String text) {
        return text == null ? null : text.replace("\r\n", "\n");
    }
}
