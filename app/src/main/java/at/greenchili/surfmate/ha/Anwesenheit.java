package at.greenchili.surfmate.ha;

/**
 * H5 Ankunft/Abfahrt als Smart-Home-Ereignis ohne Koordinaten: nur der Name einer gespeicherten Zone
 * (SafeLocationManager, mit dessen Austritts-Hysterese).
 * <ul>
 *   <li><b>Ankunft</b>: Auto steht in einer Zone und ist aus (ACC aus) – einmal je Aufenthalt. Durchfahren
 *       ohne Abstellen meldet nichts.</li>
 *   <li><b>Abfahrt</b>: nach einer gemeldeten Ankunft hat das Auto die Zone verlassen.</li>
 * </ul>
 * Rein, testbar. {@link #takt} liefert {event, ort} oder null; bei Zonenwechsel ohne Zwischenstopp zuerst die Abfahrt.
 */
public final class Anwesenheit {

    private String gemeldet;   // Zone mit gemeldeter Ankunft, sonst null

    public Anwesenheit() { }

    /** Nach Daemon-Neustart: zuletzt gemeldete Zone (keine doppelte Ankunft). */
    public Anwesenheit(String gemeldet) {
        this.gemeldet = gemeldet == null || gemeldet.isEmpty() ? null : gemeldet;
    }

    public String[] takt(String zone, boolean autoAus) {
        if (gemeldet != null && !gemeldet.equals(zone)) {
            // Zone „weg“ bei stehendem Auto = GPS verloren (Garage, Neustart) – keine Abfahrt
            if (zone == null && autoAus) return null;
            String alt = gemeldet;
            gemeldet = null;
            return new String[]{"abfahrt", alt};
        }
        if (gemeldet == null && zone != null && autoAus) {
            gemeldet = zone;
            return new String[]{"ankunft", zone};
        }
        return null;
    }

    public String gemeldet() {
        return gemeldet;
    }
}
