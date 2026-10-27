package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.19 (Kandidat Kern 10): Aufsperr-Recht schon beim Erlauben einer Handy-Kopplung – nur wenn gewählt. */
public class KopplungAufsperrenTest {

    static final long T0 = 1_000_000L;

    static final class Aufbau {
        final JSONArray[] platte = {new JSONArray()};
        final Kopplungen k = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
        final KopplungsAnfragen a = new KopplungsAnfragen(new KopplungsAnfragen.Ausgeber() {
            public String[] ausstellen(String geraet, String art, long now) {
                String kid = k.anlegen(geraet, art, now);
                return kid == null ? null : new String[] {kid, "jwt-" + kid};
            }
            public void aufsperrenErlauben(String kid) {
                java.util.List<String> r = new java.util.ArrayList<>(Kopplungen.HANDY_STANDARD);
                r.add(Kopplungen.RECHT_AUFSPERREN);
                k.rechteSetzen(kid, r);
            }
        });

        /** Anfrage anlegen, entscheiden, abholen; liefert die kid. */
        String koppeln(String art, boolean aufsperren) {
            KopplungsAnfragen.Neu n = a.anlegen("Handy", "1234", art, "100.1.2.3", T0);
            String ref = a.offeneListe(T0).optJSONObject(0).optString("ref");
            assertEquals(KopplungsAnfragen.Entscheid.OK, a.entscheiden(ref, true, aufsperren, T0 + 1));
            return a.abholen(n.id, T0 + 2).kid;
        }
    }

    @Test
    public void ohneHaekchenBleibtAufsperrenAus() {
        Aufbau x = new Aufbau();
        String kid = x.koppeln("handy", false);
        assertTrue(x.k.hatRecht(kid, Kopplungen.RECHT_ZUSPERREN));
        assertFalse(x.k.hatRecht(kid, Kopplungen.RECHT_AUFSPERREN));
    }

    @Test
    public void mitHaekchenDarfDasHandyAufsperren() {
        Aufbau x = new Aufbau();
        String kid = x.koppeln("handy", true);
        assertTrue(x.k.hatRecht(kid, Kopplungen.RECHT_AUFSPERREN));
        assertTrue("Standard-Rechte bleiben", x.k.hatRecht(kid, Kopplungen.RECHT_KLIMA));
    }

    @Test
    public void nurFuerHandyUndNurBeimErlauben() {
        Aufbau x = new Aufbau();
        String kid = x.koppeln("cockpit", true);
        assertFalse(x.k.hatRecht(kid, Kopplungen.RECHT_AUFSPERREN));
        KopplungsAnfragen.Neu n = x.a.anlegen("Handy2", "5678", "handy", "100.1.2.4", T0);
        String ref = x.a.offeneListe(T0).optJSONObject(0).optString("ref");
        x.a.entscheiden(ref, false, true, T0 + 1);
        assertEquals(null, x.a.abholen(n.id, T0 + 2).kid);
    }

    @Test
    public void apiNurMitLoginUndSeiteFragtNurBeiHandy() throws Exception {
        String api = lies("app/src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        int m = api.indexOf("pathOnly.startsWith(\"/api/kopplung/anfrage/\")");
        String block = api.substring(m, api.indexOf("return true;", m));
        assertTrue(block.indexOf("isExternRequest()") < block.indexOf("optBoolean(\"aufsperren\""));
        String sys = lies("app/src/main/assets/web/local/system.html");
        assertTrue(sys.contains("(a.art === 'handy' ? '<label"));
        assertTrue("Standard aus", sys.contains("<input type=\"checkbox\" data-auf="));
        assertFalse(sys.contains("data-auf=\"' + self.esc(a.ref) + '\" checked"));
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
