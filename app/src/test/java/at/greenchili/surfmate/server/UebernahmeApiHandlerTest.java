package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import at.greenchili.surfmate.uebernahme.OverdriveQuelle;
import at.greenchili.surfmate.uebernahme.OverdriveUebernahme;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** LADEN_VERTRAG.md §B3: Overdrive-Übernahme nur mit Login, nie über Kopplung. */
public class UebernahmeApiHandlerTest {

    static final class Schein implements UebernahmeApiHandler.Umgebung {
        boolean ext;
        public OverdriveQuelle.Befund befund(List<OverdriveUebernahme.Regel> r) {
            return OverdriveQuelle.erkenne(new File("gibt-es-nicht"), OverdriveUebernahme.dateinamen(r), false);
        }
        public JSONObject config() { return new JSONObject(); }
        public OverdriveUebernahme.Schreiber schreiber() { return null; }
        public boolean extern() { return ext; }
        public OverdriveUebernahme.Texte texte() { return (k, r) -> r; }
    }

    @Test
    public void nurMitLogin_ohneOverdriveAusgeblendet() throws Exception {
        Schein s = new Schein();
        s.ext = true;
        List<OverdriveUebernahme.Regel> keine = Collections.emptyList();
        assertEquals(403, UebernahmeApiHandler.bearbeite("GET", "/api/uebernahme/overdrive", null, keine, s)[0]);
        assertEquals(403, UebernahmeApiHandler.bearbeite("POST", "/api/uebernahme/overdrive", "{}", keine, s)[0]);
        s.ext = false;
        Object[] r = UebernahmeApiHandler.bearbeite("GET", "/api/uebernahme/overdrive", null, keine, s);
        assertEquals(200, r[0]);
        assertEquals(false, ((JSONObject) r[1]).getBoolean("vorhanden"));
        assertEquals("nicht_installiert", ((JSONObject) r[1]).getString("grund"));
        assertEquals(409, UebernahmeApiHandler.bearbeite("POST", "/api/uebernahme/overdrive", "{\"posten\":[]}", keine, s)[0]);
        assertEquals(400, UebernahmeApiHandler.bearbeite("POST", "/api/uebernahme/overdrive", "kaputt", keine, s)[0]);
        assertEquals(405, UebernahmeApiHandler.bearbeite("PUT", "/api/uebernahme/overdrive", "{}", keine, s)[0]);
        assertNull(UebernahmeApiHandler.bearbeite("GET", "/api/uebernahme/anders", null, keine, s));
    }

    /** Overdrive Stufe 2: nur Login; ohne Daten ehrlich „keine_daten“. */
    @Test
    public void verlauf_nurMitLogin_ohneDaten() throws Exception {
        final File leer = java.nio.file.Files.createTempDirectory("leer").toFile();
        at.greenchili.surfmate.uebernahme.OverdriveVerlauf v = new at.greenchili.surfmate.uebernahme.OverdriveVerlauf(
                new at.greenchili.surfmate.uebernahme.OverdriveVerlauf.Umgebung() {
                    public File quellOrdner() { return leer; }
                    public File arbeitsOrdner() { return leer; }
                    public long freiBytes(File o) { return Long.MAX_VALUE; }
                    public boolean steht() { return true; }
                    public at.greenchili.surfmate.uebernahme.VerlaufKopierer.Ziel ladeZiel() { return null; }
                    public at.greenchili.surfmate.uebernahme.VerlaufKopierer.Ziel fahrtZiel() { return null; }
                    public int preiseNachOrtskreis(java.util.List<Long> l) { return 0; }
                    public void ladeTageNeu(java.util.List<Long> l) { }
                    public void fahrtSummenNeu(java.util.List<Long> l) { }
                });
        assertEquals(403, UebernahmeApiHandler.verlaufBearbeiten("GET", "/api/uebernahme/overdrive/verlauf", true, v)[0]);
        org.junit.Assert.assertFalse(ExternGuard.erlaubt("GET", "/api/uebernahme/overdrive/verlauf"));
        Object[] g = UebernahmeApiHandler.verlaufBearbeiten("GET", "/api/uebernahme/overdrive/verlauf", false, v);
        assertEquals(200, g[0]);
        assertEquals(false, ((JSONObject) g[1]).getBoolean("vorhanden"));
        Object[] p = UebernahmeApiHandler.verlaufBearbeiten("POST", "/api/uebernahme/overdrive/verlauf", false, v);
        assertEquals(409, p[0]);
        assertEquals("keine_daten", ((JSONObject) p[1]).getString("grund"));
        assertEquals(200, UebernahmeApiHandler.verlaufBearbeiten("POST", "/api/uebernahme/overdrive/verlauf/abbrechen", false, v)[0]);
    }

    /** Kern 4s Haken TripApiHandler.ladepreisVor existiert auf diesem Stand evtl. nicht: Setzen darf dann nie scheitern. */
    @Test
    public void ladepreisHaken_tolerant() {
        boolean gesetzt = UebernahmeApiHandler.ladepreisHakenSetzen();
        boolean feldDa;
        try {
            Class.forName("at.greenchili.surfmate.trips.TripApiHandler").getField("ladepreisVor");
            feldDa = true;
        } catch (Throwable t) {
            feldDa = false;
        }
        org.junit.Assert.assertEquals(feldDa, gesetzt);
    }

    /** Einrichtung (Kern 4): der Haken wird beim Laden des HttpServers gesetzt und ist ohne Overdrive false. */
    @Test
    public void einrichtungsHaken_gesetzt() throws Exception {
        java.util.function.BooleanSupplier vorher = EinrichtungApiHandler.overdriveErkennung;
        try {
            EinrichtungApiHandler.overdriveErkennung = () -> true;
            UebernahmeApiHandler.anmelden();
            org.junit.Assert.assertFalse(EinrichtungApiHandler.overdriveErkennung.getAsBoolean());   // Testrechner ohne /data/local/tmp-Daten
            String q = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    new File("src/main/java").exists() ? "src/main/java/at/greenchili/surfmate/server/HttpServer.java"
                            : "app/src/main/java/at/greenchili/surfmate/server/HttpServer.java")), "UTF-8");
            org.junit.Assert.assertTrue(q.contains("static { UebernahmeApiHandler.anmelden(); }"));
        } finally {
            EinrichtungApiHandler.overdriveErkennung = vorher;
        }
    }
}
