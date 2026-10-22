package at.greenchili.surfmate.daemon.proxy;

/**
 * Verschlüsselte String-Konstanten.
 *
 * Bis 0.1.7 standen hier auch die Zugangsdaten eines festverdrahteten VLESS-Proxys samt
 * sing-box-Pfaden und Proxy-Einstellungen. Der Proxy ist seit 0.1.8 vollständig entfernt
 * (Koordination 21.10., Datenschutz-Regel Nr. 2); übrig bleibt nur, was noch benutzt wird.
 * Decryption: Safe.s() (AES-256-CBC, pure Java)
 */
public final class Enc {
    private Enc() {} // No instantiation

    /** at.greenchili.surfmate */
    public static final String APP_PACKAGE = Safe.s("YKamZFKi14UE/BJXMAoQJceliCt72XMA9Ph5YlA5bZg=");
}
