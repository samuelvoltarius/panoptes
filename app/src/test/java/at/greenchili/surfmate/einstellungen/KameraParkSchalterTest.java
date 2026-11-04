package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KameraParkSchalterTest {

    private static Feld feld(Katalog.Kontext k) {
        for (Feld f : Katalog.felder(k)) if ("kamera.parkFreigeben".equals(f.key)) return f;
        return null;
    }

    @Test
    public void optInStandardAus() {
        Feld f = feld(new Katalog.Kontext());
        assertNotNull(f);
        assertEquals(Feld.Typ.BOOL, f.typ);
        assertEquals(Boolean.FALSE, f.standard);
        assertFalse("auf DiLink 3 sichtbar", f.verborgen);
    }

    @Test
    public void aufDiLink45Verborgen() {
        Katalog.Kontext k = new Katalog.Kontext();
        k.kameraDauerhaft = true;
        assertTrue(feld(k).verborgen);
    }
}
