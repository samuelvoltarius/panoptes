package at.greenchili.surfmate.wallbox;

/**
 * Fehler mit festem Code aus WALLBOX_VERTRAG §3 ({@code fehler}) bzw. §6 (HTTP-Status).
 * Die Meldung enthält nie URL, Token oder Antwortinhalt der Quelle.
 */
public final class WallboxFehler extends Exception {

    public final String code;
    public final int http;

    public WallboxFehler(String code, int http) {
        super(code);
        this.code = code;
        this.http = http;
    }

    public WallboxFehler(String code) {
        this(code, 400);
    }
}
