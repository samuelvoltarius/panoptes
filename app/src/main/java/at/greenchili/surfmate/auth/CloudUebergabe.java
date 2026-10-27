package at.greenchili.surfmate.auth;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BYD-Cloud vom EV-Cockpit an Panoptes übergeben (CLOUD_UEBERGABE_VERTRAG.md, 0.1.21). Einmal, auf ausdrücklichen
 * Wunsch, nach Rückfrage mit Prüfzahl (Display, Panoptes-Web, Telegram); Ende-zu-Ende verschlüsselt:
 * X25519 → HKDF-SHA256 (salt = a_pub‖e_pub, info = "panoptes-cloud-uebergabe/1|"+ref, 28 Byte) → AES-128-GCM
 * (key = okm[0:16], nonce = okm[16:28], aad = info). Der Klartext lebt nur im Speicher und wird nach dem Speichern
 * überschrieben; im Protokoll stehen nur ref, Grund und Kopplungs-id.
 *
 * <p>Je Auto höchstens eine offene Übergabe; 3 Anfragen je Stunde und Kopplung, 6 je Tag und Auto (abgelehnte und
 * abgelaufene zählen mit). Das Recht {@code cloud_uebergabe} gilt nur für genau diese ref bis zum Ablauf.
 */
public final class CloudUebergabe {

    public static final long GUELTIG_MS = 5 * 60_000L;
    static final int MAX_STUNDE_KOPPLUNG = 3;
    static final int MAX_TAG_AUTO = 6;
    static final int MAX_KLARTEXT = 4096;
    static final String INFO = "panoptes-cloud-uebergabe/1|";
    public static final String REF_MUSTER = "r-[0-9a-f]{16}";

    /** Speichern + „Prüfen“ (Laufzeit: BydCloudApiHandler; Tests/Emulator: Attrappe). */
    public interface Einrichter {
        /**
         * @return {@code {gespeichert:bool, verified:bool, grund?:"…"}}; gespeichert=false → nichts gespeichert
         * (grund z. B. inhalt|speichern).
         */
        JSONObject einrichten(JSONObject zugang) throws Exception;
    }

    /** Rückfrage und Meldungen (Display, Telegram). Bekommt nie Zugangsdaten. */
    public interface Melder {
        void anfrage(String ref, String geraet, String pruefzahl);

        void entschieden(String ref, boolean erlaubt);

        void ergebnis(String geraet, boolean verified);

        void protokoll(String zeile);
    }

    /** Antwort an die API: HTTP-Status + JSON. */
    public static final class Antwort {
        public final int status;
        public final JSONObject json;

        public Antwort(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }

        static Antwort fehler(int status, String grund) {
            try {
                return new Antwort(status, new JSONObject().put("ok", false).put("grund", grund));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class Offen {
        final String ref;
        final String kid;
        final String geraet;
        final String pruefzahl;
        final byte[] aPriv;
        final byte[] aPub;
        final long ablauf;
        String status = "wartet";   // wartet | erlaubt | abgelehnt | abgelaufen | uebergeben

        Offen(String ref, String kid, String geraet, String pruefzahl, byte[] aPriv, byte[] aPub, long ablauf) {
            this.ref = ref;
            this.kid = kid;
            this.geraet = geraet;
            this.pruefzahl = pruefzahl;
            this.aPriv = aPriv;
            this.aPub = aPub;
            this.ablauf = ablauf;
        }

        void vergessen() {
            Arrays.fill(aPriv, (byte) 0);
        }
    }

    private final LongSupplier uhr;
    private final SecureRandom zufall;
    private final Einrichter einrichter;
    private final Melder melder;
    /** ref → Übergabe (offen oder kürzlich entschieden, damit GET den Status noch zeigt). */
    private final Map<String, Offen> alle = new HashMap<>();
    private final Map<String, Deque<Long>> jeKopplung = new HashMap<>();
    private final Deque<Long> jeAuto = new ArrayDeque<>();

    public CloudUebergabe(LongSupplier uhr, SecureRandom zufall, Einrichter einrichter, Melder melder) {
        this.uhr = uhr;
        this.zufall = zufall;
        this.einrichter = einrichter;
        this.melder = melder;
    }

    // ------------------------------------------------------------------ Ablauf

    /** Schritt 1 (Cockpit, Kopplungs-JWT art=cockpit): neue Anfrage. */
    public synchronized Antwort anfrage(String kid, String art, String geraet) throws JSONException {
        if (kid == null || kid.isEmpty()) return Antwort.fehler(401, "kopplung");
        if (!"cockpit".equals(art)) return Antwort.fehler(403, "nur_cockpit");
        long jetzt = uhr.getAsLong();
        aufraeumen(jetzt);
        for (Offen o : alle.values()) {
            if ("wartet".equals(o.status) || "erlaubt".equals(o.status)) return Antwort.fehler(409, "laeuft_schon");
        }
        Deque<Long> k = jeKopplung.computeIfAbsent(kid, x -> new ArrayDeque<>());
        while (!k.isEmpty() && jetzt - k.peekFirst() >= 3_600_000L) k.pollFirst();
        while (!jeAuto.isEmpty() && jetzt - jeAuto.peekFirst() >= 86_400_000L) jeAuto.pollFirst();
        if (k.size() >= MAX_STUNDE_KOPPLUNG || jeAuto.size() >= MAX_TAG_AUTO) return Antwort.fehler(429, "ratenlimit");
        k.addLast(jetzt);
        jeAuto.addLast(jetzt);
        String name = geraet == null ? "" : geraet.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "EV-Cockpit";
        byte[] hex = new byte[8];
        zufall.nextBytes(hex);
        String ref = "r-" + hex(hex);
        String pruefzahl = String.format(java.util.Locale.ROOT, "%04d", zufall.nextInt(10_000));
        byte[] aPriv = new byte[32];
        zufall.nextBytes(aPriv);
        byte[] aPub;
        try {
            aPub = x25519Pub(aPriv);
        } catch (Exception e) {
            return Antwort.fehler(500, "schluessel");
        }
        Offen o = new Offen(ref, kid, name, pruefzahl, aPriv, aPub, jetzt + GUELTIG_MS);
        alle.put(ref, o);
        melder.protokoll("cloud-uebergabe " + ref + " anfrage kopplung=" + kid);
        melder.anfrage(ref, name, pruefzahl);
        return new Antwort(201, new JSONObject().put("ref", ref).put("pruefzahl", pruefzahl)
                .put("a_pub", b64(aPub)).put("gueltigBis", o.ablauf));
    }

    /** Offene Anfragen für Display/Web (nur Name, Prüfzahl, Ablauf – nie Schlüssel). */
    public synchronized org.json.JSONArray offene() throws JSONException {
        aufraeumen(uhr.getAsLong());
        org.json.JSONArray a = new org.json.JSONArray();
        for (Offen o : alle.values()) {
            if (!"wartet".equals(o.status)) continue;
            a.put(new JSONObject().put("ref", o.ref).put("geraet", o.geraet).put("pruefzahl", o.pruefzahl).put("gueltigBis", o.ablauf));
        }
        return a;
    }

    /** Schritt 2 (Besitzer: Login, im Auto oder Telegram – nie Kopplungs-JWT): erlauben/ablehnen. */
    public synchronized Antwort entscheide(String ref, boolean erlauben) throws JSONException {
        aufraeumen(uhr.getAsLong());
        Offen o = ref == null ? null : alle.get(ref);
        if (o == null) return Antwort.fehler(404, "unbekannt");
        if ("abgelaufen".equals(o.status)) return Antwort.fehler(410, "abgelaufen");
        if (!"wartet".equals(o.status)) return Antwort.fehler(409, "schon_entschieden");
        o.status = erlauben ? "erlaubt" : "abgelehnt";
        if (!erlauben) o.vergessen();
        melder.protokoll("cloud-uebergabe " + ref + " " + o.status);
        melder.entschieden(ref, erlauben);
        return new Antwort(200, new JSONObject().put("ok", true).put("status", o.status));
    }

    /** Schritt 3 (Cockpit): Status abfragen. */
    public synchronized Antwort status(String ref, String kid) throws JSONException {
        aufraeumen(uhr.getAsLong());
        Offen o = ref == null ? null : alle.get(ref);
        if (o == null || !o.kid.equals(kid)) return Antwort.fehler(404, "unbekannt");
        if ("abgelaufen".equals(o.status)) {
            return new Antwort(410, new JSONObject().put("ok", false).put("status", "abgelaufen").put("grund", "abgelaufen"));
        }
        String s = "uebergeben".equals(o.status) ? "erlaubt" : o.status;
        return new Antwort(200, new JSONObject().put("ok", true).put("status", s));
    }

    /** Schritt 4/5 (Cockpit): verschlüsselte Zugangsdaten → entschlüsseln, speichern, prüfen. Danach ist die ref weg. */
    public Antwort senden(String ref, String kid, String ePubB64, String chiffreB64) throws JSONException {
        Offen o;
        synchronized (this) {
            aufraeumen(uhr.getAsLong());
            o = ref == null ? null : alle.get(ref);
            if (o == null || !o.kid.equals(kid)) return Antwort.fehler(404, "unbekannt");
            if ("abgelaufen".equals(o.status)) return Antwort.fehler(410, "abgelaufen");
            if ("uebergeben".equals(o.status)) return Antwort.fehler(409, "schon_uebergeben");
            if (!"erlaubt".equals(o.status)) return Antwort.fehler(409, "nicht_erlaubt");
            o.status = "uebergeben";   // einmal: ab hier ist die ref verbraucht, auch im Fehlerfall
        }
        byte[] klar = null;
        try {
            byte[] ePub = b64d(ePubB64, 32);
            byte[] chiffre = chiffreB64 == null ? null : b64d(chiffreB64, -1);
            if (ePub == null || chiffre == null || chiffre.length < 17 || chiffre.length > MAX_KLARTEXT + 16) {
                return ende(o, Antwort.fehler(400, "schluessel"));
            }
            byte[] shared;
            try {
                shared = x25519(o.aPriv, ePub);
            } catch (Exception e) {
                return ende(o, Antwort.fehler(400, "schluessel"));
            }
            if (alleNull(shared)) return ende(o, Antwort.fehler(400, "schluessel"));
            try {
                klar = entschluessele(shared, o.aPub, ePub, ref, chiffre);
            } catch (Exception e) {
                return ende(o, Antwort.fehler(400, "entschluesseln"));
            } finally {
                Arrays.fill(shared, (byte) 0);
            }
            JSONObject zugang = pruefeInhalt(klar);
            if (zugang == null) return ende(o, Antwort.fehler(400, "inhalt"));
            JSONObject r;
            try {
                r = einrichter.einrichten(zugang);
            } catch (Exception e) {
                return ende(o, Antwort.fehler(500, "speichern"));
            } finally {
                leeren(zugang);
            }
            if (!r.optBoolean("gespeichert", false)) {
                String g = r.optString("grund", "speichern");
                return ende(o, Antwort.fehler("inhalt".equals(g) ? 400 : 500, "inhalt".equals(g) ? "inhalt" : "speichern"));
            }
            boolean verified = r.optBoolean("verified", false);
            JSONObject j = new JSONObject().put("ok", true).put("verified", verified);
            if (!verified) j.put("grund", r.optString("grund", "pruefung"));
            melder.ergebnis(o.geraet, verified);
            return ende(o, new Antwort(200, j));
        } finally {
            if (klar != null) Arrays.fill(klar, (byte) 0);
        }
    }

    private Antwort ende(Offen o, Antwort a) {
        o.vergessen();
        melder.protokoll("cloud-uebergabe " + o.ref + " ende " + a.status + " " + a.json.optString("grund", a.json.optBoolean("verified") ? "verified" : ""));
        return a;
    }

    /** Abgelaufene markieren; entschiedene/abgelaufene nach einer Weile ganz vergessen. */
    private void aufraeumen(long jetzt) {
        for (Iterator<Map.Entry<String, Offen>> it = alle.entrySet().iterator(); it.hasNext(); ) {
            Offen o = it.next().getValue();
            if (jetzt > o.ablauf && ("wartet".equals(o.status) || "erlaubt".equals(o.status))) {
                o.status = "abgelaufen";
                o.vergessen();
                melder.entschieden(o.ref, false);
                melder.protokoll("cloud-uebergabe " + o.ref + " abgelaufen");
            }
            if (jetzt > o.ablauf + GUELTIG_MS) it.remove();
        }
    }

    // ------------------------------------------------------------------ Inhalt

    /** Klartext → Zugangs-JSON (Felder wie /api/bydcloud/setup) oder null. */
    static JSONObject pruefeInhalt(byte[] klar) {
        if (klar == null || klar.length == 0 || klar.length > MAX_KLARTEXT) return null;
        try {
            JSONObject j = new JSONObject(new String(klar, StandardCharsets.UTF_8));
            for (String f : new String[]{"username", "password", "countryCode", "language", "region"}) {
                Object v = j.opt(f);
                if (!(v instanceof String) || ((String) v).trim().isEmpty() || ((String) v).length() > 256) return null;
            }
            Object pin = j.opt("controlPin");
            if (pin != null && pin != JSONObject.NULL && !(pin instanceof String)) return null;
            return j;
        } catch (Exception e) {
            return null;
        }
    }

    private static void leeren(JSONObject j) {
        if (j == null) return;
        for (String f : new String[]{"password", "controlPin", "username"}) {
            try {
                if (j.has(f)) j.put(f, "");
            } catch (JSONException ignored) { }
        }
    }

    // ------------------------------------------------------------------ Krypto (Vertrag §2)

    static byte[] x25519Pub(byte[] priv) throws Exception {
        return com.google.crypto.tink.subtle.X25519.publicFromPrivate(priv);
    }

    static byte[] x25519(byte[] priv, byte[] pub) throws Exception {
        return com.google.crypto.tink.subtle.X25519.computeSharedSecret(priv, pub);
    }

    static boolean alleNull(byte[] b) {
        int o = 0;
        for (byte x : b) o |= x;
        return o == 0;
    }

    /** HKDF-SHA256 (RFC 5869). */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int laenge) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = m.doFinal(ikm);
        byte[] out = new byte[laenge];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < laenge; i++) {
            m.init(new SecretKeySpec(prk, "HmacSHA256"));
            m.update(t);
            m.update(info);
            m.update((byte) i);
            t = m.doFinal();
            int n = Math.min(t.length, laenge - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        Arrays.fill(prk, (byte) 0);
        return out;
    }

    /** key ‖ nonce (16 + 12 Byte) für diese Übergabe. */
    static byte[] okm(byte[] shared, byte[] aPub, byte[] ePub, String ref) throws Exception {
        byte[] salt = new byte[64];
        System.arraycopy(aPub, 0, salt, 0, 32);
        System.arraycopy(ePub, 0, salt, 32, 32);
        return hkdf(shared, salt, (INFO + ref).getBytes(StandardCharsets.UTF_8), 28);
    }

    static byte[] entschluessele(byte[] shared, byte[] aPub, byte[] ePub, String ref, byte[] chiffre) throws Exception {
        byte[] okm = okm(shared, aPub, ePub, ref);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(okm, 0, 16, "AES"), new GCMParameterSpec(128, okm, 16, 12));
            c.updateAAD((INFO + ref).getBytes(StandardCharsets.UTF_8));
            return c.doFinal(chiffre);
        } finally {
            Arrays.fill(okm, (byte) 0);
        }
    }

    /** Nur für Tests/Attrappe: Gegenstück des Cockpits. */
    static byte[] verschluessele(byte[] shared, byte[] aPub, byte[] ePub, String ref, byte[] klar) throws Exception {
        byte[] okm = okm(shared, aPub, ePub, ref);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(okm, 0, 16, "AES"), new GCMParameterSpec(128, okm, 16, 12));
        c.updateAAD((INFO + ref).getBytes(StandardCharsets.UTF_8));
        return c.doFinal(klar);
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** base64url ohne „=“; {@code laenge} ≥ 0 verlangt genau so viele Bytes. null = ungültig. */
    static byte[] b64d(String s, int laenge) {
        if (s == null || s.isEmpty() || s.length() > 8000 || !s.matches("[A-Za-z0-9_-]+")) return null;
        try {
            byte[] b = Base64.getUrlDecoder().decode(s);
            return laenge >= 0 && b.length != laenge ? null : b;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
