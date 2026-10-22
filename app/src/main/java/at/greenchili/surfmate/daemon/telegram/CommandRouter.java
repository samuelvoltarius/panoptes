package at.greenchili.surfmate.daemon.telegram;

import at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Panoptes-Befehle: deutsch zuerst, englische Entsprechungen als Alias.
 *
 * <p>Jeder Befehl wird auf seinen deutschen Hauptnamen abgebildet, bevor ein
 * Handler ihn sieht ({@code args[0]} ist danach immer der Hauptname). Alte
 * Befehlsnamen aus der Zeit vor Panoptes bleiben als stille Aliase, damit
 * gewohnte Eingaben und alte Knöpfe in älteren Chat-Nachrichten weiter gehen;
 * die Hilfe nennt sie nicht.</p>
 *
 * <p>Fernbefehle ans Auto sind einzeln freischaltbar (Config
 * {@code telegram.remote}); gesperrte Befehle antworten mit einem Hinweis.</p>
 */
public class CommandRouter {

    /** Alias → Hauptname. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    /** Hauptname → nötige Freigabe (null = immer erlaubt). */
    private static final Map<String, String> PERMISSION = new HashMap<>();

    static {
        alias("/start");
        alias("/hilfe", "/help", "/h", "/befehle", "/commands");
        alias("/status", "/zustand");
        alias("/waechter", "/wächter", "/guardian", "/guard");
        alias("/kamera", "/camera", "/foto", "/bild", "/photo");
        alias("/wo", "/where", "/parken", "/park", "/parking");
        alias("/laden", "/charge", "/charging", "/akku", "/battery");
        alias("/aufnahmen", "/clips", "/events");
        alias("/aufnahme", "/clip", "/download");
        alias("/fernzugang", "/remote", "/link", "/url");
        alias("/dienste", "/services", "/daemons");
        alias("/dienst", "/service", "/daemon");
        alias("/sicherung", "/backup");

        PERMISSION.put("/kamera", UnifiedTelegramConfig.REMOTE_CAMERA);
        PERMISSION.put("/aufnahmen", UnifiedTelegramConfig.REMOTE_CAMERA);
        PERMISSION.put("/aufnahme", UnifiedTelegramConfig.REMOTE_CAMERA);
        PERMISSION.put("/wo", UnifiedTelegramConfig.REMOTE_LOCATION);
        PERMISSION.put("/laden", UnifiedTelegramConfig.REMOTE_CHARGING);
        PERMISSION.put("/fernzugang", UnifiedTelegramConfig.REMOTE_SERVICES);
        PERMISSION.put("/dienste", UnifiedTelegramConfig.REMOTE_SERVICES);
        PERMISSION.put("/dienst", UnifiedTelegramConfig.REMOTE_SERVICES);
        PERMISSION.put("/sicherung", UnifiedTelegramConfig.REMOTE_BACKUP);
        // /waechter: Anzeigen geht immer, an/aus prüft der Handler (REMOTE_GUARD).
    }

    private static void alias(String main, String... others) {
        ALIASES.put(main, main);
        for (String o : others) ALIASES.put(o, main);
    }

    private final List<TelegramCommandHandler> handlers = new ArrayList<>();
    private final CommandContext context;

    public CommandRouter(CommandContext context) {
        this.context = context;
        handlers.add(new SurveillanceCommandHandler());
        handlers.add(new SystemCommandHandler());
        handlers.add(new CameraCommandHandler());
        handlers.add(new ChargeCommandHandler());
        handlers.add(new WhereCommandHandler());
        handlers.add(new EventCommandHandler());
        handlers.add(new DaemonCommandHandler());
        handlers.add(new BackupCommandHandler());
    }

    /**
     * Hauptname eines Befehls ("/Guardian@MeinBot" → "/waechter"), oder null,
     * wenn es kein Panoptes-Befehl ist. Package-sichtbar für Tests.
     */
    static String canonical(String command) {
        if (command == null) return null;
        String c = command.toLowerCase(Locale.ROOT);
        int at = c.indexOf('@');
        if (at > 0) c = c.substring(0, at);
        return ALIASES.get(c);
    }

    /** Alte Befehle, die heute mit einem Argument gemeint sind. */
    static String[] rewriteLegacy(String[] args) {
        String c = args[0].toLowerCase(Locale.ROOT);
        int at = c.indexOf('@');
        if (at > 0) c = c.substring(0, at);
        if ("/stop".equals(c)) return new String[]{"/waechter", "aus"};
        return args;
    }

    /** Ist der Befehl laut Einstellungen erlaubt? Package-sichtbar für Tests. */
    static String requiredPermission(String canonical) {
        return PERMISSION.get(canonical);
    }

    /**
     * Route a command to the appropriate handler.
     * @param chatId The chat ID
     * @param text The full command text (e.g., "/waechter an")
     * @return true if command was handled
     */
    public boolean route(long chatId, String text) {
        if (text == null || text.isEmpty() || !text.startsWith("/")) {
            return false;
        }

        String[] args = rewriteLegacy(text.trim().split("\\s+"));
        String main = canonical(args[0]);
        if (main == null) {
            context.sendMessage(chatId, context.tr("router.unknown_command"));
            return true;
        }
        args[0] = main;

        String perm = requiredPermission(main);
        if (perm != null && !UnifiedTelegramConfig.isRemoteAllowed(perm)) {
            context.sendMessage(chatId, context.tr("router.not_allowed"));
            return true;
        }

        for (TelegramCommandHandler handler : handlers) {
            if (handler.canHandle(main)) {
                handler.handle(chatId, args, context);
                return true;
            }
        }

        context.sendMessage(chatId, context.tr("router.unknown_command"));
        return true;
    }
}
