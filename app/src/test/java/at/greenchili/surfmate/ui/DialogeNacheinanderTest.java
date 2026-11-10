package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.38.14: „Neu in“ und „Ladekosten“ erscheinen nacheinander, nie übereinander. */
public class DialogeNacheinanderTest {

    @Test
    public void neuInUndLadekostenWartenAufeinander() throws Exception {
        String neu = lies("src/main/assets/web/shared/neuigkeiten.js");
        assertTrue("Neu in wartet auf ein offenes Fenster",
                neu.contains("(document.getElementById('pnHinweise') || document.querySelector('.modal-backdrop')) && versuche++ < 120"));
        String lade = lies("src/main/assets/web/shared/ladeverlust-hinweis.js");
        assertTrue("Ladekosten wartet auf Neu in", lade.contains("document.getElementById('pnNeu')"));
        assertTrue(lies("src/main/assets/web/shared/app-shell.js").contains("neuigkeiten.js?v=4"));
        assertTrue(lies("src/main/assets/web/local/index.html").contains("ladeverlust-hinweis.js?v=2"));
        assertTrue(lies("src/main/assets/web/local/charging.html").contains("ladeverlust-hinweis.js?v=2"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
