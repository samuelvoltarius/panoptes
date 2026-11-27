package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/**
 * 0.1.43.22 (Upstream OverDrive #1126 „expose last verified state“): Der Daemon übernimmt den zuletzt am
 * BYD-Schalter gelesenen Zustand (AN = gesperrt) aus der Broadcast-Antwort und zeigt ihn im Status.
 * „frei“ und die Versuchslogik bleiben unverändert.
 */
public class AutostartGeprueftTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void geprueftAusDerAntwort() throws Exception {
        JSONObject a = AutostartNachUpdate.auswerten(
                "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false "
                        + "geprueft=erlaubt geprueftZeit=1800000000000 geprueftErgebnis=SUCCESS\"");
        assertTrue(a.getBoolean("frei"));
        assertEquals("SUCCESS", a.getString("ergebnis"));
        assertEquals("erlaubt", a.getString("geprueft"));
        assertEquals(1_800_000_000_000L, a.getLong("geprueftZeit"));

        JSONObject b = AutostartNachUpdate.auswerten(
                "Broadcast completed: result=0, data=\"ergebnis=STILL_ON ok=false autostartMissing=true "
                        + "geprueft=gesperrt geprueftZeit=1800000000001 geprueftErgebnis=STILL_ON\"");
        assertFalse(b.getBoolean("frei"));
        assertEquals("STILL_ON", b.getString("ergebnis"));
        assertEquals("gesperrt", b.getString("geprueft"));

        assertFalse("alte App ohne Feld", AutostartNachUpdate.auswerten(
                "Broadcast completed: result=0, data=\"ergebnis=FLIP_UNCONFIRMED ok=false autostartMissing=true\"")
                .has("geprueft"));
        assertFalse("nur erlaubt/gesperrt/unbekannt", AutostartNachUpdate.auswerten(
                "Broadcast completed: result=1, data=\"autostartMissing=false geprueft=frei geprueftZeit=5\"")
                .has("geprueft"));
    }

    @Test
    public void statusZeigtZuletztGeprueft() throws Exception {
        File ok = new File(tmp.getRoot(), ".update-ok");
        Files.write(ok.toPath(), new byte[0]);
        assertTrue(ok.setLastModified(1_000_000L));
        String enable = "Broadcast completed: result=0, data=\"ergebnis=STILL_ON ok=false autostartMissing=true "
                + "geprueft=gesperrt geprueftZeit=1500000\"";
        String[] status = {"Broadcast completed: result=0, data=\"autostartMissing=true geprueft=gesperrt geprueftZeit=1500000\""};
        AutostartNachUpdate a = new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"),
                (cmd, t) -> cmd.contains("AUTOSTART_ENABLE") ? enable : status[0]);

        assertEquals("ohne Lauf: kein Feld außer frei:null", JSONObject.NULL, a.zustand(1_100_000L).get("frei"));
        assertTrue(a.pruefen(1_200_000L));
        JSONObject z = a.zustand(1_300_000L);
        assertFalse(z.getBoolean("frei"));
        assertEquals("gesperrt", z.getString("geprueft"));
        assertEquals(1_500_000L, z.getLong("geprueftZeit"));

        // Nutzer hat am Display erlaubt: der Live-Status meldet den neu gelesenen Zustand
        status[0] = "Broadcast completed: result=1, data=\"autostartMissing=false geprueft=erlaubt geprueftZeit=1600000\"";
        JSONObject z2 = a.zustand(1_300_000L + AutostartNachUpdate.LIVE_CACHE_MS);
        assertTrue(z2.getBoolean("frei"));
        assertEquals("erlaubt", z2.getString("geprueft"));
        assertEquals(1_600_000L, z2.getLong("geprueftZeit"));
        assertEquals("gemerkt", "erlaubt", a.merk().getString("geprueft"));
    }

    /**
     * Koordination 26.11. (#1126 Lücke a): automatischer Klick nach Update nur bei bestätigtem Stand UND Bildschirm an.
     * Unbekannt/nein → verschieben, kein ENABLE, kein Versuch gezählt, nichts gemerkt. Belegt-Regel bleibt.
     */
    @Test
    public void automatischNurImStandUndBeiBildschirmAn() throws Exception {
        File ok = new File(tmp.getRoot(), ".update-ok");
        Files.write(ok.toPath(), new byte[0]);
        assertTrue(ok.setLastModified(1_000_000L));
        java.util.List<String> befehle = new java.util.ArrayList<>();
        java.util.List<String> log = new java.util.ArrayList<>();
        boolean[] steht = {false};
        Boolean[] an = {null};
        AutostartNachUpdate a = new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"), (cmd, t) -> {
            befehle.add(cmd);
            return "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false\"";
        }, () -> { }, () -> new AutostartNachUpdate.Entscheidung(true, "belegt"), () -> steht[0], () -> an[0]);
        a.log = log::add;

        assertFalse("fährt/unbekannt: verschoben", a.pruefen(1_100_000L));
        steht[0] = true;
        assertFalse("Bildschirm unbekannt: verschoben", a.pruefen(1_200_000L));
        an[0] = Boolean.FALSE;
        assertFalse("Bildschirm aus: verschoben", a.pruefen(1_300_000L));
        assertTrue("kein ENABLE geschickt", befehle.stream().noneMatch(c -> c.contains("AUTOSTART_ENABLE")));
        assertFalse("nichts gemerkt, kein Versuch gezählt", a.merk().has("versuche"));
        assertEquals("einmal ins Log, nicht jede Minute", 1,
                log.stream().filter(l -> l.contains("verschoben")).count());
        assertTrue(log.get(0).contains("Stand nicht bestätigt"));

        an[0] = Boolean.TRUE;
        assertTrue(a.pruefen(1_400_000L));
        assertTrue(befehle.stream().anyMatch(c -> c.contains("AUTOSTART_ENABLE")));
        assertEquals(1, a.merk().getInt("versuche"));
        assertTrue(log.stream().anyMatch(l -> l.equals("Autostart nach Update: Klick erlaubt – belegt")));
    }

    @Test
    public void nichtBelegtWirdNichtDurchDasGateVerzoegert() throws Exception {
        File ok = new File(tmp.getRoot(), ".update-ok");
        Files.write(ok.toPath(), new byte[0]);
        assertTrue(ok.setLastModified(1_000_000L));
        java.util.List<String> befehle = new java.util.ArrayList<>();
        AutostartNachUpdate a = new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"), (cmd, t) -> {
            befehle.add(cmd);
            return "";
        }, () -> { }, () -> new AutostartNachUpdate.Entscheidung(false, "nicht belegt: anderes Modell"),
                () -> false, () -> null);
        assertTrue(a.pruefen(1_100_000L));
        assertEquals("NICHT_BELEGT", a.merk().getString("ergebnis"));
        assertTrue("nie ENABLE", befehle.stream().noneMatch(c -> c.contains("AUTOSTART_ENABLE")));
    }

    @Test
    public void bildschirmAusDumpsys() {
        assertEquals(Boolean.TRUE, AutostartNachUpdate.interaktivAus("  mWakefulness=Awake\n  mInteractive=true\n"));
        assertEquals(Boolean.FALSE, AutostartNachUpdate.interaktivAus("mWakefulness=Asleep"));
        assertEquals(Boolean.FALSE, AutostartNachUpdate.interaktivAus("mWakefulness=Dozing"));
        assertEquals(Boolean.FALSE, AutostartNachUpdate.interaktivAus("mInteractive=false"));
        assertEquals(null, AutostartNachUpdate.interaktivAus("mWakefulness=Seltsam"));
        assertEquals(null, AutostartNachUpdate.interaktivAus(""));
        assertEquals(null, AutostartNachUpdate.interaktivAus(null));
    }

    @Test
    public void ohneAngabeUnbekannt() throws Exception {
        File ok = new File(tmp.getRoot(), ".update-ok");
        Files.write(ok.toPath(), new byte[0]);
        assertTrue(ok.setLastModified(1_000_000L));
        AutostartNachUpdate a = new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"),
                (cmd, t) -> "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false\"");
        assertTrue(a.pruefen(1_200_000L));
        JSONObject z = a.zustand(1_300_000L);
        assertTrue(z.getBoolean("frei"));
        assertEquals("unbekannt", z.getString("geprueft"));
        assertEquals(0L, z.getLong("geprueftZeit"));
    }
}
