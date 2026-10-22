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

    public static String normal(String text) {
        return text == null ? null : text.replace("\r\n", "\n");
    }
}
