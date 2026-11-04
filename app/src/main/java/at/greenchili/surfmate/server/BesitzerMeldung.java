package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 0.1.38.8 Besitzer-Handy: Meldungen an den Besitzer (Telegram + Push) über das, was gekoppelte Geräte am Auto ändern.
 * Nur Titel und Gerätename, nie Werte, Schlüssel oder Orte.
 */
public final class BesitzerMeldung {

    private BesitzerMeldung() {}

    /** Stufe einer Anfrage für {@code /api/einstellungen} (rein, Tests). */
    public static Einstellungen.Stufe stufe(boolean extern, String kid, boolean webSitzung, String webKid, Kopplungen k) {
        String id = extern ? kid : webSitzung ? webKid : null;
        if (id == null) return extern || webSitzung ? Einstellungen.Stufe.KEIN_RECHT : Einstellungen.Stufe.LOGIN;
        // Browser, den der Telegram-Besitzer angemeldet hat (Art web), steht dem Besitzer gleich
        if (k.istBesitzer(id) || Kopplungen.ART_WEB.equals(k.art(id))) return Einstellungen.Stufe.BESITZER;
        if (!k.einstellungenSchreiben(id)) return Einstellungen.Stufe.KEIN_RECHT;
        // Web-Sitzung behält wie bisher mehr als der reine Kopplungs-Token (Geheimes filtert WebHandyGuard)
        return webSitzung ? Einstellungen.Stufe.BESITZER : Einstellungen.Stufe.KOPPLUNG;
    }

    /** Titel der gespeicherten Felder in der Nutzersprache (höchstens 5, dann „…“). */
    static String titel(List<Feld> felder, JSONObject gespeichert) {
        List<String> t = new ArrayList<>();
        for (Iterator<String> it = gespeichert.keys(); it.hasNext(); ) {
            String key = it.next();
            Feld f = Katalog.finde(felder, key);
            t.add(f == null ? key : Messages.text(f.titelKey, key));
        }
        java.util.Collections.sort(t);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.size() && i < 5; i++) b.append(i == 0 ? "" : ", ").append("„").append(t.get(i)).append("“");
        if (t.size() > 5) b.append(", …");
        return b.toString();
    }

    /** Einstellung(en) über eine Kopplung/Web-Sitzung geändert: Sammelmeldung je Anfrage. */
    public static void einstellungen(List<Feld> felder, JSONObject gespeichert, String kid) {
        if (gespeichert == null || gespeichert.length() == 0 || kid == null) return;
        String name = at.greenchili.surfmate.auth.Kopplungen.get().anzeigeName(kid);
        melde(Messages.text("besitzer.einstellung_geaendert", "Einstellung {0} geändert vom Gerät {1}.",
                titel(felder, gespeichert), name));
    }

    /** Telegram (CRITICAL: Zugang/Besitzer) + Push an die Handys. Fehler bleiben hier. */
    public static void melde(String text) {
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("📱 " + text, "CRITICAL");
        } catch (Throwable ignored) { }
        try {
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    new at.greenchili.surfmate.notifications.NotificationEvent("system.verbindung",
                            at.greenchili.surfmate.notifications.NotificationEvent.Severity.INFO, text, "",
                            "besitzer-" + System.currentTimeMillis(), "/local/system.html", new JSONObject()));
        } catch (Throwable ignored) { }
    }
}
