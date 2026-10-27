package at.greenchili.surfmate.wallbox;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * „Mit Home Assistant anmelden“ (0.1.19, WALLBOX_FERN_KONZEPT §5, REGEL DAU): HAs eingebautes OAuth2/IndieAuth statt eines
 * abgetippten Langzeit-Tokens – Muster Cockpit 1.17.1 ({@code ha_finden.py}).
 *
 * <ul>
 *   <li>{@code client_id} = Adresse, unter der der Browser Panoptes sieht ({@code origin + "/"}), {@code redirect_uri} auf
 *       demselben Host ({@link #RUECKRUF}) – dann braucht HA keine öffentliche Seite.</li>
 *   <li>Der Rückruf kommt von HA (fremde Seite): das Login-Cookie ({@code SameSite=Strict}) fehlt dort. Deshalb ist
 *       {@link #RUECKRUF} eine datenlose Seite ohne Login, die per Skript auf {@code /wallbox.html#ha_code=…} weiterleitet;
 *       erst die angemeldete Seite tauscht den Code ({@code POST /api/wallbox/ha/rueckruf}).</li>
 *   <li>Gespeichert wird nur der Refresh-Token (verschlüsselt, {@code ha.refresh}); der Access-Token (30 min) liegt nur im
 *       Arbeitsspeicher und wird bei Bedarf erneuert.</li>
 *   <li>{@code state}: 128 Bit Zufall, einmalig, 10 min gültig, höchstens {@link #MAX_OFFEN} gleichzeitig.</li>
 * </ul>
 */
public final class HaAnmeldung {

    /** Öffentliche Weiterleitungsseite (ohne Login, ohne Daten). */
    public static final String RUECKRUF = "/wallbox-ha-rueckruf.html";
    static final long GUELTIG_MS = 10 * 60_000L;
    static final int MAX_OFFEN = 5;
    /** So viel früher wird der Access-Token erneuert. */
    static final long VORLAUF_MS = 60_000L;

    static final class Offen {
        final String url, basis, clientId, redirect;
        final boolean oeffentlich;
        final long seit;
        Offen(String url, String basis, String clientId, String redirect, boolean oeffentlich, long seit) {
            this.url = url; this.basis = basis; this.clientId = clientId; this.redirect = redirect;
            this.oeffentlich = oeffentlich; this.seit = seit;
        }
    }

    static final class Token {
        final String refresh, access;
        final long gueltigBis;
        Token(String refresh, String access, long gueltigBis) { this.refresh = refresh; this.access = access; this.gueltigBis = gueltigBis; }
    }

    private final WallboxHttp http;
    private final LongSupplier uhr;
    private final SecureRandom zufall = new SecureRandom();
    private final Map<String, Offen> offen = new HashMap<>();
    /** Access-Token je (basis, refresh). */
    private final Map<String, Token> cache = new HashMap<>();

    HaAnmeldung(WallboxHttp http, LongSupplier uhr) {
        this.http = http;
        this.uhr = uhr;
    }

    /** Herkunft der Seite: nur Schema + Host + Port, kein Pfad. */
    static boolean herkunftGueltig(String o) {
        return o != null && o.length() <= 200 && o.matches("https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?|https?://\\[[0-9A-Fa-f:.]+\\](:[0-9]{1,5})?");
    }

    /** Neue Anmeldung: Adresse der HA-Anmeldeseite. */
    synchronized String start(String url, String basis, String herkunft, boolean oeffentlich) throws WallboxFehler {
        if (!herkunftGueltig(herkunft)) throw new WallboxFehler("einstellung_ungueltig");
        long jetzt = uhr.getAsLong();
        aufraeumen(jetzt);
        if (offen.size() >= MAX_OFFEN) throw new WallboxFehler("zu_viele_anmeldungen", 429);
        byte[] b = new byte[16];
        zufall.nextBytes(b);
        StringBuilder st = new StringBuilder();
        for (byte x : b) st.append(String.format("%02x", x));
        String state = st.toString();
        String clientId = herkunft + "/";
        String redirect = herkunft + RUECKRUF;
        offen.put(state, new Offen(url, basis, clientId, redirect, oeffentlich, jetzt));
        return basis + "/auth/authorize?response_type=code&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirect) + "&state=" + enc(state);
    }

    /** Code gegen Tokens tauschen; die offene Anmeldung ist danach verbraucht. */
    synchronized Offen pruefeState(String state) throws WallboxFehler {
        aufraeumen(uhr.getAsLong());
        Offen o = state == null ? null : offen.remove(state);
        if (o == null) throw new WallboxFehler("anmeldung_abgelaufen", 409);
        return o;
    }

    Token tausche(Offen o, String code) throws WallboxFehler, JSONException {
        if (code == null || code.isEmpty() || code.length() > 512) throw new WallboxFehler("anmeldung", 400);
        JSONObject d = token(o.basis, "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(o.clientId));
        String refresh = d.optString("refresh_token", "");
        if (refresh.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        Token t = new Token(refresh, d.optString("access_token", ""), uhr.getAsLong() + d.optLong("expires_in", 1800) * 1000L);
        synchronized (this) {
            cache.put(o.basis + "|" + refresh, t);
        }
        return t;
    }

    /** Gültiger Access-Token zu einem gespeicherten Refresh-Token (erneuert bei Bedarf). */
    String access(String basis, String refresh, String clientId) throws WallboxFehler, JSONException {
        String schl = basis + "|" + refresh;
        synchronized (this) {
            Token t = cache.get(schl);
            if (t != null && !t.access.isEmpty() && uhr.getAsLong() < t.gueltigBis - VORLAUF_MS) return t.access;
        }
        JSONObject d = token(basis, "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(clientId));
        String a = d.optString("access_token", "");
        if (a.isEmpty()) throw new WallboxFehler("anmeldung_abgelaufen", 502);
        synchronized (this) {
            cache.put(schl, new Token(refresh, a, uhr.getAsLong() + d.optLong("expires_in", 1800) * 1000L));
        }
        return a;
    }

    private JSONObject token(String basis, String rumpf) throws WallboxFehler, JSONException {
        Map<String, String> kopf = new HashMap<>();
        kopf.put("Content-Type", "application/x-www-form-urlencoded");
        WallboxHttp.Antwort a = http.anfrage("POST", basis + "/auth/token", kopf, rumpf);
        // 400/401/403: Code bzw. Refresh-Token ungültig oder widerrufen → neu anmelden
        if (a.code == 400 || a.code == 401 || a.code == 403) throw new WallboxFehler("anmeldung_abgelaufen", 502);
        if (a.code != 200) throw new WallboxFehler("nicht_erreichbar", 502);
        return new JSONObject(a.text);
    }

    private void aufraeumen(long jetzt) {
        for (Iterator<Map.Entry<String, Offen>> it = offen.entrySet().iterator(); it.hasNext(); ) {
            if (jetzt - it.next().getValue().seit > GUELTIG_MS) it.remove();
        }
    }

    synchronized int offen() {
        return offen.size();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    /** Datenlose Weiterleitungsseite: Code und State nur im Fragment an die angemeldete Seite. */
    public static String weiterleitungsSeite() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">"
                + "<title>Panoptes</title></head><body><script>"
                + "location.replace('/wallbox.html#ha_rueckruf&'+location.search.replace(/^\\?/,''));"
                + "</script></body></html>";
    }
}
