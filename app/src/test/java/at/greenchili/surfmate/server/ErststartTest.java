package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Fixrunde E2E 0.1.43.2: M1 (Neuinstallation startet in „Einfach“, auch wenn die App zu früh schrieb), G2 (Suche
 * findet Benachrichtigung, Pfeile, Waze, Google, Overlay – 5 Sprachen), G3 (kein „Neu in …“ bei Neuinstallation).
 */
public class ErststartTest {

    private static JSONObject einr(String art, String fertig) throws Exception {
        JSONObject s = new JSONObject();
        if (fertig != null) s.put("fertig", fertig);
        return new JSONObject().put("einrichtung", new JSONObject().put("art", art).put("schritte", s));
    }

    @Test
    public void frischNurBeiNeuinstallation() throws Exception {
        assertTrue(Erststart.frisch(null));
        assertTrue("leere Konfiguration", Erststart.frisch(new JSONObject()));
        assertTrue(Erststart.frisch(einr("neu", "offen")));
        assertTrue(Erststart.frisch(einr("neu", null)));
        assertFalse("Einrichtung fertig", Erststart.frisch(einr("neu", EinrichtungApiHandler.ERLEDIGT)));
        assertFalse("Bestand", Erststart.frisch(einr("bestand", "offen")));
        assertFalse("ohne Einrichtung, aber frühere Nutzung",
                Erststart.frisch(new JSONObject().put("hinweise", new JSONObject().put("version", "2026-10-21"))));
    }

    @Test
    public void umfangNieStillUmschalten() throws Exception {
        JSONObject neu = einr("neu", "offen");
        assertTrue(Erststart.umfangFestschreiben(neu));
        assertEquals("einfach", Erststart.umfang(neu));
        JSONObject bestand = einr("bestand", "erledigt");
        assertFalse("Bestand: Kern schreibt nie", Erststart.umfangFestschreiben(bestand));
        assertEquals("alle", Erststart.umfang(bestand));
        // vorhandener Wert gewinnt immer, auch bei frischer Installation
        JSONObject gesetzt = einr("neu", "offen");
        gesetzt.put("anzeige", new JSONObject().put("umfang", "alle"));
        assertFalse(Erststart.umfangFestschreiben(gesetzt));
        assertEquals("alle", Erststart.umfang(gesetzt));
        JSONObject einfach = einr("bestand", "erledigt");
        einfach.put("anzeige", new JSONObject().put("umfang", "einfach"));
        assertEquals("einfach", Erststart.umfang(einfach));
    }

    @Test
    public void neuigkeitenNurNachUpdate() throws Exception {
        assertTrue(Erststart.neuigkeitenAlsGesehen(null, einr("neu", "offen")));
        assertTrue(Erststart.neuigkeitenAlsGesehen(new JSONObject(), new JSONObject()));
        assertFalse("Update (Bestand)", Erststart.neuigkeitenAlsGesehen(null, einr("bestand", "erledigt")));
        assertFalse("schon gemerkt", Erststart.neuigkeitenAlsGesehen(
                new JSONObject().put("gesehenVersionCode", 100), einr("neu", "offen")));
    }

    @Test
    public void verdrahtetInProfilNeuigkeitenUndApp() throws Exception {
        String sys = lies("app/src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        int fest = sys.indexOf("if (Erststart.umfangFestschreiben(cfg)) {");
        assertTrue(fest > 0 && fest < sys.indexOf("res.put(\"umfang\", Erststart.umfang(cfg));"));
        String neu = lies("app/src/main/java/at/greenchili/surfmate/server/NeuigkeitenApiHandler.java");
        String get = neu.substring(neu.indexOf("if (pathOnly.equals(\"/api/neuigkeiten\") && method.equals(\"GET\")) {"),
                neu.indexOf("if (pathOnly.equals(\"/api/neuigkeiten/gesehen\")"));
        assertTrue(get.indexOf("neuinstallationAlsGesehen();") < get.indexOf("Neuigkeiten.antwort("));
        String pm = lies("app/src/main/java/at/greenchili/surfmate/ui/util/PreferencesManager.kt");
        String f = pm.substring(pm.indexOf("private fun festschreibenUmfang()"), pm.indexOf("internal fun umfangFehlt("));
        assertTrue(f.contains("for (versuch in 1..UMFANG_VERSUCHE)"));
        assertTrue(f.contains("Thread.sleep(UMFANG_PAUSE_MS)"));
        assertTrue("erst lesen, dann schreiben", f.indexOf("umfangFehlt(vorher)") < f.indexOf("updateSection("));
    }

    @Test
    public void sucheFindetDieGemeldetenWoerterInFuenfSprachen() throws Exception {
        String so = lies("app/src/main/assets/web/shared/system-overview.js");
        String liste = so.substring(so.indexOf("var STICHWOERTER = ["), so.indexOf("function stichListe()"));
        for (String w : new String[] {"benachrichtigung", "benachrichtigungszugriff", "pfeile", "waze", "google", "overlay",
                "notification", "arrows", "fleches", "notifiche", "frecce", "meldingen", "pijlen"})
            assertTrue(w, liste.contains(w));
        assertTrue(so.contains("nimm(stichListe(), 'stich');"));
        assertTrue(liste.contains("ziel: 'road-sense.html'") && liste.contains("ziel: 'meldungen.html'")
                && liste.contains("ziel: 'system.html#einrichtung'"));
        for (String v : new String[] {"=>", "let ", "const ", "`"}) assertFalse("ES5: " + v, liste.contains(v));
    }

    private static String lies(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
