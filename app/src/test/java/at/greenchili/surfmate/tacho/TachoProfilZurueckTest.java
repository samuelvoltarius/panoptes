package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.11 (C1): „Tacho wieder normal“ stoppt erst jede Projektion und stellt dann das Größenprofil zurück; ohne
 * gewählte Größe sagt die Oberfläche, wo man sie wählt. 5 Sprachen.
 */
public class TachoProfilZurueckTest {

    private final TachoNaviApp.Stopper[] echtStopper = TachoNaviApp.stopper;
    private final TachoNaviApp.ProfilZurueck echtProfil = TachoNaviApp.profilZurueck;

    @After
    public void zurueck() {
        TachoNaviApp.stopper = echtStopper;
        TachoNaviApp.profilZurueck = echtProfil;
    }

    @Test
    public void erstStoppenDannProfilUndAntwortNenntEs() throws Exception {
        List<String> folge = new ArrayList<>();
        TachoNaviApp.stopper = new TachoNaviApp.Stopper[]{ () -> { folge.add("stopp"); return true; } };
        TachoNaviApp.profilZurueck = () -> { folge.add("profil"); return "kein_profil"; };
        JSONObject r = TachoNaviApp.tachoNormal();
        assertEquals("[stopp, profil]", folge.toString());
        assertEquals("kein_profil", r.getString("profil"));
        assertTrue(r.getBoolean("success"));

        TachoNaviApp.profilZurueck = () -> { throw new IllegalStateException("kein Daemon"); };
        r = TachoNaviApp.tachoNormal();
        assertEquals("Fehler beim Profil hält „normal“ nicht auf", "fehler", r.getString("profil"));
        assertTrue(r.getBoolean("success"));
    }

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void oberflaecheZeigtDenWegZurGroesse() throws Exception {
        String a = lies("src/main/assets/web/shared/tacho-appwahl.js");
        assertTrue(a.contains("j && j.profil === 'kein_profil'"));
        String h = lies("src/main/assets/web/local/index.html");
        assertTrue(h.contains("ok && d.profil === 'kein_profil'"));
        String rs = lies("src/main/assets/web/local/road-sense.html");
        assertEquals("„Automatisch“ in beiden Größen-Auswahlen", 2,
                rs.split("<option value=\"0\" data-i18n=\"road_sense.bs_layout_auto\">", -1).length - 1);
        String js = lies("src/main/assets/web/shared/road-sense.js");
        assertTrue("Vorgabe ist Automatisch, nicht 31", js.contains("bsClusterLayout: 0,"));
        String cp = lies("src/main/assets/web/shared/cluster-projection.js");
        assertTrue(cp.contains("var VALID = [0, 29, 30, 31];"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            JSONObject vc = d.getJSONObject("vehicle_control"), rsj = d.getJSONObject("road_sense");
            String hinweis = vc.getString("cluster_tacho_profil_hinweis");
            assertTrue(l + " Hinweis nennt den Menüweg", hinweis.contains(d.getJSONObject("shell").getString("sec_apps"))
                    && hinweis.contains(d.getJSONObject("shell").getString("page_display")));
            assertTrue(l + " Hinweis nennt die Einstellung", hinweis.contains(vc.getString("tacho_groesse_titel")));
            assertTrue(l, !rsj.getString("bs_layout_auto").isEmpty());
        }
    }
}
