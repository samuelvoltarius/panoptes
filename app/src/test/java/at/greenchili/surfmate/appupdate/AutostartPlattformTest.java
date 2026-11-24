package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einrichtung.BydSeiten;
import at.greenchili.surfmate.services.AutoStartEnabler;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.43.21 (Koordination 24.11.): Den BYD-Autostart-Schalter schaltet Panoptes nur dort von selbst, wo die Bedeutung
 * „AN = Start gesperrt“ gemessen ist (DiLink 3 + Dolphin Surf). Sonst kein Klick, kein Setzen, einmal ein neutraler
 * Hinweis im Stand; die Entscheidung steht im Log.
 */
public class AutostartPlattformTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final List<String> befehle = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    int fehltGemeldet, pruefenGemeldet;
    boolean steht = true;

    static final String ERFOLG = "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false\"\n";

    File bestaetigt(long zeit) throws Exception {
        File f = new File(tmp.getRoot(), ".update-ok");
        Files.write(f.toPath(), new byte[0]);
        assertTrue(f.setLastModified(zeit));
        return f;
    }

    AutostartNachUpdate neu(File ok, Integer dilink, String modell) {
        AutostartNachUpdate a = new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"), (cmd, t) -> {
            befehle.add(cmd);
            return cmd.contains("AUTOSTART_ENABLE") ? ERFOLG : "1700000000\n";
        }, new AutostartNachUpdate.Melder() {
            public void autostartFehlt() { fehltGemeldet++; }
            public void autostartPruefen() { pruefenGemeldet++; }
        }, () -> new AutostartNachUpdate.Entscheidung(BydSeiten.autostartBelegt(dilink, modell),
                BydSeiten.autostartGrund(dilink, modell)), () -> steht);
        a.log = log::add;
        return a;
    }

    long enable() {
        return befehle.stream().filter(b -> b.contains("AUTOSTART_ENABLE")).count();
    }

    @Test
    public void surfSchaltetWieBisher() throws Exception {
        AutostartNachUpdate a = neu(bestaetigt(1_800_000_000_000L), 3, "Dolphin Surf");
        assertTrue(a.pruefen(1000L));
        assertEquals(1, enable());
        assertTrue(a.zustand(2000L).getBoolean("frei"));
        assertEquals(0, pruefenGemeldet);
        assertTrue(log.get(0), log.get(0).contains("Klick erlaubt") && log.get(0).contains("DiLink 3")
                && log.get(0).contains("Dolphin Surf"));
        // Knopf am Display: wie bisher
        assertTrue(a.freigeben(3000L).getBoolean("frei"));
        assertEquals(2, enable());
    }

    @Test
    public void unbekanntesModellUndDiLink45KlickenNichtUndMeldenNeutral() throws Exception {
        Object[][] faelle = {{3, "unbekannt"}, {3, "Dolphin"}, {4, "Atto 3"}, {5, "Seal"}, {null, "Dolphin Surf"}};
        long marke = 1_800_000_000_000L;
        for (Object[] f : faelle) {
            befehle.clear();
            log.clear();
            pruefenGemeldet = 0;
            fehltGemeldet = 0;
            new File(tmp.getRoot(), "merk.json").delete();
            AutostartNachUpdate a = neu(bestaetigt(marke), (Integer) f[0], (String) f[1]);
            String fall = f[0] + "/" + f[1];
            assertTrue(fall, a.pruefen(1000L));
            assertEquals(fall + ": kein Klick, kein Broadcast, keine Marke", 0, befehle.size());
            assertEquals(fall + ": neutraler Hinweis", 1, pruefenGemeldet);
            assertEquals(fall + ": nicht „bitte erlauben“", 0, fehltGemeldet);
            assertTrue(fall, log.get(0).contains("kein Klick") && log.get(0).contains("nicht belegt"));
            // nur einmal je Installation
            for (int i = 0; i < 5; i++) assertFalse(fall, a.pruefen(2000L + i));
            assertEquals(1, pruefenGemeldet);
            assertEquals(0, befehle.size());
            // Startseite zeigt keinen Knopf (frei:null), und der Knopf klickt auch nicht
            assertEquals(fall, JSONObject.NULL, a.zustand(3000L).get("frei"));
            JSONObject k = a.freigeben(4000L);
            assertEquals(AutostartNachUpdate.NICHT_BELEGT, k.getString("ergebnis"));
            assertEquals(fall, 0, befehle.size());
            marke += 1000;
        }
    }

    @Test
    public void hinweisErstImStand() throws Exception {
        steht = false;
        AutostartNachUpdate a = neu(bestaetigt(1_800_000_000_000L), 5, "Seal");
        assertTrue(a.pruefen(1000L));
        assertEquals("fährt: noch kein Hinweis", 0, pruefenGemeldet);
        assertFalse(a.pruefen(2000L));
        assertEquals(0, pruefenGemeldet);
        steht = true;
        assertTrue(a.pruefen(3000L));
        assertEquals(1, pruefenGemeldet);
        assertFalse(a.pruefen(4000L));
        assertEquals(1, pruefenGemeldet);
        assertEquals(0, befehle.size());
    }

    @Test
    public void updateLaesstVorhandenenZustandUnberuehrt() throws Exception {
        // Vorige Installation: frei. Neues Update auf nicht belegter Plattform: kein Broadcast, keine Marke, kein „settings“.
        File merk = new File(tmp.getRoot(), "merk.json");
        Files.write(merk.toPath(), new JSONObject().put("installMarke", 1L).put("frei", true).put("ergebnis", "SUCCESS")
                .toString().getBytes(StandardCharsets.UTF_8));
        AutostartNachUpdate a = neu(bestaetigt(1_800_000_000_000L), 4, "Seal U");
        assertTrue(a.pruefen(1000L));
        assertTrue("nichts am Auto: " + befehle, befehle.isEmpty());
        JSONObject m = new JSONObject(new String(Files.readAllBytes(merk.toPath()), StandardCharsets.UTF_8));
        assertEquals(AutostartNachUpdate.NICHT_BELEGT, m.getString("ergebnis"));
        assertFalse("kein „frei“ behauptet", m.has("frei"));
        assertFalse("keine Marke gesetzt", m.has("marke"));
        assertTrue(m.getString("entscheidung").contains("nicht belegt"));
        // Plattform-Prüfung kaputt → nicht belegt, ebenfalls nichts
        befehle.clear();
        merk.delete();
        AutostartNachUpdate b = new AutostartNachUpdate(bestaetigt(1_800_000_500_000L), merk, (cmd, t) -> {
            befehle.add(cmd);
            return ERFOLG;
        }, () -> { }, () -> { throw new IllegalStateException("config"); }, () -> true);
        assertTrue(b.pruefen(1000L));
        assertTrue(befehle.isEmpty());
    }

    @Test
    public void appSeiteKlicktNurAufBelegterPlattform() throws Exception {
        assertNull("Surf: weiter wie bisher", AutoStartEnabler.vorab(true, "belegt"));
        assertEquals(AutoStartEnabler.Result.NICHT_BELEGT, AutoStartEnabler.vorab(false, "nicht belegt"));
        assertTrue(BydSeiten.autostartGrund(5, "Seal").startsWith("nicht belegt: DiLink 5, Modell Seal"));
        assertTrue(BydSeiten.autostartGrund(null, null).contains("DiLink unbekannt, Modell unbekannt"));
        assertTrue(BydSeiten.autostartGrund(3, "Dolphin Surf").startsWith("belegt"));
        // Der einzige Klick-Weg (Bedienungshilfe) fragt vor enabler.run() die Plattform
        String q = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/services/KeepAliveAccessibilityService.java");
        int vorab = q.indexOf("AutoStartEnabler.vorab(");
        int run = q.indexOf("result = enabler.run()");
        assertTrue(vorab > 0 && run > vorab);
        assertTrue(q.contains("if (result == null) result = enabler.run();"));
        // Assistent: neutraler Text statt „Haken weg“
        String d = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/overlay/SetupGuideDialog.java");
        assertTrue(d.contains("Result.NICHT_BELEGT"));
        assertTrue(d.contains("R.string.einr_byd_seiten_text_neutral"));
    }

    @Test
    public void hinweisTextInFuenfSprachen() throws Exception {
        File b = new File("src/main/assets/server-i18n");
        if (!b.isDirectory()) b = new File("app/src/main/assets/server-i18n");
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject u = new JSONObject(new String(Files.readAllBytes(new File(b, l + ".json").toPath()),
                    StandardCharsets.UTF_8)).getJSONObject("update_klein");
            String t = u.optString("autostart_pruefen");
            assertTrue(l, t.length() > 40);
        }
    }
}
