package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Faktor muss gegen die gemeinsamen Prüfdaten von panoptes-karte 1.1.0 bestehen (Koordination 21.10.2026).
 * Quelle: https://byd.serveone.at/tiles/lib/panoptes-karte-1.1.0.pruefdaten.json, als Ressource mit sha256.
 */
public class FaktorPruefdatenTest {

    static final String SHA256 = "b515406370d56d1db71028fef0ff69b39bb2b317ff8854b7be8f764c5a0adcca";

    private static byte[] bytes() throws Exception {
        try (InputStream in = FaktorPruefdatenTest.class.getResourceAsStream("/karte/panoptes-karte-1.1.0.pruefdaten.json")) {
            if (in == null) throw new AssertionError("Prüfdaten fehlen");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static JSONObject daten() throws Exception {
        return new JSONObject(new String(bytes(), StandardCharsets.UTF_8));
    }

    private static Double d(JSONObject o, String k) throws Exception {
        return o.has(k) && !o.isNull(k) ? o.getDouble(k) : null;
    }

    @Test
    public void testDataIsTheReleasedFile() throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes());
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        assertEquals("Prüfdaten verändert oder Zeilenenden umgewandelt", SHA256, sb.toString());
        assertEquals("1.1.0", daten().getString("version"));
    }

    @Test
    public void faktorMatchesAllVectors() throws Exception {
        JSONArray f = daten().getJSONArray("faktor");
        assertTrue(f.length() >= 7);
        for (int i = 0; i < f.length(); i++) {
            JSONObject c = f.getJSONObject(i);
            JSONArray va = c.getJSONArray("verhaeltnisse");
            List<Double> v = new ArrayList<>();
            for (int j = 0; j < va.length(); j++) v.add(va.getDouble(j));
            String name = c.getString("name");
            assertEquals(name, c.getDouble("faktor"), Faktor.faktorAus(v), 1e-12);
            JSONObject m = Faktor.faktorMeldung("Dolphin Surf", v, "panoptes", "a-0123456789abcdef");
            assertEquals(name, c.getDouble("faktor_gerundet"), m.getDouble("faktor"), 0);
            assertEquals(name, c.getInt("fahrten"), m.getInt("fahrten"));
        }
    }

    @Test
    public void verhaeltnisMatchesAllVectors() throws Exception {
        JSONArray a = daten().getJSONArray("verhaeltnis");
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            JSONObject fahrt = c.getJSONObject("fahrt");
            Double got = Faktor.verhaeltnis(d(fahrt, "modell_kwh"), d(fahrt, "kwh"), d(fahrt, "akku_kwh"),
                    d(fahrt, "soc_start"), d(fahrt, "soc_ende"));
            if (c.isNull("verhaeltnis")) assertNull(c.getString("name"), got);
            else assertEquals(c.getString("name"), c.getDouble("verhaeltnis"), got, 1e-12);
        }
    }

    @Test
    public void meldungCarriesOnlyTheAllowedFields() {
        JSONObject m = Faktor.faktorMeldung("Dolphin Surf", Arrays.asList(1.1, 1.3), "panoptes", "a-0123456789abcdef");
        assertEquals(5, m.length());
        for (String k : new String[] {"fahrzeug", "faktor", "fahrten", "quelle", "auto"}) assertTrue(k, m.has(k));
        JSONObject ohneAuto = Faktor.faktorMeldung("Dolphin Surf", Arrays.asList(1.1), "panoptes", null);
        assertFalse(ohneAuto.has("auto"));
        assertEquals(4, ohneAuto.length());
    }

    @Test
    public void listKeepsAtMost200() {
        List<Double> l = new ArrayList<>();
        for (int i = 0; i < 250; i++) l = Faktor.verhaeltnisAnhaengen(l, 1.0 + i / 1000.0);
        assertEquals(Faktor.MAX_JE_PROFIL, l.size());
        assertEquals(1.249, l.get(l.size() - 1), 1e-12);
        assertEquals(1.05, l.get(0), 1e-12);
    }
}
