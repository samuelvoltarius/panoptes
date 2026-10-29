package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.junit.Test;

/** 0.1.28 P5: Freigabe nur auf Knopfdruck, ein Schein je Kopplung und Clip, Ratenlimit, Protokoll begrenzt. */
public class ClipExportTest {

    static final class Speicher implements ClipExport.Speicher {
        JSONArray z = new JSONArray();
        public JSONArray lade() { return z; }
        public void speichere(JSONArray a) { z = a; }
    }

    static final String D = "ereignis_20261029_101500.mp4";
    static final long T = 1_800_000_000_000L;

    @Test
    public void nurEreignisClipsUndNurAufKnopfdruck() {
        ClipExport e = new ClipExport(new Speicher());
        assertEquals("nur_auf_knopfdruck", e.freigeben("k1", "Handy", "a", D, false, T).grund);
        assertEquals("kein_ereignis_clip", e.freigeben("k1", "Handy", "a", "event_20261029.mp4", true, T).grund);
        assertEquals("kein_ereignis_clip", e.freigeben("k1", "Handy", "a", null, true, T).grund);
        assertEquals("kopplung", e.freigeben(null, "Handy", "a", D, true, T).grund);
        assertNotNull(e.freigeben("k1", "Handy", "a", D, true, T).schein);
    }

    @Test
    public void neuerKnopfdruckErsetztAltenSchein() {
        ClipExport e = new ClipExport(new Speicher());
        String s1 = e.freigeben("k1", "H", "a", D, true, T).schein;
        String s2 = e.freigeben("k1", "H", "a", D, true, T + 1).schein;
        assertNotEquals(s1, s2);
        assertNull(e.einloesen("k1", s1, "a", T + 2));
        assertEquals(D, e.einloesen("k1", s2, "a", T + 2));
        assertNull("anderer Clip", e.einloesen("k1", s2, "b", T + 2));
        assertNull("abgelaufen", e.einloesen("k1", s2, "a", T + 1 + ClipExport.SCHEIN_MS + 1));
    }

    @Test
    public void ratenlimitJeKopplungUndStunde() {
        ClipExport e = new ClipExport(new Speicher());
        for (int i = 0; i < ClipExport.MAX_FREIGABEN_STUNDE; i++) {
            assertNotNull(e.freigeben("k1", "H", "c" + i, D, true, T + i).schein);
        }
        assertEquals("zu_oft", e.freigeben("k1", "H", "x", D, true, T + 100).grund);
        assertNotNull("andere Kopplung unberührt", e.freigeben("k2", "H", "x", D, true, T + 100).schein);
        assertNotNull("nach einer Stunde wieder", e.freigeben("k1", "H", "x", D, true, T + 3_600_001L).schein);
    }

    @Test
    public void protokollBegrenztNeuesteZuerst() throws Exception {
        Speicher sp = new Speicher();
        ClipExport e = new ClipExport(sp);
        for (int i = 0; i < ClipExport.MAX_PROTOKOLL + 20; i++) e.freigeben("k" + i, "H", "a", D, false, T + i);
        assertEquals(ClipExport.MAX_PROTOKOLL, sp.z.length());
        JSONArray p = e.protokoll(3);
        assertEquals(3, p.length());
        assertEquals(T + ClipExport.MAX_PROTOKOLL + 19, p.getJSONObject(0).getLong("zeit"));
        assertEquals("abgelehnt", p.getJSONObject(0).getString("was"));
    }

    @Test
    public void uebertragenNurEinmalAmEnde() throws Exception {
        Speicher sp = new Speicher();
        ClipExport e = new ClipExport(sp);
        String s = e.freigeben("k1", "H", "a", D, true, T).schein;
        e.bereichGesendet(s, "H", 0, 49, 100, T);
        assertEquals(1, sp.z.length());
        e.bereichGesendet(s, "H", 50, 99, 100, T);
        e.bereichGesendet(s, "H", 50, 99, 100, T);
        assertEquals(2, sp.z.length());
        assertEquals("uebertragen", sp.z.getJSONObject(1).getString("was"));
    }
}
