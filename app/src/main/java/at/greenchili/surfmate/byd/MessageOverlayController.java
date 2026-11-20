package at.greenchili.surfmate.byd;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * Daemon-side bridge that shows an on-screen toast/dialog for the "Show Toast" /
 * "Show Dialog" automation + key-mapping actions.
 *
 * <p>The daemon (UID 2000, {@code app_process}) has no UI surface, so — exactly like the
 * Play Audio / Speak path in {@link AudioPlaybackController} — this shells
 * {@code am start-foreground-service} against the exported app-process
 * {@code MessageOverlayService}, which draws a {@code TYPE_APPLICATION_OVERLAY} window in
 * the real app process. Fire-and-forget (no waitFor) so a slow {@code am} never stalls the
 * HTTP-worker / keymap-fire thread.
 */
public final class MessageOverlayController {

    private static final String TAG = "MessageOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PKG = "at.greenchili.surfmate";
    private static final String SERVICE = "at.greenchili.surfmate/.overlay.MessageOverlayService";
    private static final String ACTION_DISMISS = "at.greenchili.surfmate.action.DISMISS_MESSAGE";
    private static final int ACK_TIMEOUT_MS = 3000;

    private MessageOverlayController() {}

    public static final class DisplayResult {
        public final boolean displayed;
        public final String reason;

        private DisplayResult(boolean displayed, String reason) {
            this.displayed = displayed;
            this.reason = reason == null ? "" : reason;
        }

        public static DisplayResult displayed() {
            return new DisplayResult(true, "");
        }

        public static DisplayResult failed(String reason) {
            return new DisplayResult(false, reason);
        }
    }

    /**
     * Show a toast (auto-dismissing pill). {@code duration} = short|long,
     * {@code position} = top|center|bottom, {@code severity} = info|warning|alert.
     */
    public static boolean showToast(String message, String duration, String position, String severity) {
        if (message == null || message.trim().isEmpty()) {
            logger.warn("showToast: empty message");
            return false;
        }
        exec(toastCommand(message, duration, position, severity, ""));
        logger.info("showToast: dispatched to MessageOverlayService");
        return true;
    }

    /** Display the toast and wait briefly for WindowManager.addView(). */
    public static DisplayResult showToastAcknowledged(
            String message, String duration, String position, String severity) {
        if (message == null || message.trim().isEmpty()) {
            return DisplayResult.failed("Message is required");
        }
        return dispatchAcknowledged(ackArgs ->
                toastCommand(message, duration, position, severity, ackArgs));
    }

    /**
     * Show a dialog (title + body + OK button) as a non-focus-stealing overlay.
     * {@code button} defaults to OK; {@code timeoutSec} > 0 auto-dismisses even without OK.
     */
    public static boolean showDialog(String title, String message, String button,
                                     String severity, int timeoutSec) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            logger.warn("showDialog: empty title+message");
            return false;
        }
        exec(dialogCommand(title, message, button, severity, timeoutSec, ""));
        logger.info("showDialog: dispatched to MessageOverlayService");
        return true;
    }

    /** Display the dialog and wait briefly for WindowManager.addView(). */
    public static DisplayResult showDialogAcknowledged(
            String title, String message, String button,
            String severity, int timeoutSec) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            return DisplayResult.failed("Message is required");
        }
        return dispatchAcknowledged(ackArgs ->
                dialogCommand(title, message, button, severity, timeoutSec, ackArgs));
    }

    /**
     * 0.1.43.16: Wie ein Dialog ausging. Nur {@link #OK} heißt „der Fahrer hat OK getippt“; alles andere zählt nicht als
     * gelesen (z. B. von selbst nach {@code timeoutSec} geschlossen).
     */
    public enum DialogAusgang {
        /** Nicht am Display (kein Overlay-Recht, Dienst antwortet nicht, …). */
        NICHT_GEZEIGT,
        /** OK getippt. */
        OK,
        /** Daneben (Abdunklung) getippt. */
        WEG_GETIPPT,
        /** Von selbst geschlossen, ohne Tipp. */
        ABGELAUFEN,
        /** Von einer anderen Meldung verdrängt oder der Dienst wurde beendet. */
        VERDRAENGT,
        /** Gezeigt, aber kein Schluss-Bescheid gekommen. */
        UNBEKANNT,
        /** 0.1.43.17: das Auto steht nicht mehr sicher, der Kern hat den Dialog sofort geschlossen. */
        ABBRUCH_FAHRT,
        /** 0.1.43.17: ein anderer Frage-Dialog ist gerade offen; dieser wurde gar nicht erst gezeigt. */
        BELEGT;

        /** Stand wirklich (lange genug) am Display: zählt für Telegram/Handy und als Versuch. */
        public boolean gezeigt() {
            return this != NICHT_GEZEIGT && !ohneVersuch();
        }

        /**
         * 0.1.43.17 (E2E 0.1.43.16, 5b und Verdrängung): kostet keinen Versuch und gilt als nicht gezeigt. Der nächste
         * Takt versucht es im sicheren Stand normal wieder.
         */
        public boolean ohneVersuch() {
            return this == ABBRUCH_FAHRT || this == BELEGT || this == VERDRAENGT;
        }

        /** Antwort des Overlay-Dienstes auf den Schluss-Bescheid. */
        public static DialogAusgang ausSchluss(String status) {
            if ("SCHLUSS_OK".equals(status)) return OK;
            if ("SCHLUSS_WEG".equals(status)) return WEG_GETIPPT;
            if ("SCHLUSS_ABGELAUFEN".equals(status)) return ABGELAUFEN;
            if ("SCHLUSS_VERDRAENGT".equals(status)) return VERDRAENGT;
            return UNBEKANNT;
        }
    }

    /** Spielraum über {@code timeoutSec} hinaus, bis der Schluss-Bescheid kommen muss. */
    static final int SCHLUSS_SPIEL_S = 20;

    /**
     * 0.1.43.16: Dialog zeigen und warten, bis er zu ist; liefert, wie er ausging. Blockiert höchstens
     * {@code timeoutSec + }{@value #SCHLUSS_SPIEL_S}{@code  s}, darum nur aus einem eigenen Takt-Faden aufrufen.
     * Der Overlay-Dienst meldet den Schluss über denselben Ack-Weg (zweite Verbindung, gleicher Token).
     */
    public static DialogAusgang showDialogMitAusgang(
            String title, String message, String button, String severity, int timeoutSec) {
        return showDialogMitAusgang(title, message, button, severity, timeoutSec, null);
    }

    /** 0.1.43.17: höchstens ein Frage-Dialog zugleich (Rechte-Meldung und Wächter-Frage hintereinander statt verdrängt). */
    static final java.util.concurrent.locks.ReentrantLock FRAGE = new java.util.concurrent.locks.ReentrantLock();
    /** 0.1.43.17: so oft prüft der Kern bei offenem Dialog, ob das Auto noch sicher steht. */
    static final int STAND_PRUEF_MS = 1000;
    private static final ThreadLocal<java.util.function.BooleanSupplier> weiterSicher = new ThreadLocal<>();

    /**
     * 0.1.43.17 (E2E 0.1.43.16, 5b): wie oben, dazu prüft der Kern bei offenem Dialog etwa jede Sekunde {@code standSicher}
     * (fail-closed: Ausnahme = nicht sicher). Fährt das Auto los, schließt er den Dialog sofort: {@link DialogAusgang#ABBRUCH_FAHRT}.
     * Ist schon ein anderer Frage-Dialog offen: {@link DialogAusgang#BELEGT}, ohne etwas zu zeigen.
     */
    public static DialogAusgang showDialogMitAusgang(String title, String message, String button, String severity,
            int timeoutSec, java.util.function.BooleanSupplier standSicher) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            return DialogAusgang.NICHT_GEZEIGT;
        }
        if (!FRAGE.tryLock()) return DialogAusgang.BELEGT;
        try {
            weiterSicher.set(standSicher);
            int t = Math.max(10, Math.min(300, timeoutSec));
            String[] r = dispatch(ackArgs -> dialogCommand(title, message, button, severity, t, ackArgs + " --ez schlussAck true"),
                    (t + SCHLUSS_SPIEL_S) * 1000);
            if (!"DISPLAYED".equals(r[0])) return DialogAusgang.NICHT_GEZEIGT;
            if (ABBRUCH_FAHRT.equals(r[1])) return DialogAusgang.ABBRUCH_FAHRT;
            return DialogAusgang.ausSchluss(r[1]);
        } finally {
            weiterSicher.remove();
            FRAGE.unlock();
        }
    }

    static final String ABBRUCH_FAHRT = "ABBRUCH_FAHRT";

    /**
     * F1-Band oben rechts: zwei Zeilen, Randfarbe nach {@code stufe} (passt|knapp|reicht_nicht),
     * {@code durationMs} sichtbar. Nur Anzeige, nicht berührbar.
     */
    public static boolean showBand(String title, String message, String stufe, long durationMs) {
        if ((title == null || title.trim().isEmpty()) && (message == null || message.trim().isEmpty())) {
            return false;
        }
        exec(bandCommand(title, message, stufe, durationMs));
        return true;
    }

    /** Anzeige-Einstellungen des Bands (0.1.9.1, Projektinhaber: „zu klein, oben rechts“): {groesse, position}. */
    public static String[] bandStil(org.json.JSONObject root) {
        org.json.JSONObject a = root == null ? null : root.optJSONObject("anzeige");
        String g = a == null ? "" : a.optString("bandGroesse", "");
        String p = a == null ? "" : a.optString("bandPosition", "");
        return new String[] {"normal".equals(g) ? "normal" : "gross", "oben_rechts".equals(p) ? "oben_rechts" : "oben_mitte"};
    }

    public static String bandCommand(String title, String message, String stufe, long durationMs) {
        String[] stil;
        try {
            stil = bandStil(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            stil = bandStil(null);
        }
        return bandCommand(title, message, stufe, durationMs, stil[0], stil[1]);
    }

    public static String bandCommand(String title, String message, String stufe, long durationMs,
                                     String groesse, String position) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind band"
                + " --es groesse " + q(orDefault(groesse, "gross"))
                + " --es position " + q(orDefault(position, "oben_mitte"))
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es stufe " + q(orDefault(stufe, "passt"))
                + " --es durationMs " + q(String.valueOf(Math.max(2000, Math.min(15000, durationMs))));
    }

    /**
     * Kopplungs-Anfrage am Auto-Display: Titel, Prüfzahl, [Ablehnen] [Erlauben]. Die Knöpfe rufen im
     * App-Prozess {@code POST /api/kopplung/anfrage/{ref}/erlauben|ablehnen} (voller Login). Kein Ton.
     * Übergeben wird nur der Griff {@code ref}, nie die Abhol-id.
     */
    public static boolean showKopplung(String ref, String title, String message, String erlauben,
                                       String ablehnen, int timeoutSec) {
        if (ref == null || !ref.matches("^[0-9a-f]{16}$")) return false;
        exec(kopplungCommand(ref, title, message, erlauben, ablehnen, timeoutSec));
        return true;
    }

    /**
     * 0.1.21: Rückfrage wie bei der Kopplung, aber an einen anderen Daemon-Pfad (BYD-Cloud-Übergabe:
     * {@code /api/cloud-uebergabe/r-<ref>}). Der Dienst nimmt nur die zwei bekannten Pfadformen an.
     */
    public static boolean showRueckfrage(String ref, String pfad, String title, String message, String erlauben,
                                         String ablehnen, int timeoutSec) {
        if (ref == null || !ref.matches("^[0-9a-f]{16}$")
                || !(("/api/cloud-uebergabe/r-" + ref).equals(pfad) || ("/api/kopplung/weiteres-geraet/r-" + ref).equals(pfad))) {
            return false;   // 0.1.25: auch „weiteres Gerät koppeln“
        }
        exec(kopplungCommand(ref, title, message, erlauben, ablehnen, timeoutSec) + " --es pfad " + q(pfad));
        return true;
    }

    public static String kopplungCommand(String ref, String title, String message, String erlauben,
                                         String ablehnen, int timeoutSec) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind kopplung"
                + " --es ref " + q(ref)
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es erlauben " + q(orDefault(erlauben, "OK"))
                + " --es ablehnen " + q(orDefault(ablehnen, "X"))
                + " --ei timeoutSec " + Math.max(10, Math.min(300, timeoutSec));
    }

    /**
     * 0.1.28 Rückkehr-Karte nach dem Parken: {@code art} = aufnahmen | nichts | nicht_scharf. Bei „aufnahmen“ große
     * Knöpfe [Schließen] [Ansehen]; „Ansehen“ öffnet die Aufnahmen-Seite und spielt {@code aufnahmeId} ab. Sonst
     * nur Text, verschwindet nach {@code sichtbarSek}. Kein Ton, kein Ort.
     */
    public static boolean showRueckkehr(String art, String title, String message, String ansehen, String schliessen,
                                        String aufnahmeId, int sichtbarSek) {
        String cmd = rueckkehrCommand(art, title, message, ansehen, schliessen, aufnahmeId, sichtbarSek);
        if (cmd == null) return false;
        exec(cmd);
        logger.info("showRueckkehr: " + art);
        return true;
    }

    public static String rueckkehrCommand(String art, String title, String message, String ansehen,
                                          String schliessen, String aufnahmeId, int sichtbarSek) {
        if (!"aufnahmen".equals(art) && !"nichts".equals(art) && !"nicht_scharf".equals(art)) return null;
        String id = at.greenchili.surfmate.ui.NavigateZiel.aufnahmeGueltig(aufnahmeId) ? aufnahmeId : "";
        return "am start-foreground-service -n " + SERVICE
                + " --es kind rueckkehr"
                + " --es art " + q(art)
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es ansehen " + q(orDefault(ansehen, "OK"))
                + " --es schliessen " + q(orDefault(schliessen, "X"))
                + " --es aufnahme " + q(id)
                + " --ei timeoutSec " + Math.max(5, Math.min(300, sichtbarSek));
    }

    /**
     * 0.1.31 Update-Hinweis: [Was ist neu?] [Später] [Jetzt installieren]. Wartet auf die Bestätigung des Overlays,
     * damit nur eine wirklich gezeigte Karte zählt. {@code griff} schließt sie gezielt ({@link #dismissKopplung}).
     * Kein Ton.
     */
    public static boolean showUpdateHinweis(String griff, String version, String title, String message,
                                            String notizen, String installieren, String spaeter, String wasNeu,
                                            String schliessen, String zuruecknehmen, String fehler,
                                            int sichtbarSek) {
        return dispatchAcknowledged(ackArgs -> updateHinweisCommand(griff, version, title, message, notizen,
                installieren, spaeter, wasNeu, schliessen, zuruecknehmen, fehler, sichtbarSek) + ackArgs).displayed;
    }

    public static String updateHinweisCommand(String griff, String version, String title, String message,
                                              String notizen, String installieren, String spaeter, String wasNeu,
                                              String schliessen, String zuruecknehmen, String fehler,
                                              int sichtbarSek) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind update_hinweis"
                + " --es ref " + q(griff)
                + " --es version " + q(orDefault(version, ""))
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es notizen " + q(notizen == null ? "" : notizen)
                + " --es installieren " + q(orDefault(installieren, "OK"))
                + " --es spaeter " + q(orDefault(spaeter, "X"))
                + " --es wasNeu " + q(orDefault(wasNeu, "?"))
                + " --es schliessen " + q(orDefault(schliessen, "X"))
                + " --es zuruecknehmen " + q(orDefault(zuruecknehmen, "X"))
                + " --es fehler " + q(orDefault(fehler, ""))
                + " --ei timeoutSec " + Math.max(10, Math.min(300, sichtbarSek));
    }

    /** Schließt das Kopplungs-Overlay genau dieser Anfrage (anderswo entschieden). */
    public static void dismissKopplung(String ref) {
        if (ref == null || !ref.matches("^[0-9a-f]{16}$")) return;
        exec("am broadcast -a " + ACTION_DISMISS_KOPPLUNG + " -p " + PKG + " --es ref " + q(ref));
    }

    static final String ACTION_DISMISS_KOPPLUNG = "at.greenchili.surfmate.action.DISMISS_KOPPLUNG";

    /** Dismiss any showing toast/dialog. Idempotent (no-op if nothing is up). */
    public static void dismiss() {
        exec("am broadcast -a " + ACTION_DISMISS + " -p " + PKG);
        exec("am stopservice -n " + SERVICE);
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private interface CommandFactory {
        String create(String acknowledgementArgs);
    }

    private static DisplayResult dispatchAcknowledged(CommandFactory factory) {
        String[] r = dispatch(factory, 0);
        return "DISPLAYED".equals(r[0]) ? DisplayResult.displayed() : DisplayResult.failed(r[2]);
    }

    /**
     * Befehl absetzen und auf die Quittung warten. {@code schlussWarteMs} > 0: danach auch auf den Schluss-Bescheid
     * (zweite Verbindung, gleicher Token) warten. Liefert {Quittung (DISPLAYED|FAILED), Schluss (SCHLUSS_…|""), Grund}.
     */
    private static String[] dispatch(CommandFactory factory, int schlussWarteMs) {
        DisplayResult quittung = quittung(factory, schlussWarteMs);
        return quittung == null ? new String[] {"FAILED", "", "The car message service is unavailable"}
                : new String[] {quittung.displayed ? "DISPLAYED" : "FAILED", letzterSchluss.get(), quittung.reason};
    }

    private static final ThreadLocal<String> letzterSchluss = ThreadLocal.withInitial(() -> "");

    private static DisplayResult quittung(CommandFactory factory, int schlussWarteMs) {
        letzterSchluss.set("");
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(ACK_TIMEOUT_MS);
            String token = UUID.randomUUID().toString();
            String ackArgs = " --es ackPort " + q(String.valueOf(server.getLocalPort()))
                    + " --es ackToken " + q(token);
            exec(factory.create(ackArgs));

            try (Socket client = server.accept()) {
                client.setSoTimeout(1000);
                DataInputStream input = new DataInputStream(client.getInputStream());
                String receivedToken = input.readUTF();
                String status = input.readUTF();
                String reason = input.readUTF();
                if (!token.equals(receivedToken)) {
                    return DisplayResult.failed(
                            "Message acknowledgement was rejected");
                }
                if (status.startsWith("SCHLUSS_")) {
                    // Schluss kam vor der Quittung (sofort verdrängt): gezeigt war er trotzdem
                    letzterSchluss.set(status);
                    return DisplayResult.displayed();
                }
                if ("DISPLAYED".equals(status) && schlussWarteMs > 0) {
                    letzterSchluss.set(schlussLesen(server, token, schlussWarteMs, weiterSicher.get(),
                            MessageOverlayController::dismiss));
                }
                return "DISPLAYED".equals(status)
                        ? DisplayResult.displayed()
                        : DisplayResult.failed(
                                reason == null || reason.trim().isEmpty()
                                        ? "The car could not display the message"
                                        : reason);
            } catch (SocketTimeoutException timeout) {
                return DisplayResult.failed(
                        "The car did not acknowledge the message");
            }
        } catch (Throwable error) {
            logger.warn("Message acknowledgement failed: " + error.getMessage());
            return DisplayResult.failed("The car message service is unavailable");
        }
    }

    /**
     * Schluss-Bescheid des Dialogs abwarten (gleicher Token); "" = keiner gekommen. 0.1.43.17: mit {@code standSicher}
     * wird höchstens {@value #STAND_PRUEF_MS} ms am Stück gewartet und dazwischen geprüft; nicht mehr sicher →
     * {@code abbrechen} (Dialog schließen) und {@value #ABBRUCH_FAHRT}.
     */
    static String schlussLesen(ServerSocket server, String token, int warteMs,
            java.util.function.BooleanSupplier standSicher, Runnable abbrechen) {
        long ende = System.currentTimeMillis() + warteMs;
        while (true) {
            long rest = ende - System.currentTimeMillis();
            if (rest <= 0) return "";
            if (standSicher != null && !sicher(standSicher)) {
                try {
                    abbrechen.run();
                } catch (Throwable ignored) {
                }
                return ABBRUCH_FAHRT;
            }
            try {
                server.setSoTimeout((int) Math.max(1, standSicher == null ? rest : Math.min(rest, STAND_PRUEF_MS)));
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    String t = input.readUTF();
                    String status = input.readUTF();
                    input.readUTF();
                    if (token.equals(t) && status.startsWith("SCHLUSS_")) return status;
                }
            } catch (SocketTimeoutException timeout) {
                if (standSicher == null) return "";
                // nur der Prüftakt: weiter warten (Ende prüft die Schleife)
            } catch (Throwable e) {
                // fremde oder kaputte Verbindung: weiter warten bis zum Ende
            }
        }
    }

    private static boolean sicher(java.util.function.BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String toastCommand(
            String message, String duration, String position, String severity,
            String ackArgs) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind toast"
                + " --es message " + q(message)
                + " --es duration " + q(orDefault(duration, "short"))
                + " --es position " + q(orDefault(position, "bottom"))
                + " --es severity " + q(orDefault(severity, "info"))
                + ackArgs;
    }

    private static String dialogCommand(
            String title, String message, String button,
            String severity, int timeoutSec, String ackArgs) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind dialog"
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es button " + q(orDefault(button, "OK"))
                + " --es severity " + q(orDefault(severity, "info"))
                + " --ei timeoutSec " + Math.max(0, timeoutSec)
                + ackArgs;
    }

    /** Shell-quote one `am` extra value the POSIX way. */
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }
}
