package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.handy.HandySignatur;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 0.1.39 Sicherheitspunkt (PLAN_BESITZER_SIGNATUR_013810): heikle Besitzer-Aufrufe (Rechte vergeben, koppeln, entfernen,
 * Besitzer wechseln) brauchen zusätzlich zu {@code bestaetigt:true} eine Signatur des Biometrie-Geräteschlüssels.
 *
 * <p>Signiert wird wie beim Aufsperren {@code panoptes-handy-v1|<zweck>|<kopplungId>|<zeitMs>|<nonce>}
 * ({@link HandySignatur#nachricht}); der {@link #zweck} enthält die Nutzdaten, damit ein Weg dazwischen (Brücke) Art,
 * Rechte oder Ziel nicht tauschen kann. Keystore/Secure Enclave: {@code signatur:{zeit, nonce, sig}}; Passkey (Web-App):
 * {@code webauthn:{zeit, nonce, credentialId, clientDataJSON, authenticatorData, signature}} mit der Challenge
 * {@code WebAuthn.challenge(zweck, …)}. Der Schlüssel muss {@link Kopplungen#schluesselBesitzer} sein.
 *
 * <p>Display, Login und Telegram {@code /kopplungen} laufen nicht hier durch und bleiben unverändert (nie aussperren).
 */
public final class BesitzerSignatur {

    private BesitzerSignatur() {}

    public static final String GRUND_NOETIG = "signatur_noetig", GRUND_SCHLUESSEL_FEHLT = "schluessel_fehlt",
            GRUND_UNBESTAETIGT = "schluessel_unbestaetigt";

    /** Fehlversuche je Kopplung und Minute, danach {@code zu_oft} (eigenes Limit, verbraucht nicht das des Besitzers). */
    static final int MAX_FEHL_JE_MINUTE = 5;
    private static final java.util.Map<String, java.util.ArrayDeque<Long>> FEHL = new java.util.HashMap<>();

    /** Muss dieser Aufruf signiert sein? Ablehnen einer Anfrage nie (damit man sich nie ausbremst), Wächter/Dienst nicht. */
    static boolean pflicht(String pfad, JSONObject req) {
        switch (pfad) {
            case BesitzerApiHandler.CODE:
            case BesitzerApiHandler.ENTFERNEN:
            case BesitzerApiHandler.WEITERGEBEN:
            case BesitzerApiHandler.ABGEBEN:
            case BesitzerApiHandler.SELBST:
                return true;
            case BesitzerApiHandler.ENTSCHEIDEN:
                return req.optBoolean("erlauben", false);
            default:
                return false;
        }
    }

    /**
     * Zweck mit den Nutzdaten: {@code besitzer.<kurz>:<feld>=<wert>;…}, Felder fest je Pfad und alphabetisch, Listen
     * sortiert und mit {@code ,} verbunden. null = nicht signierbar (Pfad ohne Zweck oder Wert mit {@code ; = , |}).
     */
    public static String zweck(String pfad, JSONObject req) {
        String kurz;
        List<String> teile = new ArrayList<>();
        switch (pfad) {
            case BesitzerApiHandler.CODE:
                kurz = "code";
                teile.add(feld("art", req.optString("art", "")));
                // „dauer“ nur, wenn mitgeschickt (der Kern nimmt sonst seinen Standard)
                if (req.has("dauer")) teile.add(feld("dauer", req.optString("dauer", "")));
                teile.add(feld("rechte", liste(req.optJSONArray("rechte"))));
                break;
            case BesitzerApiHandler.ENTSCHEIDEN:
                kurz = "entscheiden";
                teile.add(feld("erlauben", req.optBoolean("erlauben", false) ? "1" : "0"));
                teile.add(feld("ref", req.optString("ref", "")));
                break;
            case BesitzerApiHandler.ENTFERNEN:
                kurz = "entfernen";
                teile.add(feld("id", req.optString("id", "")));
                break;
            case BesitzerApiHandler.WEITERGEBEN:
                kurz = "weitergeben";
                teile.add(feld("id", req.optString("id", "")));
                break;
            case BesitzerApiHandler.ABGEBEN:
                kurz = "abgeben";
                break;
            case BesitzerApiHandler.SELBST:
                kurz = "rechte";
                teile.add(feld("rechte", liste(req.optJSONArray("rechte"))));
                break;
            case BesitzerApiHandler.WAECHTER:
                kurz = "waechter";
                teile.add(feld("an", req.optBoolean("an", false) ? "1" : "0"));
                break;
            case BesitzerApiHandler.DIENST:
                kurz = "dienst";
                teile.add(feld("name", req.optString("name", "")));
                break;
            default:
                return null;
        }
        if (teile.contains(null)) return null;
        return "besitzer." + kurz + ":" + String.join(";", teile);
    }

    private static String feld(String name, String wert) {
        if (wert == null || !sauber(wert)) return null;
        return name + "=" + wert;
    }

    private static boolean sauber(String w) {
        return w.indexOf(';') < 0 && w.indexOf('=') < 0 && w.indexOf('|') < 0 && w.length() <= 200;
    }

    private static String liste(JSONArray a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String x = a.optString(i, "");
            if (x.indexOf(',') >= 0 || !sauber(x)) return null;
            if (!l.contains(x)) l.add(x);
        }
        Collections.sort(l);
        return String.join(",", l);
    }

    /**
     * Prüft die Signatur eines Besitzer-Aufrufs. @return null = in Ordnung (signiert und gültig, oder nicht nötig und
     * keine mitgeschickt), sonst der Grund. Eine mitgeschickte Signatur wird auch dort geprüft, wo sie nicht Pflicht ist.
     */
    static String pruefe(Kopplungen k, String kid, String pfad, JSONObject req, long jetzt) {
        boolean dabei = req.has("signatur") || req.has("webauthn");
        if (!pflicht(pfad, req) && !dabei) return null;
        if (zuOftFehl(kid, jetzt)) return "zu_oft";
        String g = grund(k, kid, pfad, req, jetzt);
        if (g != null) fehlMerken(kid, jetzt);
        return g;
    }

    private static String grund(Kopplungen k, String kid, String pfad, JSONObject req, long jetzt) {
        String schluessel = k.schluessel(kid);
        boolean passkey = k.istPasskey(kid);
        // wie beim Aufsperren: „nur Passkey“ mit altem Schlüssel daneben gilt nicht
        if (schluessel == null || (k.nurPasskey(kid) && !passkey)) return GRUND_SCHLUESSEL_FEHLT;
        if (!k.schluesselBesitzer(kid)) return GRUND_UNBESTAETIGT;
        JSONObject s = req.optJSONObject("signatur"), w = req.optJSONObject("webauthn");
        if (s == null && w == null) return GRUND_NOETIG;
        String zweck = zweck(pfad, req);
        if (zweck == null) return HandySignatur.GRUND_SIGNATUR;
        HandySignatur sig = HandyApiHandler.SIGNATUR;   // eine Nonce-Liste mit dem Aufsperren
        // die beiden Wege vertreten sich nie, jede Kopplung hat genau einen
        if (passkey && w != null && s == null) {
            // eigener Zweck: eine Besitzer-Bestätigung gilt nie fürs Aufsperren (und umgekehrt)
            HandySignatur.Ergebnis e = sig.pruefeWebauthn(zweck, schluessel, k.credentialId(kid), k.zaehler(kid), kid,
                    w.optLong("zeit", -1), w.optString("nonce", ""), w.optString("credentialId", ""),
                    w.optString("clientDataJSON", ""), w.optString("authenticatorData", ""),
                    w.optString("signature", ""), k.anzeigeName(kid), jetzt);
            if (e.grund != null) return e.grund;
            if (e.zaehler > 0 && !k.zaehlerSetzen(kid, e.zaehler)) return "speichern";
            return null;
        }
        if (!passkey && s != null && w == null) {
            return sig.pruefe(schluessel, zweck, kid, s.optLong("zeit", -1), s.optString("nonce", ""),
                    s.optString("sig", ""), jetzt);
        }
        return HandySignatur.GRUND_SIGNATUR;
    }

    private static synchronized boolean zuOftFehl(String kid, long jetzt) {
        java.util.ArrayDeque<Long> d = FEHL.get(kid);
        if (d == null) return false;
        while (!d.isEmpty() && jetzt - d.peekFirst() > 60_000L) d.pollFirst();
        return d.size() >= MAX_FEHL_JE_MINUTE;
    }

    private static synchronized void fehlMerken(String kid, long jetzt) {
        if (FEHL.size() > 100) FEHL.clear();
        FEHL.computeIfAbsent(kid, x -> new java.util.ArrayDeque<>()).addLast(jetzt);
    }

    /** Für Tests. */
    static synchronized void fehlLeeren() {
        FEHL.clear();
    }
}
