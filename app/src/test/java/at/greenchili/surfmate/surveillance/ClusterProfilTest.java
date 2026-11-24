package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.11 (C1, Seal U DM-i: BYD-Tacho zu groß und abgeschnitten): Ans Auto geht nur noch ein BESTÄTIGTES
 * Größenprofil (vom Nutzer gespeichert). Das aus dem Modellnamen geratene Profil (Seal U → 30) wurde vor jeder
 * Projektion gesendet und beim Schließen „wiederhergestellt“ – so blieb das falsche stehen.
 */
public class ClusterProfilTest {

    private static JSONObject j(String s) throws Exception { return new JSONObject(s); }

    @Test
    public void nurEinGespeichertesProfilGilt() throws Exception {
        assertEquals("nichts gespeichert → nichts senden", 0, ClusterProjectionController.bestaetigtesProfil(null, null));
        assertEquals(0, ClusterProjectionController.bestaetigtesProfil(j("{}"), j("{\"target\":\"cluster\"}")));
        assertEquals(30, ClusterProjectionController.bestaetigtesProfil(j("{}"), j("{\"clusterSizeProfile\":30}")));
        assertEquals("älterer Ort surveillance", 31,
                ClusterProjectionController.bestaetigtesProfil(j("{\"clusterSizeProfile\":31}"), j("{}")));
        assertEquals("„Automatisch“ (0) am Fahrerdisplay schlägt den alten Wert", 0,
                ClusterProjectionController.bestaetigtesProfil(j("{\"clusterSizeProfile\":31}"), j("{\"clusterSizeProfile\":0}")));
        assertEquals("ungültig → nichts senden, nie geraten", 0,
                ClusterProjectionController.bestaetigtesProfil(j("{}"), j("{\"clusterSizeProfile\":1}")));
        assertEquals(29, ClusterProjectionController.bestaetigtesProfil(j("{}"), j("{\"clusterSizeProfile\":29}")));
    }

    @Test
    public void modellVorschlagMitGanzenWoertern() {
        assertEquals("Seal U / DM-i: kein Vorschlag (30 war dort falsch)", 0, ClusterProjectionController.modellVorschlag("BYD Seal U DM-i"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("SEAL U"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("Seal-U"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("Song Plus DM-i"));
        assertEquals("„han“ nicht als Teilstring", 0, ClusterProjectionController.modellVorschlag("Shanghai Edition"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("Chang An"));
        // 0.1.43.20 (P2): keine Namensteile mehr – Han/Tang/Song haben verschiedene Tacho-Größen
        assertEquals(0, ClusterProjectionController.modellVorschlag("Han EV"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("Tang"));
        assertEquals(29, ClusterProjectionController.modellVorschlag("Atto 3"));
        assertEquals(29, ClusterProjectionController.modellVorschlag("yuan plus"));
        assertEquals("Atto 2 ist nicht Atto 3", 0, ClusterProjectionController.modellVorschlag("Atto 2"));
        assertEquals(31, ClusterProjectionController.modellVorschlag("Seal"));
        assertEquals(0, ClusterProjectionController.modellVorschlag("Dolphin Surf"));
        assertEquals(0, ClusterProjectionController.modellVorschlag(null));
    }

    private static String quelle() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        if (!f.exists()) f = new File("app/" + f.getPath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void geratenesProfilWirdNieGesendet() throws Exception {
        String q = quelle();
        assertFalse("kein Fingerabdruck-Seed mehr", q.contains("fingerprintSizeProfile"));
        assertTrue("Start ohne bestätigtes Profil: 0", q.contains("    private int sizeProfileOpcode = 0;"));
        assertTrue("Öffnen/Schließen lesen nur das bestätigte Profil",
                q.contains("sizeProfileOpcode = bestaetigtesProfil(s, bs);"));
        int a = q.indexOf("private static int readLegacyRestoreProfileOpcode() {");
        String boot = q.substring(a, q.indexOf("static int bestaetigtesProfil(", a));
        assertTrue("Boot-Wiederherstellung: bestätigt oder nur 18→0",
                boot.contains("int sizeProfile = bestaetigtesProfil(surveillance, blindspot);"));
        assertTrue(boot.contains("return 0;"));
        assertFalse(boot.contains("seedDefault"));
        // modellVorschlag ist nur Anzeige/Fehler melden, nirgends ein Sende-Wert
        assertEquals("modellVorschlag nur in profilStand",
                1, q.split("modellVorschlag\\(", -1).length - 2);
    }

    @Test
    public void zurueckZurBydAnzeigeSendetImmer18Dann0() throws Exception {
        String q = quelle();
        int a = q.indexOf("public static String profilZuruecksetzen() {");
        String f = q.substring(a, q.indexOf("// ── Cluster display presence", a));
        assertTrue("DiLink 5: der DI5-Teardown macht den Rückweg", f.contains("return \"nicht_moeglich\""));
        assertTrue("offene Projektion: das Schließen stellt selbst her", f.contains("if (c.projState != ST_CLOSED) return \"projektion\";"));
        assertTrue(f.contains("final int profil = readLegacyRestoreProfileOpcode();"));
        // 0.1.43.20 (P4): 18 → 0 auch ohne bestätigtes Profil; das Profil nur, wenn eines bestätigt ist
        assertFalse("18/0 nie vom Profil abhängig", f.contains("if (profil == 0) return \"kein_profil\";"));
        assertTrue(f.contains("return profil == 0 ? \"kein_profil\" : \"gesendet\";"));
        int z = f.indexOf("sendInfoStaticResult(OP_CLOSE)"), r = f.indexOf("sendInfoStaticResult(OP_REFRESH)"),
                p = f.indexOf("sendInfoStaticResult(profil)");
        assertTrue("Reihenfolge 18 → 0 → Profil, wie beim Schließen", z > 0 && z < r && r < p);
        assertTrue("Profil erst nach 18/0 und nur wenn gewählt",
                f.indexOf("if (profil == 0 ||") > r && f.indexOf("if (profil == 0 ||") < p);
        assertTrue("auf dem Projektions-Thread (kein Rennen mit Öffnen/Schließen)", f.contains("c.projHandler.post("));
    }
}
