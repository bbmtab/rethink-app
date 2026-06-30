package com.celzero.bravedns.core.proxy

import com.celzero.bravedns.core.ca.CertificateAuthority
import com.celzero.bravedns.core.filter.CosmeticFilter
import com.celzero.bravedns.core.filter.CspInjector
import com.celzero.bravedns.core.filter.HtmlFilter
import com.celzero.bravedns.core.filter.ProceduralFilter
import com.celzero.bravedns.core.filter.ScriptletFilter
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.*
import javax.net.ssl.*

/**
 * LocalHttpsProxy is a lightweight, non-blocking HTTP/HTTPS MITM Inspection Proxy server.
 * It listens on port 8443 and inspects traffic in an opt-in mode.
 * 
 * It enforces HTTP/1.1 end-to-end to prevent multiplexed HTTP/2 frames from leaking into the decrypted 
 * stream, allowing highly reliable, linear request/response inspection and modification.
 */
object LocalHttpsProxy {

    private const val TAG = "LocalHttpsProxy"
    private const val DEFAULT_PORT = 8443

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverJob: Job? = null

    private var appContext: android.content.Context? = null
    private var persistentState: com.celzero.bravedns.service.PersistentState? = null

    private val PERSISTENT_BYPASS_SEEDS = setOf(
        "google.com",
        "googleapis.com",
        "gstatic.com",
        "apple.com",
        "icloud.com",
        "play.google.com",
        "android.clients.google.com"
    )

    private val dynamicBypassSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val allowedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Initializes the proxy with persistent state to load and persist bypassed hosts.
     * 
     * @param state The persistent state configuration manager.
     */
    fun initialize(state: com.celzero.bravedns.service.PersistentState) {
        this.persistentState = state
        loadBypassCacheFromPreferences()
    }

    /**
     * Initializes the proxy with both application context and persistent state.
     * 
     * @param context The Android Context.
     * @param state The persistent state configuration manager.
     */
    fun initialize(context: android.content.Context, state: com.celzero.bravedns.service.PersistentState) {
        this.appContext = context.applicationContext
        this.persistentState = state
        loadBypassCacheFromPreferences()
    }

    /**
     * Sets the package names allowed for HTTPS inspection.
     * If empty, all packages are allowed.
     */
    fun setAllowedPackages(packages: Set<String>) {
        allowedPackages.clear()
        allowedPackages.addAll(packages)
        logInfo("HTTPS inspection allowed packages updated: ${packages.size} packages")
    }

    private fun isConnectionFromAllowedPackage(clientSocket: Socket): Boolean {
        val context = appContext ?: return true
        if (allowedPackages.isEmpty()) return true

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                if (cm != null) {
                    val local = InetSocketAddress(clientSocket.inetAddress, clientSocket.port)
                    val remote = InetSocketAddress(clientSocket.localAddress, clientSocket.localPort)
                    val uid = cm.getConnectionOwnerUid(6, local, remote) // 6 is IPPROTO_TCP
                    if (uid != -1) {
                        val pm = context.packageManager
                        val packages = pm.getPackagesForUid(uid)
                        if (packages != null) {
                            for (pkg in packages) {
                                if (allowedPackages.contains(pkg)) {
                                    return true
                                }
                            }
                            logInfo("Connection from package(s) ${packages.joinToString(", ")} is NOT in allowed packages, bypassing MITM")
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                logWarn("Failed to get connection owner UID: ${e.message}")
            }
        }
        return true // Default to inspect if cannot resolve UID/packages
    }

    private fun loadBypassCacheFromPreferences() {
        val saved = persistentState?.httpsBypassHosts ?: return
        if (saved.isNotEmpty()) {
            val hosts = saved.split(",")
            for (h in hosts) {
                val trimmed = h.trim().lowercase(Locale.US)
                if (trimmed.isNotEmpty()) {
                    dynamicBypassSet.add(trimmed)
                }
            }
        }
    }

    private fun persistBypassCache() {
        val state = persistentState ?: return
        val serialized = dynamicBypassSet.joinToString(",")
        state.httpsBypassHosts = serialized
    }

    private fun addToBypassCache(host: String) {
        val cleanedHost = host.trim().lowercase(Locale.US)
        if (cleanedHost.isNotEmpty() && !PERSISTENT_BYPASS_SEEDS.contains(cleanedHost)) {
            if (dynamicBypassSet.add(cleanedHost)) {
                logInfo("Host '$cleanedHost' added to persistent bypass cache due to handshake failure")
                persistBypassCache()
            }
        }
    }

    /**
     * Listener interface to decouple traffic inspection and filtering logic from the network proxy server.
     */
    interface ProxyListener {
        /**
         * Triggered before a decrypted HTTP request is forwarded upstream.
         * @return true to allow the request, false to block and return a fallback resource.
         */
        fun onRequestInspection(
            url: String,
            host: String,
            method: String,
            headers: List<String>,
            resourceType: Int
        ): Boolean

        /**
         * Triggered when a decrypted HTTP response is received from upstream.
         * @param decompressedBody Decoded body string (e.g. HTML/JSON/JS) if text-based, or null if binary/skipped.
         */
        fun onResponseInspection(
            url: String,
            statusCode: Int,
            headers: List<String>,
            decompressedBody: String?
        )
    }

    @Volatile
    var proxyListener: ProxyListener? = null

    /**
     * Start the proxy server in a background Coroutine on Dispatchers.IO.
     */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (isRunning) {
            logWarn("Proxy is already running")
            return
        }

        isRunning = true
        logInfo("Starting local HTTPS proxy server on port $port...")

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch {
                        handleClientConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logError("Exception in server accept loop: ${e.message}", e)
                }
            } finally {
                stop()
            }
        }
    }

    /**
     * Stops the proxy server and frees up socket resources.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        logInfo("Stopping local HTTPS proxy server...")

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logError("Error closing server socket: ${e.message}")
        }
        serverSocket = null

        serverJob?.cancel()
        serverJob = null
    }

    /**
     * Handles an incoming client connection.
     */
    private suspend fun handleClientConnection(clientSocket: Socket) {
        clientSocket.use { client ->
            try {
                val inputStream = client.getInputStream()
                
                // Read the first request line byte-by-byte to prevent buffering loss of TLS handshake bytes
                val firstLine = readLineByteByByte(inputStream)
                if (firstLine.isEmpty()) return

                logDebug("Proxy received request line: $firstLine")

                val parts = firstLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                if (method.equals("CONNECT", ignoreCase = true)) {
                    val hostParts = target.split(":")
                    val host = hostParts[0]
                    val port = if (hostParts.size > 1) hostParts[1].toInt() else 443

                    // Read remaining HTTP CONNECT headers
                    while (true) {
                        val headerLine = readLineByteByByte(inputStream)
                        if (headerLine.isEmpty()) break
                    }

                    handleConnectTunnel(client, host, port)
                } else {
                    // Plain HTTP request - route as HTTP proxy
                    handlePlainHttp(client, firstLine)
                }
            } catch (e: Exception) {
                logError("Error handling client connection: ${e.message}", e)
            }
        }
    }

    /**
     * Manages HTTP CONNECT tunneling.
     */
    private suspend fun handleConnectTunnel(clientSocket: Socket, host: String, port: Int) {
        val upstreamSocket = Socket()
        try {
            upstreamSocket.connect(InetSocketAddress(host, port), 10000)
        } catch (e: Exception) {
            logError("Failed to connect to upstream $host:$port: ${e.message}")
            try {
                val out = clientSocket.getOutputStream()
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                out.flush()
            } catch (ignore: Exception) {}
            return
        }

        upstreamSocket.use { upstream ->
            try {
                val clientOut = clientSocket.getOutputStream()
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                val shouldInspect = shouldInspectDomain(host) && isConnectionFromAllowedPackage(clientSocket)

                if (shouldInspect) {
                    performMitmInspection(clientSocket, upstream, host, port)
                } else {
                    logInfo("Bypassing $host (Raw TCP pass-through mode)")
                    pipeRawConnections(clientSocket, upstream)
                }
            } catch (e: Exception) {
                logError("Error in CONNECT tunnel for $host: ${e.message}", e)
            }
        }
    }

    /**
     * Upgrades the client and upstream sockets to SSL and inspects the decrypted traffic.
     */
    private suspend fun performMitmInspection(
        clientSocket: Socket,
        upstreamSocket: Socket,
        host: String,
        port: Int
    ) {
        var downstreamSslSocket: SSLSocket? = null
        var upstreamSslSocket: SSLSocket? = null

        try {
            // 1. Upstream TLS Handshake (Enforce HTTP/1.1 to avoid HTTP/2 multiplexing streams)
            val upstreamFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            upstreamSslSocket = upstreamFactory.createSocket(
                upstreamSocket,
                host,
                port,
                true
            ) as SSLSocket

            try {
                val params = upstreamSslSocket.sslParameters
                params.applicationProtocols = arrayOf("http/1.1") // Enforce downgrade
                upstreamSslSocket.sslParameters = params
            } catch (e: Exception) {
                logWarn("ALPN setting on upstream socket not supported: ${e.message}")
            }

            upstreamSslSocket.startHandshake()

            // 2. Downstream TLS Handshake (Enforce HTTP/1.1 only)
            val keyAndCert = CertificateAuthority.generateLeafKeyAndCert(host)
            val sslContext = createSslContextForLeaf(keyAndCert)
            
            downstreamSslSocket = sslContext.socketFactory.createSocket(
                clientSocket,
                clientSocket.inetAddress.hostAddress,
                clientSocket.port,
                true
            ) as SSLSocket

            downstreamSslSocket.useClientMode = false

            try {
                val params = downstreamSslSocket.sslParameters
                params.applicationProtocols = arrayOf("http/1.1")
                downstreamSslSocket.sslParameters = params
            } catch (e: Exception) {
                logWarn("ALPN setting on downstream socket not supported: ${e.message}")
            }

            downstreamSslSocket.startHandshake()

            // 3. Process Decrypted Traffic
            logInfo("Established TLS MITM tunnel for $host")
            handleMitmTraffic(downstreamSslSocket, upstreamSslSocket, host)

        } catch (e: Exception) {
            logError("TLS MITM Handshake failed for $host: ${e.message}")
            addToBypassCache(host)
            try { downstreamSslSocket?.close() } catch (ignore: Exception) {}
            try { upstreamSslSocket?.close() } catch (ignore: Exception) {}
        }
    }

    /**
     * Intercepts, modifies, and pipes decrypted MITM traffic using a highly robust
     * HTTP/1.1 transaction loop. Supports Keep-Alive connection reuse.
     */
    private suspend fun handleMitmTraffic(downstream: SSLSocket, upstream: SSLSocket, host: String) = coroutineScope {
        val clientInput = BufferedInputStream(downstream.inputStream)
        val clientOutput = BufferedOutputStream(downstream.outputStream)
        val serverInput = BufferedInputStream(upstream.inputStream)
        val serverOutput = BufferedOutputStream(upstream.outputStream)

        try {
            while (isActive && !downstream.isClosed && !upstream.isClosed) {
                // 1. Read Request Line from client
                val requestLine = readHeaderLine(clientInput)
                if (requestLine == null || requestLine.isEmpty()) {
                    break // Connection terminated
                }

                // 2. Read and Modify Request Headers
                val requestHeaders = mutableListOf<String>()
                var contentLength: Long = 0
                var isChunked = false
                var referer: String? = null
                var acceptHeader: String? = null
                var contentTypeHeader: String? = null
                var secFetchDest: String? = null

                while (true) {
                    val headerLine = readHeaderLine(clientInput) ?: break
                    if (headerLine.isEmpty()) break

                    val lowerLine = headerLine.lowercase(Locale.US)
                    
                    // Filter or modify specific headers
                    val finalHeader = when {
                        lowerLine.startsWith("accept-encoding:") -> {
                            // Strip "br" (Brotli) so the server sends Gzip/Deflate which we can natively decode
                            val encodings = headerLine.substring(16).split(",")
                            val filtered = encodings.map { it.trim() }.filter { !it.equals("br", ignoreCase = true) }
                            "Accept-Encoding: ${filtered.joinToString(", ")}"
                        }
                        else -> {
                            // Extract metadata fields
                            if (lowerLine.startsWith("content-length:")) {
                                contentLength = headerLine.substring(15).trim().toLongOrNull() ?: 0
                            } else if (lowerLine.startsWith("transfer-encoding:") && lowerLine.contains("chunked")) {
                                isChunked = true
                            } else if (lowerLine.startsWith("referer:")) {
                                referer = headerLine.substring(8).trim()
                            } else if (lowerLine.startsWith("accept:")) {
                                acceptHeader = headerLine.substring(7).trim()
                            } else if (lowerLine.startsWith("content-type:")) {
                                contentTypeHeader = headerLine.substring(13).trim()
                            } else if (lowerLine.startsWith("sec-fetch-dest:")) {
                                secFetchDest = headerLine.substring(15).trim()
                            }
                            headerLine
                        }
                    }
                    requestHeaders.add(finalHeader)
                }

                // Parse parts
                val requestParts = requestLine.split(" ")
                if (requestParts.size < 3) break
                val method = requestParts[0]
                val path = requestParts[1]

                val fullUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
                    path
                } else {
                    "https://$host$path"
                }

                // 3. Inspect request
                val resourceType = determineResourceTypeFallback(path, acceptHeader, contentTypeHeader, secFetchDest)
                val isAllowed = proxyListener?.onRequestInspection(fullUrl, host, method, requestHeaders, resourceType) ?: true

                if (!isAllowed) {
                    logInfo("MITM Stream Intercepted/Blocked request: $fullUrl")
                    
                    // Consume and discard body bytes
                    discardRequestBody(clientInput, contentLength, isChunked)

                    // Write Local Block Response
                    sendLocalBlockResponse(clientOutput, resourceType)
                    continue // Stay in keep-alive loop for next request
                }

                // 4. Forward allowed request line and headers to Upstream
                serverOutput.write("$requestLine\r\n".toByteArray(Charsets.UTF_8))
                for (header in requestHeaders) {
                    serverOutput.write("$header\r\n".toByteArray(Charsets.UTF_8))
                }
                serverOutput.write("\r\n".toByteArray(Charsets.UTF_8))
                serverOutput.flush()

                // Forward request body if any
                if (contentLength > 0 || isChunked) {
                    pipeRequestBody(clientInput, serverOutput, contentLength, isChunked)
                }

                // 5. Read response line from Upstream
                val responseLine = readHeaderLine(serverInput)
                if (responseLine == null || responseLine.isEmpty()) {
                    break
                }

                // 6. Read response headers
                val responseHeaders = mutableListOf<String>()
                var respContentLength: Long = -1
                var respIsChunked = false
                var contentEncoding: String? = null
                var isHtmlOrText = false
                var isHtml = false
                var statusCode = 200

                // Parse status code
                val responseParts = responseLine.split(" ")
                if (responseParts.size >= 2) {
                    statusCode = responseParts[1].toIntOrNull() ?: 200
                }

                while (true) {
                    val headerLine = readHeaderLine(serverInput) ?: break
                    if (headerLine.isEmpty()) break
                    responseHeaders.add(headerLine)

                    val lowerLine = headerLine.lowercase(Locale.US)
                    if (lowerLine.startsWith("content-length:")) {
                        respContentLength = headerLine.substring(15).trim().toLongOrNull() ?: -1
                    } else if (lowerLine.startsWith("transfer-encoding:") && lowerLine.contains("chunked")) {
                        respIsChunked = true
                    } else if (lowerLine.startsWith("content-encoding:")) {
                        contentEncoding = headerLine.substring(17).trim().lowercase(Locale.US)
                    } else if (lowerLine.startsWith("content-type:")) {
                        val type = headerLine.substring(13).trim().lowercase(Locale.US)
                        if (type.contains("html") || type.contains("text") || type.contains("javascript") || type.contains("json") || type.contains("css")) {
                            isHtmlOrText = true
                        }
                        if (type.contains("html")) {
                            isHtml = true
                        }
                    }
                }

                // 7. Process and forward response
                val hasBody = respIsChunked || respContentLength > 0 || respContentLength == -1L
                if (!hasBody) {
                    // No body: write response line and headers immediately
                    clientOutput.write("$responseLine\r\n".toByteArray(Charsets.UTF_8))
                    for (header in responseHeaders) {
                        clientOutput.write("$header\r\n".toByteArray(Charsets.UTF_8))
                    }
                    clientOutput.write("\r\n".toByteArray(Charsets.UTF_8))
                    clientOutput.flush()
                } else {
                    // Body exists: delegate header and body writing to pipeResponseBody
                    pipeResponseBody(
                        fullUrl,
                        host,
                        statusCode,
                        responseLine,
                        responseHeaders,
                        serverInput,
                        clientOutput,
                        respContentLength,
                        respIsChunked,
                        contentEncoding,
                        isHtmlOrText,
                        isHtml
                    )
                }
            }
        } catch (e: Exception) {
            logError("Error in MITM transaction stream: ${e.message}")
        } finally {
            try { downstream.close() } catch (ignore: Exception) {}
            try { upstream.close() } catch (ignore: Exception) {}
        }
    }

    /**
     * Processes response bodies. Performs on-the-fly decompression (GZIP/Deflate)
     * of text resources to feed the ProxyListener plaintext content.
     */
    private fun pipeResponseBody(
        url: String,
        host: String,
        statusCode: Int,
        responseLine: String,
        headers: List<String>,
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        isChunked: Boolean,
        contentEncoding: String?,
        isHtmlOrText: Boolean,
        isHtml: Boolean
    ) {
        val shouldInspect = proxyListener != null && isHtmlOrText && (contentLength == -1L || contentLength < 5 * 1024 * 1024)

        if (shouldInspect) {
            try {
                val baos = ByteArrayOutputStream()
                if (isChunked) {
                    readChunkedDataToStream(input, baos)
                } else if (contentLength >= 0) {
                    val buffer = ByteArray(4096)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val r = input.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
                        if (r == -1) break
                        baos.write(buffer, 0, r)
                        remaining -= r
                    }
                } else {
                    // Read until EOF
                    val buffer = ByteArray(4096)
                    var r: Int
                    while (input.read(buffer).also { r = it } != -1) {
                        baos.write(buffer, 0, r)
                    }
                }

                val rawBytes = baos.toByteArray()
                var decompressedBody: String? = null

                if (rawBytes.isNotEmpty()) {
                    try {
                        val decompressedBytes = when (contentEncoding) {
                            "gzip" -> decompressGzip(rawBytes)
                            "deflate" -> decompressDeflate(rawBytes)
                            else -> rawBytes
                        }
                        decompressedBody = decompressedBytes.toString(Charsets.UTF_8)
                    } catch (e: Exception) {
                        logError("Failed to decompress body for $url: ${e.message}")
                    }
                }

                // Apply HTML filtering (remove elements matching ##^ rules)
                if (isHtml && decompressedBody != null && HtmlFilter.hasRulesForDomain(host)) {
                    val filteredBody = HtmlFilter.applyFilters(host, decompressedBody)
                    if (filteredBody != decompressedBody) {
                        decompressedBody = filteredBody
                        // Body was modified, we'll re-encode after all injections
                    }
                }

                // Trigger decoupled listener inspection callback
                proxyListener?.onResponseInspection(url, statusCode, headers, decompressedBody)

                // Check and apply Scriptlet injection JS (runs BEFORE other page scripts)
                val scriptletJs = if (isHtml) ScriptletFilter.getScriptletCodeForDomain(host, appContext) else null
                // Check and apply CSS cosmetic filter styling
                val css = if (isHtml) CosmeticFilter.getCssForDomain(host) else null
                // Check and apply procedural cosmetic filter JS
                val proceduralJs = if (isHtml) ProceduralFilter.getScriptForDomain(host) else null
                var finalBytes = rawBytes
                var headersModified = false
                val finalHeaders = headers.toMutableList()

                val hasAnyInjection = scriptletJs != null || css != null || proceduralJs != null
                if (hasAnyInjection && decompressedBody != null) {
                    val injectionBuilder = StringBuilder()
                    // Scriptlet JS injected FIRST so it runs before page scripts
                    if (scriptletJs != null) {
                        injectionBuilder.append("<script>").append(scriptletJs).append("</script>")
                    }
                    // Procedural cosmetic JS second
                    if (proceduralJs != null) {
                        injectionBuilder.append("<script>").append(proceduralJs).append("</script>")
                    }
                    // CSS cosmetic styling last
                    if (css != null) {
                        injectionBuilder.append("<style>").append(css).append("</style>")
                    }
                    val injection = injectionBuilder.toString()

                    val headIndex = decompressedBody.indexOf("</head>", ignoreCase = true)
                    val modifiedBody = if (headIndex != -1) {
                        decompressedBody.substring(0, headIndex) + injection + decompressedBody.substring(headIndex)
                    } else {
                        decompressedBody + injection
                    }

                    finalBytes = modifiedBody.toByteArray(Charsets.UTF_8)

                    // Modify headers to send standard uncompressed, unchunked identity body
                    val iterator = finalHeaders.iterator()
                    while (iterator.hasNext()) {
                        val h = iterator.next().lowercase(Locale.US)
                        if (h.startsWith("content-length:") ||
                            h.startsWith("content-encoding:") ||
                            h.startsWith("transfer-encoding:")
                        ) {
                            iterator.remove()
                        }
                    }
                    finalHeaders.add("Content-Length: ${finalBytes.size}")
                    headersModified = true
                }

                // Inject or merge Content-Security-Policy header
                val additionalCsp = CspInjector.getCspForDomain(host)
                if (additionalCsp != null) {
                    val cspHeaderPrefix = "content-security-policy:"
                    val existingIdx = finalHeaders.indexOfFirst {
                        it.lowercase(Locale.US).startsWith(cspHeaderPrefix)
                    }
                    if (existingIdx != -1) {
                        // Merge with existing CSP — new directives appended only if not present
                        val merged = CspInjector.mergeWithExistingCsp(finalHeaders[existingIdx], additionalCsp)
                        finalHeaders[existingIdx] = "Content-Security-Policy: $merged"
                    } else {
                        finalHeaders.add("Content-Security-Policy: $additionalCsp")
                    }
                }

                // Write status line and headers
                output.write("$responseLine\r\n".toByteArray(Charsets.UTF_8))
                for (header in finalHeaders) {
                    output.write("$header\r\n".toByteArray(Charsets.UTF_8))
                }
                output.write("\r\n".toByteArray(Charsets.UTF_8))
                output.flush()

                // Write response body
                if (headersModified) {
                    output.write(finalBytes)
                } else {
                    if (isChunked) {
                        // Write back using valid chunk format
                        output.write("${rawBytes.size.toString(16)}\r\n".toByteArray(Charsets.UTF_8))
                        output.write(rawBytes)
                        output.write("\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8))
                    } else {
                        output.write(rawBytes)
                    }
                }
                output.flush()

            } catch (e: Exception) {
                logError("Exception while inspecting and piping body: ${e.message}")
            }
        } else {
            // High-speed transparent binary piping path - write original response headers first
            try {
                output.write("$responseLine\r\n".toByteArray(Charsets.UTF_8))
                for (header in headers) {
                    output.write("$header\r\n".toByteArray(Charsets.UTF_8))
                }
                output.write("\r\n".toByteArray(Charsets.UTF_8))
                output.flush()

                if (isChunked) {
                    pipeChunkedData(input, output)
                } else if (contentLength >= 0) {
                    pipeFixedLengthData(input, output, contentLength)
                } else {
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } catch (e: Exception) {
                logError("Exception while forwarding uninspected binary stream: ${e.message}")
            }
        }
    }

    /**
     * Reads a line from buffered input stream.
     */
    private fun readHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8")
    }

    private fun discardRequestBody(input: InputStream, length: Long, isChunked: Boolean) {
        try {
            if (isChunked) {
                discardChunkedData(input)
            } else if (length > 0) {
                var remaining = length
                val buffer = ByteArray(4096)
                while (remaining > 0) {
                    val r = input.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
                    if (r == -1) break
                    remaining -= r
                }
            }
        } catch (ignore: Exception) {}
    }

    private fun pipeRequestBody(input: InputStream, output: OutputStream, length: Long, isChunked: Boolean) {
        if (isChunked) {
            pipeChunkedData(input, output)
        } else {
            pipeFixedLengthData(input, output, length)
        }
    }

    private fun pipeFixedLengthData(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val r = input.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
            if (r == -1) throw EOFException("Premature EOF in HTTP body")
            output.write(buffer, 0, r)
            remaining -= r
        }
        output.flush()
    }

    private fun pipeChunkedData(input: InputStream, output: OutputStream) {
        while (true) {
            val sizeLine = readHeaderLine(input) ?: throw EOFException("EOF in chunked headers")
            output.write("$sizeLine\r\n".toByteArray(Charsets.UTF_8))
            val size = sizeLine.trim().split(";")[0].toInt(16)
            if (size == 0) {
                val trailing = readHeaderLine(input) ?: ""
                output.write("\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                break
            }

            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val r = input.read(buffer, read, size - read)
                if (r == -1) throw EOFException("EOF in chunked body data")
                read += r
            }
            output.write(buffer, 0, size)

            val trailing = readHeaderLine(input) ?: ""
            output.write("\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun discardChunkedData(input: InputStream) {
        while (true) {
            val sizeLine = readHeaderLine(input) ?: throw EOFException("EOF in chunked headers")
            val size = sizeLine.trim().split(";")[0].toInt(16)
            if (size == 0) {
                readHeaderLine(input)
                break
            }
            var remaining = size
            val buffer = ByteArray(minOf(4096, remaining))
            while (remaining > 0) {
                val r = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (r == -1) throw EOFException("EOF in chunked body")
                remaining -= r
            }
            readHeaderLine(input)
        }
    }

    private fun readChunkedDataToStream(input: InputStream, output: OutputStream) {
        while (true) {
            val sizeLine = readHeaderLine(input) ?: throw EOFException("EOF in chunked headers")
            val size = sizeLine.trim().split(";")[0].toInt(16)
            if (size == 0) {
                readHeaderLine(input)
                break
            }
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val r = input.read(buffer, read, size - read)
                if (r == -1) throw EOFException("EOF in chunked body data")
                read += r
            }
            output.write(buffer, 0, size)
            readHeaderLine(input)
        }
    }

    /**
     * Handles absolute-URI requests of plain HTTP traffic.
     */
    private suspend fun handlePlainHttp(clientSocket: Socket, firstLine: String) {
        try {
            val parts = firstLine.split(" ")
            val urlStr = parts[1]
            val uri = java.net.URI(urlStr)
            val host = uri.host ?: return
            val port = if (uri.port != -1) uri.port else 80

            val isAllowed = proxyListener?.onRequestInspection(
                url = urlStr,
                host = host,
                method = parts[0],
                headers = emptyList(),
                resourceType = 1 shl 8 // ResourceType.OTHER
            ) ?: true

            if (!isAllowed) {
                logInfo("Plain HTTP Request Blocked: $urlStr")
                val clientOut = clientSocket.getOutputStream()
                sendLocalBlockResponse(clientOut, 1 shl 8)
                return
            }

            val upstreamSocket = Socket()
            upstreamSocket.connect(InetSocketAddress(host, port), 10000)

            upstreamSocket.use { upstream ->
                val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + (if (uri.rawQuery != null) "?" + uri.rawQuery else "")
                val upstreamOut = upstream.getOutputStream()
                upstreamOut.write("$parts[0] $path ${parts[2]}\r\n".toByteArray())
                
                pipeRawConnections(clientSocket, upstream)
            }
        } catch (e: Exception) {
            logError("Plain HTTP proxy failed: ${e.message}")
        }
    }

    /**
     * Low-level helper to pipe raw connections in pass-through (bypass) mode.
     */
    private suspend fun pipeRawConnections(client: Socket, upstream: Socket) = coroutineScope {
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()
        val upstreamInput = upstream.getInputStream()
        val upstreamOutput = upstream.getOutputStream()

        val job1 = launch {
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (clientInput.read(buffer).also { read = it } != -1) {
                    upstreamOutput.write(buffer, 0, read)
                    upstreamOutput.flush()
                }
            } catch (e: Exception) {
            } finally {
                try { upstream.close() } catch (ignore: Exception) {}
            }
        }

        val job2 = launch {
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (upstreamInput.read(buffer).also { read = it } != -1) {
                    clientOutput.write(buffer, 0, read)
                    clientOutput.flush()
                }
            } catch (e: Exception) {
            } finally {
                try { client.close() } catch (ignore: Exception) {}
            }
        }

        joinAll(job1, job2)
    }

    private fun sendLocalBlockResponse(output: OutputStream, resourceType: Int) {
        val isImage = resourceType == 1 shl 3 // ResourceType.IMAGE
        if (isImage) {
            // Transparent 1x1 GIF pixels for blocked images
            val transparentGif = byteArrayOf(
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x21, 0xf9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
            )
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/gif\r\n" +
                "Content-Length: ${transparentGif.size}\r\n" +
                "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            output.write(transparentGif)
        } else {
            // Empty 200 OK block for scripts/stylesheets/documents
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
        }
        output.flush()
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(compressed)
        val gzis = java.util.zip.GZIPInputStream(bais)
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var len: Int
        while (gzis.read(buffer).also { len = it } != -1) {
            baos.write(buffer, 0, len)
        }
        return baos.toByteArray()
    }

    private fun decompressDeflate(compressed: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(compressed)
        val iis = java.util.zip.InflaterInputStream(bais)
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var len: Int
        while (iis.read(buffer).also { len = it } != -1) {
            baos.write(buffer, 0, len)
        }
        return baos.toByteArray()
    }

    private fun createSslContextForLeaf(keyAndCert: CertificateAuthority.KeyAndCert): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        val password = "password".toCharArray()
        val chain = arrayOf(keyAndCert.certificate, CertificateAuthority.getRootCertificate())
        keyStore.setKeyEntry("key", keyAndCert.privateKey, password, chain)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext
    }

    internal fun shouldInspectDomain(domain: String): Boolean {
        val cleanedDomain = domain.trim().lowercase(Locale.US)
        
        // 1. Check pre-seeded known pinned domains
        if (PERSISTENT_BYPASS_SEEDS.any { cleanedDomain == it || cleanedDomain.endsWith(".$it") }) {
            return false
        }
        
        // 2. Check dynamic bypass cache
        if (dynamicBypassSet.any { cleanedDomain == it || cleanedDomain.endsWith(".$it") }) {
            return false
        }
        
        return true
    }

    private fun readLineByteByByte(inputStream: InputStream): String {
        val baos = ByteArrayOutputStream()
        var b: Int
        while (true) {
            b = inputStream.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8").trim()
    }

    private fun determineResourceTypeFallback(
        path: String,
        accept: String?,
        contentType: String?,
        secFetchDest: String?
    ): Int {
        // Fallback mapper linking proxy headers directly to the resource types
        val lowerPath = path.lowercase(Locale.US)
        if (secFetchDest != null) {
            when (secFetchDest.lowercase(Locale.US)) {
                "document" -> return 1 shl 0
                "iframe" -> return 1 shl 5
                "script" -> return 1 shl 2
                "style" -> return 1 shl 1
                "image" -> return 1 shl 3
                "font" -> return 1 shl 4
                "video", "audio" -> return 1 shl 7
            }
        }
        if (lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs")) return 1 shl 2
        if (lowerPath.endsWith(".css")) return 1 shl 1
        if (lowerPath.endsWith(".png") || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".gif") || lowerPath.endsWith(".webp") || lowerPath.endsWith(".svg")) return 1 shl 3
        if (lowerPath.endsWith(".woff") || lowerPath.endsWith(".woff2") || lowerPath.endsWith(".ttf")) return 1 shl 4
        
        if (accept != null) {
            val lowerAccept = accept.lowercase(Locale.US)
            if (lowerAccept.contains("text/html")) return 1 shl 0
            if (lowerAccept.contains("text/css")) return 1 shl 1
            if (lowerAccept.contains("image/")) return 1 shl 3
        }
        return 1 shl 8 // ResourceType.OTHER
    }

    /* Logging abstractions with pure JVM fallback mechanisms */

    private fun logInfo(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (t: Throwable) {
            println("[$TAG] INFO: $msg")
        }
    }

    private fun logWarn(msg: String) {
        try {
            android.util.Log.w(TAG, msg)
        } catch (t: Throwable) {
            println("[$TAG] WARN: $msg")
        }
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.e(TAG, msg, tr)
            } else {
                android.util.Log.e(TAG, msg)
            }
        } catch (t: Throwable) {
            if (tr != null) {
                System.err.println("[$TAG] ERROR: $msg")
                tr.printStackTrace()
            } else {
                System.err.println("[$TAG] ERROR: $msg")
            }
        }
    }

    private fun logDebug(msg: String) {
        try {
            android.util.Log.d(TAG, msg)
        } catch (t: Throwable) {
            println("[$TAG] DEBUG: $msg")
        }
    }
}
