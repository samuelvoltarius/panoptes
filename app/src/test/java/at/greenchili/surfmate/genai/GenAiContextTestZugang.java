package at.greenchili.surfmate.genai;

import org.json.JSONArray;

/** Nur Tests: Zugang zur paketinternen Log-Auswahl von GenAiContext (für Tests in anderen Paketen). */
public final class GenAiContextTestZugang {
    private GenAiContextTestZugang() {}

    public static JSONArray waehle(String roh) {
        return GenAiContext.selectDiagnosticLines(roh, 28);
    }
}
