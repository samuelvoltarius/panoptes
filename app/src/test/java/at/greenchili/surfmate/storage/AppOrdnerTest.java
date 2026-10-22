package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Ordner SurfMate → Panoptes je Datenträger (0.1.10, Koordination 22.10.), drei Volumes als Attrappe. */
public class AppOrdnerTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private String volume(String name, boolean alt, boolean neu) throws Exception {
        File v = tmp.newFolder(name);
        if (alt) {
            assertTrue(new File(v, "SurfMate/recordings").mkdirs());
            Files.write(new File(v, "SurfMate/recordings/a.mp4").toPath(), new byte[] {1, 2, 3});
        }
        if (neu) assertTrue(new File(v, "Panoptes").mkdirs());
        return v.getPath();
    }

    @Test
    public void dreiVolumesJeEigeneRegel() throws Exception {
        String intern = volume("intern", true, false);   // nur alt → umbenennen
        String sd = volume("sd", true, true);            // beide → nichts verschieben
        String usb = volume("usb", false, false);        // neu installiert → Panoptes

        assertEquals(AppOrdner.Ergebnis.UMBENANNT, AppOrdner.umziehen(intern, AppOrdner.ECHT));
        assertTrue("Datei unverändert mitgekommen", new File(intern, "Panoptes/recordings/a.mp4").isFile());
        assertFalse(new File(intern, "SurfMate").exists());
        assertEquals(new File(intern, "Panoptes"), AppOrdner.basis(intern));

        assertEquals(AppOrdner.Ergebnis.BEIDE_DA, AppOrdner.umziehen(sd, AppOrdner.ECHT));
        assertTrue("nichts verschoben", new File(sd, "SurfMate/recordings/a.mp4").isFile());
        assertEquals("weiter aus SurfMate lesen", new File(sd, "SurfMate"), AppOrdner.basis(sd));

        assertEquals(AppOrdner.Ergebnis.NICHTS_ZU_TUN, AppOrdner.umziehen(usb, AppOrdner.ECHT));
        assertEquals(new File(usb, "Panoptes"), AppOrdner.basis(usb));
    }

    @Test
    public void renameFehlgeschlagenNichtsVerschoben() throws Exception {
        String v = volume("fehl", true, false);
        AppOrdner.Fs klemmt = new AppOrdner.Fs() {
            @Override public boolean istOrdner(File f) { return f.isDirectory(); }
            @Override public boolean umbenennen(File von, File nach) { return false; }
        };
        assertEquals(AppOrdner.Ergebnis.FEHLGESCHLAGEN, AppOrdner.umziehen(v, klemmt));
        assertEquals(new File(v, "SurfMate"), AppOrdner.basis(v));
        assertTrue(new File(v, "SurfMate/recordings/a.mp4").isFile());
        // Eintrag „Noch offen“ je Datenträger, bei Erfolg wieder weg
        AppOrdner.merke(v, AppOrdner.Ergebnis.FEHLGESCHLAGEN);
        assertTrue(AppOrdner.offen().toString().contains("ordner_umbenennen"));
        assertTrue(AppOrdner.offen().toString().contains("fehlgeschlagen"));
        AppOrdner.merke(v, AppOrdner.Ergebnis.UMBENANNT);
        assertFalse(AppOrdner.offen().toString().contains(v.replace("\\", "\\\\")));
    }

    @Test
    public void alteGespeichertePfadeFindenNeuenOrt() throws Exception {
        String v = volume("pfad", true, false);
        String alt = new File(v, "SurfMate/recordings/a.mp4").getPath().replace('\\', '/');
        assertEquals("solange alt existiert, bleibt er", alt, AppOrdner.aufloesen(alt));
        AppOrdner.umziehen(v, AppOrdner.ECHT);
        String neu = AppOrdner.aufloesen(alt);
        assertTrue(neu, neu.contains("/Panoptes/recordings/a.mp4"));
        assertEquals("unbekannte Datei: unverändert", alt.replace("a.mp4", "x.mp4"),
                AppOrdner.aufloesen(alt.replace("a.mp4", "x.mp4")));
        assertEquals("/data/x/y", AppOrdner.aufloesen("/data/x/y"));
    }

    @Test
    public void anzeigeFuerWebseiten() {
        assertEquals("Panoptes/recordings", AppOrdner.anzeige("/storage/C093-6A86/Panoptes/recordings"));
        assertEquals("SurfMate/trips", AppOrdner.anzeige("/storage/emulated/0/SurfMate/trips"));
        assertEquals("Panoptes", AppOrdner.anzeige("/storage/emulated/0/Panoptes"));
        assertEquals("/data/x", AppOrdner.anzeige("/data/x"));
    }

    @Test
    public void keineFestenSurfMatePfadeMehr() throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            long n = s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    .filter(p -> !p.toString().endsWith("AppOrdner.java") && !p.toString().endsWith("RecordingsIndex.java"))
                    .filter(p -> {
                        try {
                            String q = Quelltext.lies(p);
                            return q.contains("/storage/emulated/0/SurfMate") || q.contains("/sdcard/SurfMate")
                                    || q.contains("/storage/sdcard/SurfMate") || q.contains("\"SurfMate\")");
                        } catch (Exception e) {
                            return false;
                        }
                    }).count();
            assertEquals(0, n);
        }
    }
}
