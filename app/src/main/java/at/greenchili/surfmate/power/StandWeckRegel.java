package at.greenchili.surfmate.power;

/**
 * 0.1.19 (Kern, A+): Entscheidung, ob der Stand-Keep-alive im 8-min-Takt ein volles {@code PowerManager.wakeUp}
 * („ACC_ON“) schickt. Reine Logik ohne Android.
 *
 * <p>Hintergrund: Das 8-min-wakeUp soll nur den Wachzustand des Systems (DiLink 3: USB-VBUS folgt ihm) wieder
 * herstellen, wenn er im Stand weggedriftet ist. Es lässt aber das Panel kurz aufleuchten, bis der Keep-alive es
 * wieder abdunkelt – sichtbar am geparkten Auto (A+). Ist das System noch wach ({@code isInteractive()} = true),
 * bringt das wakeUp nichts außer dem Aufleuchten → weglassen. Die Schienen (MCU/USB/SD) werden unabhängig davon
 * weiter alle 8 min neu gesetzt.
 *
 * <p>Wecken bleibt: bei der SD-Schienen-Rettung (eigener Zweck, Backoff) · wenn das System schläft
 * ({@code interaktiv = false}) · wenn der Zustand unlesbar ist ({@code null}) – so wird die Erreichbarkeit nie
 * schlechter als bisher.
 */
public final class StandWeckRegel {

    private StandWeckRegel() {}

    /**
     * @param sdRettung  Takt ist eine SD-Schienen-Rettung
     * @param interaktiv {@code PowerManager.isInteractive()}, {@code null} = unbekannt
     * @return true = volles wakeUp schicken (bisheriges Verhalten)
     */
    public static boolean vollesWecken(boolean sdRettung, Boolean interaktiv) {
        if (sdRettung) return true;
        return !Boolean.TRUE.equals(interaktiv);
    }
}
