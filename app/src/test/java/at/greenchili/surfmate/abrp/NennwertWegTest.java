package at.greenchili.surfmate.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/** 0.1.11 (Kern 7): Weg des Akku-Nennwerts als eigenes Feld; ohne Erkennung nie „vom Auto“. */
public class NennwertWegTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void ohneNennwertUnbekanntUndKeinBmsWert() throws Exception {
        SohEstimator s = new SohEstimator(new File(tmp.getRoot(), "soh.properties"));
        assertEquals("unbekannt", s.getNennwertWeg());
        assertEquals(0.0, s.getBmsKwh(), 0);
    }
}
