package at.greenchili.surfmate.handy;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.ble.BleNaehe;
import at.greenchili.surfmate.byd.routing.FahrzeugAktionen;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 0.1.38.8 „Aufsperren beim Annähern“ (04.11.2026, Auflagen der Koordination, ANNAEHERUNG_VERTRAG.md).
 * Eigener Schalter {@code fahrzeugAktionen.annaeherung} (Standard AUS, nur mit {@code aufsperren}, Warnsatz).
 *
 * <p>Ablauf: das Handy meldet sich mit einer Signatur seines Nähe-Schlüssels samt Standort ({@link #melden}). Erst dann
 * – nie dauerhaft – scannt das Auto kurz nach der BLE-Bake des Handys. Aufgesperrt wird nur, wenn ALLES stimmt:
 * Schalter, Recht, gültiger Nähe-Schlüssel, Signatur, Handy-Genauigkeit ≤ {@link #MAX_GENAUIGKEIT_M} m und Abstand
 * ≤ {@link #MAX_ABSTAND_M} m zum Auto, Bake gesehen, Auto aus, verriegelt, kein Wächter-Ereignis, und nur einmal je
 * Annäherung. Danach: bleibt {@link #ZU_NACH_MS} jede Tür zu, sperrt das Auto wieder zu und liest das Schloss zurück;
 * klappt das nicht, sofort Telegram „Auto ist offen“ und die Automatik geht aus.
 *
 * <p>Selbst-Abschaltung: Kopplung entfernt, Nähe-Schlüssel abgelaufen, {@link #FEHLVERSUCHE} ungültige Signaturen in
 * Folge, Wiederzusperren misslungen. Standort des Handys nur im Speicher für die Rechnung, nie gespeichert oder geloggt.
 * Fail-closed: fehlt etwas (kein Bluetooth, kein GPS, Schloss unlesbar), passiert nichts.
 */
public final class Annaeherung {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Annaeherung");

    public static final double MAX_ABSTAND_M = 25, MAX_GENAUIGKEIT_M = 20, MAX_AUTO_GENAUIGKEIT_M = 50;
    public static final long ZU_NACH_MS = 60_000L, SCHLOSS_PRUEF_MS = 15_000L, TUER_TAKT_MS = 2_000L;
    /** Einmal je Annäherung: wieder scharf nach so langer Ruhe dieser Kopplung (oder Zündung an). */
    public static final long NEU_SCHARF_MS = 10 * 60_000L;
    public static final int FEHLVERSUCHE = 3;
    /** Scan-Ratenlimit (12 V): 1 je 30 s, 20 je Stunde, je Kopplung. */
    public static final long SCAN_ABSTAND_MS = 30_000L;
    public static final int SCANS_JE_STUNDE = 20;

    /** Stand je Kopplung ({@code annaeherung_stand}). */
    public static final String PRUEFE = "pruefe", AUFGESPERRT = "aufgesperrt", ABGELEHNT = "abgelehnt",
            WIEDER_ZU = "wieder_zu", TUER_OFFEN = "tuer_offen", OFFEN = "offen";

    /** Auto, Bluetooth, Router und Meldung – im Test ersetzt. */
    public interface Umgebung {
        long jetzt();
        /** {lat, lon, genauigkeitM} des Autos oder null. */
        double[] autoPosition();
        /** Auto aus (frisches power_level 0, ACC aus). */
        boolean autoAus();
        /** 1 = verriegelt, 0 = offen, −1 = unlesbar. */
        int schloss();
        boolean waechterEreignis();
        /** Kurzer Scan: {@code {ok, gesehen, rssi}} bzw. {@code {ok:false, zustand}}; null = keine Antwort. */
        JSONObject bleSuchen(java.util.List<String> baken);
        /** 1 = eine Tür offen, 0 = alle zu, −1 = unlesbar. */
        int tuerOffen();
        /** Aufsperren mit dieser Freigabe (baut HandyApiHandler, Auslösung Quelle Annäherung). */
        VehicleCommandRouter.CommandResult aufsperren(HandySignatur.Freigabe f, String wer);
        VehicleCommandRouter.CommandResult zusperren();
        void melden(String telegramKey, String pushKey, Object... args);
        /** Im Hintergrund ausführen (Test: sofort). */
        void spaeter(Runnable r);
        /** Warten (Test: Uhr vorstellen). */
        void warte(long ms) throws InterruptedException;
    }

    static final class Zustand {
        long letzteMeldung = Long.MIN_VALUE / 2;
        boolean schonAusgeloest;
        int fehlversuche;
        String status, grund;
        final Deque<Long> scans = new ArrayDeque<>();
    }

    private static final Map<String, Zustand> ZUSTAND = new HashMap<>();
    private static final HandySignatur SIGNATUR = new HandySignatur();

    private Annaeherung() {}

    // ------------------------------------------------------------------ Meldung vom Handy

    /**
     * {@code {aktion:"annaeherung", lat, lon, genauigkeitM, signatur:{zeit, nonce, sig}}}. Antwort sofort
     * {@code {ok:true, status:"pruefe"}} (Scan läuft im Hintergrund) oder {@code {ok:false, grund}} bzw.
     * {@code {ok:true, status:"abgelehnt", grund}}.
     */
    public static JSONObject melden(JSONObject req, String kid, Kopplungen k, Umgebung u) throws Exception {
        long jetzt = u.jetzt();
        if (!FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG)) return fehler(FahrzeugAktionen.GRUND_AUS);
        if (!k.istHandy(kid) || !k.hatRecht(kid, Kopplungen.RECHT_AUFSPERREN)) return fehler("nicht_erlaubt");
        String schluessel = k.naeheSchluessel(kid, jetzt);
        byte[] bake = k.naeheBake(kid);
        if (schluessel == null || bake == null) {
            if (k.naeheBis(kid) > 0) {
                // abgelaufen: Automatik aus, Hinweis
                k.naeheLoeschen(kid);
                selbstAus("naehe_abgelaufen");
            }
            return fehler("kein_naehe_schluessel");
        }
        double lat = req.optDouble("lat", Double.NaN), lon = req.optDouble("lon", Double.NaN);
        double acc = req.optDouble("genauigkeitM", Double.NaN);
        if (!gueltig(lat, -90, 90) || !gueltig(lon, -180, 180) || !gueltig(acc, 0, 100_000)) return fehler("werte");
        Zustand z;
        synchronized (ZUSTAND) {
            z = ZUSTAND.computeIfAbsent(kid, x -> new Zustand());
            if (!scanErlaubt(z, jetzt)) return fehler("ratenlimit");
        }
        JSONObject s = req.optJSONObject("signatur");
        String name = k.anzeigeName(kid);
        HandySignatur.Ergebnis e = s == null ? null : SIGNATUR.pruefeAnnaeherung(schluessel,
                signierteAktion(lat, lon, acc), kid, s.optLong("zeit", -1), s.optString("nonce", ""),
                s.optString("sig", ""), name, jetzt);
        if (e == null || e.grund != null) {
            int n;
            synchronized (ZUSTAND) {
                n = ++z.fehlversuche;
            }
            logger.warn("Annäherung „" + name + "“ abgelehnt: " + (e == null ? HandySignatur.GRUND_SIGNATUR : e.grund));
            if (n >= FEHLVERSUCHE) selbstAus("fehlversuche");
            return fehler(e == null ? HandySignatur.GRUND_SIGNATUR : e.grund);
        }
        synchronized (ZUSTAND) {
            z.fehlversuche = 0;
            // einmal je Annäherung: nach NEU_SCHARF_MS Ruhe dieser Kopplung wieder erlaubt
            if (jetzt - z.letzteMeldung >= NEU_SCHARF_MS) z.schonAusgeloest = false;
            z.letzteMeldung = jetzt;
            if (z.schonAusgeloest) return stand(z, ABGELEHNT, "schon_ausgeloest");
        }
        if (acc > MAX_GENAUIGKEIT_M) return stand(z, ABGELEHNT, "ungenau");
        String grund = autoGrund(u, lat, lon);
        if (grund != null) return stand(z, ABGELEHNT, grund);
        stand(z, PRUEFE, null);
        final HandySignatur.Freigabe freigabe = e.freigabe;
        u.spaeter(() -> pruefenUndAufsperren(z, bake, freigabe, name, lat, lon, u));
        return new JSONObject().put("ok", true).put("status", PRUEFE);
    }

    /** Was signiert wird: Standort mit festen Stellen, damit Handy und Auto dieselbe Zeichenkette bauen. */
    public static String signierteAktion(double lat, double lon, double acc) {
        return HandySignatur.ANNAEHERUNG + ":" + String.format(Locale.ROOT, "%.6f", lat) + ":"
                + String.format(Locale.ROOT, "%.6f", lon) + ":" + Math.round(acc);
    }

    /** Grund, warum das Auto gerade nicht passt, sonst null (Reihenfolge: billig zuerst). */
    static String autoGrund(Umgebung u, double lat, double lon) {
        if (!u.autoAus()) return "auto_an";
        if (u.waechterEreignis()) return "waechter_ereignis";
        int s = u.schloss();
        if (s != 1) return s == 0 ? "nicht_verriegelt" : "schloss_unbekannt";
        double[] a = u.autoPosition();
        if (a == null || !gueltig(a[0], -90, 90) || !gueltig(a[1], -180, 180)) return "auto_ort_unbekannt";
        if (a.length > 2 && a[2] > MAX_AUTO_GENAUIGKEIT_M) return "auto_ort_ungenau";
        if (abstandM(lat, lon, a[0], a[1]) > MAX_ABSTAND_M) return "zu_weit";
        return null;
    }

    static void pruefenUndAufsperren(Zustand z, byte[] bake, HandySignatur.Freigabe f, String name, double lat,
                                     double lon, Umgebung u) {
        try {
            JSONObject b = u.bleSuchen(BleNaehe.erwartet(bake, u.jetzt()));
            if (b == null || !b.optBoolean("ok", false)) {
                stand(z, ABGELEHNT, b == null ? "ble_keine_antwort" : "ble_" + b.optString("zustand", "fehler"));
                return;
            }
            if (!b.optBoolean("gesehen", false)) {
                stand(z, ABGELEHNT, "nicht_in_der_naehe");
                return;
            }
            // der Scan hat gedauert: Auto noch einmal frisch prüfen
            String grund = autoGrund(u, lat, lon);
            if (grund != null) {
                stand(z, ABGELEHNT, grund);
                return;
            }
            synchronized (ZUSTAND) {
                if (z.schonAusgeloest) {
                    stand(z, ABGELEHNT, "schon_ausgeloest");
                    return;
                }
                z.schonAusgeloest = true;
            }
            VehicleCommandRouter.CommandResult r = u.aufsperren(f, name);
            if (r == null || r.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                stand(z, ABGELEHNT, r == null ? "fehlgeschlagen" : r.grund != null ? r.grund
                        : r.outcome.name().toLowerCase(Locale.ROOT));
                return;
            }
            stand(z, AUFGESPERRT, null);
            u.melden("aktionen.annaeherung_auf", "aktionen.push_annaeherung_auf", name);
            wiederZusperren(z, u);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            logger.warn("Annäherung: " + t.getClass().getSimpleName());
            stand(z, ABGELEHNT, "fehler");
        }
    }

    /** 60 s jede Tür zu → zusperren, Schloss zurücklesen; scheitert das → „Auto ist offen“ und Automatik aus. */
    static void wiederZusperren(Zustand z, Umgebung u) throws InterruptedException {
        long ende = u.jetzt() + ZU_NACH_MS;
        while (u.jetzt() < ende) {
            if (u.tuerOffen() == 1) {
                stand(z, TUER_OFFEN, null);
                logger.info("Annäherung: Tür geöffnet, bleibt offen");
                return;
            }
            u.warte(TUER_TAKT_MS);
        }
        VehicleCommandRouter.CommandResult r = u.zusperren();
        long bis = u.jetzt() + SCHLOSS_PRUEF_MS;
        boolean zu = false;
        while (true) {
            if (u.schloss() == 1) {
                zu = true;
                break;
            }
            if (u.jetzt() >= bis) break;
            u.warte(TUER_TAKT_MS);
        }
        if (zu) {
            stand(z, WIEDER_ZU, null);
            u.melden("aktionen.annaeherung_wieder_zu", "aktionen.push_annaeherung_wieder_zu");
            return;
        }
        stand(z, OFFEN, r == null || r.outcome == null ? "null" : r.outcome.name().toLowerCase(Locale.ROOT));
        u.melden("aktionen.offen", "aktionen.push_offen");
        selbstAus("zusperren_fehlgeschlagen");
    }

    // ------------------------------------------------------------------ Hilfen

    /** Zündung an: jede Kopplung darf bei der nächsten Annäherung wieder. */
    public static void zuendungAn() {
        synchronized (ZUSTAND) {
            for (Zustand z : ZUSTAND.values()) z.schonAusgeloest = false;
        }
    }

    /** Automatik selbst abschalten (mit Telegram-Hinweis über FahrzeugAktionen). */
    public static void selbstAus(String grund) {
        if (!FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG)) return;
        logger.warn("Aufsperren beim Annähern schaltet sich ab: " + grund);
        FahrzeugAktionen.allesAus(true, grund);
    }

    /** Stand dieser Kopplung für {@code annaeherung_stand}. */
    public static JSONObject stand(String kid) throws Exception {
        Zustand z;
        synchronized (ZUSTAND) {
            z = ZUSTAND.get(kid);
        }
        JSONObject o = new JSONObject().put("ok", true).put("an", FahrzeugAktionen.an(FahrzeugAktionen.ANNAEHERUNG));
        if (z != null && z.status != null) {
            o.put("status", z.status);
            if (z.grund != null) o.put("grund", z.grund);
        }
        return o;
    }

    private static JSONObject stand(Zustand z, String status, String grund) {
        synchronized (ZUSTAND) {
            z.status = status;
            z.grund = grund;
        }
        if (grund != null) logger.info("Annäherung: " + status + " (" + grund + ")");
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("status", status);
            if (grund != null) o.put("grund", grund);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static boolean scanErlaubt(Zustand z, long jetzt) {
        while (!z.scans.isEmpty() && z.scans.peekFirst() <= jetzt - 3_600_000L) z.scans.pollFirst();
        if (!z.scans.isEmpty() && jetzt - z.scans.peekLast() < SCAN_ABSTAND_MS) return false;
        if (z.scans.size() >= SCANS_JE_STUNDE) return false;
        z.scans.addLast(jetzt);
        return true;
    }

    static boolean gueltig(double v, double min, double max) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= min && v <= max;
    }

    /** Abstand in Metern (Haversine). */
    static double abstandM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000d, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static JSONObject fehler(String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("grund", grund);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Nur für Tests: alles vergessen. */
    static void zuruecksetzen() {
        synchronized (ZUSTAND) {
            ZUSTAND.clear();
        }
    }
}
