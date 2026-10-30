package at.greenchili.surfmate.bruecke;

import java.util.Arrays;
import java.util.List;

/** Testzugang zu paketinternen Brücken-Listen (0.1.30 Kern Sicherheit). */
public final class BrueckeTestZugang {

    private BrueckeTestZugang() {}

    public static List<String> kameraPfade() {
        return Arrays.asList(BrueckeWeiterleitung.KAMERA_PFADE);
    }

    public static boolean istStandbildPfad(String p) {
        return BrueckeWeiterleitung.istStandbildPfad(p);
    }

    public static boolean istKameraPfad(String p) {
        return BrueckeWeiterleitung.istKameraPfad(p);
    }
}
