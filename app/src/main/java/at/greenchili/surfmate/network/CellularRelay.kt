package at.greenchili.surfmate.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import at.greenchili.surfmate.logging.LogManager
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP-CONNECT / SOCKS5 proxy for tethered clients whose OUTBOUND sockets are
 * explicitly bound to the CELLULAR network.
 *
 * Why this exists: on this head unit, tethered clients cannot reach the internet
 * by ordinary means. The carrier PDN carries no global IPv4 (only the RFC-7335
 * CLAT stub) and, while the AP is up, the device has no default network at all —
 * kernel NAT forwarding drops forwarded packets, and an unbound userspace proxy
 * fails with "missing default interface". Asking ConnectivityManager for the
 * cellular Network and binding each socket to it is the one path that works;
 * DNS is resolved on the same Network so NAT64 synthesis applies.
 *
 * Consequence for callers: this only serves clients configured to use a proxy.
 * It cannot make the platform's connectivity probe pass, so a client will still
 * show no "WiFi internet" indicator even while browsing through it.
 */
object CellularRelay {

    private const val TAG = "CellularRelay"

    /** Listen port advertised to clients. */
    const val PORT = 8221

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val IO_TIMEOUT_MS = 60_000
    private const val BUF = 16 * 1024

    /** Hard cap on concurrent tunnels; a parked head unit must not fork forever. */
    internal const val MAX_TUNNELS = 64

    /**
     * 0.1.43.22 (Upstream Overdrive #686, MIT): höchstens so viele Tunnel je Client-Adresse, damit
     * ein einzelnes Gerät die 64 Plätze nicht allein belegt.
     */
    internal const val MAX_TUNNELS_PER_CLIENT = 32

    private val log = LogManager.getInstance()
    private val running = AtomicBoolean(false)

    /**
     * 0.1.43.22 (#686): Der Platz wird VOR der Übergabe an den Worker atomar reserviert, nicht erst
     * im Worker gezählt. Sonst kommt ein Schwall Verbindungen an der Prüfung vorbei.
     */
    private val gate = RelayAdmissionGate(MAX_TUNNELS)
    private val perClient = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 0.1.43.22 (#686): alle offenen Sockets, damit [stop] laufende Tunnel sofort schließt. */
    private val liveSockets: MutableSet<Socket> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    /**
     * 0.1.43.22 (Upstream #475, nur Relay-Teil): liefert das Hotspot-Subnetz als Präfix mit Punkt
     * (z. B. "192.168.43."). Gesetzt von [HotspotManager] beim Start. Null → nur Loopback.
     */
    @Volatile private var apPrefixProvider: (() -> String?)? = null
    @Volatile private var apPrefixCache: String? = null
    @Volatile private var apPrefixCacheAt = 0L
    private const val AP_PREFIX_CACHE_MS = 10_000L

    /** Zähler für Status und Deploy-Zurücklesen (keine Client-Adressen). */
    private val abgewiesenFremd = java.util.concurrent.atomic.AtomicLong(0)
    private val abgewiesenVoll = java.util.concurrent.atomic.AtomicLong(0)
    private val abgewiesenZiel = java.util.concurrent.atomic.AtomicLong(0)
    private val abgewiesenKopf = java.util.concurrent.atomic.AtomicLong(0)

    // Relayed traffic never traverses the AP interface as forwarded packets — it
    // terminates in this process and re-egresses on a cellular socket — so the
    // /proc counters for wlan0 cannot see it. Count it here instead, or the data
    // limit would never trip and the UI would sit at 0 B.
    private val relayedRx = java.util.concurrent.atomic.AtomicLong(0)
    private val relayedTx = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var server: ServerSocket? = null
    /** 0.1.43.22 (Upstream v65): zweiter Listener auf der Hotspot-Adresse, siehe [bindHotspot]. */
    @Volatile private var apServer: ServerSocket? = null
    @Volatile private var apAddress: String? = null
    private val lifecycleLock = Any()
    @Volatile private var accept: Thread? = null
    @Volatile private var cell: Network? = null
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cm: ConnectivityManager? = null

    private val pool: ThreadPoolExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "cell-relay").apply { isDaemon = true }
    } as ThreadPoolExecutor

    /** True while the listener is accepting. */
    fun isRunning(): Boolean = running.get()

    /**
     * Start the listener and hold a cellular network request. Idempotent.
     * Requesting the network keeps the radio's data call up even when the AP has
     * taken the WiFi interface, which is what makes the bound sockets routable.
     */
    fun start(context: Context, apPrefix: (() -> String?)? = null) {
        synchronized(lifecycleLock) { startLocked(context, apPrefix) }
    }

    private fun startLocked(context: Context, apPrefix: (() -> String?)?) {
        if (!running.compareAndSet(false, true)) return
        apPrefixProvider = apPrefix
        apPrefixCache = null
        apPrefixCacheAt = 0L
        val ctx = context.applicationContext
        try {
            val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                running.set(false)
                log.warn(TAG, "no ConnectivityManager; relay not started")
                return
            }
            cm = manager
            requestCellular(manager)

            // 0.1.43.22 (Upstream v65): nie mehr 0.0.0.0. Ohne Zugangsdaten hört der Relay nur auf
            // Loopback und – sobald das AP eine Adresse hat – auf der eigenen Hotspot-IPv4 ([bindHotspot]).
            // Das WLAN des Autos als Client, Mobilfunk und Tailnet erreichen den Port so gar nicht.
            // Zusätzlich lässt [acceptLoop] nur Hotspot-Subnetz + Loopback herein.
            val ss = ServerSocket()
            server = ss   // vor bind veröffentlichen, damit stop() auch einen gescheiterten bind schließt
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(LOOPBACK, PORT))

            val t = Thread({ acceptLoop(ss) }, "cell-relay-accept").apply { isDaemon = true }
            accept = t
            t.start()
            log.info(TAG, "relay listening on $LOOPBACK:$PORT (clients: loopback + AP ${currentApPrefix() ?: "-"}x)")
        } catch (t: Throwable) {
            log.warn(TAG, "relay start failed: ${t.message}")
            stop()
        }
    }

    /** Adresse, an die der Relay immer bindet (nie die Wildcard). */
    internal const val LOOPBACK = "127.0.0.1"

    /**
     * 0.1.43.22 (Upstream v65 `bindHotspot`, MIT): zusätzlich auf der eigenen IPv4 des Hotspots hören;
     * null schließt diesen Listener. Idempotent. Nur eine private 192.168.x-Adresse wird gebunden.
     */
    fun bindHotspot(address: String?) {
        synchronized(lifecycleLock) {
            if (!running.get()) return
            if (address != null && address == apAddress && apServer?.isClosed == false) return
            try { apServer?.close() } catch (t: Throwable) { }
            apServer = null
            apAddress = null
            if (address == null) return
            if (!isBindableHotspotAddress(address)) {
                log.warn(TAG, "hotspot address not bindable for relay (not 192.168.x)")
                return
            }
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(address, PORT))
                apServer = ss
                apAddress = address
                Thread({ acceptLoop(ss) }, "cell-relay-accept-ap").apply { isDaemon = true }.start()
                log.info(TAG, "relay listening on hotspot address :$PORT")
            } catch (t: Throwable) {
                try { ss.close() } catch (e: Throwable) { }
                log.warn(TAG, "relay hotspot bind failed: ${t.message}")
            }
        }
    }

    /** True, sobald der Listener auf der Hotspot-Adresse läuft. */
    fun isHotspotBound(): Boolean = apServer?.isClosed == false

    /** Rein, testbar: nur eine konkrete private 192.168.x.y-Adresse, nie Wildcard/Loopback/sonstwas. */
    @JvmStatic
    fun isBindableHotspotAddress(address: String?): Boolean {
        val a = address?.trim() ?: return false
        val m = Regex("""^192\.168\.(\d{1,3})\.(\d{1,3})$""").matchEntire(a) ?: return false
        val c = m.groupValues[1].toInt()
        val d = m.groupValues[2].toInt()
        return c in 0..255 && d in 1..254
    }

    /** Stop accepting and release the cellular request. Safe to call when stopped. */
    fun stop() {
        synchronized(lifecycleLock) { stopLocked() }
    }

    private fun stopLocked() {
        running.set(false)
        try { server?.close() } catch (t: Throwable) { }
        server = null
        try { apServer?.close() } catch (t: Throwable) { }
        apServer = null
        apAddress = null
        accept = null
        // 0.1.43.22 (#686): Ist der Relay aus, laufen auch keine Tunnel weiter
        val offen = liveSockets.toList()
        liveSockets.clear()
        for (s in offen) { try { s.close() } catch (t: Throwable) { } }
        val manager = cm
        val cb = netCallback
        if (manager != null && cb != null) {
            try { manager.unregisterNetworkCallback(cb) } catch (t: Throwable) { }
        }
        netCallback = null
        cell = null
        log.info(TAG, "relay stopped")
    }

    // ==================== Zulassung (0.1.43.22, Upstream #475/#686) ====================

    /**
     * Darf dieser Client den Relay benutzen? Loopback immer. Sonst nur IPv4 aus dem Hotspot-Subnetz
     * ([apPrefix] = "192.168.c."). IPv4-gemappte IPv6-Adressen (::ffff:a.b.c.d) zählen als IPv4.
     * Ohne bekanntes Subnetz nur Loopback (fail closed). Reine Funktion, testbar.
     */
    @JvmStatic
    fun isAllowedClient(addr: InetAddress?, apPrefix: String?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true
        val v4 = alsIpv4(addr) ?: return false
        if (v4.isLoopbackAddress) return true
        val p = apPrefix?.trim().orEmpty()
        if (!AP_PRAEFIX.matches(p)) return false
        val ip = v4.hostAddress ?: return false
        return ip.startsWith(p)
    }

    private val AP_PRAEFIX = Regex("""^192\.168\.\d{1,3}\.$""")

    private fun alsIpv4(addr: InetAddress): Inet4Address? {
        if (addr is Inet4Address) return addr
        if (addr is Inet6Address) {
            val b = addr.address
            val gemappt = b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } &&
                b[10].toInt() == -1 && b[11].toInt() == -1
            if (gemappt) {
                return try {
                    InetAddress.getByAddress(b.copyOfRange(12, 16)) as? Inet4Address
                } catch (t: Throwable) { null }
            }
        }
        return null
    }

    /**
     * Zielregel (Upstream #475, Relay-Teil): nur öffentliche Ziele. Gesperrt sind die Head Unit
     * selbst (Loopback), Link-Local, RFC 1918, ULA fc00::/7, Multicast, 0.0.0.0/8, 100.64/10 und die
     * Wildcard. Sonst käme ein Hotspot-Client über den Relay an lokale Dienste (Web-Server, adb).
     */
    @JvmStatic
    fun isAllowedDestination(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isMulticastAddress || addr.isSiteLocalAddress) return false
        if (addr is Inet6Address) {
            val b = addr.address
            if (b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC) return false   // fc00::/7
            alsIpv4(addr)?.let { return isAllowedDestination(it) }
        }
        if (addr is Inet4Address) {
            val b0 = addr.address[0].toInt() and 0xFF
            val b1 = addr.address[1].toInt() and 0xFF
            if (b0 == 0) return false                         // 0.0.0.0/8
            if (b0 == 100 && b1 in 64..127) return false      // 100.64/10 (CGNAT, Tailscale)
        }
        return true
    }

    /**
     * Upstream #686: Proxy-eigene Kopfzeilen (Proxy-Authorization, Proxy-Connection) gehen nie an
     * den Ziel-Server. Gefaltete oder kaputte Kopfzeilen → null (Anfrage wird abgewiesen), statt
     * eine versteckte Proxy-Zugangsangabe weiterzureichen. Andere Kopfzeilen bleiben unverändert.
     */
    @JvmStatic
    fun originRequestWithoutProxyHeaders(head: String): String? {
        if (!head.endsWith("\r\n\r\n")) return null
        val lines = head.split("\r\n")
        if (lines.size < 3 || lines[0].isEmpty()) return null
        val out = StringBuilder().append(lines[0]).append("\r\n")
        for (i in 1 until lines.size - 2) {
            val line = lines[i]
            if (line.isEmpty() || line[0] == ' ' || line[0] == '\t') return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val field = line.substring(0, colon)
            if (field != field.trim()) return null
            if (!field.equals("Proxy-Authorization", ignoreCase = true) &&
                !field.equals("Proxy-Connection", ignoreCase = true)) {
                out.append(line).append("\r\n")
            }
        }
        return out.append("\r\n").toString()
    }

    private fun currentApPrefix(): String? {
        val now = System.currentTimeMillis()
        if (apPrefixCacheAt != 0L && now - apPrefixCacheAt < AP_PREFIX_CACHE_MS) return apPrefixCache
        val p = try { apPrefixProvider?.invoke() } catch (t: Throwable) { null }
        apPrefixCache = p
        apPrefixCacheAt = now
        return p
    }

    /** Stand für Status/Deploy: läuft, offene Tunnel, Grenzen, Abweisungen. Keine Client-Adressen. */
    @JvmStatic
    fun status(): org.json.JSONObject = org.json.JSONObject().apply {
        put("running", running.get())
        put("tunnels", gate.activeCount())
        put("maxTunnels", MAX_TUNNELS)
        put("maxTunnelsPerClient", MAX_TUNNELS_PER_CLIENT)
        put("clientFilter", "loopback+ap")
        put("listen", if (isHotspotBound()) "loopback+hotspot" else "loopback")
        put("rejectedForeign", abgewiesenFremd.get())
        put("rejectedFull", abgewiesenVoll.get())
        put("rejectedDestination", abgewiesenZiel.get())
        put("rejectedHeader", abgewiesenKopf.get())
    }

    /** Endpoint string for the UI, e.g. "192.168.43.1:8221". */
    fun endpoint(gateway: String): String = "$gateway:$PORT"

    /** Bytes relayed this session: first = to clients, second = from clients. */
    fun relayedBytes(): Pair<Long, Long> = relayedRx.get() to relayedTx.get()

    /** Zero the relay counters, e.g. when a new hotspot session begins. */
    fun resetCounters() {
        relayedRx.set(0)
        relayedTx.set(0)
    }

    private fun requestCellular(manager: ConnectivityManager) {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cell = network
                log.info(TAG, "cellular available for relay")
            }
            override fun onLost(network: Network) {
                if (cell == network) cell = null
                log.info(TAG, "cellular lost for relay")
            }
        }
        netCallback = cb
        manager.requestNetwork(req, cb)
    }

    /**
     * Best cellular handle available. The callback is authoritative, but fall back
     * to a scan so a tunnel opened during the AP transition isn't refused just
     * because the callback hasn't landed yet.
     */
    private fun cellular(): Network? {
        cell?.let { return it }
        val manager = cm ?: return null
        return try {
            manager.allNetworks.firstOrNull { n ->
                val nc = manager.getNetworkCapabilities(n)
                nc != null &&
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }?.also { cell = it }
        } catch (t: Throwable) {
            null
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running.get() && ss !== server) {
                    // Nur der Hotspot-Listener endete (ersetzt oder Adresse weg): ihn verwerfen,
                    // der nächste Hotspot-Abgleich bindet neu.
                    synchronized(lifecycleLock) { if (apServer === ss) { apServer = null; apAddress = null } }
                    try { ss.close() } catch (e: Throwable) { }
                    return
                }
                if (running.get()) {
                    log.warn(TAG, "accept failed: ${t.message}")
                    // Ein kaputter Loopback-Listener darf die Mobilfunk-Anfrage nicht hängen lassen
                    stop()
                }
                return
            }
            // 0.1.43.22 (#475): nur Hotspot-Clients und Loopback
            if (!isAllowedClient(client.inetAddress, currentApPrefix())) {
                if (abgewiesenFremd.incrementAndGet() % 50 == 1L) {
                    log.warn(TAG, "client outside hotspot subnet refused (count=${abgewiesenFremd.get()})")
                }
                try { client.close() } catch (e: Throwable) { }
                continue
            }
            // 0.1.43.22 (#686): Platz atomar VOR der Übergabe reservieren
            if (!gate.tryAcquire()) {
                abgewiesenVoll.incrementAndGet()
                log.warn(TAG, "tunnel cap reached; dropping client")
                try { client.close() } catch (e: Throwable) { }
                continue
            }
            val key = client.inetAddress?.hostAddress ?: "?"
            val jetzt = perClient.compute(key) { _, v -> (v ?: 0) + 1 } ?: 1
            if (jetzt > MAX_TUNNELS_PER_CLIENT) {
                clientZurueck(key)
                gate.release()
                abgewiesenVoll.incrementAndGet()
                log.warn(TAG, "per-client tunnel cap reached; dropping client")
                try { client.close() } catch (e: Throwable) { }
                continue
            }
            liveSockets.add(client)
            if (!running.get()) {
                // stop() lief zwischen accept und add: nicht mehr annehmen
                freigeben(client, key)
                continue
            }
            try {
                pool.execute { serve(client, key) }
            } catch (t: Throwable) {
                freigeben(client, key)
            }
        }
    }

    /** Einmal je angenommenem Client: Socket zu, Platz und Client-Zähler zurück. */
    private fun freigeben(client: Socket, key: String) {
        liveSockets.remove(client)
        try { client.close() } catch (e: Throwable) { }
        clientZurueck(key)
        gate.release()
    }

    /** Je-Client-Zähler atomar zurück; bei 0 fällt der Eintrag weg. */
    private fun clientZurueck(key: String) {
        perClient.compute(key) { _, v -> if (v == null || v <= 1) null else v - 1 }
    }

    private fun serve(client: Socket, key: String) {
        var upstream: Socket? = null
        try {
            client.soTimeout = IO_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val first = input.read()
            if (first < 0) return
            upstream = if (first == 0x05) {
                socks5(client, input)
            } else {
                httpProxy(client, input, first)
            }
            if (upstream == null) return
            liveSockets.add(upstream)
            if (!running.get()) return
            // Pump both directions; each side half-closes on EOF so neither peer
            // waits on its own timeout for a completed response.
            val up = upstream
            // rx = bytes delivered TO the client, tx = bytes sent by the client.
            val other = Thread({ pipe(up, client, relayedRx) }, "cell-relay-rx").apply { isDaemon = true }
            other.start()
            pipe(client, up, relayedTx)
            other.join(IO_TIMEOUT_MS.toLong())
        } catch (t: Throwable) {
            log.debug(TAG, "tunnel ended: ${t.message}")
        } finally {
            upstream?.let { liveSockets.remove(it) }
            try { upstream?.close() } catch (t: Throwable) { }
            freigeben(client, key)
        }
    }

    /** Open a cellular-bound socket, resolving the host on that network too. */
    private fun dial(host: String, port: Int): Socket? {
        val net = cellular()
        if (net == null) {
            log.warn(TAG, "no cellular network; refusing $host:$port")
            return null
        }
        return try {
            // Resolve ON the cellular network: that is what yields NAT64-synthesised
            // addresses for IPv4-only destinations on this IPv6 carrier.
            val addr = net.getAllByName(host).firstOrNull() ?: return null
            // 0.1.43.22 (#475): erst auflösen, dann private/lokale Ziele ablehnen
            if (host.equals("localhost", ignoreCase = true) || !isAllowedDestination(addr)) {
                abgewiesenZiel.incrementAndGet()
                log.warn(TAG, "refusing non-public destination")
                return null
            }
            val s = Socket()
            net.bindSocket(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = IO_TIMEOUT_MS
            s
        } catch (t: Throwable) {
            log.warn(TAG, "dial $host:$port failed: ${t.message}")
            null
        }
    }

    // ==================== HTTP ====================

    /**
     * Handle CONNECT (tunnel) and absolute-URI GET/POST (plain proxy). [firstByte]
     * was already consumed by the caller's protocol sniff.
     */
    private fun httpProxy(client: Socket, input: InputStream, firstByte: Int): Socket? {
        val head = StringBuilder().append(firstByte.toChar())
        var consecutive = 0
        while (consecutive < 2) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            head.append(c)
            when {
                c == '\n' -> consecutive++
                c == '\r' -> { /* part of the CRLF pair */ }
                else -> consecutive = 0
            }
            if (head.length > 32 * 1024) return null   // malformed / hostile
        }
        val text = head.toString()
        val requestLine = text.substringBefore("\r\n")
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        if (parts[0].equals("CONNECT", ignoreCase = true)) {
            val hp = parts[1]
            val idx = hp.lastIndexOf(':')
            if (idx <= 0) return null
            val host = hp.substring(0, idx)
            val port = hp.substring(idx + 1).toIntOrNull() ?: return null
            val up = dial(host, port) ?: run {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                return null
            }
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            return up
        }

        // Plain proxying: the request line carries an absolute URI.
        // 0.1.43.22 (#686): ohne Proxy-Authorization/Proxy-Connection, die gelten nur dem Proxy
        val originRequest = originRequestWithoutProxyHeaders(text) ?: run {
            abgewiesenKopf.incrementAndGet()
            client.getOutputStream().write(
                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            return null
        }
        val uri = try { URI(parts[1]) } catch (t: Throwable) { null } ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        val up = dial(host, port) ?: run {
            client.getOutputStream().write(
                "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            return null
        }
        // Replay the request (absolute URI is fine for the origin), proxy headers stripped.
        up.getOutputStream().write(originRequest.toByteArray(Charsets.ISO_8859_1))
        up.getOutputStream().flush()
        return up
    }

    // ==================== SOCKS5 ====================

    /** Minimal SOCKS5 CONNECT, no auth. The 0x05 version byte is already read. */
    private fun socks5(client: Socket, input: InputStream): Socket? {
        val out = client.getOutputStream()
        val methodCount = input.read()
        if (methodCount <= 0) return null
        repeat(methodCount) { if (input.read() < 0) return null }
        out.write(byteArrayOf(0x05, 0x00))   // no authentication
        out.flush()

        if (input.read() != 0x05) return null
        val cmd = input.read()
        input.read()                          // reserved
        val type = input.read()
        val host: String = when (type) {
            0x01 -> {   // IPv4
                val b = ByteArray(4)
                if (!readFully(input, b)) return null
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {   // hostname
                val len = input.read()
                if (len <= 0) return null
                val b = ByteArray(len)
                if (!readFully(input, b)) return null
                String(b, Charsets.ISO_8859_1)
            }
            0x04 -> {   // IPv6
                val b = ByteArray(16)
                if (!readFully(input, b)) return null
                java.net.InetAddress.getByAddress(b).hostAddress ?: return null
            }
            else -> return null
        }
        val p = ByteArray(2)
        if (!readFully(input, p)) return null
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

        // 0x01 == CONNECT; this relay intentionally supports nothing else.
        val up = if (cmd == 0x01) dial(host, port) else null
        if (up == null) {
            // reply=0x05 connection refused, with a zeroed BND address
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            return null
        }
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
        return up
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    // ==================== plumbing ====================

    /**
     * Copy until EOF then half-close, so the peer observes the end of stream.
     * Without the shutdown a client sits on its own timeout even though the whole
     * response already arrived.
     */
    private fun pipe(from: Socket, to: Socket, counter: java.util.concurrent.atomic.AtomicLong?) {
        try {
            val i: InputStream = from.getInputStream()
            val o: OutputStream = to.getOutputStream()
            val buf = ByteArray(BUF)
            while (true) {
                val r = i.read(buf)
                if (r <= 0) break
                o.write(buf, 0, r)
                o.flush()
                counter?.addAndGet(r.toLong())
            }
        } catch (t: Throwable) {
            // Normal when either end closes.
        }
        try { to.shutdownOutput() } catch (t: Throwable) { }
        try { from.shutdownInput() } catch (t: Throwable) { }
    }
}
