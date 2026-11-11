package at.greenchili.surfmate.testutil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 0.1.39 T1: Test-Pfadsuchen enden an der Repo-Wurzel (.git), nie in einem Checkout darüber. */
public class RepoWurzelTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void haeltAnDerWurzel() throws Exception {
        // äußerer Checkout mit .git-Ordner, darin ein Worktree mit .git-Datei (wie <repo>\.claude\worktrees\x)
        Path aussen = tmp.newFolder("surfmate").toPath();
        Files.createDirectory(aussen.resolve(".git"));
        Path wt = Files.createDirectories(aussen.resolve(".claude/worktrees/x"));
        Files.write(wt.resolve(".git"), "gitdir: …".getBytes(StandardCharsets.UTF_8));
        Path app = Files.createDirectories(wt.resolve("app"));

        assertEquals(wt, RepoWurzel.hoch(app));
        assertNull("nie über die Wurzel des Worktrees hinaus", RepoWurzel.hoch(wt));
        assertNull("auch der Hauptcheckout endet bei sich", RepoWurzel.hoch(aussen));
        assertTrue(RepoWurzel.istWurzel(wt));
        assertNull(RepoWurzel.hoch(null));
    }

    @Test
    public void keinTestGehtSelbstNachOben() throws Exception {
        Path test = Paths.get("src/test");
        if (!Files.exists(test)) test = Paths.get("app/src/test");
        Pattern java = Pattern.compile("\\b(\\w+)\\s*=\\s*\\1\\.getParent\\(\\)");
        Pattern kt = Pattern.compile("\\b(\\w+)\\s*=\\s*\\1\\.parent\\b");
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> st = Files.walk(test)) {
            st.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(p -> {
                if (p.getFileName().toString().startsWith("RepoWurzel")) return;
                try {
                    String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    Pattern rx = p.toString().endsWith(".kt") ? kt : java;
                    java.util.regex.Matcher m = rx.matcher(s);
                    while (m.find()) {
                        // ClassLoader-Ketten (loader = loader.getParent()) sind keine Pfadsuche
                        if (!m.group(1).equals("loader")) { treffer.add(p.getFileName().toString()); break; }
                    }
                } catch (Exception ignored) { }
            });
        }
        assertTrue("Suche nach oben ohne RepoWurzel.hoch: " + treffer, treffer.isEmpty());
    }
}
