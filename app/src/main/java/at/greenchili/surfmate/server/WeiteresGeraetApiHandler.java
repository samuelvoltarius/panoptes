package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.KopplungsQr;
import at.greenchili.surfmate.auth.WeiteresGeraet;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.security.SecureRandom;

/**
 * 0.1.25 „weiteres Gerät koppeln“ (KOPPLUNG_WEITERES_GERAET_VERTRAG.md):
 * <ul>
 *   <li>Cockpit (Kopplungs-JWT art=cockpit; ExternGuard lässt nur diese Art durch):
 *       {@code POST /api/kopplung/weiteres-geraet {art:"handy", geraet, pruefzahl}},
 *       {@code GET /api/kopplung/weiteres-geraet/r-<16 hex>} (bei „erlaubt“ einmal {@code qr})</li>
 *   <li>Besitzer (Login bzw. im Auto, nie Kopplungs-JWT, nie Web-Sitzung): {@code GET /api/kopplung/weiteres-geraet}
 *       (offene Rückfragen), {@code POST /api/kopplung/weiteres-geraet/r-<hex>/erlauben|ablehnen} – auch vom
 *       Display-Overlay und vom Telegram-Knopf.</li>
 * </ul>
 */
public final class WeiteresGeraetApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("WeiteresGeraet");
    static final String BASIS = "/api/kopplung/weiteres-geraet";

    private WeiteresGeraetApiHandler() {}

    /** Art der Kopplung (cockpit|handy|…); Tests: setzbar. */
    static volatile java.util.function.Function<String, String> artVon = kid -> at.greenchili.surfmate.auth.Kopplungen.get().art(kid);

    /** Laufzeit: Brücke an + Zustimmung; QR mit frischem Code und Einmal-Zugang (gleiche Form wie system.html). */
    static volatile WeiteresGeraet.QrQuelle quelle = new WeiteresGeraet.QrQuelle() {
        @Override public boolean brueckeBereit() {
            return at.greenchili.surfmate.bruecke.Bruecke.get().ein() && at.greenchili.surfmate.bruecke.Zustimmung.get().erteilt();
        }

        @Override public String qr(long jetzt) throws Exception {
            if (!brueckeBereit()) return null;
            at.greenchili.surfmate.bruecke.Bruecke b = at.greenchili.surfmate.bruecke.Bruecke.get();
            String einmal = b.einmalAnlegen(jetzt + at.greenchili.surfmate.auth.PairingCodes.TTL_MS);
            String code = at.greenchili.surfmate.auth.PairingCodes.create(jetzt, true);   // 0.1.26.1: ohne Recht „update“
            JSONObject st = BrueckeApiHandler.status(b);
            // ohne Adresse (u): das neue Handy kommt über die Brücke; den Direktweg lernt es nach der Kopplung
            return KopplungsQr.url(st.optString("qrBasis", ""),
                    KopplungsQr.inhalt(code, null, st.optString("p", ""), st.optString("a", ""), einmal));
        }
    };

    private static volatile WeiteresGeraet dienst;

    static WeiteresGeraet dienst() {
        WeiteresGeraet d = dienst;
        if (d == null) {
            synchronized (WeiteresGeraetApiHandler.class) {
                d = dienst;
                if (d == null) {
                    d = new WeiteresGeraet(System::currentTimeMillis, new SecureRandom(), new WeiteresGeraet.QrQuelle() {
                        public boolean brueckeBereit() { return quelle.brueckeBereit(); }
                        public String qr(long jetzt) throws Exception { return quelle.qr(jetzt); }
                    }, MELDER);
                    dienst = d;
                }
            }
        }
        return d;
    }

    /** Nur für Tests: frischer Zustand. */
    static void zuruecksetzen() {
        synchronized (WeiteresGeraetApiHandler.class) {
            dienst = null;
        }
    }

    static final WeiteresGeraet.Melder MELDER = new WeiteresGeraet.Melder() {
        @Override public void anfrage(String ref, String geraet, String pruefzahl) {
            try {
                at.greenchili.surfmate.telegram.TelegramNotifier.notifyWeiteresGeraet(ref, geraet, pruefzahl);
            } catch (Throwable ignored) { }
            try {
                if (!at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.isMovementBlocked()) {
                    at.greenchili.surfmate.byd.MessageOverlayController.showRueckfrage(ref.substring(2), BASIS + "/" + ref,
                            Messages.get("notifications.weiteres_geraet_titel", geraet),
                            Messages.get("notifications.weiteres_geraet_text", pruefzahl),
                            Messages.get("notifications.kopplung_erlauben"),
                            Messages.get("notifications.kopplung_ablehnen"),
                            (int) (WeiteresGeraet.GUELTIG_MS / 1000));
                }
            } catch (Throwable ignored) { }
        }

        @Override public void entschieden(String ref, boolean erlaubt) {
            try {
                at.greenchili.surfmate.byd.MessageOverlayController.dismissKopplung(ref.substring(2));
            } catch (Throwable ignored) { }
        }

        @Override public void protokoll(String zeile) {
            logger.info(zeile);
        }
    };

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.startsWith(BASIS)) return false;
        boolean extern = AuthMiddleware.isExternRequest();
        WeiteresGeraet.Antwort a;
        if (BASIS.equals(p) && "GET".equals(method)) {
            if (extern) a = fehler(403, "nur_besitzer");
            else a = new WeiteresGeraet.Antwort(200, new JSONObject().put("ok", true).put("offen", dienst().offene()));
        } else if (BASIS.equals(p) && "POST".equals(method)) {
            if (!extern) a = fehler(401, "kopplung");
            else {
                String kid = AuthMiddleware.requestKopplungsId();
                a = dienst().anfrage(kid, kid == null ? null : artVon.apply(kid), json(body));
            }
        } else if (p.matches(BASIS + "/" + WeiteresGeraet.REF_MUSTER + "/(erlauben|ablehnen)") && "POST".equals(method)) {
            if (extern) a = fehler(403, "nur_besitzer");
            else a = dienst().entscheide(p.substring(BASIS.length() + 1, p.lastIndexOf('/')), p.endsWith("/erlauben"));
        } else if (p.matches(BASIS + "/" + WeiteresGeraet.REF_MUSTER) && "GET".equals(method)) {
            if (!extern) a = fehler(401, "kopplung");
            else a = dienst().status(p.substring(BASIS.length() + 1), AuthMiddleware.requestKopplungsId());
        } else {
            return false;
        }
        HttpResponse.sendJson(out, a.status, a.json.toString());
        return true;
    }

    private static WeiteresGeraet.Antwort fehler(int status, String grund) throws Exception {
        return new WeiteresGeraet.Antwort(status, new JSONObject().put("ok", false).put("grund", grund));
    }

    private static JSONObject json(String body) {
        try {
            return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
