package at.greenchili.surfmate.auth;

/**
 * 0.1.24 (Koordination 28.10.): Nach jeder neuen Kopplung mit Code – direkt oder über die Brücke – erfährt es der
 * Besitzer: Telegram (falls eingerichtet) und ein Band am Auto-Display, „Neues Gerät verbunden: Name. Warst du das
 * nicht? … entfernen“. Keine zusätzliche Rückfrage: den Code sieht nur der Besitzer.
 *
 * <p>0.1.25: Telegram bekommt einen Knopf [Entfernen]. Er trägt nur einen Einmal-Griff (16 hex, 24 h gültig, nur im
 * Arbeitsspeicher), nie die Kopplungs-id; eingelöst wird er über {@code POST /api/kopplung/entfernen/<ref>} (nur Login).
 */
public final class NeueKopplungMeldung {

    private NeueKopplungMeldung() {}

    static final long BAND_MS = 20_000L;
    static final long GRIFF_MS = 24 * 3600_000L;
    static final int MAX_GRIFFE = 32;

    private static final java.security.SecureRandom ZUFALL = new java.security.SecureRandom();
    /** ref -> {kid, bis} */
    private static final java.util.LinkedHashMap<String, Object[]> GRIFFE = new java.util.LinkedHashMap<>();

    /** Name für die Meldung: gekürzt, ohne Zeilenumbrüche und Markdown-Zeichen (Telegram). */
    static String anzeigeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\r\\n\\t*_`\\[\\]]", " ").trim();
        if (n.isEmpty()) n = "?";
        return n.length() > 24 ? n.substring(0, 24) + "…" : n;
    }

    /** Einmal-Griff für den Entfernen-Knopf anlegen (ältester fliegt bei mehr als {@link #MAX_GRIFFE}). */
    static synchronized String griff(String kid, long jetzt) {
        byte[] b = new byte[8];
        ZUFALL.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        String ref = sb.toString();
        GRIFFE.put(ref, new Object[] {kid, jetzt + GRIFF_MS});
        while (GRIFFE.size() > MAX_GRIFFE) GRIFFE.remove(GRIFFE.keySet().iterator().next());
        return ref;
    }

    /** Griff einlösen (einmal): Kopplungs-id oder null (unbekannt, abgelaufen, schon benutzt). */
    public static synchronized String einloesen(String ref, long jetzt) {
        Object[] e = ref == null ? null : GRIFFE.remove(ref);
        if (e == null || (Long) e[1] <= jetzt) return null;
        return (String) e[0];
    }

    public static void melden(String name, String kid) {
        String n = anzeigeName(name);
        try {
            String ref = kid == null ? null : griff(kid, System.currentTimeMillis());
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyNeueKopplung(ref, n);
        } catch (Throwable ignored) {
        }
        try {
            // Band kurz (Kern 12, Freeze 0.1.24: lange Zeilen wurden abgeschnitten); Telegram behält den Langtext
            at.greenchili.surfmate.byd.MessageOverlayController.showBand(
                    at.greenchili.surfmate.server.Messages.get("notifications.neue_kopplung_band_titel", n),
                    at.greenchili.surfmate.server.Messages.get("notifications.neue_kopplung_band_text"), "knapp", BAND_MS);
        } catch (Throwable ignored) {
        }
    }
}
