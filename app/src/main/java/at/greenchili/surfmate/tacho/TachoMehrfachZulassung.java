package at.greenchili.surfmate.tacho;

import java.util.HashSet;
import java.util.Set;

/** Zentrale fail-closed Policy fuer einen spaeteren Mehrfach-App-Laufzeitpfad. */
public final class TachoMehrfachZulassung {
    /**
     * Bleibt hart false, bis ein eigener Slot-Daemon, Totwinkel-Restore und physische
     * Abnahmen existieren. Keine Konfiguration und keine API kann diesen Wert oeffnen.
     */
    public static final boolean LAUFZEIT_AKTIV = false;

    private TachoMehrfachZulassung() {}

    public static final class Eingaben {
        public boolean expliziteNutzerwahl;
        public boolean diLink5;
        public boolean singleOs;
        public boolean dolphinSurf;
        public boolean legacyProjektionBelegt;
        public String bewegungsGrund;
        public int displayBreite;
        public int displayHoehe;
        public Set<String> startbar = new HashSet<>();
        public Set<String> freigegeben = new HashSet<>();
        /**
         * Ergebnis von {@link TachoNaviApp#fahrtSperre} je Paket (null = frei). Jeder Slot muss
         * zusaetzlich durch dieselbe Einzel-App-Sperre, sonst entstuende ein Nebenweg.
         */
        public java.util.Map<String, String> fahrtSperre = new java.util.HashMap<>();
    }

    /** Oeffentliche Laufzeitzulassung: der vorbereitete Unterbau ist absichtlich nie startbar. */
    public static String pruefeAktivierung(TachoMehrfachLayout layout, Eingaben e) {
        if (!LAUFZEIT_AKTIV) return "mehrfach_unterbau_nicht_aktiv";
        return pruefeEntwurf(layout, e);
    }

    /**
     * Testbare Zielpolicy hinter dem harten Laufzeit-Gate. Sie darf erst von Fahrzeugcode
     * aufgerufen werden, wenn der in der Analyse beschriebene Release-Gate geschlossen ist.
     */
    static String pruefeEntwurf(TachoMehrfachLayout layout, Eingaben e) {
        if (e == null || !e.expliziteNutzerwahl) return "nutzerwahl_noetig";
        if (e.bewegungsGrund != null) return "nur_im_stand";
        if (e.diLink5) return "dilink5_gesperrt";
        if (e.singleOs) return "single_os_gesperrt";
        if (e.dolphinSurf) return "dolphin_surf_gesperrt";
        if (!e.legacyProjektionBelegt) return "plattform_unbelegt";
        if (layout == null || layout.id.isEmpty()) return "layout_ungueltig";
        if (layout.slots.size() < 2 || layout.slots.size() > TachoMehrfachLayout.MAX_SLOTS) {
            return "slot_anzahl_ungueltig";
        }
        if (e.displayBreite <= 0 || e.displayHoehe <= 0) return "display_ungueltig";

        Set<String> slotIds = new HashSet<>();
        Set<String> pakete = new HashSet<>();
        for (int i = 0; i < layout.slots.size(); i++) {
            TachoMehrfachLayout.Slot slot = layout.slots.get(i);
            if (slot.id.isEmpty() || !slotIds.add(slot.id)) return "slot_id_ungueltig";
            if (!TachoNaviApp.gueltigesFremdpaket(slot.paket) || !pakete.add(slot.paket)) {
                return "paket_ungueltig";
            }
            if (!e.startbar.contains(slot.paket)) return "nicht_installiert";
            if (!e.freigegeben.contains(slot.paket)) return "nicht_freigegeben";
            if (e.fahrtSperre == null || !e.fahrtSperre.containsKey(slot.paket)
                    || e.fahrtSperre.get(slot.paket) != null) return "fahrt_sperre";
            if (!slot.liegtIn(e.displayBreite, e.displayHoehe)) return "slot_ausserhalb";
            for (int j = 0; j < i; j++) {
                if (slot.ueberlappt(layout.slots.get(j))) return "slots_ueberlappen";
            }
        }
        return null;
    }
}
