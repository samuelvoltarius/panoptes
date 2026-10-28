package at.greenchili.surfmate.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.26.1 MiniMax über die Anthropic-kompatible API (Befund am Auto: „The provider returned no text.“). */
public class GenAiMinimaxTest {

    @Test
    public void thinkingVorDemTextZaehltNicht() throws Exception {
        // so antwortet MiniMax-M3.1-Flash (gemessen 28.10.2026): erst thinking, dann text
        JSONObject antwort = new JSONObject().put("stop_reason", "end_turn").put("content", new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "Der Nutzer fragt …"))
                .put(new JSONObject().put("type", "text").put("text", "Eine Stunde hat 60 Minuten.")));
        assertEquals("Eine Stunde hat 60 Minuten.", GenAiRuntime.extractText(GenAiConfig.PROVIDER_ANTHROPIC, antwort));
        // nur thinking (Budget aufgebraucht) → leer, also ehrlich „keine Antwort“
        JSONObject nurDenken = new JSONObject().put("stop_reason", "max_tokens").put("content", new JSONArray()
                .put(new JSONObject().put("type", "thinking").put("thinking", "…")));
        assertEquals("", GenAiRuntime.extractText(GenAiConfig.PROVIDER_ANTHROPIC, nurDenken));
    }

    @Test
    public void amLimitEigeneMeldungUndProtokollOhneInhalt() throws Exception {
        JSONObject nurDenken = new JSONObject().put("stop_reason", "max_tokens")
                .put("content", new JSONArray().put(new JSONObject().put("type", "thinking")
                        .put("thinking", "GEHEIMER-DENKTEXT")))
                .put("usage", new JSONObject().put("input_tokens", 900).put("output_tokens", 400));
        assertEquals("max_tokens", GenAiRuntime.stopGrund(nurDenken));
        assertEquals("provider_output_limit", GenAiRuntime.leerFehler("max_tokens").code);
        assertEquals("provider_output_limit", GenAiRuntime.leerFehler("length").code);
        assertEquals("empty_provider_response", GenAiRuntime.leerFehler("end_turn").code);
        assertEquals("empty_provider_response", GenAiRuntime.leerFehler("").code);
        String befund = GenAiRuntime.leerBefund(nurDenken);
        assertTrue(befund, befund.contains("stop_reason=max_tokens"));
        assertTrue(befund, befund.contains("content=[thinking]"));
        assertTrue(befund, befund.contains("\"output_tokens\":400"));
        assertFalse("nie Inhalte im Protokoll", befund.contains("GEHEIMER-DENKTEXT"));
        // OpenAI-kompatibel
        JSONObject oa = new JSONObject().put("choices", new JSONArray()
                .put(new JSONObject().put("finish_reason", "length")));
        assertEquals("length", GenAiRuntime.stopGrund(oa));
        String js = lies("src/main/assets/web/shared/genai.js");
        assertTrue(js.contains("provider_output_limit:"));
    }

    @Test
    public void denkteilImTextUndJsonZaunWeg() {
        assertEquals("Antwort", GenAiRuntime.ohneDenkteil("<think>überlege\nlange</think>\nAntwort"));
        assertEquals("Vorher", GenAiRuntime.ohneDenkteil("Vorher<think>abgebrochen"));
        assertEquals("{\"a\":1}", GenAiRuntime.ohneDenkteil("```json\n{\"a\":1}\n```"));
        // ein normaler Code-Block bleibt
        String code = "```\nls -la\n```";
        assertEquals(code, GenAiRuntime.ohneDenkteil(code));
    }

    @Test
    public void fremderHostBekommtMehrBudgetUndSchemaAlsAnweisung() throws Exception {
        assertTrue(GenAiRuntime.istAnthropicHost("https://api.anthropic.com"));
        assertTrue(GenAiRuntime.istAnthropicHost(""));
        assertFalse(GenAiRuntime.istAnthropicHost("https://api.minimax.io/anthropic"));
        assertEquals("https://api.minimax.io/anthropic/v1/messages",
                GenAiRuntime.endpoint("https://api.minimax.io/anthropic", "/v1/messages"));
        assertTrue(GenAiRuntime.FREMD_MIN_TOKENS >= 8192);
        String q = lies("src/main/java/at/greenchili/surfmate/genai/GenAiRuntime.java");
        assertTrue(q.contains("fremd ? Math.max(config.maxOutputTokens, FREMD_MIN_TOKENS)"));
        assertTrue(q.contains("It must match this JSON Schema"));
    }

    @Test
    public void vorlageUndHinweisInDerOberflaeche() throws Exception {
        String js = lies("src/main/assets/web/shared/genai.js");
        assertTrue(js.contains("minimax: 'https://api.minimax.io/anthropic'"));
        assertTrue(js.contains("['MiniMax-M3.1-Flash', 'MiniMax-M3']"));
        assertTrue("gespeichert wird anthropic", js.contains("=== 'minimax' ? 'anthropic'"));
        String html = lies("src/main/assets/web/local/assistant.html");
        assertTrue(html.contains("<option value=\"minimax\">MiniMax</option>"));
        assertTrue(html.contains("genai.minimax_hinweis"));
        assertFalse("Chips ohne Sprach-Schlüssel", html.matches("(?s).*class=\"ai-chip\" type=\"button\" data-mode=\"[a-z_]+\" data-i18n.*"));
    }

    @Test
    public void vorschlaegeUndFehlerInAllenFuenfSprachen() throws Exception {
        String[] keys = {"minimax_hinweis", "prompt_more_battery", "prompt_latest_trip", "prompt_find_automation",
                "err_empty_provider_response", "err_provider_error", "err_provider_output_limit"};
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject g = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("genai");
            for (String k : keys) assertFalse(l + " " + k, g.optString(k, "").isEmpty());
        }
        JSONObject de = new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("genai");
        assertFalse(de.getString("prompt_more_battery").contains("battery"));
        assertTrue(de.getString("minimax_hinweis").contains("China"));
    }

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
