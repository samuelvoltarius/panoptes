package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.InstallerKopplung;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 0.1.43.8 {@code POST /api/installer/kopplung {adresse?}} – Kopplungs-QR für die Handy-App gleich nach
 * „Ins Auto installieren“ (docs/INSTALLER_KOPPLUNG.md, Logik in {@link InstallerKopplung}).
 *
 * <p>Nur adb shell am Auto: direkt am Auto ({@link AuthMiddleware#isDirektAmAuto()}: Loopback, kein Tunnel, keine
 * Brücke, keine Kopplung, keine Web-Sitzung) UND die Linux-UID des Gegen-Sockets ist 2000 (shell) UND der Socket gehört
 * nicht tailscaled (der läuft auch als 2000 und reicht Tailnet-Verbindungen an 127.0.0.1 weiter). So kommt nur, wer
 * am Auto schon per ADB zugelassen ist (die Handy-App über {@code adb … tcp:8180}). Andere Apps, Launcher, Kopplungen,
 * PC, Tailnet und Login von außen: 403. Fail-closed bei unlesbarer UID. Kein Notschalter.
 *
 * <p>Antwort mit {@code Cache-Control: no-store}; der Code steht nie im Log. Befehls-Log: {@code kopplung.installer}
 * mit {@code besitzer} bzw. {@code weiteres} (bzw. {@code abgelehnt}).
 */
final class InstallerKopplungApiHandler {

    static final String PFAD = "/api/installer/kopplung";
    static final int SHELL_UID = 2000;

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        boolean direktAmAuto();
        int quellport();
        int uid(int quellport);
        boolean tailscaled(int quellport);
        InstallerKopplung.Quelle kopplung();
        long jetzt();
        void protokoll(String ergebnis);
    }

    static volatile Quelle quelle = new Geraet();

    private InstallerKopplungApiHandler() {}

    /** Rein: Status und Antwort-JSON. */
    static Object[] antwort(String method, String body, Quelle q) {
        try {
            if (!"POST".equalsIgnoreCase(method)) return new Object[] {405, fehler("methode")};
            if (!q.direktAmAuto()) return abgelehnt(q);
            int port = q.quellport();
            int uid = port > 0 ? q.uid(port) : -1;
            if (uid != SHELL_UID || q.tailscaled(port)) return abgelehnt(q);
            String adresse = null;
            if (body != null && !body.trim().isEmpty()) {
                adresse = new JSONObject(body).optString("adresse", null);
            }
            JSONObject r = InstallerKopplung.erzeugen(q.kopplung(), adresse, q.jetzt());
            if (!r.optBoolean("ok")) return new Object[] {409, r};
            q.protokoll(r.optBoolean("besitzer") ? "besitzer" : "weiteres");
            return new Object[] {200, r};
        } catch (Throwable t) {
            return new Object[] {403, fehler("nur_adb")};
        }
    }

    private static Object[] abgelehnt(Quelle q) {
        try { q.protokoll("abgelehnt"); } catch (Throwable ignored) { }
        return new Object[] {403, fehler("nur_adb")};
    }

    private static JSONObject fehler(String grund) {
        try {
            return new JSONObject().put("ok", false).put("grund", grund);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!PFAD.equals(p)) return false;
        Object[] a = antwort(method, body, quelle);
        int status = (Integer) a[0];
        if (status == 200) {
            HttpResponse.sendBinaryNoStore(out, "application/json; charset=utf-8", a[1].toString().getBytes(StandardCharsets.UTF_8), null);
        } else {
            HttpResponse.sendJson(out, status, a[1].toString());
        }
        return true;
    }

    private static final class Geraet implements Quelle {
        @Override public boolean direktAmAuto() { return AuthMiddleware.isDirektAmAuto(); }
        @Override public int quellport() { return AuthMiddleware.direktQuellport(); }
        @Override public int uid(int quellport) {
            return LoopbackPeer.clientUid(quellport, at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT);
        }
        @Override public boolean tailscaled(int quellport) {
            return LoopbackPeer.gehoertTailscaled(quellport, at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT);
        }
        @Override public long jetzt() { return System.currentTimeMillis(); }
        @Override public void protokoll(String ergebnis) { BefehlsLog.ereignis("kopplung.installer", ergebnis, null, null); }
        @Override public InstallerKopplung.Quelle kopplung() {
            return new InstallerKopplung.Quelle() {
                @Override public boolean besitzerDa() {
                    return !at.greenchili.surfmate.auth.Kopplungen.get().besitzerIds().isEmpty();
                }
                @Override public String code(long jetzt, boolean weiteres) {
                    return at.greenchili.surfmate.auth.PairingCodes.create(jetzt, weiteres);
                }
                @Override public JSONObject bruecke() {
                    try {
                        at.greenchili.surfmate.bruecke.Bruecke b = at.greenchili.surfmate.bruecke.Bruecke.get();
                        if (!b.ein() || !at.greenchili.surfmate.bruecke.Zustimmung.get().erteilt()) return null;
                        return BrueckeApiHandler.status(b);
                    } catch (Throwable t) {
                        return null;
                    }
                }
                @Override public String einmal(long bis) {
                    try {
                        return at.greenchili.surfmate.bruecke.Bruecke.get().einmalAnlegen(bis);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            };
        }
    }
}
