package at.greenchili.surfmate.server;

import java.io.OutputStream;

/**
 * 0.1.22 Gewohnheiten (GEWOHNHEITEN_KONZEPT §2/§3): {@code GET /api/gewohnheiten} – Schalter, Zahl der Einträge,
 * Herkünfte und erkannte Gewohnheiten. Das Log selbst wird nie ausgeliefert (bleibt im Auto). Schalter, Aufbewahrung
 * und „Alles vergessen“ laufen über /api/einstellungen ({@code gewohnheiten.*}); die Vorschlagskarte kommt mit 0.1.23.
 */
public final class GewohnheitenApiHandler {

    private GewohnheitenApiHandler() {}

    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!"/api/gewohnheiten".equals(p) || !"GET".equals(method)) return false;
        HttpResponse.sendJson(out, 200, at.greenchili.surfmate.gewohnheiten.Gewohnheiten.get().status().toString());
        return true;
    }
}
