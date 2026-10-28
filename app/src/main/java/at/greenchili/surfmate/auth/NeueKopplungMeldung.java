package at.greenchili.surfmate.auth;

/**
 * 0.1.24 (Koordination 28.10.): Nach jeder neuen Kopplung mit Code – direkt oder über die Brücke – erfährt es der
 * Besitzer: Telegram (falls eingerichtet) und ein Band am Auto-Display, „Neues Gerät verbunden: Name. Warst du das
 * nicht? … entfernen“. Keine zusätzliche Rückfrage: den Code sieht nur der Besitzer.
 */
public final class NeueKopplungMeldung {

    private NeueKopplungMeldung() {}

    static final long BAND_MS = 20_000L;

    /** Name für die Meldung: gekürzt, ohne Zeilenumbrüche und Markdown-Zeichen (Telegram). */
    static String anzeigeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\r\\n\\t*_`\\[\\]]", " ").trim();
        if (n.isEmpty()) n = "?";
        return n.length() > 40 ? n.substring(0, 40) + "…" : n;
    }

    public static void melden(String name) {
        String n = anzeigeName(name);
        String titel = at.greenchili.surfmate.server.Messages.get("notifications.neue_kopplung_titel", n);
        String text = at.greenchili.surfmate.server.Messages.get("notifications.neue_kopplung_text");
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(titel + "\n" + text);
        } catch (Throwable ignored) {
        }
        try {
            at.greenchili.surfmate.byd.MessageOverlayController.showBand(titel, text, "knapp", BAND_MS);
        } catch (Throwable ignored) {
        }
    }
}
