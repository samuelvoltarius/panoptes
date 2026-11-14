package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 0.1.43.5 (Paket A, 14.11.): Welches Fahrzeugbild {@code /api/models/art} liefert – dasselbe wie der Launcher.
 *
 * <p>Eine Quelle: {@code web/shared/models/manifest.json}. Ein Eintrag mit {@code "top": true} (die fünf CC-BY-Modelle)
 * bekommt das Rendering aus {@code models/bilder/<id>_grund.webp} + {@code <id>_lack.webp}, in die die Lackfarbe linear
 * eingerechnet wird (wie {@code FahrzeugBild.mische} im Launcher). Ein Eintrag ohne {@code top}, aber mit erlaubtem
 * {@code "photo"} bekommt das freigestellte Foto {@code models/fotos/<id>.webp}. Beides erzeugt
 * {@code tools/launcher_fahrzeugbilder.py}. Alles andere bleibt beim bisherigen Kern-Bild bzw. der Silhouette.
 *
 * <p>Die Namensnennung ist dieselbe wie im Launcher ({@code katalog.json}): „3D: … · CC BY 4.0 · verändert“ bzw.
 * „Foto: … · CC BY-SA 4.0 · bearbeitet“ (CC0 ohne „bearbeitet“). Rein und ohne Android, im Test belegt.
 */
public final class FahrzeugArt {
    private FahrzeugArt() {}

    public static final String RENDER = "render";
    public static final String FOTO = "foto";

    /** Gewähltes Bild: Art ({@link #RENDER}/{@link #FOTO}), Manifest-ID (= Dateiname) und Namensnennung (null bei CC0-3D). */
    public static final class Wahl {
        public final String art;
        public final String id;
        public final String nennung;

        Wahl(String art, String id, String nennung) {
            this.art = art;
            this.id = id;
            this.nennung = nennung;
        }
    }

    /** Wie {@code FahrzeugModelle.norm} im Launcher: nur Buchstaben/Ziffern, klein. */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** Manifest-Eintrag zu einer Modell-ID oder einem Alias (z. B. „Dolphin Surf“ → seagull, „BYD Seal U DM-i“). */
    public static JSONObject eintrag(JSONObject manifest, String modell) {
        JSONArray liste = manifest == null ? null : manifest.optJSONArray("models");
        String n = norm(modell);
        if (liste == null || n.isEmpty()) return null;
        String bydLos = n.startsWith("byd") ? n.substring(3) : n;
        for (int i = 0; i < liste.length(); i++) {
            JSONObject e = liste.optJSONObject(i);
            if (e == null) continue;
            String id = norm(e.optString("id"));
            if (id.equals(n) || id.equals(bydLos)) return e;
            JSONArray al = e.optJSONArray("aliases");
            for (int k = 0; al != null && k < al.length(); k++) {
                String a = norm(al.optString(k));
                if (a.equals(n) || a.equals(bydLos)) return e;
            }
        }
        // Seal U DM-i heißt in China Song Plus DM-i
        if (bydLos.equals("songplusdmi")) return eintrag(manifest, "seal-u-dmi");
        return null;
    }

    /** Lizenz-Kürzel wie im Werkzeug; NC/ND/Unbekanntes → null (nicht erlaubt). */
    public static String fotoLizenz(String lizenz) {
        String l = lizenz == null ? "" : lizenz.toUpperCase(Locale.ROOT).replace(' ', '-');
        if (l.equals("CC0") || l.equals("CC0-1.0")) return "CC0";
        if (l.equals("PD") || l.equals("PUBLIC-DOMAIN") || l.equals("GEMEINFREI")) return "gemeinfrei";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CC-(BY|BY-SA)-(\\d\\.\\d)").matcher(l);
        return m.matches() ? "CC " + m.group(1) + " " + m.group(2) : null;
    }

    public static Wahl waehle(JSONObject manifest, String modell) {
        JSONObject e = eintrag(manifest, modell);
        if (e == null) return null;
        String id = e.optString("id");
        if (e.optBoolean("top", false) && e.optBoolean("verfuegbar", true)) {
            String lizenz = e.optString("license", "");
            String nennung = lizenz.startsWith("CC-BY")
                    ? "3D: " + e.optString("author", "?") + " · CC BY " + lizenz.substring(lizenz.lastIndexOf('-') + 1) + " · verändert"
                    : null;
            return new Wahl(RENDER, id, nennung);
        }
        JSONObject f = e.optJSONObject("photo");
        if (f == null) return null;
        String l = fotoLizenz(f.optString("license", null));
        String urheber = f.optString("author", "");
        if (l == null || urheber.isEmpty() || f.optString("sourceUrl", "").isEmpty()) return null;
        return new Wahl(FOTO, id, "Foto: " + urheber + " · " + l + (l.startsWith("CC BY") ? " · bearbeitet" : ""));
    }

    /** "#RRGGBB"/"RRGGBB" → 0xRRGGBB, sonst null. */
    public static Integer hex(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (!t.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(t, 16);
    }

    private static final float[] LIN = new float[256];
    private static final int[] SRGB = new int[4096];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LIN[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 4096; i++) {
            double v = i / 4095.0;
            double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            SRGB[i] = Math.max(0, Math.min(255, (int) (s * 255.0 + 0.5)));
        }
    }

    private static int zuSrgb(float lin) {
        return SRGB[Math.max(0, Math.min(4095, (int) (lin * 4095f + 0.5f)))];
    }

    /**
     * Grund (ARGB) + Lack (Graustufe im Rotkanal) × Lackfarbe, linear gerechnet; Alpha aus dem Grund. Gleiche Rechnung
     * wie {@code FahrzeugBild.mische} im Launcher, damit beide dieselbe Farbe zeigen. Ergebnis in {@code grund}.
     */
    public static int[] mische(int[] grund, int[] lack, int farbe) {
        float lr = LIN[(farbe >> 16) & 0xff], lg = LIN[(farbe >> 8) & 0xff], lb = LIN[farbe & 0xff];
        int n = Math.min(grund.length, lack.length);
        for (int i = 0; i < n; i++) {
            int g = grund[i];
            float d = LIN[(lack[i] >> 16) & 0xff];
            if (d == 0f) continue;
            int r = zuSrgb(LIN[(g >> 16) & 0xff] + d * lr);
            int gg = zuSrgb(LIN[(g >> 8) & 0xff] + d * lg);
            int b = zuSrgb(LIN[g & 0xff] + d * lb);
            grund[i] = (g & 0xff000000) | (r << 16) | (gg << 8) | b;
        }
        return grund;
    }
}
