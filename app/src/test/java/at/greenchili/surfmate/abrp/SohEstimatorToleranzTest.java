package at.greenchili.surfmate.abrp;

import static org.junit.Assert.assertEquals;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Roh-SOH knapp über 100 % ist Messrauschen (Surf 43,2 kWh: raw 100,1 %), keine Sättigung. */
public class SohEstimatorToleranzTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DaemonLogger.Config previousLoggerConfig;

    @Before
    public void disableAndroidLogging() {
        previousLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(new DaemonLogger.Config()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
    }

    @After
    public void restoreLoggerConfig() {
        DaemonLogger.configure(previousLoggerConfig);
    }

    private SohEstimator surf() throws Exception {
        SohEstimator e = new SohEstimator(new File(temporaryFolder.newFolder(), "soh.properties"));
        java.lang.reflect.Field f = SohEstimator.class.getDeclaredField("nominalCapacityKwh");
        f.setAccessible(true);
        f.setDouble(e, 43.2);
        assertEquals("Nennwert muss gesetzt sein", 43.2, e.getNominalCapacityKwh(), 0.0001);
        return e;
    }

    private static double remainFuer(double rohSohProzent, double soc) {
        return 43.2 * rohSohProzent / 100.0 * soc / 100.0;
    }

    @Test
    public void rauschenKnappUeber100KlemmtStillOhneWarnzaehler() throws Exception {
        SohEstimator e = surf();
        assertEquals(100.0, e.computeLiveSoh(remainFuer(100.1, 80), 80, Double.NaN), 0.0001);
        assertEquals(100.0, e.computeLiveSoh(remainFuer(102.9, 100), 100, Double.NaN), 0.0001);
        assertEquals(0, e.saturationStreakForTest());
    }

    @Test
    public void normalerWertBleibtUnveraendert() throws Exception {
        SohEstimator e = surf();
        assertEquals(99.4, e.computeLiveSoh(remainFuer(99.4, 60), 60, Double.NaN), 0.001);
        assertEquals(0, e.saturationStreakForTest());
    }

    @Test
    public void deutlichUeberToleranzKlemmtUndZaehlt() throws Exception {
        SohEstimator e = surf();
        assertEquals(100.0, e.computeLiveSoh(remainFuer(110.0, 80), 80, Double.NaN), 0.0001);
        assertEquals(1, e.saturationStreakForTest());
        // ein normaler Wert setzt den Zähler zurück
        e.computeLiveSoh(remainFuer(97.0, 80), 80, Double.NaN);
        assertEquals(0, e.saturationStreakForTest());
    }

    @Test
    public void zuNiedrigKlemmtAuf60() throws Exception {
        SohEstimator e = surf();
        assertEquals(60.0, e.computeLiveSoh(remainFuer(40.0, 80), 80, Double.NaN), 0.0001);
        assertEquals(1, e.saturationStreakForTest());
    }
}
