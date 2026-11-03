package at.greenchili.surfmate.tacho;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reiner Transaktionsvertrag fuer spaetere Slot-Ressourcen.
 *
 * <p>Neue Slots werden unsichtbar vorbereitet. Erst wenn alle bereit sind, darf ein einziger
 * Commit sie sichtbar machen. Jeder Fehler fuehrt ueber {@link Operationen#normaleInstrumente}
 * zur normalen Instrumentenanzeige; ein halbes Layout ist nie ein gueltiges Ergebnis.
 */
public final class TachoMehrfachWechselPlan {
    public interface Operationen {
        void vorbereiten(TachoMehrfachLayout.Slot slot) throws Exception;
        void commit(TachoMehrfachLayout ziel) throws Exception;
        void zurueckrollen(TachoMehrfachLayout.Slot slot) throws Exception;
        void freigeben(TachoMehrfachLayout.Slot slot) throws Exception;
        void normaleInstrumente() throws Exception;
        void fehler(String schritt, String slotId, Throwable fehler);
    }

    private TachoMehrfachWechselPlan() {}

    public static boolean wechseln(TachoMehrfachLayout alt, TachoMehrfachLayout ziel,
                                    Operationen operationen) {
        if (ziel == null || operationen == null) return false;
        List<TachoMehrfachLayout.Slot> vorbereitet = new ArrayList<>();
        try {
            for (TachoMehrfachLayout.Slot slot : ziel.slots) {
                operationen.vorbereiten(slot);
                vorbereitet.add(slot);
            }
            operationen.commit(ziel);
        } catch (Throwable fehler) {
            operationen.fehler("vorbereiten_oder_commit", "", fehler);
            rollbackRueckwaerts(vorbereitet, operationen);
            normaleInstrumente(operationen);
            return false;
        }

        boolean sauber = true;
        List<TachoMehrfachLayout.Slot> alteSlots = alt == null
                ? Collections.<TachoMehrfachLayout.Slot>emptyList() : alt.slots;
        for (int i = alteSlots.size() - 1; i >= 0; i--) {
            TachoMehrfachLayout.Slot slot = alteSlots.get(i);
            try {
                operationen.freigeben(slot);
            } catch (Throwable fehler) {
                sauber = false;
                operationen.fehler("freigeben", slot.id, fehler);
            }
        }
        if (!sauber) normaleInstrumente(operationen);
        return sauber;
    }

    public static boolean stoppen(TachoMehrfachLayout aktiv, Operationen operationen) {
        if (operationen == null) return false;
        boolean sauber = true;
        List<TachoMehrfachLayout.Slot> slots = aktiv == null
                ? Collections.<TachoMehrfachLayout.Slot>emptyList() : aktiv.slots;
        for (int i = slots.size() - 1; i >= 0; i--) {
            TachoMehrfachLayout.Slot slot = slots.get(i);
            try {
                operationen.freigeben(slot);
            } catch (Throwable fehler) {
                sauber = false;
                operationen.fehler("freigeben", slot.id, fehler);
            }
        }
        return normaleInstrumente(operationen) && sauber;
    }

    private static void rollbackRueckwaerts(List<TachoMehrfachLayout.Slot> slots,
                                             Operationen operationen) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            TachoMehrfachLayout.Slot slot = slots.get(i);
            try {
                operationen.zurueckrollen(slot);
            } catch (Throwable fehler) {
                operationen.fehler("rollback", slot.id, fehler);
            }
        }
    }

    private static boolean normaleInstrumente(Operationen operationen) {
        try {
            operationen.normaleInstrumente();
            return true;
        } catch (Throwable fehler) {
            operationen.fehler("instrumente", "", fehler);
            return false;
        }
    }
}
