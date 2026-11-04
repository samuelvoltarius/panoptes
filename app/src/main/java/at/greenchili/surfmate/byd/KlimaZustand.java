package at.greenchili.surfmate.byd;

import at.greenchili.surfmate.byd.cloud.BydCloudDataProvider;
import at.greenchili.surfmate.byd.cloud.VehicleCloudSnapshot;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;

/**
 * 0.1.38.7: <b>eine</b> Stelle, an der Panoptes den Klima-Zustand bewertet (Web, Handy-API, Launcher, Telegram, HA-Zustand lesen
 * alle das Ergebnis; Projektinhaber 04.11.: „Klima aus“ bei laufendem Fernklima). Reihenfolge der Belege, wenn das Auto selbst schläft
 * (Zündung aus, SDK meldet 0 ohne Aussagekraft):
 * <ol>
 *   <li>frischer Cloud-Rücklese-Wert {@code airRunState} ({@link KlimaLaeuft}, 1 = läuft, gemessen am 04.11.2026 beim Fernklima);</li>
 *   <li>der eigene erfolgreiche Fern-Start „läuft laut Befehl bis HH:MM“ ({@link FernKlima}, nur Befehlsprotokoll);</li>
 *   <li>sonst „unbekannt“, nie „aus“ nur wegen lokal 0.</li>
 * </ol>
 * Läuft das Auto, gilt der lokale Zustand ({@code acStartState} 0/1) zuerst.
 */
public final class KlimaZustand {

    private KlimaZustand() {}

    /** {@code climate.fern} (laut Befehl) oder null: der letzte erfolgreiche Fern-Start, läuft noch bzw. vor kurzem beendet. */
    public static org.json.JSONObject fernJson() {
        try {
            return FernKlima.json(VehicleCommandRouter.getInstance().fernKlimaSitzung(), System.currentTimeMillis());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @param autoAn       Auto nachweislich an (lokale Klima-Werte gelten) bzw. DiLink 5
     * @param acStartState lokal 1 = an, 0 = aus, sonst −1
     */
    public static KlimaLaeuft.Ergebnis aktuell(boolean autoAn, int acStartState) {
        int air = -1;
        long airAlter = -1;
        try {
            VehicleCloudSnapshot cs = BydCloudDataProvider.getInstance().getSnapshot();
            if (cs != null && cs.airRunState >= 0) {
                air = cs.airRunState;
                long t = cs.vehicleInfoTimestamp > 0 ? Math.min(cs.vehicleInfoTimestamp, cs.receivedAt) : cs.receivedAt;
                airAlter = System.currentTimeMillis() - t;
            }
        } catch (Throwable ignored) {
            // ohne Cloud-Rücklesen bleibt es bei Befehl bzw. unbekannt
        }
        boolean befohlen = false;
        try {
            befohlen = VehicleCommandRouter.getInstance().isRemoteClimateActive();
        } catch (Throwable ignored) {
            // wie oben
        }
        return KlimaLaeuft.bewerte(autoAn, acStartState < 0 ? -1 : acStartState, air, airAlter, befohlen);
    }
}
