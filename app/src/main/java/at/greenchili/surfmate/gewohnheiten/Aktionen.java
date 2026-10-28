package at.greenchili.surfmate.gewohnheiten;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2 und §6): die einzigen Bedienhandlungen, die mitgeschrieben werden – eine
 * Erlaubnisliste, kein Verbot. Aufsperren, Hupe, Licht, Blinker, Auto finden, Fenster, Türen, Spiegel stehen nicht
 * darin und werden deshalb nie mitgeschrieben und nie vorgeschlagen (REGEL AUFSPERREN, A+).
 */
public final class Aktionen {

    public static final String KLIMA_AN = "klima_an", KLIMA_AUS = "klima_aus", KLIMA_TEMP = "klima_temp";
    public static final String SITZHEIZUNG = "sitzheizung", SITZLUEFTUNG = "sitzlueftung", LENKRADHEIZUNG = "lenkradheizung";
    public static final String WALLBOX_STROM = "wallbox_strom", WALLBOX_START = "wallbox_start", WALLBOX_STOPP = "wallbox_stopp";
    public static final String WAECHTER_AN = "waechter_an", WAECHTER_AUS = "waechter_aus";
    public static final String LADEPLAN = "ladeplan";
    public static final String CAMPING_AN = "camping_an", CAMPING_AUS = "camping_aus";

    /** Wird mitgeschrieben. */
    public static final Set<String> MITSCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            KLIMA_AN, KLIMA_AUS, KLIMA_TEMP, SITZHEIZUNG, SITZLUEFTUNG, LENKRADHEIZUNG,
            WALLBOX_STROM, WALLBOX_START, WALLBOX_STOPP, WAECHTER_AN, WAECHTER_AUS, LADEPLAN, CAMPING_AN, CAMPING_AUS)));

    /**
     * Darf als Gewohnheit vorgeschlagen werden. Nicht: „Wächter aus“ (schwächt den Schutz, Entscheidung F1) und
     * Ladeplan-Änderungen (der Ladeplan ist selbst schon ein Plan) – beide werden nur mitgeschrieben.
     */
    public static final Set<String> VORSCHLAGBAR = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            KLIMA_AN, KLIMA_AUS, KLIMA_TEMP, SITZHEIZUNG, SITZLUEFTUNG, LENKRADHEIZUNG,
            WALLBOX_STROM, WALLBOX_START, WALLBOX_STOPP, WAECHTER_AN, CAMPING_AN, CAMPING_AUS)));

    /** Wärmende Handlungen: Zusatzbedingung „unter X °C“ möglich. */
    static final Set<String> WAERMEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            KLIMA_AN, SITZHEIZUNG, LENKRADHEIZUNG)));

    /** Wallbox: Zusatzbedingung „wenn angesteckt“ möglich. */
    static final Set<String> WALLBOX = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            WALLBOX_STROM, WALLBOX_START, WALLBOX_STOPP)));

    private Aktionen() {}
}
