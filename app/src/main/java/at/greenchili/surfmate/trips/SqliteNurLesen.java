package at.greenchili.surfmate.trips;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 0.1.43.20: Liest eine SQLite-Datei aus einem Byte-Abbild, ohne SQLite-Bibliothek und ohne die Datei zu öffnen.
 *
 * <p>Wozu: BYD führt ein eigenes Fahrtenbuch ({@code /sdcard/energydata/EC_database.db}). Panoptes darf es nie
 * sperren oder verändern. Darum liest {@link BydFahrtenbuch} die Datei einmal in den Speicher (eine Kopie) und diese
 * Klasse wertet nur die Kopie aus: kein Journal, keine Sperre, kein Schreiben. Die Daemons nutzen sonst H2, Androids
 * SQLite gibt es in den JVM-Tests nicht.
 *
 * <p>Umfang: nur Tabellen-B-Bäume (Leaf 0x0D, Interior 0x05), Überlaufseiten, UTF-8/UTF-16-Text. Indizes, WAL und
 * „WITHOUT ROWID“ werden nicht gebraucht. Jede Unstimmigkeit wirft {@link IOException}; Aufrufer zeigen dann nichts.
 */
public final class SqliteNurLesen {

    /** Obergrenze für das Abbild (BYDs Datei hat ~57 KB; mehr ist kein Fahrtenbuch mehr). */
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final byte[] KOPF = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TIEFE = 24;

    private final byte[] d;
    private final int seite;
    private final int nutzbar;
    private final int seiten;
    private final Charset text;

    public SqliteNurLesen(byte[] abbild) throws IOException {
        if (abbild == null || abbild.length < 512 || abbild.length > MAX_BYTES) throw new IOException("groesse");
        for (int i = 0; i < KOPF.length; i++) if (abbild[i] != KOPF[i]) throw new IOException("kein sqlite");
        int ps = u16(abbild, 16);
        if (ps == 1) ps = 65536;
        if (ps < 512 || (ps & (ps - 1)) != 0) throw new IOException("seitengroesse");
        int reserviert = abbild[20] & 0xFF;
        this.d = abbild;
        this.seite = ps;
        this.nutzbar = ps - reserviert;
        if (nutzbar < 480) throw new IOException("nutzbar");
        this.seiten = abbild.length / ps;
        if (seiten < 1) throw new IOException("seiten");
        int enc = (int) u32(abbild, 56);
        this.text = enc == 2 ? StandardCharsets.UTF_16LE : enc == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
    }

    /** Eine Tabelle laut sqlite_master: Name, Wurzelseite, CREATE-Text. */
    public static final class Tabelle {
        public final String name;
        public final int wurzel;
        public final String sql;
        Tabelle(String name, int wurzel, String sql) { this.name = name; this.wurzel = wurzel; this.sql = sql; }
    }

    /** Eine Zeile: rowid und Werte in Spaltenreihenfolge (Long, Double, String, byte[] oder null). */
    public static final class Zeile {
        public final long rowid;
        public final Object[] werte;
        Zeile(long rowid, Object[] werte) { this.rowid = rowid; this.werte = werte; }
        public Object wert(int i) { return i >= 0 && i < werte.length ? werte[i] : null; }
    }

    /** Tabelle aus sqlite_master (Seite 1) oder null, wenn es sie nicht gibt. Groß/klein egal. */
    public Tabelle tabelle(String name) throws IOException {
        for (Zeile z : zeilen(1)) {
            if (!"table".equals(z.wert(0))) continue;
            Object n = z.wert(1);
            if (!(n instanceof String) || !((String) n).equalsIgnoreCase(name)) continue;
            Object w = z.wert(3);
            if (!(w instanceof Long)) throw new IOException("wurzel");
            long wurzel = (Long) w;
            if (wurzel < 1 || wurzel > seiten) throw new IOException("wurzel");
            Object sql = z.wert(4);
            return new Tabelle((String) n, (int) wurzel, sql instanceof String ? (String) sql : "");
        }
        return null;
    }

    /** Alle Zeilen eines Tabellen-B-Baums ab {@code wurzel}, in rowid-Reihenfolge. */
    public List<Zeile> zeilen(int wurzel) throws IOException {
        List<Zeile> aus = new ArrayList<>();
        lauf(wurzel, 0, aus, new HashSet<Integer>());
        return aus;
    }

    private void lauf(int nr, int tiefe, List<Zeile> aus, Set<Integer> gesehen) throws IOException {
        if (tiefe > MAX_TIEFE || nr < 1 || nr > seiten || !gesehen.add(nr)) throw new IOException("baum");
        int basis = (nr - 1) * seite;
        int kopf = nr == 1 ? basis + 100 : basis;
        int typ = d[kopf] & 0xFF;
        int zellen = u16(d, kopf + 3);
        if (typ == 0x0D) {
            for (int i = 0; i < zellen; i++) aus.add(blattZelle(basis, zeiger(basis, kopf + 8, i)));
        } else if (typ == 0x05) {
            for (int i = 0; i < zellen; i++) {
                int z = zeiger(basis, kopf + 12, i);
                pruefe(z, 4);
                lauf((int) u32(d, z), tiefe + 1, aus, gesehen);
            }
            lauf((int) u32(d, kopf + 8), tiefe + 1, aus, gesehen);
        } else {
            throw new IOException("seitentyp " + typ);
        }
    }

    private int zeiger(int basis, int feld, int i) throws IOException {
        pruefe(feld + 2 * i, 2);
        int z = basis + u16(d, feld + 2 * i);
        if (z < basis || z >= basis + nutzbar) throw new IOException("zelle");
        return z;
    }

    private Zeile blattZelle(int basis, int p) throws IOException {
        long[] v = varint(p);
        long laenge = v[0];
        p += (int) v[1];
        v = varint(p);
        long rowid = v[0];
        p += (int) v[1];
        if (laenge < 0 || laenge > MAX_BYTES) throw new IOException("nutzlast");
        byte[] nutz = nutzlast(p, (int) laenge);
        return new Zeile(rowid, satz(nutz));
    }

    /** Nutzlast samt Überlaufseiten (Formel aus dem SQLite-Dateiformat, Abschnitt 1.6). */
    private byte[] nutzlast(int p, int laenge) throws IOException {
        int x = nutzbar - 35;
        int lokal;
        if (laenge <= x) {
            lokal = laenge;
        } else {
            int m = ((nutzbar - 12) * 32 / 255) - 23;
            int k = m + ((laenge - m) % (nutzbar - 4));
            lokal = k <= x ? k : m;
        }
        byte[] aus = new byte[laenge];
        pruefe(p, lokal);
        System.arraycopy(d, p, aus, 0, lokal);
        int fertig = lokal;
        if (fertig < laenge) {
            pruefe(p + lokal, 4);
            long next = u32(d, p + lokal);
            Set<Long> gesehen = new HashSet<>();
            while (fertig < laenge) {
                if (next < 1 || next > seiten || !gesehen.add(next)) throw new IOException("ueberlauf");
                int b = (int) (next - 1) * seite;
                int n = Math.min(laenge - fertig, nutzbar - 4);
                pruefe(b, 4 + n);
                System.arraycopy(d, b + 4, aus, fertig, n);
                fertig += n;
                next = u32(d, b);
            }
        }
        return aus;
    }

    private Object[] satz(byte[] s) throws IOException {
        long[] v = varint(s, 0);
        int kopfLaenge = (int) v[0];
        if (kopfLaenge < 1 || kopfLaenge > s.length) throw new IOException("satzkopf");
        int p = (int) v[1];
        List<Long> typen = new ArrayList<>();
        while (p < kopfLaenge) {
            v = varint(s, p);
            typen.add(v[0]);
            p += (int) v[1];
        }
        Object[] aus = new Object[typen.size()];
        int q = kopfLaenge;
        for (int i = 0; i < aus.length; i++) {
            long t = typen.get(i);
            int n = groesse(t);
            if (q + n > s.length) throw new IOException("satz");
            aus[i] = wert(s, q, t, n);
            q += n;
        }
        return aus;
    }

    private static int groesse(long t) throws IOException {
        if (t == 0 || t == 8 || t == 9) return 0;
        if (t >= 1 && t <= 4) return (int) t;
        if (t == 5) return 6;
        if (t == 6 || t == 7) return 8;
        if (t >= 12) return (int) ((t - (t % 2 == 0 ? 12 : 13)) / 2);
        throw new IOException("typ " + t);
    }

    private Object wert(byte[] s, int p, long t, int n) {
        if (t == 0) return null;
        if (t == 8) return 0L;
        if (t == 9) return 1L;
        if (t == 7) return Double.longBitsToDouble(ganz(s, p, 8));
        if (t <= 6) {
            long w = ganz(s, p, n);
            int bits = n * 8;
            if (bits < 64) w = (w << (64 - bits)) >> (64 - bits);   // Vorzeichen erweitern
            return w;
        }
        if (t % 2 == 0) {
            byte[] b = new byte[n];
            System.arraycopy(s, p, b, 0, n);
            return b;
        }
        return new String(s, p, n, text);
    }

    private static long ganz(byte[] s, int p, int n) {
        long w = 0;
        for (int i = 0; i < n; i++) w = (w << 8) | (s[p + i] & 0xFF);
        return w;
    }

    private long[] varint(int p) throws IOException { return varint(d, p); }

    private static long[] varint(byte[] b, int p) throws IOException {
        long w = 0;
        for (int i = 0; i < 9; i++) {
            if (p + i >= b.length) throw new IOException("varint");
            int x = b[p + i] & 0xFF;
            if (i == 8) return new long[]{(w << 8) | x, 9};
            w = (w << 7) | (x & 0x7F);
            if ((x & 0x80) == 0) return new long[]{w, i + 1};
        }
        throw new IOException("varint");
    }

    private void pruefe(int p, int n) throws IOException {
        if (p < 0 || n < 0 || p + n > d.length) throw new IOException("grenze");
    }

    private static int u16(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }

    private static long u32(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /**
     * Spaltennamen aus einem CREATE-TABLE-Text, in Reihenfolge. Tabellen-Bedingungen (PRIMARY KEY(...), UNIQUE, CHECK,
     * FOREIGN KEY, CONSTRAINT) zählen nicht. Leere Liste, wenn der Text nicht passt.
     */
    public static List<String> spalten(String sql) {
        List<String> aus = new ArrayList<>();
        if (sql == null) return aus;
        int a = sql.indexOf('('), e = sql.lastIndexOf(')');
        if (a < 0 || e <= a) return aus;
        String innen = sql.substring(a + 1, e);
        int tiefe = 0, start = 0;
        List<String> teile = new ArrayList<>();
        for (int i = 0; i < innen.length(); i++) {
            char c = innen.charAt(i);
            if (c == '(') tiefe++;
            else if (c == ')') tiefe--;
            else if (c == ',' && tiefe == 0) { teile.add(innen.substring(start, i)); start = i + 1; }
        }
        teile.add(innen.substring(start));
        for (String t : teile) {
            String s = t.trim();
            if (s.isEmpty()) continue;
            String erstes = s.split("\\s+")[0];
            String gross = erstes.toUpperCase(Locale.ROOT);
            if (gross.equals("PRIMARY") || gross.equals("UNIQUE") || gross.equals("CHECK")
                    || gross.equals("FOREIGN") || gross.equals("CONSTRAINT")) continue;
            String n = erstes.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
            aus.add(n.toLowerCase(Locale.ROOT));
        }
        return aus;
    }
}
