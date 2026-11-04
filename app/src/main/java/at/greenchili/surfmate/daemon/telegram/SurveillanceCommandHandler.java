package at.greenchili.surfmate.daemon.telegram;

import at.greenchili.surfmate.server.LocaleManager;
import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import org.json.JSONObject;

import java.util.Locale;

/**
 * {@code /waechter [an|aus]} und {@code /status}.
 *
 * <p>Der Wächter wird über den Kamera-Dienst (IPC 19977) ein- und
 * ausgeschaltet. „An“ heißt: er wacht auf, sobald das Auto abgestellt ist
 * (läuft das Auto gerade, wird es nur gemerkt).</p>
 */
public class SurveillanceCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19977;

    @Override
    public boolean canHandle(String command) {
        return "/waechter".equals(command) || "/status".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if ("/status".equals(args[0])) {
            handleStatus(chatId, ctx);
            return;
        }
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("standard".equals(what)) {
            standardAn(chatId, ctx);
            return;
        }
        Boolean on = parseOnOff(what);
        if (on == null) {
            showGuard(chatId, ctx);
            return;
        }
        if (!UnifiedTelegramConfig.isRemoteAllowed(UnifiedTelegramConfig.REMOTE_GUARD)) {
            ctx.sendMessage(chatId, ctx.tr("router.not_allowed"));
            return;
        }
        if (on) guardOn(chatId, ctx); else guardOff(chatId, ctx);
    }

    /** "an"/"ein"/"on" → true, "aus"/"off" → false, sonst null. */
    static Boolean parseOnOff(String s) {
        if (s == null) return null;
        switch (s) {
            case "an": case "ein": case "on": case "start": case "wach": case "1":
                return Boolean.TRUE;
            case "aus": case "off": case "stop": case "schlafen": case "0":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * 0.1.38.8 Knopf unter der einmaligen Frage „Wächter einschalten?“: schaltet wie der Assistent ein (Standard „nur bei
     * Erschütterung“, Rechtshinweis bestätigt). Der Kern lehnt eine veraltete Frage ab (inzwischen eingerichtet oder bewusst aus).
     */
    private void standardAn(long chatId, CommandContext ctx) {
        if (!UnifiedTelegramConfig.isRemoteAllowed(UnifiedTelegramConfig.REMOTE_GUARD)) {
            ctx.sendMessage(chatId, ctx.tr("router.not_allowed"));
            return;
        }
        JSONObject r = ctx.daemonHttp("POST", "/api/surveillance/standard-an",
                standardAnBody());
        if (r != null && r.optBoolean("success", false)) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("guard.standard_an_ok"), new String[][][]{
                    {{ctx.tr("buttons.guard_off"), "cmd:/waechter aus"},
                            {ctx.tr("buttons.status"), "cmd:/status"}}});
        } else if (r != null && "nicht_mehr_noetig".equals(r.optString("error", ""))) {
            ctx.sendMessage(chatId, ctx.tr("guard.standard_an_veraltet"));
        } else {
            ctx.sendMessage(chatId, ctx.tr("guard.on_failed"));
        }
    }

    /** Der Rechtshinweis (Version) geht mit: nur wer ihn gesehen hat, schaltet so ein. */
    static JSONObject standardAnBody() {
        try {
            return new JSONObject().put("rechtshinweis", at.greenchili.surfmate.einstellungen.Katalog.GUARD_LEGAL_VERSION);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private void guardOn(long chatId, CommandContext ctx) {
        JSONObject r = ipc("START", ctx);
        if (r != null && r.optBoolean("success", false)) {
            String text = r.optBoolean("deferred", false)
                    ? ctx.tr("guard.on_deferred") : ctx.tr("guard.on");
            ctx.sendMessageWithButtons(chatId, text, new String[][][]{
                    {{ctx.tr("buttons.guard_off"), "cmd:/waechter aus"},
                            {ctx.tr("buttons.status"), "cmd:/status"}}});
        } else {
            ctx.sendMessage(chatId, ctx.tr("guard.on_failed"));
        }
    }

    private void guardOff(long chatId, CommandContext ctx) {
        JSONObject r = ipc("STOP", ctx);
        if (r != null && r.optBoolean("success", false)) {
            ctx.sendMessageWithButtons(chatId, ctx.tr("guard.off"), new String[][][]{
                    {{ctx.tr("buttons.guard_on"), "cmd:/waechter an"},
                            {ctx.tr("buttons.status"), "cmd:/status"}}});
        } else {
            ctx.sendMessage(chatId, ctx.tr("guard.off_failed"));
        }
    }

    private void showGuard(long chatId, CommandContext ctx) {
        JSONObject st = ipc("STATUS", ctx);
        if (st == null) {
            ctx.sendMessage(chatId, DienstHinweis.text(ctx));   // 0.1.38.8: startet / aus / Ausfall
            return;
        }
        boolean enabled = st.optBoolean("enabled", false);
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("guard.title")).append("\n");
        sb.append(guardLine(st, ctx)).append("\n\n");
        sb.append(ctx.tr("guard.usage"));
        ctx.sendMessageWithButtons(chatId, sb.toString(), guardButtons(enabled, ctx));
    }

    static String guardLine(JSONObject st, CommandContext ctx) {
        boolean enabled = st.optBoolean("enabled", false);
        boolean active = st.optBoolean("active", false);
        if (enabled && active) return ctx.tr("status.guard_awake");
        if (enabled) return ctx.tr("status.guard_waiting");
        return ctx.tr("status.guard_asleep");
    }

    private static String[][][] guardButtons(boolean enabled, CommandContext ctx) {
        return enabled
                ? new String[][][]{{{ctx.tr("buttons.guard_off"), "cmd:/waechter aus"},
                        {ctx.tr("buttons.events"), "cmd:/aufnahmen"}}}
                : new String[][][]{{{ctx.tr("buttons.guard_on"), "cmd:/waechter an"},
                        {ctx.tr("buttons.events"), "cmd:/aufnahmen"}}};
    }

    private void handleStatus(long chatId, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("status.title")).append("\n\n");

        JSONObject st = ipc("STATUS", ctx);
        boolean enabled = st != null && st.optBoolean("enabled", false);
        if (st == null) {
            sb.append(DienstHinweis.text(ctx)).append("\n");   // 0.1.38.8: startet / aus / Ausfall
        } else {
            sb.append(guardLine(st, ctx)).append("\n");
        }

        JSONObject soc = data("GET_BATTERY_SOC", ctx);
        JSONObject range = data("GET_DRIVING_RANGE", ctx);
        if (soc != null && soc.has("socPercent")) {
            String socText = fmt0(soc.optDouble("socPercent"));
            if (range != null && range.optDouble("elecRangeKm", 0) > 0) {
                sb.append(ctx.tr("status.soc_range", socText, fmt0(range.optDouble("elecRangeKm")))).append("\n");
            } else {
                sb.append(ctx.tr("status.soc", socText)).append("\n");
            }
        }
        JSONObject chg = data("GET_CHARGING_STATE", ctx);
        String chgLine = ChargeCommandHandler.chargingLine(chg, ctx);
        if (chgLine != null) sb.append(chgLine).append("\n");

        // 0.1.38.7: Fernklima laut Befehl, sonst keine Zeile (nie „Klima aus“, wenn Panoptes es nicht weiß)
        String fernLine = fernKlimaLine(data("GET_FERNKLIMA", ctx), ctx);
        if (fernLine != null) sb.append(fernLine).append("\n");

        JSONObject v12 = data("GET_BATTERY_POWER", ctx);
        if (v12 != null && v12.optDouble("voltageVolts", 0) > 0) {
            String volts = String.format(locale(), "%.1f", v12.optDouble("voltageVolts"));
            sb.append(ctx.tr(v12.optBoolean("isWarning", false) || v12.optBoolean("isCritical", false)
                    ? "status.v12_low" : "status.v12", volts)).append("\n");
        }

        double c = readCpuTemperatureCelsius();
        if (c > 0) {
            sb.append(ctx.tr(c > 60 ? "status.temp_hot" : "status.temp",
                    String.format(locale(), "%.0f", c))).append("\n");
        }

        if (at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig
                .getQuietHours().optBoolean("enabled", false)) {
            org.json.JSONObject q = UnifiedTelegramConfig.getQuietHours();
            sb.append(ctx.tr("status.quiet", q.optString("from"), q.optString("to"))).append("\n");
        }

        ctx.sendMessageWithButtons(chatId, sb.toString().trim(), new String[][][]{
                guardButtons(enabled, ctx)[0],
                {{ctx.tr("buttons.camera"), "cmd:/kamera"},
                        {ctx.tr("buttons.location"), "cmd:/wo"}}});
    }

    /**
     * 0.1.38.7: Zeile zum Fernklima aus {@code GET_FERNKLIMA} ({@code FernKlima.json}) oder null. „Läuft“ und „beendet“ stehen
     * immer als „laut Befehl“ da, nie als Messwert des Autos.
     */
    static String fernKlimaLine(JSONObject f, CommandContext ctx) {
        if (f == null || !f.has("aktiv")) return null;
        java.text.SimpleDateFormat zeit = new java.text.SimpleDateFormat("HH:mm", locale());
        if (f.optBoolean("aktiv", false)) {
            String art = "";
            int stufe = f.optInt("stufe", -1);
            String a = f.isNull("art") ? "" : f.optString("art", "");
            if (stufe >= 1 && stufe <= 17) {
                // fern.stufe = Balkenstufe 1..17 (Mitte 9); im Text steht der Abstand zur Mitte: Stufe 12 -> Heizen Stufe 3
                String n = String.valueOf(at.greenchili.surfmate.byd.KlimaStufe.textStufe(stufe));
                if ("heizen".equals(a)) art = ctx.tr("status.fern_heizen", n);
                else if ("kuehlen".equals(a)) art = ctx.tr("status.fern_kuehlen", n);
            }
            if ("neutral".equals(a)) art = ctx.tr("status.fern_neutral");
            return ctx.tr("status.fernklima_laeuft", zeit.format(new java.util.Date(f.optLong("gestartet"))),
                    zeit.format(new java.util.Date(f.optLong("bis"))), art);
        }
        if (f.optBoolean("beendet", false) && !f.isNull("beendetUm")) {
            return ctx.tr("status.fernklima_beendet", zeit.format(new java.util.Date(f.optLong("beendetUm"))));
        }
        return null;
    }

    static Locale locale() {
        try {
            return Locale.forLanguageTag(LocaleManager.get());
        } catch (Exception e) {
            return Locale.GERMANY;
        }
    }

    static String fmt0(double d) {
        return String.format(locale(), "%.0f", d);
    }

    private JSONObject ipc(String command, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", command);
            return ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fahrzeugwert über IPC; null, wenn nicht da. */
    static JSONObject data(String command, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", command);
            JSONObject r = ctx.sendIpcCommand(CAMERA_IPC_PORT, cmd);
            if (r == null || !r.optBoolean("success", false)) return null;
            return r.optJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    private static final String[] CPU_THERMAL_KEYWORDS = {
        "cpu", "soc", "cluster", "little", "big", "prime",
        "cpu-thermal", "cpu_thermal", "cpuss", "cpuss-0", "cpuss-1",
        "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1", "tsens_tz_sensor"
    };

    private double readCpuTemperatureCelsius() {
        for (int i = 0; i < 30; i++) {
            try {
                java.io.File typeFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (!typeFile.exists()) continue;
                String type;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(typeFile))) {
                    type = br.readLine();
                }
                if (type == null) continue;
                String typeLower = type.toLowerCase().trim();
                boolean match = false;
                for (String kw : CPU_THERMAL_KEYWORDS) {
                    if (typeLower.contains(kw)) { match = true; break; }
                }
                if (!match) continue;

                java.io.File tempFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!tempFile.exists()) continue;
                String tempLine;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(tempFile))) {
                    tempLine = br.readLine();
                }
                if (tempLine == null || tempLine.trim().isEmpty()) continue;
                double raw = Double.parseDouble(tempLine.trim());
                double result = raw > 1000 ? raw / 1000.0 : raw;
                if (result >= 10 && result <= 120) return result;
            } catch (Exception ignored) {}
        }
        return 0;
    }
}
