package at.greenchili.surfmate.tacho;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public class TachoMehrfachUnterbauTest {
    private static TachoMehrfachLayout layout() {
        return new TachoMehrfachLayout("zweier", "Zwei Apps", Arrays.asList(
                new TachoMehrfachLayout.Slot("links", "com.example.one", 0, 0, 640, 480),
                new TachoMehrfachLayout.Slot("rechts", "com.example.two", 640, 0, 640, 480)));
    }

    private static TachoMehrfachZulassung.Eingaben erlaubt() {
        TachoMehrfachZulassung.Eingaben e = new TachoMehrfachZulassung.Eingaben();
        e.expliziteNutzerwahl = true;
        e.legacyProjektionBelegt = true;
        e.displayBreite = 1280;
        e.displayHoehe = 480;
        e.startbar = new HashSet<>(Arrays.asList("com.example.one", "com.example.two"));
        e.freigegeben = new HashSet<>(e.startbar);
        for (String p : e.startbar) e.fahrtSperre.put(p, null);
        return e;
    }

    @Test public void laufzeitBleibtHartInaktiv() {
        assertFalse(TachoMehrfachZulassung.LAUFZEIT_AKTIV);
        assertEquals("mehrfach_unterbau_nicht_aktiv",
                TachoMehrfachZulassung.pruefeAktivierung(layout(), erlaubt()));
    }

    @Test public void zielpolicyBrauchtNutzerwahlUndJedePaketfreigabe() {
        TachoMehrfachZulassung.Eingaben e = erlaubt();
        e.expliziteNutzerwahl = false;
        assertEquals("nutzerwahl_noetig", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
        e.expliziteNutzerwahl = true;
        e.freigegeben.remove("com.example.two");
        assertEquals("nicht_freigegeben", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
    }

    @Test public void di5SingleOsUndSurfBleibenEinzelnFailClosed() {
        TachoMehrfachZulassung.Eingaben e = erlaubt();
        e.diLink5 = true;
        assertEquals("dilink5_gesperrt", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
        e.diLink5 = false; e.singleOs = true;
        assertEquals("single_os_gesperrt", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
        e.singleOs = false; e.dolphinSurf = true;
        assertEquals("dolphin_surf_gesperrt", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
    }

    @Test public void unklareFahrtOderPlattformBleibtGesperrt() {
        TachoMehrfachZulassung.Eingaben e = erlaubt();
        e.bewegungsGrund = "state_unknown";
        assertEquals("nur_im_stand", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
        e.bewegungsGrund = null; e.legacyProjektionBelegt = false;
        assertEquals("plattform_unbelegt", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
    }

    @Test public void geometrieMussImDisplayLiegenUndDarfNichtUeberlappen() {
        TachoMehrfachZulassung.Eingaben e = erlaubt();
        TachoMehrfachLayout ausserhalb = new TachoMehrfachLayout("x", "x", Arrays.asList(
                new TachoMehrfachLayout.Slot("a", "com.example.one", 0, 0, 900, 480),
                new TachoMehrfachLayout.Slot("b", "com.example.two", 900, 0, 500, 480)));
        assertEquals("slot_ausserhalb", TachoMehrfachZulassung.pruefeEntwurf(ausserhalb, e));
        TachoMehrfachLayout overlap = new TachoMehrfachLayout("x", "x", Arrays.asList(
                new TachoMehrfachLayout.Slot("a", "com.example.one", 0, 0, 800, 480),
                new TachoMehrfachLayout.Slot("b", "com.example.two", 700, 0, 580, 480)));
        assertEquals("slots_ueberlappen", TachoMehrfachZulassung.pruefeEntwurf(overlap, e));
    }

    @Test public void gueltigerEntwurfPassiertNurDieNochInaktiveZielpolicy() {
        assertNull(TachoMehrfachZulassung.pruefeEntwurf(layout(), erlaubt()));
    }

    @Test public void fehlgeschlagenerCommitRolltRueckwaertsUndZeigtInstrumente() {
        List<String> schritte = new ArrayList<>();
        TachoMehrfachWechselPlan.Operationen ops = operationen(schritte, true, false);
        assertFalse(TachoMehrfachWechselPlan.wechseln(null, layout(), ops));
        assertEquals(Arrays.asList("vor:links", "vor:rechts", "commit", "fehler:vorbereiten_oder_commit",
                "rollback:rechts", "rollback:links", "instrumente"), schritte);
    }

    @Test public void releaseFehlerNachCommitFaelltAufInstrumenteZurueck() {
        List<String> schritte = new ArrayList<>();
        TachoMehrfachWechselPlan.Operationen ops = operationen(schritte, false, true);
        assertFalse(TachoMehrfachWechselPlan.wechseln(layout(), layout(), ops));
        assertTrue(schritte.contains("instrumente"));
        assertTrue(schritte.contains("fehler:freigeben"));
    }

    @Test public void stopGibtRueckwaertsFreiUndStelltImmerInstrumenteHer() {
        List<String> schritte = new ArrayList<>();
        assertTrue(TachoMehrfachWechselPlan.stoppen(layout(), operationen(schritte, false, false)));
        assertEquals(Arrays.asList("frei:rechts", "frei:links", "instrumente"), schritte);
    }

    @Test public void linkeUndRechteKameraStellenExaktDasselbeLayoutWiederHer() {
        for (boolean links : new boolean[] {true, false}) {
            TachoMehrfachPrioritaet p = new TachoMehrfachPrioritaet();
            p.layoutAktiv("zweier", 17L);
            p.kameraStart(links);
            assertEquals(links ? TachoMehrfachPrioritaet.Anzeige.KAMERA_LINKS
                    : TachoMehrfachPrioritaet.Anzeige.KAMERA_RECHTS, p.anzeige());
            p.kameraEnde(links, true);
            assertEquals(TachoMehrfachPrioritaet.Anzeige.LAYOUT, p.anzeige());
            assertEquals("zweier", p.layoutId());
            assertEquals(17L, p.layoutGeneration());
        }
    }

    @Test public void kameraOderRestoreFehlerUndBesitzwechselFallenAufInstrumente() {
        TachoMehrfachPrioritaet p = new TachoMehrfachPrioritaet();
        p.layoutAktiv("alt", 1L);
        p.kameraStart(true);
        p.kameraEnde(true, false);
        assertEquals(TachoMehrfachPrioritaet.Anzeige.INSTRUMENTE, p.anzeige());

        p.layoutAktiv("alt", 2L);
        p.kameraStart(false);
        p.layoutAktiv("neu", 3L);
        p.kameraEnde(false, true);
        assertEquals(TachoMehrfachPrioritaet.Anzeige.INSTRUMENTE, p.anzeige());
    }

    private static TachoMehrfachWechselPlan.Operationen operationen(
            List<String> schritte, boolean commitFehler, boolean releaseFehler) {
        return new TachoMehrfachWechselPlan.Operationen() {
            public void vorbereiten(TachoMehrfachLayout.Slot s) { schritte.add("vor:" + s.id); }
            public void commit(TachoMehrfachLayout ziel) throws Exception {
                schritte.add("commit"); if (commitFehler) throw new Exception("commit");
            }
            public void zurueckrollen(TachoMehrfachLayout.Slot s) { schritte.add("rollback:" + s.id); }
            public void freigeben(TachoMehrfachLayout.Slot s) throws Exception {
                schritte.add("frei:" + s.id);
                if (releaseFehler && "rechts".equals(s.id)) throw new Exception("release");
            }
            public void normaleInstrumente() { schritte.add("instrumente"); }
            public void fehler(String schritt, String slotId, Throwable fehler) {
                schritte.add("fehler:" + schritt);
            }
        };
    }

    @Test
    public void jederSlotBrauchtDieEinzelAppSperre() {
        TachoMehrfachZulassung.Eingaben e = erlaubt();
        e.fahrtSperre.put("com.example.two", "nur_im_stand");
        assertEquals("fahrt_sperre", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
        e = erlaubt();
        e.fahrtSperre.remove("com.example.one");
        assertEquals("fahrt_sperre", TachoMehrfachZulassung.pruefeEntwurf(layout(), e));
    }
}
