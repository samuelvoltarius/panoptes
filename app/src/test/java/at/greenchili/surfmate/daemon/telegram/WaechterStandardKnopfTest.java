package at.greenchili.surfmate.daemon.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.1.38.8 Der Knopf unter der einmaligen Frage „Wächter einschalten?“ ({@code /waechter standard}) schickt den
 * Rechtshinweis (Version) mit; der Kern entscheidet. Hier nur der Body; der Weg steht im Quellvertrag
 * ({@code WaechterStandardHinweisTest}).
 */
public class WaechterStandardKnopfTest {

    @Test
    public void bodyTraegtDieVersionDesRechtshinweises() throws Exception {
        JSONObject b = SurveillanceCommandHandler.standardAnBody();
        assertEquals(Katalog.GUARD_LEGAL_VERSION, b.getString("rechtshinweis"));
        assertTrue(b.length() == 1);
    }

    @Test
    public void standardIstKeinAnAusBefehl() {
        // „standard“ darf nicht als an/aus gelesen werden (sonst ginge der Knopf an der Frage vorbei)
        assertEquals(null, SurveillanceCommandHandler.parseOnOff("standard"));
    }
}
