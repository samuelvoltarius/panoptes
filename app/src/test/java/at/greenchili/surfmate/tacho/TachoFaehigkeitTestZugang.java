package at.greenchili.surfmate.tacho;

/** Testzugang fuer Tests ausserhalb des Pakets (Quelltext lesen, Show-Pfad des Tachos). */
public final class TachoFaehigkeitTestZugang {
    private TachoFaehigkeitTestZugang() {}

    public static String lies(String rel) throws java.io.IOException { return TachoFaehigkeitTest.lies(rel); }

    public static String showPfad() { return TachoKameraAnsicht.showPfad(TachoKameraAnsicht.HECK, new int[] {1, 2, 300, 200}); }
}
