package at.greenchili.surfmate.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.27 (API_FUER_SURFMATE §18): Wetter nur über den eigenen Server, Rasterzelle 0,1° (≈ 10 km), ohne Kennung. */
public class WetterServerTest {

    @Test
    public void zelleAufEineNachkommastelle() {
        assertEquals("48.3_14.3", WeatherTemperature.zelle(48.3123, 14.2794));   // Linz
        assertEquals("48.3_14.3", WeatherTemperature.zelle(48.25, 14.25));
        assertEquals("-33.9_-151.2", WeatherTemperature.zelle(-33.8688, -151.2093));
        assertEquals("nie „-0.0“", "0.0_0.0", WeatherTemperature.zelle(-0.04, -0.01));
        assertTrue(WeatherTemperature.url(48.3123, 14.2794).endsWith("/api/wetter/v1/jetzt?zelle=48.3_14.3"));
    }

    @Test
    public void antwortDesServers() throws Exception {
        // live gelesen 28.10. (Planer-Sitzung): {"temp_c": 9.6, "regen_6h_prozent": 43, …, "ok": true}
        assertEquals(9.6, WeatherTemperature.uebernehmen(new JSONObject(
                "{\"temp_c\":9.6,\"regen_6h_prozent\":43,\"stand\":\"2026-10-28T22:30:00Z\",\"quelle\":\"open-meteo\",\"lizenz\":\"CC BY 4.0\",\"ok\":true}")), 1e-9);
        assertEquals(9.6, WeatherTemperature.getCached(), 1e-9);
        assertEquals(43, WeatherTemperature.getCachedPrecipProbability());
        // regen null: Temperatur trotzdem, Regen bleibt beim letzten Wert
        assertEquals(8.0, WeatherTemperature.uebernehmen(new JSONObject("{\"temp_c\":8,\"regen_6h_prozent\":null,\"ok\":true}")), 1e-9);
        assertEquals(43, WeatherTemperature.getCachedPrecipProbability());
        // veraltet (≤ 1 h) wird übernommen
        assertEquals(7.5, WeatherTemperature.uebernehmen(new JSONObject("{\"temp_c\":7.5,\"veraltet\":true,\"ok\":true}")), 1e-9);
        // Mittelwert hat Vorrang (gleiche Bedeutung wie vor 0.1.27), sonst Höchstwert
        WeatherTemperature.uebernehmen(new JSONObject("{\"temp_c\":7.5,\"regen_6h_prozent\":80,\"regen_6h_mittel_prozent\":35,\"ok\":true}"));
        assertEquals(35, WeatherTemperature.getCachedPrecipProbability());
        WeatherTemperature.uebernehmen(new JSONObject("{\"temp_c\":7.5,\"regen_6h_prozent\":80,\"regen_6h_mittel_prozent\":null,\"ok\":true}"));
        assertEquals(80, WeatherTemperature.getCachedPrecipProbability());
        // kaputt / nicht ok: nichts übernehmen
        assertTrue(Double.isNaN(WeatherTemperature.uebernehmen(new JSONObject("{\"ok\":false}"))));
        assertTrue(Double.isNaN(WeatherTemperature.uebernehmen(new JSONObject("{\"temp_c\":195,\"ok\":true}"))));
        assertEquals("letzter gültiger Wert bleibt", 7.5, WeatherTemperature.getCached(), 1e-9);
    }
}
