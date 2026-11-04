package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.TreeSet;

/**
 * 0.1.38.8 Integration (Koordination 04.11.): Hupe, Licht, Warnblinker, Aufsperren und „Aufsperren beim Annähern“
 * schaltet keine Kopplung ein, auch nicht das Besitzer-Handy – nur Display oder voller Login. Ausschalten aus der
 * Ferne bleibt: Telegram {@code /aktionen aus} (IPC) und die Handy-Aktionen {@code aktionen_aus}/{@code annaeherung_aus}
 * laufen über {@link FahrzeugAktionen#allesAus}, nicht über {@code /api/einstellungen}.
 */
public class AktionenNurAmAutoTest {

    @After
    public void ab() {
        FahrzeugAktionen.fuerTest(null);
    }

    @Test
    public void einschaltenNurAmAutoOderMitLogin() {
        TreeSet<String> gefunden = new TreeSet<>();
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if (!f.key.startsWith(FahrzeugAktionen.ABSCHNITT + ".")) continue;
            gefunden.add(f.key);
            assertNull(f.key, Einstellungen.sperre(f, Einstellungen.Stufe.LOGIN));
            assertEquals(f.key, "nurAmAuto", Einstellungen.sperre(f, Einstellungen.Stufe.BESITZER));
            assertNotNull(f.key, Einstellungen.sperre(f, Einstellungen.Stufe.KOPPLUNG));
            assertNotNull(f.key, Einstellungen.sperre(f, Einstellungen.Stufe.KEIN_RECHT));
        }
        TreeSet<String> erwartet = new TreeSet<>();
        for (String f : FahrzeugAktionen.FUNKTIONEN) erwartet.add(FahrzeugAktionen.ABSCHNITT + "." + f);
        erwartet.add(FahrzeugAktionen.ABSCHNITT + "." + FahrzeugAktionen.ANNAEHERUNG);
        assertEquals(erwartet, gefunden);
        assertEquals(5, gefunden.size());
    }

    @Test
    public void ausschaltenAusDerFerneGehtWeiter() throws Exception {
        JSONObject a = new JSONObject();
        for (String f : FahrzeugAktionen.FUNKTIONEN) a.put(f, true);
        a.put(FahrzeugAktionen.ANNAEHERUNG, true);
        JSONObject config = new JSONObject().put(FahrzeugAktionen.ABSCHNITT, a);
        FahrzeugAktionen.fuerTest(config);

        assertTrue(FahrzeugAktionen.allesAus(true, FahrzeugAktionen.QUELLE_HANDY));      // annaeherung_aus
        assertFalse(FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        assertTrue(FahrzeugAktionen.an(FahrzeugAktionen.AUFSPERREN));
        assertTrue(FahrzeugAktionen.allesAus(false, "telegram"));                        // /aktionen aus
        for (String f : FahrzeugAktionen.FUNKTIONEN) assertFalse(f, FahrzeugAktionen.an(f));
    }

    @Test
    public void fernAusWegeGehenNichtUeberEinstellungen() throws Exception {
        String handy = lies("src/main/java/at/greenchili/surfmate/server/HandyApiHandler.java");
        assertTrue(handy.contains("\"annaeherung_aus\".equals(aktion)"));
        assertTrue(handy.contains("FahrzeugAktionen.allesAus(\"annaeherung_aus\".equals(aktion)"));
        String ipc = lies("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java");
        assertTrue(ipc.contains("FahrzeugAktionen.allesAus(\"automatik_aus\".equals(aktion)"));
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(Paths.get(pfad)), StandardCharsets.UTF_8);
    }
}
