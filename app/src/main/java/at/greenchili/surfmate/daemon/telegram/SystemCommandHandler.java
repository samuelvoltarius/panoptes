package at.greenchili.surfmate.daemon.telegram;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * {@code /start} (Begrüßung), {@code /menue} und {@code /mehr} (0.1.38.2 Hauptmenü), {@code /hilfe}, {@code /dienste}
 * und {@code /fernzugang}.
 */
public class SystemCommandHandler implements TelegramCommandHandler {
    
    @Override
    public boolean canHandle(String command) {
        return "/start".equals(command) || "/hilfe".equals(command) || "/menue".equals(command)
                || "/mehr".equals(command) || "/dienste".equals(command) || "/fernzugang".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        
        switch (cmd) {
            case "/dienste":
                handleDaemons(chatId, ctx);
                break;
            case "/fernzugang":
                handleUrl(chatId, ctx);
                break;
            case "/start":
                // 0.1.38.2: fester Knopf „☰ Menü“ unten, dann gleich das Menü (kein Abtippen)
                ctx.sendMessageWithMenuKey(chatId, ctx.tr("welcome.paired"), ctx.tr("menu.btn_menu"));
                handleMenu(chatId, ctx);
                break;
            case "/menue":
                handleMenu(chatId, ctx);
                break;
            case "/mehr":
                handleMore(chatId, ctx);
                break;
            case "/hilfe":
                handleHelp(chatId, ctx);
                break;
        }
    }
    
    private void handleDaemons(long chatId, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("daemons.title"));
        
        // All known daemons: {cmdName, processName, displayName, canStart, canStop}
        // cmdName is used for /daemon <name> start|stop
        // canStart: "yes" if can be started via telegram, "no" if must use app UI
        // canStop: "yes" if can be stopped via telegram, "no" if should not be stopped remotely
        String[][] allDaemons = {
            {"camera", "byd_cam_daemon", "daemon_names.camera", "yes", "yes"},
            {"acc", "acc_sentry_daemon", "daemon_names.acc_sentry", "yes", "yes"},
            {"sentry", "sentry_daemon", "daemon_names.sentry", "yes", "yes"},
            {"telegram", "telegram_bot_daemon", "daemon_names.telegram", "no", "no"},
            {"tailscale", "tailscaled", "daemon_names.tailscale_tunnel", "yes", "yes"},
            {"wireguard", "wgproxy", "daemon_names.wireguard_tunnel", "yes", "yes"},
        };
        
        java.util.List<String[][]> buttonRows = new java.util.ArrayList<>();
        int runningCount = 0;
        int stoppedCount = 0;
        
        // First pass: show running daemons
        for (String[] d : allDaemons) {
            String cmdName = d[0];
            String processName = d[1];
            String displayName = ctx.tr(d[2]);
            boolean canStart = "yes".equals(d[3]);
            boolean canStop = "yes".equals(d[4]);
            boolean running = isDaemonRunning(processName, ctx);
            
            if (running) {
                sb.append("✅ ").append(displayName).append("\n");
                runningCount++;
                
                // Add stop button if allowed
                if (canStop) {
                    buttonRows.add(new String[][]{{ctx.tr("buttons.stop_named", displayName), "dm:" + cmdName + ":stop"}});
                }
            } else {
                sb.append("⛔ ").append(displayName).append("\n");
                stoppedCount++;
                
                // Add start button for startable daemons
                if (canStart) {
                    buttonRows.add(new String[][]{{ctx.tr("buttons.start_named", displayName), "dm:" + cmdName + ":start"}});
                }
            }
        }
        
        // Add refresh button
        buttonRows.add(new String[][]{{ctx.tr("buttons.refresh"), "cmd:/dienste"}});
        
        String[][][] buttons = buttonRows.toArray(new String[0][][]);
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }
    
    private void handleUrl(long chatId, CommandContext ctx) {
        try {
            // Check each tunnel independently and collect URLs for any that are running.
            // 0.1.14: nur unser tailscaled zählt (ein Notzugang bzw. fremder tailscaled nicht)
            String tailscaleRunning = ctx.execShell(at.greenchili.surfmate.launcher.TailscaledProzesse.pidsSkript(
                    at.greenchili.surfmate.launcher.TailscaledProzesse.PANOPTES_STATEDIR));

            boolean tailscaleUp = tailscaleRunning != null && !tailscaleRunning.trim().isEmpty();

            if (!tailscaleUp) {
                ctx.sendMessage(chatId, ctx.tr("url.none_running"));
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(ctx.tr("url.title"));
            int resolved = 0;
            int pending = 0;

            if (tailscaleUp) {
                String url = null;
                String getIpResult = ctx.execShell(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.tailscale/tailscale --socket 127.0.0.1:8632 ip --1"));
                if (getIpResult != null && !getIpResult.trim().isEmpty()) {
                    url = "http://" + getIpResult.trim() + ":8180";
                }
                if (url != null) {
                    sb.append(ctx.tr("url.resolved_line",
                            ctx.tr("daemon_names.tailscale_tunnel"), url));
                    resolved++;
                } else {
                    sb.append(ctx.tr("url.pending_line",
                            ctx.tr("daemon_names.tailscale_tunnel")));
                    pending++;
                }
            }

            // Last-resort fallback: if nothing resolved from logs, try the saved URL file.
            if (resolved == 0) {
                File urlFile = new File(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/tunnel_url.txt"));
                if (urlFile.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(urlFile)));
                    String saved = reader.readLine();
                    reader.close();
                    if (saved != null && !saved.isEmpty()) {
                        sb.append(ctx.tr("url.last_known", saved.trim()));
                    }
                }
            }

            if (pending > 0) {
                sb.append(ctx.tr("url.retry_pending"));
            }

            ctx.sendMessage(chatId, sb.toString());
        } catch (Exception e) {
            ctx.sendMessage(chatId, ctx.tr("url.error",
                    ctx.technicalDetail(e.getMessage())));
        }
    }
    
    private void handleHelp(long chatId, CommandContext ctx) {
        String version = at.greenchili.surfmate.updater.AppUpdater.getDisplayVersionFromFile();
        ctx.sendMessageWithButtons(chatId, helpText(ctx, version), new String[][][]{
            {{ctx.tr("buttons.status"), "cmd:/status"}, {ctx.tr("buttons.events"), "cmd:/aufnahmen"}},
            {{ctx.tr("buttons.camera"), "cmd:/kamera"}, {ctx.tr("buttons.location"), "cmd:/wo"}},
            {{ctx.tr("menu.btn_menu"), "cmd:/menue"}, {ctx.tr("menu.btn_updates"), "cmd:/updates"}}
        });
    }

    private void handleMenu(long chatId, CommandContext ctx) {
        ctx.sendMessageWithButtons(chatId, menuText(ctx), menuButtons(ctx));
    }

    private void handleMore(long chatId, CommandContext ctx) {
        ctx.sendMessageWithButtons(chatId, ctx.tr("menu.more_title"), moreButtons(ctx));
    }

    private static boolean erlaubt(String recht) {
        return at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isRemoteAllowed(recht);
    }

    /** Text über dem Hauptmenü; mit Hinweis, wenn Knöpfe ausgeschaltet sind. Package-sichtbar für Tests. */
    static String menuText(CommandContext ctx) {
        String t = ctx.tr("menu.title");
        boolean versteckt = !erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CAMERA)
                || !erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_LOCATION)
                || !erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CHARGING);
        return versteckt ? t + "\n\n" + ctx.tr("menu.locked") : t;
    }

    /**
     * 0.1.38.2 Hauptmenü (REGEL 50+): wenige große Knöpfe, zwei je Reihe, ausgeschaltete Fernbefehle unsichtbar.
     * Package-sichtbar für Tests.
     */
    static String[][][] menuButtons(CommandContext ctx) {
        java.util.List<String[]> k = new java.util.ArrayList<>();
        k.add(new String[]{ctx.tr("menu.btn_status"), "cmd:/status"});
        k.add(new String[]{ctx.tr("menu.btn_guard"), "cmd:/waechter"});
        if (erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_LOCATION)) {
            k.add(new String[]{ctx.tr("menu.btn_where"), "cmd:/wo"});
        }
        if (erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CAMERA)) {
            k.add(new String[]{ctx.tr("menu.btn_camera"), "cmd:/kamera"});
            k.add(new String[]{ctx.tr("menu.btn_clips"), "cmd:/aufnahmen"});
        }
        if (erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CHARGING)) {
            k.add(new String[]{ctx.tr("menu.btn_charge"), "cmd:/laden"});
        }
        k.add(new String[]{ctx.tr("menu.btn_updates"), "cmd:/updates"});
        k.add(new String[]{ctx.tr("menu.btn_more"), "cmd:/mehr"});
        return paare(k);
    }

    /** „Mehr“: Camping, Tier, Link, Dienste, Sicherung, Hilfe – auch hier nur, was eingeschaltet ist. */
    static String[][][] moreButtons(CommandContext ctx) {
        java.util.List<String[]> k = new java.util.ArrayList<>();
        k.add(new String[]{ctx.tr("menu.btn_camping"), "cmd:/camping"});
        k.add(new String[]{ctx.tr("menu.btn_tier"), "cmd:/tier"});
        if (erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_SERVICES)) {
            k.add(new String[]{ctx.tr("menu.btn_remote"), "cmd:/fernzugang"});
            k.add(new String[]{ctx.tr("menu.btn_services"), "cmd:/dienste"});
        }
        if (erlaubt(at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_BACKUP)) {
            k.add(new String[]{ctx.tr("menu.btn_backup"), "cmd:/sicherung"});
            k.add(new String[]{ctx.tr("menu.btn_backup_trips"), "cmd:/sicherung fahrten"});
        }
        k.add(new String[]{ctx.tr("buttons.help"), "cmd:/hilfe"});
        k.add(new String[]{ctx.tr("menu.btn_back"), "cmd:/menue"});
        return paare(k);
    }

    private static String[][][] paare(java.util.List<String[]> k) {
        java.util.List<String[][]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < k.size(); i += 2) {
            rows.add(i + 1 < k.size() ? new String[][]{k.get(i), k.get(i + 1)} : new String[][]{k.get(i)});
        }
        return rows.toArray(new String[0][][]);
    }

    /**
     * Hilfe aus den freigeschalteten Befehlen. Gesperrte Fernbefehle werden
     * nicht gezeigt, nur ein Hinweis, wo man sie freischaltet.
     * Package-sichtbar für Tests.
     */
    static String helpText(CommandContext ctx, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("help.title")).append("\n\n");
        sb.append(ctx.tr("help.menu")).append("\n");
        sb.append(ctx.tr("help.status")).append("\n");
        boolean guard = at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isRemoteAllowed(
                at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_GUARD);
        sb.append(ctx.tr(guard ? "help.guard" : "help.guard_view")).append("\n");
        sb.append(ctx.tr(guard ? "help.camping" : "help.camping_view")).append("\n");
        sb.append(ctx.tr("help.tier")).append("\n");
        String[][] optional = {
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CAMERA, "help.camera"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CAMERA, "help.clips"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_LOCATION, "help.where"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_CHARGING, "help.charge"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_SERVICES, "help.remote"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_SERVICES, "help.services"},
                {at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.REMOTE_BACKUP, "help.backup"},
        };
        boolean hidden = !guard;
        for (String[] o : optional) {
            if (at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.isRemoteAllowed(o[0])) {
                sb.append(ctx.tr(o[1])).append("\n");
            } else {
                hidden = true;
            }
        }
        sb.append(ctx.tr("help.updates")).append("\n");
        sb.append(ctx.tr("help.kopplungen")).append("\n");   // 0.1.38.8 verlorenes Handy
        sb.append(ctx.tr("help.help")).append("\n");
        if (hidden) sb.append("\n").append(ctx.tr("help.some_locked"));
        sb.append("\n").append(ctx.tr("help.footer", version == null ? "" : version));
        return sb.toString();
    }
    
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell(
                "ps -A | " + DaemonCommandHandler.processMatcher(processName));
        return output != null && !output.trim().isEmpty();
    }
}
