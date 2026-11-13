package at.greenchili.surfmate.server;

import org.json.JSONObject;

/** Nur für Tests: Zugang zu {@code NaviApiHandler.tempolimitMelden} (Paket-sichtbar) aus anderen Paketen. */
public final class NaviApiHandlerTempolimitBruecke {

    private NaviApiHandlerTempolimitBruecke() {}

    public static JSONObject melden(String body, long jetztMs) throws Exception {
        return NaviApiHandler.tempolimitMelden(body, jetztMs);
    }
}
