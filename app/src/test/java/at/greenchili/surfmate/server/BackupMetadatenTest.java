package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 0.1.11 (Kern 7): Backup-Export-Kategorie „metadaten“ (nur Metadaten, für update-pflicht). */
public class BackupMetadatenTest {

    private static BackupMetadaten.Seiten quelle(int n, boolean mitUrl) throws Exception {
        List<JSONObject> alle = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject o = new JSONObject().put("id", "r" + i).put("type", i % 3 == 0 ? "sentry" : "normal")
                    .put("available", i % 5 != 0).put("timestamp", 1000L + i);
            if (mitUrl) o.put("videoUrl", "/video/id/r" + i).put("sizeFormatted", "1 MB").put("date", "x").put("time", "y");
            alle.add(o);
        }
        return (limit, offset) -> alle.subList(Math.min(offset, alle.size()), Math.min(offset + limit, alle.size()));
    }

    @Test
    public void allesSeitenweiseUndKompakt() throws Exception {
        JSONObject m = BackupMetadaten.bauen(quelle(1234, true), quelle(7, false), 42, 3, 99L);
        JSONObject a = m.getJSONObject("aufnahmen");
        assertTrue(a.getBoolean("verfuegbar"));
        assertEquals(1234, a.getInt("anzahl"));
        assertEquals(1234, a.getJSONArray("zeilen").length());
        assertTrue(a.getBoolean("vollstaendig"));
        assertEquals("auch nicht verfügbare Aufnahmen zählen", 1234 - 247, a.getInt("anzahlVerfuegbar"));
        assertEquals(412, a.getInt("anzahlWaechter"));
        JSONObject z = a.getJSONArray("zeilen").getJSONObject(0);
        assertFalse(z.has("videoUrl"));
        assertFalse(z.has("sizeFormatted"));
        assertFalse(z.has("date"));
        assertEquals("r0", z.getString("id"));
        assertEquals(7, m.getJSONObject("parken").getInt("anzahl"));
        assertEquals(42, m.getJSONObject("fahrten").getInt("anzahl"));
        assertEquals(3, m.getJSONObject("fahrten").getInt("dateiFehlt"));
        assertEquals(99L, m.getLong("stand"));
    }

    @Test
    public void fehlenderDienstIstNichtNull() throws Exception {
        JSONObject m = BackupMetadaten.bauen(null, null, -1, 0, 1L);
        assertFalse(m.getJSONObject("aufnahmen").getBoolean("verfuegbar"));
        assertFalse(m.getJSONObject("parken").getBoolean("verfuegbar"));
        assertFalse(m.getJSONObject("fahrten").getBoolean("verfuegbar"));
        assertFalse("nie eine erfundene 0", m.getJSONObject("fahrten").has("anzahl"));
    }

    @Test
    public void fehlerInDerQuelleWirdGemeldet() throws Exception {
        BackupMetadaten.Seiten kaputt = (l, o) -> { throw new IllegalStateException("db zu"); };
        JSONObject a = BackupMetadaten.bauen(kaputt, null, 0, 0, 1L).getJSONObject("aufnahmen");
        assertFalse(a.getBoolean("verfuegbar"));
        assertEquals("db zu", a.getString("fehler"));
    }

    @Test
    public void parameterExakt() {
        assertTrue(ConfigBackupApiHandler.hasParamTrue("/api/backup/export?trips=true&metadaten=true", "metadaten"));
        assertTrue(ConfigBackupApiHandler.hasParamTrue("/api/backup/export?trips=true", "trips"));
        assertFalse(ConfigBackupApiHandler.hasParamTrue("/api/backup/export?xtrips=true", "trips"));
        assertFalse(ConfigBackupApiHandler.hasParamTrue("/api/backup/export?metadaten=1", "metadaten"));
        assertFalse(ConfigBackupApiHandler.hasParamTrue("/api/backup/export", "trips"));
    }
}
