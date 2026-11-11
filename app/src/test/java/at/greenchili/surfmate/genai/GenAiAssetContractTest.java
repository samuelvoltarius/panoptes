package at.greenchili.surfmate.genai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class GenAiAssetContractTest {

    @Test
    public void assistantIsTextFirstAndSessionOnly() throws Exception {
        String html = readProjectFile(
                "src/main/assets/web/local/assistant.html");
        String javascript = readProjectFile(
                "src/main/assets/web/shared/genai.js");
        String handler = readProjectFile(
                "src/main/java/at/greenchili/surfmate/server/GenAiApiHandler.java");
        String chatSocket = readProjectFile(
                "src/main/java/at/greenchili/surfmate/server/GenAiChatWebSocket.java");
        String voiceSocket = readProjectFile(
                "src/main/java/at/greenchili/surfmate/server/GenAiVoiceWebSocket.java");
        String runtime = readProjectFile(
                "src/main/java/at/greenchili/surfmate/genai/GenAiRuntime.java");
        String english = readProjectFile("src/main/assets/web/i18n/en.json");

        assertTrue(html.contains("id=\"genAiInput\""));
        assertTrue(html.contains("id=\"genAiVoiceBtn\""));
        assertTrue(html.contains("genAiVoiceBtn\" type=\"button\" disabled"));
        assertTrue(javascript.contains("messages: []"));
        assertFalse(javascript.contains("localStorage"));
        assertFalse(javascript.contains("speechSynthesis"));
        assertFalse(javascript.contains("SpeechRecognition"));
        assertTrue(javascript.contains("/api/genai/automation/draft"));
        assertTrue(javascript.contains("/api/genai/automation/commit"));
        assertTrue(javascript.contains("draft.saved"));
        assertTrue(javascript.contains("t('genai.draft_saved_manual')"));
        assertTrue(english.contains(
                "\"draft_saved_manual\": \"Saved. It starts by hand only"));
        assertTrue(handler.contains(
                "saveManualAutomation("));
        assertTrue(handler.contains(
                "UUID.nameUUIDFromBytes("));
        assertTrue(handler.contains(".put(\"saved\", true)"));
        assertTrue(javascript.contains("'/ws/genai'"));
        assertTrue(javascript.contains("'/ws/genai/chat'"));
        assertTrue(javascript.contains("type: 'cancel'"));
        assertTrue(javascript.contains("scheduleStreamRender"));
        assertTrue(javascript.contains("navigator.mediaDevices"));
        assertTrue(javascript.contains("AudioWorkletNode"));
        assertTrue(javascript.contains("createScriptProcessor"));
        assertTrue(javascript.contains("usageLabel"));
        assertFalse(javascript.contains("input_audio_transcription"));
        assertTrue(html.contains("data-mode=\"trip_comparison\""));
        assertTrue(html.contains(
                "data-mode=\"automation_diagnostics\""));
        assertTrue(javascript.contains(
                "automation_diagnostics: 'genai.source_automation_diagnostics'"));
        assertTrue(html.contains("data-mode=\"vehicle_action\""));
        assertTrue(html.contains("data-mode=\"vehicle_history\""));
        assertTrue(html.contains("id=\"genAiRoutineSuggestions\""));
        assertTrue(html.contains("id=\"genAiIncidentPacks\""));
        assertTrue(html.contains(
                "id=\"genAiRoutineLearningEnabled\""));
        assertTrue(javascript.contains(
                "'/api/genai/action/execute'"));
        assertTrue(javascript.contains(
                "'/api/genai/history/query'"));
        assertTrue(javascript.contains(
                "'/api/genai/routines/decision'"));
        assertTrue(javascript.contains(
                "'/api/genai/incidents?limit=5'"));
        assertTrue(javascript.contains("t('genai.confirm_run')"));
        assertTrue(english.contains("\"confirm_run\": \"Yes, do it\""));
        assertTrue(javascript.contains("action_proposal"));
        assertTrue(javascript.contains(
                "this.selectedMode = response.needsInput"));
        assertTrue(javascript.contains("context_request"));
        assertTrue(javascript.contains("confirm_context"));
        assertTrue(javascript.contains("t('genai.share_once')"));
        assertTrue(javascript.contains("t('genai.voice_request'"));
        assertTrue(html.contains("class=\"assistant-page\""));
        assertTrue(html.contains("class=\"ai-composer-dock\""));
        assertTrue(javascript.contains(
                "this.renderRichText(bubble, content)"));
        assertTrue(javascript.contains(
                "document.createTextNode("));
        assertFalse(javascript.contains("bubble.innerHTML"));
        assertTrue(javascript.contains("this.communityCards(message.communityResults)"));
        assertTrue(javascript.contains("t('genai.confirm_import'"));
        assertTrue(html.contains(
                "<select class=\"md-input\" id=\"genAiModelPreset\""));
        assertTrue(html.contains(
                "<select class=\"md-input\" id=\"genAiRealtimeModelPreset\""));
        assertFalse(html.contains("<datalist"));
        assertFalse(html.contains("list=\"genAi"));
        assertTrue(javascript.contains("fillModelSelect"));
        assertTrue(javascript.contains("applyModelPreset"));
        assertTrue(javascript.contains("responseLanguage: function"));
        assertTrue(javascript.contains(
                "language: this.responseLanguage()"));
        assertTrue(javascript.contains(
                "language: self.responseLanguage()"));
        assertTrue(handler.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(handler.contains(
                "GenAiVehicleHistory.execute("));
        assertTrue(handler.contains(
                "GenAiRoutineLearner.decision("));
        assertTrue(handler.contains(
                "GenAiIncidentPacks.create("));
        assertTrue(chatSocket.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(voiceSocket.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(html.contains("@media (max-width: 900px)"));
        assertTrue(html.contains(".ai-insight-card {"));
        assertTrue(html.contains("margin: 0 !important;"));
        assertTrue(html.contains(
                "class=\"card ai-insight-card\""));
        assertTrue(html.contains("core.js?v=pn6"));
        assertTrue(html.contains("placeholder=\"Frag Panoptes …\""));
        assertTrue(html.contains("data-i18n-attr=\"placeholder:genai.input_placeholder\""));
        assertTrue(html.contains(
                "min-height: 180px; max-height: 720px"));
        assertFalse(html.contains(
                "bottom: calc(90px + var(--safe-bottom, 0px))"));
        assertTrue(runtime.contains(
                "Panoptes is the app name, not the user's name"));
        assertTrue(html.contains("genai.js?v=13"));
        assertTrue(javascript.contains("BYD.utils.confirmDialog"));
    }

    @Test
    public void daemonKillSwitchDestroysTransport() throws Exception {
        String runtime = readProjectFile(
                "src/main/java/at/greenchili/surfmate/genai/GenAiRuntime.java");

        assertTrue(runtime.contains("call.cancel()"));
        assertFalse(runtime.contains("stopTransportAsync"));
        assertTrue(runtime.contains(
                "synchronized (clientLock)"));
        assertTrue(runtime.contains(
                "ensureGenerationMatches(requestGeneration)"));
        assertTrue(runtime.contains("old.dispatcher().cancelAll()"));
        assertTrue(runtime.contains("old.connectionPool().evictAll()"));
        assertTrue(runtime.contains(
                "old.dispatcher().executorService().shutdownNow()"));
        assertTrue(runtime.contains("ProxyHelper.getFailClosedHttpProxy()"));
        assertTrue(runtime.contains("\"proxy_required_unavailable\""));
    }

    @Test
    public void groundingStripsSensitiveVehicleAndMediaFields() throws Exception {
        String context = readProjectFile(
                "src/main/java/at/greenchili/surfmate/genai/GenAiContext.java");

        assertTrue(context.contains("Exact trip coordinates"));
        assertTrue(context.contains("Recording paths, filenames"));
        assertFalse(context.contains("\"startLat\", \"startLon\""));
        assertFalse(context.contains("\"telemetryFilePath\""));
        assertFalse(context.contains("\"vin\""));
    }

    // 0.1.39 A1: insightsAreOptInCardsInsideExistingDashboards entfällt – die alte native Oberfläche ist gelöscht (DashboardFragment)

    // 0.1.39 A1: assistantIsDefaultVisibleAndUsesExistingNavigationToggle entfällt – die alte native Oberfläche ist gelöscht (Leiste/Einstellungen)

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return at.greenchili.surfmate.testutil.Quelltext.lies(file);
    }
}
