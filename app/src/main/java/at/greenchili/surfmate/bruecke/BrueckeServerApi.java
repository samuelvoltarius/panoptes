package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** REST am Brücken-Server (BRUECKE_VERTRAG §2/§6). Löschaufrufe mit {@code Authorization: Bearer <G_auto>}. */
final class BrueckeServerApi implements Bruecke.ServerApi {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private static int ausfuehren(Request r) {
        try (Response res = HTTP.newCall(r).execute()) {
            return res.code();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String url(String server, String pfad) {
        return server + "/v1/" + pfad;
    }

    public int postfachAnlegen(String server, String p, String gHash) {
        try {
            String body = new JSONObject().put("p", p).put("g_hash", gHash).toString();
            return ausfuehren(new Request.Builder().url(url(server, "postfach")).post(RequestBody.create(body, JSON)).build());
        } catch (Exception e) {
            return -1;
        }
    }

    public int postfachLoeschen(String server, String p, String g) {
        return ausfuehren(new Request.Builder().url(url(server, "postfach/" + p))
                .header("Authorization", "Bearer " + g).delete().build());
    }

    public int zugangHinzu(String server, String p, String g, String gHandyHash) {
        try {
            String body = new JSONObject().put("g_handy_hash", gHandyHash).toString();
            return ausfuehren(new Request.Builder().url(url(server, "postfach/" + p + "/zugang"))
                    .header("Authorization", "Bearer " + g).post(RequestBody.create(body, JSON)).build());
        } catch (Exception e) {
            return -1;
        }
    }

    public int zugangWeg(String server, String p, String g, String gHandyHash) {
        return ausfuehren(new Request.Builder().url(url(server, "postfach/" + p + "/zugang/" + gHandyHash))
                .header("Authorization", "Bearer " + g).delete().build());
    }
}
