package at.greenchili.surfmate.sprache;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import at.greenchili.surfmate.R;

/**
 * 0.1.44 (P2) Sprachbefehl: hört einmal zu, zeigt groß, was verstanden wurde, fragt bei Fahrzeugbefehlen nach
 * („Klima einschalten? Ja/Nein“ – per Stimme oder großem Knopf; keine Antwort = Nein) und führt dann über den Kern aus
 * ({@code POST /api/sprache/v1/befehl}). Läuft im eigenen Prozess {@code :sprache}.
 *
 * <p>Solange diese Ansicht offen ist, ist sie selbst die Anzeige „Mikrofon an“. Das Mikrofon ist nur beim Zuhören offen
 * (höchstens {@link SprachRegeln#HOEREN_MS} bzw. {@link SprachRegeln#RUECKFRAGE_MS}). Mikrofon-Erlaubnis fragt Android
 * beim ersten Mal; ohne Erlaubnis hört nichts zu.
 */
public final class SprachActivity extends Activity {

    static final String AKTION_ENDE = "at.greenchili.surfmate.sprache.ENDE";
    private static final int RECHT_ANFRAGE = 41;
    private static final String KERN = "http://127.0.0.1:" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT
            + "/api/sprache/v1/befehl";

    private final Handler haupt = new Handler(Looper.getMainLooper());
    private final AtomicBoolean abbruch = new AtomicBoolean(false);
    private String sprache;
    private boolean weck;
    /** 0.1.43.3: angetippter Befehl aus dem Launcher (geprüft) oder null = zuhören. */
    private String vorgabe;
    private TextView titel, text;
    private LinearLayout knoepfe;
    private volatile String rueckfrageAntwort;   // "ja"/"nein" per Knopf

    private final BroadcastReceiver ende = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { abbruch.set(true); schliessen(0); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sprache = SprachBefehle.sprache(getIntent().getStringExtra(SprachStart.EXTRA_SPRACHE));
        weck = getIntent().getBooleanExtra(SprachStart.EXTRA_WECK, false);
        vorgabe = SprachStart.vorgabe(getIntent().getStringExtra(SprachStart.EXTRA_BEFEHL));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setFinishOnTouchOutside(false);
        bauen();
        registerReceiver(ende, new IntentFilter(AKTION_ENDE));
        if (vorgabe != null) {
            // 0.1.43.3: angetippter Satz – kein Zuhören nötig; Rückfrage per Knopf (per Stimme nur mit Recht und Modell).
            new Thread(() -> ablauf(), "Sprachbefehl").start();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Nur der Nutzer erteilt das Recht (Android-Abfrage), nie still.
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, RECHT_ANFRAGE);
        } else {
            los();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] rechte, int[] ergebnis) {
        if (code != RECHT_ANFRAGE) return;
        if (ergebnis.length > 0 && ergebnis[0] == PackageManager.PERMISSION_GRANTED) los();
        else zeigen(getString(R.string.sprache_titel), getString(R.string.sprache_kein_mikrofon), 5000);
    }

    private void los() {
        boolean recht = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String nein = SprachRegeln.hoerenVerweigert(recht, sprache, SprachErkenner.modellDa(this, sprache));
        if (nein != null) { zeigen(getString(R.string.sprache_titel), getString(SprachRegeln.textZuGrund(nein)), 6000); return; }
        zeigen(getString(R.string.sprache_hoere), getString(R.string.sprache_beispiel), -1);
        SprachWeckDienst.pausieren();   // nie zwei Zuhörer am Mikrofon
        new Thread(() -> { schlafenKurz(); ablauf(); }, "Sprachbefehl").start();
    }

    /** Ein Durchgang: zuhören (oder angetippter Satz), verstehen, ggf. nachfragen, ausführen. */
    private void ablauf() {
        String gehoert = vorgabe != null ? ersterSatz(vorgabe)
                : SprachErkenner.hoeren(this, sprache, SprachBefehle.grammatik(sprache, false), SprachRegeln.HOEREN_MS, abbruch);
        if (abbruch.get()) return;
        String befehl = vorgabe != null ? vorgabe : SprachBefehle.befehl(sprache, gehoert);
        if (befehl == null || SprachBefehle.JA.equals(befehl) || SprachBefehle.NEIN.equals(befehl)) {
            post(getString(R.string.sprache_nicht_verstanden), getString(R.string.sprache_beispiel), 6000);
            return;
        }
        if (SprachBefehle.VERBOTEN.equals(befehl)) {
            // Regel A+: Hupe, Blinker, Licht, Alarm nie per Sprache.
            post(gehoert, getString(R.string.sprache_verboten), 6000);
            return;
        }
        if (SprachBefehle.NAVI_HEIM.equals(befehl)) {
            startActivity(new Intent(this, SprachStart.class).setAction(SprachStart.AKTION_NAVI_HEIM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            post(gehoert, getString(R.string.sprache_navi_heim), 3000);
            return;
        }
        JSONObject a = kern(befehl, null);
        if (SprachBefehle.brauchtRueckfrage(befehl)) {
            String marke = a == null ? "" : a.optString("marke", "");
            if (a == null || !a.optBoolean("rueckfrage", false) || marke.isEmpty()) { post(gehoert, fehlerText(a), 6000); return; }
            haupt.post(() -> rueckfrageZeigen(befehl));
            String antwort = antwortHolen();
            if (rueckfrageAntwort != null) antwort = rueckfrageAntwort;
            if (abbruch.get() && rueckfrageAntwort == null) return;
            if (!SprachBefehle.JA.equals(antwort)) { post(frage(befehl), getString(R.string.sprache_abgebrochen), 3000); return; }
            a = kern(befehl, marke);
        }
        post(gehoert, ergebnisText(befehl, a), 5000);
    }

    /** Ja/Nein: per Stimme nur mit Mikrofon-Recht und Modell, sonst nur per Knopf. Keine Antwort = Nein. */
    private String antwortHolen() {
        boolean stimme = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && SprachBefehle.erkennbar(sprache) && SprachErkenner.modellDa(this, sprache);
        if (stimme) {
            return SprachBefehle.befehl(sprache,
                    SprachErkenner.hoeren(this, sprache, SprachBefehle.grammatik(sprache, true), SprachRegeln.RUECKFRAGE_MS, abbruch));
        }
        long ende = System.currentTimeMillis() + SprachRegeln.RUECKFRAGE_MS;
        while (rueckfrageAntwort == null && System.currentTimeMillis() < ende) schlafenKurz();
        return rueckfrageAntwort;
    }

    private String ersterSatz(String befehl) {
        java.util.List<String> s = SprachBefehle.saetze(sprache, befehl);
        return s.isEmpty() ? befehl : s.get(0);
    }

    // ---- Kern
    private JSONObject kern(String befehl, String marke) {
        java.net.HttpURLConnection c = null;
        try {
            JSONObject in = new JSONObject().put("befehl", befehl);
            if (marke != null) in.put("marke", marke);
            c = (java.net.HttpURLConnection) new java.net.URL(KERN).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(in.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.io.InputStream s = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
            if (s == null) return null;
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] puffer = new byte[2048];
            for (int n; (n = s.read(puffer)) > 0; ) b.write(puffer, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String frage(String befehl) {
        switch (befehl) {
            case SprachBefehle.KLIMA_AN: return getString(R.string.sprache_frage_klima_an);
            case SprachBefehle.KLIMA_AUS: return getString(R.string.sprache_frage_klima_aus);
            case SprachBefehle.WAERMER: return getString(R.string.sprache_frage_waermer);
            default: return getString(R.string.sprache_frage_kaelter);
        }
    }

    private String fehlerText(JSONObject a) {
        String g = a == null ? "" : a.optString("grund", "");
        if ("schalter_aus".equals(g)) return getString(R.string.sprache_schalter_aus);
        if ("grenze".equals(g)) return getString(R.string.sprache_grenze);
        if ("temperatur_unbekannt".equals(g) || "stufe_unbekannt".equals(g)) return getString(R.string.sprache_stufe_unbekannt);
        return getString(R.string.sprache_fehler);
    }

    private String ergebnisText(String befehl, JSONObject a) {
        if (a == null || !a.optBoolean("ok", false)) {
            if (a != null && "unbekannt".equals(a.optString("grund"))) {
                return getString(SprachBefehle.REICHWEITE.equals(befehl) ? R.string.sprache_reichweite_unbekannt : R.string.sprache_ort_unbekannt);
            }
            return fehlerText(a);
        }
        switch (befehl) {
            case SprachBefehle.REICHWEITE: return getString(R.string.sprache_reichweite, a.optInt("km"));
            case SprachBefehle.WO_BIN_ICH: return getString(R.string.sprache_ort, a.optString("ort"));
            case SprachBefehle.MUSIK_WEITER: return getString(R.string.sprache_musik_weiter);
            case SprachBefehle.MUSIK_ZURUECK: return getString(R.string.sprache_musik_zurueck);
            case SprachBefehle.MUSIK_PAUSE: return getString(R.string.sprache_musik_pause);
            default: return getString(R.string.sprache_erledigt);
        }
    }

    // ---- Ansicht (groß, für den Finger und den Blick im Auto)
    private void bauen() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(28), dp(24), dp(28), dp(24));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(14, 22, 32));
        g.setCornerRadius(dp(20));
        g.setStroke(dp(3), Color.rgb(181, 242, 61));
        w.setBackground(g);
        titel = tv(30, true);
        text = tv(22, false);
        w.addView(titel);
        w.addView(text);
        knoepfe = new LinearLayout(this);
        knoepfe.setOrientation(LinearLayout.HORIZONTAL);
        knoepfe.setGravity(Gravity.END);
        w.addView(knoepfe);
        standardKnoepfe();
        setContentView(w, new ViewGroup.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void standardKnoepfe() {
        knoepfe.removeAllViews();
        if (SprachStart.schalterAn(SprachBefehle.SCHALTER_WECKWORT) && SprachBefehle.WECKWORT.containsKey(sprache)) {
            boolean an = SprachWeckDienst.laeuft();
            knoepfe.addView(knopf(getString(an ? R.string.sprache_weckwort_stopp : R.string.sprache_weckwort_start,
                    SprachBefehle.WECKWORT.get(sprache)), v -> {
                startActivity(new Intent(this, SprachStart.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setAction(an ? SprachStart.AKTION_WECK_AUS : SprachStart.AKTION_WECK_AN));
                abbruch.set(true);
                schliessen(0);
            }));
        }
        knoepfe.addView(knopf(getString(R.string.sprache_schliessen), v -> { abbruch.set(true); schliessen(0); }));
    }

    private void rueckfrageZeigen(String befehl) {
        titel.setText(frage(befehl));
        text.setText(getString(R.string.sprache_ja_nein));
        knoepfe.removeAllViews();
        knoepfe.addView(knopf(getString(R.string.sprache_nein), v -> { rueckfrageAntwort = SprachBefehle.NEIN; abbruch.set(true); }));
        knoepfe.addView(knopf(getString(R.string.sprache_ja), v -> { rueckfrageAntwort = SprachBefehle.JA; abbruch.set(true); }));
    }

    private Button knopf(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setMinHeight(dp(72));
        b.setMinWidth(dp(160));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(16), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView tv(int sp, boolean fett) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (fett) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    /** Dem Weckwort Zeit geben, das Mikrofon freizugeben (höchstens ein 100-ms-Stück). */
    private static void schlafenKurz() {
        try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void post(String t1, String t2, int schliessenMs) { haupt.post(() -> zeigen(t1, t2, schliessenMs)); }

    private void zeigen(String t1, String t2, int schliessenMs) {
        if (isFinishing()) return;
        titel.setText(t1 == null || t1.isEmpty() ? "…" : t1);
        text.setText(t2);
        if (schliessenMs >= 0) { standardKnoepfe(); schliessen(schliessenMs); }
    }

    private void schliessen(int ms) {
        haupt.removeCallbacksAndMessages(null);
        haupt.postDelayed(() -> { if (!isFinishing()) finish(); }, ms);
    }

    @Override
    protected void onDestroy() {
        abbruch.set(true);
        try { unregisterReceiver(ende); } catch (Throwable ignored) { }
        haupt.removeCallbacksAndMessages(null);
        SprachWeckDienst.weiter(this);   // Weckwort (falls an) hört wieder zu
        super.onDestroy();
    }
}
