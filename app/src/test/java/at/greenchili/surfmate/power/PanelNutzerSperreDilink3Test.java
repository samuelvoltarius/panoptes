package at.greenchili.surfmate.power;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.38.8 (Deploy 3, Surf DiLink 3, Zündung aus): {@code screen_power} setzte die 60-s-Nutzer-Sperre, der
 * AccSentryDaemon dunkelte auf dem Legacy-Weg trotzdem im 10-s-Takt wieder ab. Jetzt gilt die Sperre auch dort;
 * nach Ablauf wird normal abgedunkelt (12 V), die Sperre wird nie verlängert.
 */
public class PanelNutzerSperreDilink3Test {
    private static final long JETZT = 1_800_000_000_000L;

    @Test
    public void aktiveSperreHaeltDasPanelAn() {
        assertTrue("Sperre läuft noch 30 s → kein OFF", StealthPanel.userOverrideLive(JETZT + 30_000L, JETZT));
        assertTrue("genau 60 s", StealthPanel.userOverrideLive(JETZT + 60_000L, JETZT));
    }

    @Test
    public void abgelaufeneOderUnsinnigeSperreDunkeltNormalAb() {
        assertFalse("abgelaufen → OFF", StealthPanel.userOverrideLive(JETZT - 1L, JETZT));
        assertFalse("nie gesetzt → OFF", StealthPanel.userOverrideLive(0L, JETZT));
        assertFalse("Zukunftswert nach Uhr-Rücksprung darf das Abdunkeln nie dauerhaft sperren",
                StealthPanel.userOverrideLive(JETZT + 60_001L, JETZT));
    }

    @Test
    public void legacyWegPrueftDieSperreVorDemOff() throws Exception {
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int zweig = d.indexOf("if (!verifiedPanelMode) {");
        int off = d.indexOf("return setBacklightState(\n                        generation, false)", zweig);
        int sperre = d.indexOf("&& !isLegacyUserOverrideActive()) {", zweig);
        assertTrue("Legacy-Zweig gefunden", zweig > 0 && off > zweig);
        assertTrue("Sperre steht vor dem OFF im Legacy-Zweig", sperre > zweig && sperre < off);
        int hilfe = d.indexOf("private static boolean isLegacyUserOverrideActive()");
        int panelAus = d.indexOf("StealthPanel.isPanelOff(appContext)) return false;", hilfe);
        int lesen = d.indexOf("StealthPanel.isUserOverrideActive();", hilfe);
        assertTrue("bei nachweislich dunklem Panel keine Config-Neulesung", hilfe > 0 && panelAus > hilfe && lesen > panelAus);
        String p = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/power/StealthPanel.java")), StandardCharsets.UTF_8);
        assertTrue("Sperrdauer unverändert 60 s", p.contains("USER_OVERRIDE_GRACE_MS = 60_000L"));
    }
}
