package at.greenchili.surfmate.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 0.1.39 T1: Gemeinsame Grenze für alle Test-Hilfen, die ab {@code user.dir} nach oben nach Repo-Dateien suchen.
 *
 * <p>Bisher lief jede Suche per {@code getParent()} bis zur Laufwerkswurzel. Liegt ein Worktree unter einem anderen
 * Checkout (z. B. {@code <repo>\.claude\worktrees\…}), fand sie dort Dateien, die im geprüften Stand längst gelöscht
 * sind, und meldete falsches Grün (A1, Integrator 10.11.). {@link #hoch} geht nie über die Repo-Wurzel hinaus: den
 * ersten Ordner mit {@code .git} (Ordner im Hauptcheckout, Datei in einem Worktree).
 */
public final class RepoWurzel {

    private RepoWurzel() {}

    /** Ist {@code ordner} die Wurzel des Repos (enthält {@code .git} als Ordner oder Datei)? */
    public static boolean istWurzel(Path ordner) {
        return ordner != null && Files.exists(ordner.resolve(".git"));
    }

    /** Ein Ordner nach oben; {@code null} an der Repo-Wurzel (wie {@code getParent()} an der Laufwerkswurzel). */
    public static Path hoch(Path ordner) {
        if (ordner == null || istWurzel(ordner)) return null;
        return ordner.getParent();
    }
}
