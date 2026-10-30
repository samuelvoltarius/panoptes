package at.greenchili.surfmate.fix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.appupdate.FernUpdate;
import at.greenchili.surfmate.appupdate.UpdateRegel;
import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.KopplungsAnfragen;
import at.greenchili.surfmate.bruecke.Bruecke;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** 0.1.32.1: Sammel-Prüfungen der Reparaturen (Rechte, Update-Takt, Mobilfunk-Frage, Texte). */
public class Fix01321Test {

    private static Kopplungen kopplungen() {
        JSONArray[] platte = {new JSONArray()};
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
    }

    private static final java.util.List<String> STANDARD = Arrays.asList("klima", "zusperren", "push", "web", "route");

    @Test
    public void neueHandyKopplungNieMitAufsperrenWallboxUpdate() {
        Kopplungen k = kopplungen();
        String erste = k.anlegen("Pixel", "handy", 1);
        String zweite = k.anlegen("Brille", "handy", 2);
        for (String id : new String[] {erste, zweite}) {
            assertEquals(STANDARD, k.rechte(id));
        }
    }

    @Test
    public void dienstBekommtNurDienstRechte() {
        Kopplungen k = kopplungen();
        KopplungsAnfragen a = new KopplungsAnfragen(new KopplungsAnfragen.Ausgeber() {
            public String[] ausstellen(String geraet, String art, long now) {
                return new String[] {k.anlegen(geraet, art, now), "jwt"};
            }
            public void zusatzRechte(String kid, boolean auf, boolean wb, boolean upd) {
                throw new AssertionError("Dienst darf keine Zusatzrechte bekommen");
            }
        });
        KopplungsAnfragen.Neu n = a.anlegen("Xaventra", "1234", "dienst", "ip", 1);
        String ref = a.offeneListe(1).optJSONObject(0).optString("ref");
        assertEquals(KopplungsAnfragen.Entscheid.OK, a.entscheiden(ref, true, true, true, true, 2));
        String kid = a.abholen(n.id, 3).kid;
        assertTrue(k.rechte(kid).isEmpty());
        assertTrue(k.rechteSetzen(kid, Arrays.asList("standbild", "aufsperren", "wallbox", "update", "klima")));
        assertEquals(Arrays.asList("standbild"), k.rechte(kid));
    }

    @Test
    public void quelltextSetztNichtsVorUndNichtsStill() throws Exception {
        String sys = lies("app/src/main/assets/web/local/system.html");
        assertFalse(sys.contains("checked data-upd"));
        assertFalse(sys.contains("checked data-auf"));
        assertFalse(sys.contains("checked data-wb"));
        assertTrue("Rückfrage vor jedem Zusatzrecht", sys.contains("function bestaetigt(x, text)"));
        String overlay = lies("app/src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java");
        assertFalse(overlay.contains("update\\\":true"));
        String auth = lies("app/src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java");
        assertFalse(auth.contains("RECHT_UPDATE"));
    }

    @Test
    public void pruefTaktNachFehlerKuerzer() {
        long h = 3_600_000L;
        assertFalse("4,5 h ohne Fehler: noch nicht fällig", UpdateRegel.pruefenFaellig(45 * h / 10, 0, false));
        assertTrue(UpdateRegel.pruefenFaellig(6 * h, 0, false));
        assertTrue("nach Fehlversuch nach 30 min", UpdateRegel.pruefenFaellig(h / 2, 0, true));
        assertFalse(UpdateRegel.pruefenFaellig(h / 4, 0, true));
    }

    private static Bruecke bruecke(JSONObject stand) {
        return new Bruecke(new Bruecke.Platte() {
            public JSONObject lade() { return stand; }
            public boolean speichere(JSONObject z) { return true; }
        }, null, null);
    }

    @Test
    public void kameraNeuOhneLimitBestandBleibtStufeNullBleibt() throws Exception {
        Bruecke neu = bruecke(new JSONObject());
        assertEquals(Bruecke.MEDIEN_KEIN_LIMIT, neu.medienTagMb());
        assertTrue(neu.medienVerbrauchen(50L * 1024 * 1024 * 1024, 1));
        assertEquals(500, bruecke(new JSONObject().put("ein", true)).medienTagMb());
        assertEquals(100, bruecke(new JSONObject().put("medienTagMb", 100)).medienTagMb());
        Bruecke null0 = bruecke(new JSONObject().put("medienTagMb", 0));
        assertEquals(0, null0.medienTagMb());
        assertFalse("Stufe 0 sperrt weiter", null0.medienVerbrauchen(0, 1));
        assertTrue(neu.medienTagMbSetzen(Bruecke.MEDIEN_KEIN_LIMIT));
        assertFalse(neu.medienTagMbSetzen(-2));
    }

    private static final class Env implements FernUpdate.Umgebung {
        final File basis;
        boolean frage = true, mitMobil;
        int gestartet;
        Env(File b) { basis = b; }
        public JSONObject status(String sprache) {
            try {
                return new JSONObject().put("aktuell", new JSONObject().put("versionCode", 50))
                        .put("verfuegbar", new JSONObject().put("version", "0.1.33").put("versionCode", 52)
                                .put("groesseBytes", 71_000_000L))
                        .put("installierbar", false).put("nichtWeil", "wartet_wlan").put("mobilFrage", frage);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        public String installieren(int vc) { gestartet = vc; return "wartet_wlan"; }
        public String installieren(int vc, boolean mobil) { gestartet = vc; mitMobil = mobil; return null; }
        public String fahrsperre() { return null; }
        public void telegram(String v, String g) { }
        public File basis() { return basis; }
        public long jetzt() { return 1000; }
    }

    @Test
    public void ohneWlanFragtHandyMitGroesseUndGiltNurMitEinwilligung() throws Exception {
        Env e = new Env(Files.createTempDirectory("fern").toFile());
        FernUpdate f = new FernUpdate(e);
        JSONObject r = f.starten("0.1.33", true, "Handy", "de");
        assertFalse(r.getBoolean("ok"));
        assertTrue(r.getBoolean("mobilFrage"));
        assertEquals(71_000_000L, r.getLong("groesseBytes"));
        assertEquals("ohne Einwilligung kein Start", 0, e.gestartet);
        assertTrue(f.starten("0.1.33", true, true, "Handy", "de").getBoolean("ok"));
        assertTrue(e.mitMobil);
        assertEquals(52, e.gestartet);
    }

    @Test
    public void bestandOhneFrageBleibtBeiWartetWlan() throws Exception {
        Env e = new Env(Files.createTempDirectory("fern").toFile());
        e.frage = false;
        JSONObject r = new FernUpdate(e).starten("0.1.33", true, true, "Handy", "de");
        assertFalse(r.getBoolean("ok"));
        assertEquals("wartet_wlan", r.getString("grund"));
        assertEquals(0, e.gestartet);
    }

    @Test
    public void campingTextUndWahlInFuenfSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject c = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("camping");
            for (String k : new String[] {"weg_titel", "weg_probelauf", "weg_auto_an", "weg_cloud", "weg_cloud_frage"}) {
                assertTrue(l + " " + k, !c.optString(k).isEmpty());
            }
        }
        assertFalse(lies("app/src/main/assets/web/local/camping.html").contains("heizt in dieser Version noch nicht"));
        assertFalse(new JSONObject(lies("app/src/main/assets/web/i18n/de.json")).getJSONObject("camping")
                .getString("trial").contains("noch nicht"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
