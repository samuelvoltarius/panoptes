package at.greenchili.surfmate.tacho;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reines, noch nicht an die Fahrzeugprojektion angeschlossenes Slot-Modell.
 *
 * <p>Die Trennung von Preset und Laufzeitbesitz folgt dem Layout-Ansatz von DashCast
 * (Kiroha/byd-dashcast, MIT), ohne dessen Daemon- oder UI-Code zu uebernehmen. Die
 * Herkunft und die technische Abgrenzung stehen in {@code TACHO_MEHRFACH_APPS_ANALYSE.md}.
 */
public final class TachoMehrfachLayout {
    public static final int MAX_SLOTS = 4;

    public final String id;
    public final String name;
    public final List<Slot> slots;

    public TachoMehrfachLayout(String id, String name, List<Slot> slots) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null ? "" : name.trim();
        this.slots = Collections.unmodifiableList(new ArrayList<>(
                slots == null ? Collections.<Slot>emptyList() : slots));
    }

    public static final class Slot {
        public final String id;
        public final String paket;
        public final int x;
        public final int y;
        public final int breite;
        public final int hoehe;

        public Slot(String id, String paket, int x, int y, int breite, int hoehe) {
            this.id = id == null ? "" : id.trim();
            this.paket = paket == null ? "" : paket.trim();
            this.x = x;
            this.y = y;
            this.breite = breite;
            this.hoehe = hoehe;
        }

        boolean liegtIn(int displayBreite, int displayHoehe) {
            if (x < 0 || y < 0 || breite <= 0 || hoehe <= 0) return false;
            long rechts = (long) x + breite;
            long unten = (long) y + hoehe;
            return rechts <= displayBreite && unten <= displayHoehe;
        }

        boolean ueberlappt(Slot andere) {
            return x < andere.x + andere.breite && x + breite > andere.x
                    && y < andere.y + andere.hoehe && y + hoehe > andere.y;
        }
    }
}
