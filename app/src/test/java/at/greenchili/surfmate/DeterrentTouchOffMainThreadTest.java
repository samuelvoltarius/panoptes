package at.greenchili.surfmate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Absturz 20.10.2026 21:12–21:13 (logcat -b crash, 3×): NetworkOnMainThreadException in
 * DeterrentActivity.dispatchTouchEvent -> PrintWriter.println auf den Daemon-Socket.
 * Der Socket darf im Touch-Pfad (Haupt-Thread) nie direkt beschrieben werden.
 */
public class DeterrentTouchOffMainThreadTest {

    @Test
    public void dismissIsWrittenOnTheGateWriterThread() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/DeterrentActivity.java")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int start = src.indexOf("private void sendInputDismiss() {");
        int end = src.indexOf("private void finishAfterTeardownGrace()", start);
        assertTrue(start > 0 && end > start);
        String body = src.substring(start, end);
        int exec = body.indexOf("gateWriter.execute(");
        int write = body.indexOf("writer.println(\"DISMISS\")");
        assertTrue("println nur im Executor", exec > 0 && write > exec);
        assertFalse(body.contains("inputCaptureWriter.println("));
    }
}
