package at.greenchili.surfmate.server;

import org.json.JSONObject;

/**
 * 0.1.41 (LC, Punkt 3 der Launcher-Liste, Alfreds Wort 11.11.): „Navi aufs Kombi“ als Knopf in der Karten-Ansicht des
 * Launchers. KEIN eigener Projektionsweg: der Knopf ruft genau den vorhandenen Kern-Weg
 * {@link at.greenchili.surfmate.tacho.TachoNaviApp#setze(boolean, String)} auf – mit allen seinen Sperren
 * (Hinweis bestätigt, App im Stand geprüft und freigegeben, Einschalten nur im Stand, Plattformurteil
 * {@link at.greenchili.surfmate.tacho.TachoFaehigkeit}: Surf/Single-OS, DiLink 5 und Unbekanntes bleiben gesperrt).
 * Zurück geht über den vorhandenen Weg {@code /api/launcher/v1/tacho/normal}.
 *
 * <ul>
 *   <li>{@code GET  /api/launcher/v1/tacho/kombi?paket=…} – nur lesend: bietet dieses Auto das an, ist die App
 *       freigegeben, läuft gerade etwas am Kombi.</li>
 *   <li>{@code POST /api/launcher/v1/tacho/kombi} {@code {paket}} – Navi-App aufs Kombi, nur Navi-Apps.</li>
 * </ul>
 * Beide nur von der Launcher-APK mit Panoptes-Signatur (wie Klima, Update, Tacho normal); im Befehls-Log über den
 * Präfix {@code /api/launcher/v1/tacho/}. Freigeben, Hinweis bestätigen und Probe gibt es im Launcher nie – nur in
 * Panoptes mit Login.
 */
public final class LauncherKombi {

    public static final String PFAD = "/api/launcher/v1/tacho/kombi";

    private LauncherKombi() {}

    /**
     * Darf der Launcher dieses Paket überhaupt anfragen? Nur bekannte Navi-Apps: der Knopf heißt „Navi aufs Kombi“,
     * andere Apps (Video, Spiele) bleiben beim Web-Weg mit seinem Hinweis.
     */
    static String paketSperre(String paket, boolean istNavi) {
        if (paket == null || paket.trim().isEmpty()) return "ungueltiges_paket";
        if (!istNavi) return "keine_navi";
        return null;
    }

    /** Antwort des lesenden Status; reine Zusammenstellung (Test ohne Auto). */
    static JSONObject status(boolean angeboten, String grund, boolean freigegeben, boolean hinweisVerstanden,
                             String aktiv) throws Exception {
        return new JSONObject()
                .put("angeboten", angeboten)
                .put("grund", angeboten ? "" : (grund == null || grund.isEmpty() ? "faehigkeit_unbekannt" : grund))
                .put("freigegeben", freigegeben)
                .put("hinweisVerstanden", hinweisVerstanden)
                .put("aktiv", aktiv == null ? JSONObject.NULL : aktiv);
    }

    /** {@code GET}: liest nur (Plattformurteil + Freigabe), startet nichts. */
    static JSONObject lesen(String paket) throws Exception {
        JSONObject s = at.greenchili.surfmate.tacho.TachoNaviApp.status();
        String p = paket == null ? "" : paket.trim();
        boolean frei = !p.isEmpty() && at.greenchili.surfmate.tacho.TachoNaviApp.istFreigegeben(p);
        String aktiv = s.isNull("aktiv") ? null : s.optString("aktiv", null);
        return status(s.optBoolean("angeboten", false), s.optString("grund", ""), frei,
                s.optBoolean("hinweisVerstanden", false), aktiv);
    }

    /** {@code POST}: Navi-App aufs Kombi über den Kern-Weg; jede Ablehnung kommt mit Grund zurück. */
    static JSONObject starten(String paket) throws Exception {
        String p = paket == null ? "" : paket.trim();
        String sperre = paketSperre(p, at.greenchili.surfmate.tacho.TachoNaviApp.istNavi(p));
        JSONObject r;
        if (sperre != null) r = new JSONObject().put("ok", false).put("grund", sperre);
        else r = at.greenchili.surfmate.tacho.TachoNaviApp.setze(true, p);
        try {
            BefehlsLog.ereignis("launcher.kombi", r.optBoolean("ok") ? "ok" : r.optString("grund", "fehler"), null, null);
        } catch (Throwable ignored) { }
        return r;
    }
}
