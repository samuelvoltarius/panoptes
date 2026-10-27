package at.greenchili.surfmate.bruecke;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Innenrahmen der Brücke (BRUECKE_VERTRAG §3.4, 0.1.20 festgelegt): Eine Anfrage bzw. Antwort (JSON, UTF-8) wird in
 * Stücke ≤ {@link #MAX_STUECK} Byte geteilt. Jedes Stück ist der Klartext genau einer Noise-Transportnachricht:
 * {@code [Flag 1 Byte][Daten]}, Flag {@code 0x01} = letztes Stück, {@code 0x00} = es folgt mehr.
 */
public final class BrueckeRahmen {

    public static final int MAX_STUECK = 60 * 1024;
    public static final byte WEITER = 0x00, LETZTES = 0x01;

    private BrueckeRahmen() {}

    public static List<byte[]> stueckeln(byte[] nachricht) {
        List<byte[]> r = new ArrayList<>();
        int o = 0;
        do {
            int n = Math.min(MAX_STUECK, nachricht.length - o);
            byte[] s = new byte[n + 1];
            s[0] = o + n >= nachricht.length ? LETZTES : WEITER;
            System.arraycopy(nachricht, o, s, 1, n);
            r.add(s);
            o += n;
        } while (o < nachricht.length);
        return r;
    }

    /** Setzt Stücke zu einer Nachricht zusammen; Obergrenze schützt den Speicher. */
    public static final class Sammler {
        private final int max;
        private final ByteArrayOutputStream puffer = new ByteArrayOutputStream();

        public Sammler(int maxBytes) {
            this.max = maxBytes;
        }

        /** @return die ganze Nachricht beim letzten Stück, sonst null. */
        public byte[] hinzu(byte[] stueck) throws java.io.IOException {
            if (stueck == null || stueck.length < 1 || stueck.length - 1 > MAX_STUECK) throw new java.io.IOException("Stück");
            if (stueck[0] != WEITER && stueck[0] != LETZTES) throw new java.io.IOException("Flag");
            if (puffer.size() + stueck.length - 1 > max) {
                puffer.reset();
                throw new java.io.IOException("zu groß");
            }
            puffer.write(stueck, 1, stueck.length - 1);
            if (stueck[0] != LETZTES) return null;
            byte[] r = puffer.toByteArray();
            puffer.reset();
            return r;
        }
    }
}
