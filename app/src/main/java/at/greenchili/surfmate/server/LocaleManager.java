package at.greenchili.surfmate.server;

import android.content.Context;
import android.content.SharedPreferences;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-process locale persistence for the SurfMate daemon.
 *
 * <p>Backed by {@link UnifiedConfigManager} under the {@code nativeShell}
 * section. Both the app process (UID 10xxx, runs the picker dialog) and the
 * daemon process (UID 2000, runs the HTTP server + {@link Messages}) read
 * and write the same {@code surfmate_config.json}, which the daemon creates
 * with {@code 0666} so the app UID can write to the existing file even
 * though it can't create new files in {@code /data/user_de/0/com.android.shell/surfmate/}.
 *
 * <p>This replaces an earlier design that wrote a plain-text file at
 * {@code /data/user_de/0/com.android.shell/surfmate/.surfmate/locale}. That path required the picker's
 * UID to {@code mkdir} {@code /data/user_de/0/com.android.shell/surfmate/.surfmate/}, which the app
 * UID can't do — the write silently failed and the language reverted to
 * the system locale on next cold start. {@link #migrateLegacyIfNeeded()}
 * imports any value left over from that scheme on first call.
 *
 * <p>The locale chosen here drives:
 * <ul>
 *   <li>The {@code locale} field in {@code /status} (so the WebView picks up
 *       changes made via the Android settings drawer on next poll).</li>
 *   <li>{@link Messages} catalog lookups for server-emitted JSON
 *       {@code "error"} / {@code "message"} fields.</li>
 *   <li>SRT sidecar generation language at recording close.</li>
 * </ul>
 */
public final class LocaleManager {

    /** All locales we ship translations for. en is the base. */
    public static final List<String> SUPPORTED = Arrays.asList("en", "de", "fr", "it", "nl");

    private static final Set<String> SUPPORTED_SET = new HashSet<>(SUPPORTED);
    private static final String DEFAULT_LANG = "en";
    /** Sprache, solange niemand eine gewählt hat (Panoptes: Deutsch). */
    public static final String PRODUCT_DEFAULT_LANG = "de";

    /** Section + key inside {@link UnifiedConfigManager}. */
    private static final String K_LOCALE = "locale";
    private static final String PREFS_NAME = "surfmate_locale";
    private static final String PREF_LOCALE = "locale";
    private static final String PREF_PENDING = "pending_unified_write";
    private static volatile Context appContext;
    private static final Object LOCAL_STATE_LOCK = new Object();
    private static final AtomicBoolean pendingReplayRunning = new AtomicBoolean(false);
    private static final long[] PENDING_REPLAY_DELAYS_MS = {
            0L, 2_000L, 10_000L, 30_000L, 55_000L, 120_000L, 300_000L
    };

    /**
     * Legacy file from before locale moved to {@link UnifiedConfigManager}.
     * Imported once (best-effort) and then deleted. The mkdirs on this path
     * fails from the app UID, which is exactly the bug we're migrating away
     * from — so reads stay best-effort and the migration is a one-shot.
     */
    private static final String LEGACY_STATE_FILE = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.surfmate/locale");
    private static volatile boolean legacyMigrationChecked = false;

    /** In-memory cache so we don't re-parse the unified config on every request. */
    private static volatile String cachedLocale;
    private static volatile long cachedAt;
    private static final long CACHE_TTL_MS = 5_000L;

    private LocaleManager() {}

    /**
     * Attach app-private storage before the first Activity is created.
     *
     * <p>The unified config remains authoritative across the app and daemon.
     * SharedPreferences is only a durability fallback for an app-side locale
     * pick made while the daemon IPC writer is unavailable during an update.
     */
    public static void attach(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    private static SharedPreferences preferences() {
        Context context = appContext;
        return context == null
                ? null
                : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String validStoredTag(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (AUTO_TAG.equalsIgnoreCase(raw)) return AUTO_TAG;
        return resolveOrNull(raw);
    }

    private static String readLocalFallback() {
        synchronized (LOCAL_STATE_LOCK) {
            try {
                SharedPreferences prefs = preferences();
                return prefs == null ? null : validStoredTag(prefs.getString(PREF_LOCALE, null));
            } catch (Exception e) {
                CameraDaemon.log("LocaleManager.readLocalFallback: " + e.getMessage());
                return null;
            }
        }
    }

    private static boolean hasPendingLocalWrite() {
        synchronized (LOCAL_STATE_LOCK) {
            try {
                SharedPreferences prefs = preferences();
                return prefs != null && prefs.getBoolean(PREF_PENDING, false);
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static void persistLocalFallback(String tag, boolean pending) {
        synchronized (LOCAL_STATE_LOCK) {
            try {
                SharedPreferences prefs = preferences();
                if (prefs == null) return;
                String current = prefs.getString(PREF_LOCALE, null);
                boolean currentPending = prefs.getBoolean(PREF_PENDING, false);
                if (tag.equals(current) && pending == currentPending) return;
                // commit() is intentional: the locale must survive an immediate
                // AppCompat recreation or process death after the picker closes.
                prefs.edit()
                        .putString(PREF_LOCALE, tag)
                        .putBoolean(PREF_PENDING, pending)
                        .commit();
            } catch (Exception e) {
                CameraDaemon.log("LocaleManager.persistLocalFallback: " + e.getMessage());
            }
        }
    }

    /**
     * Clear the pending marker only if the write we just acknowledged still
     * represents the user's latest choice. A newer picker action may arrive
     * while the daemon IPC round-trip is in flight.
     */
    private static boolean clearPendingLocalWriteIfCurrent(String writtenTag) {
        synchronized (LOCAL_STATE_LOCK) {
            try {
                SharedPreferences prefs = preferences();
                if (prefs == null) return true;
                String current = validStoredTag(prefs.getString(PREF_LOCALE, null));
                if (!writtenTag.equals(current)) return false;
                if (!prefs.getBoolean(PREF_PENDING, false)) return true;
                return prefs.edit().putBoolean(PREF_PENDING, false).commit();
            } catch (Exception e) {
                CameraDaemon.log("LocaleManager.clearPending: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Drop daemon-side locale/message caches after another process commits a
     * nativeShell.locale update. This is intentionally O(1) and runs only on
     * an actual locale mutation, never on the status polling path.
     */
    public static void invalidateCaches() {
        cachedLocale = null;
        cachedAt = 0L;
        Messages.invalidate();
    }

    /**
     * Replay an app-private locale choice that could not reach the daemon.
     *
     * <p>One daemon thread is created only while a write is pending. Attempts
     * are sparse and bounded so daemon startup/update windows are covered
     * without adding steady-state polling or blocking Android's main thread.
     */
    public static void replayPendingWriteAsync() {
        if (!hasPendingLocalWrite()
                || !pendingReplayRunning.compareAndSet(false, true)) {
            return;
        }
        Thread replay = new Thread(() -> {
            boolean completed = false;
            long startedAt = System.currentTimeMillis();
            try {
                for (long delayMs : PENDING_REPLAY_DELAYS_MS) {
                    long waitMs = startedAt + delayMs - System.currentTimeMillis();
                    if (waitMs > 0L) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (replayPendingWrite()) {
                        completed = true;
                        return;
                    }
                }
            } finally {
                pendingReplayRunning.set(false);
                // Close the tiny race where a newer selection is persisted
                // after the final successful check but before the guard clears.
                if (completed && hasPendingLocalWrite()) {
                    replayPendingWriteAsync();
                }
            }
        }, "LocalePersistenceReplay");
        replay.setDaemon(true);
        replay.start();
    }

    /**
     * Returns true once no pending choice remains. If the user changes the
     * locale during an acknowledged write, immediately replay the newer value
     * (bounded burst) instead of clearing its pending marker.
     */
    private static boolean replayPendingWrite() {
        for (int burst = 0; burst < 3; burst++) {
            String tag = readLocalFallback();
            if (tag == null || !hasPendingLocalWrite()) return true;
            try {
                JSONObject delta = new JSONObject();
                delta.put(K_LOCALE, tag);
                if (!UnifiedConfigManager.updateSection("nativeShell", delta)) {
                    return false;
                }
                if (clearPendingLocalWriteIfCurrent(tag)) {
                    invalidateCaches();
                    return true;
                }
            } catch (Exception e) {
                CameraDaemon.log("LocaleManager.replayPending: " + e.getMessage());
                return false;
            }
        }
        return !hasPendingLocalWrite();
    }

    /**
     * One-shot import of any locale picked before this build. Idempotent —
     * after the first successful migration the legacy file is removed and
     * the in-process latch keeps subsequent calls O(1). Failure to delete
     * the legacy file (cross-UID perm denial) is fine: a later call from
     * the privileged UID can clean up.
     */
    private static volatile boolean uebernahmeGeprueft = false;
    /** Merker im Abschnitt nativeShell: Nutzer hat im Sprachwähler gewählt (dann nie migrieren). */
    static final String K_GEWAEHLT = "localeGewaehlt";
    /** Merker: einmalige Prüfung „übernommenes en“ ist erledigt. */
    static final String K_MIGRIERT = "localeMigriert";

    /**
     * 0.1.7.1 (Alfreds Test): Aus der Overdrive-Übernahme steht {@code locale = "en"}, ohne dass jemand Englisch
     * gewählt hat – die App erschien englisch, obwohl das Auto auf Deutsch steht. Einmalig: Steht „en“ ohne
     * Merker {@link #K_GEWAEHLT} und ist die Systemsprache nicht Englisch, gilt die Systemsprache (unterstützt),
     * sonst Deutsch. Rein, testbar. @return neue Sprache oder null (nichts ändern)
     */
    static String uebernommenesEnZiel(JSONObject nativeShell, String systemSprache) {
        if (nativeShell == null) return null;
        if (nativeShell.optBoolean(K_GEWAEHLT, false) || nativeShell.optBoolean(K_MIGRIERT, false)) return null;
        if (!"en".equalsIgnoreCase(nativeShell.optString(K_LOCALE, ""))) return null;
        String sys = systemSprache == null ? "" : systemSprache.trim().toLowerCase(Locale.ROOT);
        if (sys.startsWith("en")) return null;
        String r = sys.isEmpty() ? null : resolveOrNull(sys);
        return r == null || "en".equals(r) ? PRODUCT_DEFAULT_LANG : r;
    }

    /** Systemsprache des Autos (persist.sys.locale, sonst ro.product.locale); der Daemon erbt sonst en_US. */
    static String systemSprache() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            String v = (String) get.invoke(null, "persist.sys.locale");
            if (v == null || v.isEmpty()) v = (String) get.invoke(null, "ro.product.locale");
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void uebernahmeEnMigrieren() {
        if (uebernahmeGeprueft) return;
        uebernahmeGeprueft = true;
        try {
            JSONObject section = UnifiedConfigManager.getNativeShell();
            if (section.optBoolean(K_MIGRIERT, false) || section.optBoolean(K_GEWAEHLT, false)) return;
            String ziel = uebernommenesEnZiel(section, systemSprache());
            JSONObject delta = new JSONObject();
            delta.put(K_MIGRIERT, true);
            if (ziel != null) delta.put(K_LOCALE, ziel);
            if (!UnifiedConfigManager.updateSection("nativeShell", delta)) {
                uebernahmeGeprueft = false;   // App-Prozess darf ggf. nicht schreiben – nächster Start/Daemon
                return;
            }
            if (ziel != null) {
                // WebView-Sprache (appearance.locale) nur, wenn sie ebenfalls das übernommene „en“ trägt
                JSONObject app = UnifiedConfigManager.getAppearance();
                if ("en".equalsIgnoreCase(app.optString("locale", ""))) {
                    JSONObject a = new JSONObject();
                    a.put("locale", ziel);
                    UnifiedConfigManager.updateSection("appearance", a);
                }
                persistLocalFallback(ziel, false);
                invalidateCaches();
                CameraDaemon.log("LocaleManager: übernommenes en → " + ziel + " (Systemsprache)");
            }
        } catch (Exception e) {
            uebernahmeGeprueft = false;
            CameraDaemon.log("LocaleManager.uebernahmeEn: " + e.getMessage());
        }
    }

    private static synchronized void migrateLegacyIfNeeded() {
        uebernahmeEnMigrieren();
        if (legacyMigrationChecked) return;
        legacyMigrationChecked = true;
        try {
            JSONObject section = UnifiedConfigManager.getNativeShell();
            if (section.has(K_LOCALE)) return;
            File f = new File(LEGACY_STATE_FILE);
            if (!f.exists() || !f.canRead()) return;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[16];
                int n = fis.read(buf);
                if (n <= 0) return;
                String tag = validStoredTag(new String(buf, 0, n, "UTF-8").trim());
                if (tag == null) return;
                JSONObject delta = new JSONObject();
                delta.put(K_LOCALE, tag);
                UnifiedConfigManager.updateSection("nativeShell", delta);
            }
            try { f.delete(); } catch (Exception ignored) {}
        } catch (Exception e) {
            CameraDaemon.log("LocaleManager.migrateLegacy: " + e.getMessage());
        }
    }

    /**
     * Resolve any tag (e.g. "zh-Hans-CN", "pt", "no") to one of {@link #SUPPORTED}.
     * Mirrors the JS-side {@code resolveLang} so server and client agree.
     */
    public static String resolveOrNull(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        // Exact match first
        for (String s : SUPPORTED) {
            if (s.toLowerCase(Locale.ROOT).equals(lower)) return s;
        }
        // Bare-language fallback
        int dash = lower.indexOf('-');
        String bare = dash > 0 ? lower.substring(0, dash) : lower;
        for (String s : SUPPORTED) {
            String b = s.toLowerCase(Locale.ROOT);
            int d = b.indexOf('-');
            if ((d > 0 ? b.substring(0, d) : b).equals(bare)) return s;
        }
        return null;
    }

    public static String resolve(String raw) {
        String resolved = resolveOrNull(raw);
        return resolved == null ? DEFAULT_LANG : resolved;
    }

    public static boolean isSupported(String tag) {
        return tag != null && SUPPORTED_SET.contains(tag);
    }

    /**
     * Sentinel written to the state file when the user picks "Auto (follow
     * system)". Distinguishes "user explicitly wants system locale" from
     * "user has never chosen" (latter also resolves to system).
     */
    public static final String AUTO_TAG = "auto";

    /**
     * Raw persisted value: a supported tag, {@link #AUTO_TAG}, or {@code null}
     * if nothing has been written yet. Used by the picker UI so it can show
     * the "Auto" row as currently selected when appropriate. The HTTP server
     * and message catalogs should keep using {@link #get()}.
     */
    public static String getRaw() {
        migrateLegacyIfNeeded();
        String local = readLocalFallback();
        if (local != null && hasPendingLocalWrite()) {
            return local;
        }
        try {
            JSONObject section = UnifiedConfigManager.getNativeShell();
            String tag = validStoredTag(section.optString(K_LOCALE, ""));
            if (tag != null) {
                persistLocalFallback(tag, false);
                return tag;
            }
        } catch (Exception e) {
            CameraDaemon.log("LocaleManager.getRaw: " + e.getMessage());
        }
        return local;
    }

    /**
     * Returns true when the user has explicitly chosen "Auto", or when no
     * choice has ever been persisted. In both cases the active locale should
     * follow the device default (BCP-47 of {@code Locale.getDefault()}).
     */
    public static boolean isAuto() {
        String raw = getRaw();
        return raw == null || AUTO_TAG.equals(raw);
    }

    /**
     * Persist the "follow system" sentinel. After this, {@link #get()} will
     * resolve via the device default each call (cache invalidated on write).
     */
    public static void setAuto() {
        boolean saved = false;
        try {
            JSONObject delta = new JSONObject();
            delta.put(K_LOCALE, AUTO_TAG);
            delta.put(K_GEWAEHLT, true);
            saved = UnifiedConfigManager.updateSection("nativeShell", delta);
        } catch (Exception e) {
            CameraDaemon.log("LocaleManager.setAuto: " + e.getMessage());
        }
        persistLocalFallback(AUTO_TAG, !saved);
        invalidateCaches();
        if (!saved) replayPendingWriteAsync();
    }

    /**
     * Parse an HTTP {@code Accept-Language} header (e.g.
     * {@code "fr-CA,fr;q=0.9,en;q=0.8"}) and return the first supported locale.
     * Used on first request only — once the user has explicitly chosen a
     * locale via the picker we honour {@link #get()} instead.
     */
    public static String fromAcceptLanguage(String header) {
        if (header == null || header.isEmpty()) return DEFAULT_LANG;
        String[] parts = header.split(",");
        for (String p : parts) {
            String tag = p.trim();
            int semi = tag.indexOf(';');
            if (semi > 0) tag = tag.substring(0, semi).trim();
            if (tag.isEmpty()) continue;
            String resolved = resolve(tag);
            // resolve() returns 'en' both for "I want English" and "I want
            // something we don't support"; only treat the latter as a miss.
            if (!resolved.equals(DEFAULT_LANG)
                    || tag.toLowerCase(Locale.ROOT).startsWith("en")) {
                return resolved;
            }
        }
        return DEFAULT_LANG;
    }

    /**
     * Resolve the active locale. Returns one of {@link #SUPPORTED}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Persisted user pick (specific tag) → that tag.</li>
     *   <li>Persisted "Auto" sentinel OR no file → resolve {@link
     *       java.util.Locale#getDefault()} via {@link #resolve(String)}.</li>
     * </ol>
     *
     * <p>This way the HTTP server and {@link Messages} always see a real,
     * supported tag without having to know about Auto.
     */
    public static String get() {
        long now = System.currentTimeMillis();
        if (cachedLocale != null && now - cachedAt < CACHE_TTL_MS) return cachedLocale;
        String resolved = DEFAULT_LANG;
        try {
            String raw = getRaw();
            if (raw != null && !AUTO_TAG.equals(raw) && isSupported(raw)) {
                resolved = raw;
            } else if (raw == null) {
                // Noch nie gewählt: Panoptes startet auf Deutsch (Community DACH).
                // Der Daemon erbt sonst ro.product.locale (oft en_US), nicht die
                // Systemsprache des Autos. „Auto“ im Sprachwähler folgt dem System.
                resolved = PRODUCT_DEFAULT_LANG;
            } else {
                // Auto / unset → resolve from Locale.getDefault(). The
                // daemon process inherits the BYD system locale at fork.
                java.util.Locale def = java.util.Locale.getDefault();
                String tag = def.getLanguage();
                String region = def.getCountry();
                if (region != null && !region.isEmpty()) tag = tag + "-" + region;
                resolved = resolve(tag);
            }
        } catch (Exception e) {
            CameraDaemon.log("LocaleManager.get: " + e.getMessage());
        }
        cachedLocale = resolved;
        cachedAt = now;
        return resolved;
    }

    /**
     * Persist a new locale. Returns the resolved tag actually written
     * (so callers can echo it back even if the input was an alias).
     *
     * <p>The literal string {@code "auto"} writes the {@link #AUTO_TAG}
     * sentinel so subsequent {@link #get()} calls follow the system
     * locale; the returned tag is then the system-resolved language so
     * callers can show the right UI feedback.
     */
    public static String set(String tag) {
        if (tag != null && AUTO_TAG.equalsIgnoreCase(tag.trim())) {
            setAuto();
            return get();
        }
        String resolved = resolve(tag);
        boolean saved = false;
        try {
            JSONObject delta = new JSONObject();
            delta.put(K_LOCALE, resolved);
            delta.put(K_GEWAEHLT, true);
            saved = UnifiedConfigManager.updateSection("nativeShell", delta);
        } catch (Exception e) {
            CameraDaemon.log("LocaleManager.set: " + e.getMessage());
        }
        persistLocalFallback(resolved, !saved);
        cachedLocale = resolved;
        cachedAt = System.currentTimeMillis();
        // Drop any cached Messages catalog so the next server-side
        // i18n lookup loads the new locale's JSON.
        Messages.invalidate();
        if (!saved) replayPendingWriteAsync();
        return resolved;
    }
}
