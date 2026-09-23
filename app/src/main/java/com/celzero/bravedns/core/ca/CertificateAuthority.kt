package com.celzero.bravedns.core.ca

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*

/**
 * CertificateAuthority manages the lifecycle of the local self-signed Root CA
 * and handles the dynamic generation of domain-specific (leaf) SSL/TLS certificates
 * for HTTPS inspection.
 *
 * It uses Android Keystore for secure key storage and BouncyCastle for certificate creation.
 */
object CertificateAuthority {

    private const val TAG = "CertificateAuthority"
    private const val ROOT_CA_ALIAS = "RethinkDNSRootCA"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    data class KeyAndCert(val privateKey: PrivateKey, val certificate: X509Certificate)

    private class SimpleLruCache<K, V>(private val maxSize: Int) {
        private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean {
                return size > maxSize
            }
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun remove(key: K): V? = map.remove(key)

        @Synchronized
        fun evictAll() {
            map.clear()
        }
    }

    // In-memory LRU cache for dynamic leaf certificates (max 500 entries)
    private val leafCertCache = SimpleLruCache<String, KeyAndCert>(500)

    private var rootPrivateKey: PrivateKey? = null
    private var rootCertificate: X509Certificate? = null

    /**
     * App-private dir for persisting the BC-built Root CA bytes (DECISION-022).
     * The keystore is the key holder; the persisted BC cert is the authority on
     * WHAT the certificate is — keystore round-trips are ROM-dependent and have
     * returned extension-less system certs. Null on JVM/tests → persistence off.
     */
    internal var persistedFilesDir: java.io.File? = null
    internal const val PERSISTED_CA_FILENAME = "rethink_root_ca.der"

    init {
        // Register BouncyCastle provider if it is not already registered (critical for JVM unit tests and some devices)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Initializes the Root Certificate Authority with an Android Context for persistent storage.
     * Call this variant on Android devices.
     */
    @Synchronized
    fun initializeCA(context: Context) {
        try {
            persistedFilesDir = context.applicationContext.filesDir
        } catch (e: Exception) {
        }
        initializeCA()
    }

    /**
     * Discipline gate (DECISION-021): a persisted Root CA is reusable ONLY when
     * it is a currently-valid CA certificate — BasicConstraints CA:true plus
     * keyCertSign usage. Stale entries (notably extension-less AndroidKeyStore
     * system certs persisted by pre-fix builds) are unusable: Android 11+
     * refuses to install them as a CA ("private key required" in every
     * installer slot) and reusing them would preserve the breakage forever.
     * Pure function of the certificate — no Android APIs, unit-testable on JVM.
     */
    fun isRootCaUsable(cert: X509Certificate): Boolean {
        try {
            cert.checkValidity()
        } catch (e: Exception) {
            return false
        }
        // basicConstraints < 0 means "not a CA" — the CA installer rejects it.
        if (cert.basicConstraints < 0) return false
        // keyCertSign is bit 5 of KeyUsage.
        val keyUsage = cert.keyUsage ?: return false
        if (keyUsage.size <= 5 || !keyUsage[5]) return false
        return true
    }

    /**
     * Initializes the Root Certificate Authority.
     * Loads the existing Root CA from AndroidKeyStore, or generates a new one.
     * For unit tests (JVM), falls back to a software keystore since AndroidKeyStore is not available.
     */
    @Synchronized
    fun initializeCA() {
        try {
            val keyStore = try {
                KeyStore.getInstance("AndroidKeyStore")
            } catch (e: Exception) {
                // Fallback for JVM unit tests - use PKCS12 software keystore
                KeyStore.getInstance("PKCS12")
            }
            keyStore.load(null)

            if (ensureAuthoritativeCert(keyStore)) return
            // No valid CA found — generate a new one and persist it in AndroidKeyStore
            generateAndStoreRootCA(keyStore)
            // Persist the BC-built bytes independently (DECISION-022): the keystore
            // round-trip is ROM-dependent and may hand back an extension-less
            // system cert. The persisted BC cert is the authority from here on.
            rootCertificate?.let { persistCaCert(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves the authoritative Root CA into memory. Precedence
     * (DECISION-022): in-memory usable cert → persisted BC-built cert paired to
     * the keystore key → usable keystore entry itself (adopted + persisted).
     * Anything else → false, caller regenerates. A persisted BC cert shares the
     * keypair with the keystore entry, so adopting it never changes identity —
     * no spurious CA-reinstall prompts.
     */
    private fun ensureAuthoritativeCert(keyStore: KeyStore): Boolean {
        rootCertificate?.let { if (isRootCaUsable(it)) return true }

        val key: Key?
        val entryCert: java.security.cert.Certificate?
        try {
            if (!keyStore.containsAlias(ROOT_CA_ALIAS)) return false
            key = keyStore.getKey(ROOT_CA_ALIAS, null)
            entryCert = keyStore.getCertificate(ROOT_CA_ALIAS)
        } catch (e: Exception) {
            return false
        }
        if (key !is PrivateKey || entryCert !is X509Certificate) {
            try {
                keyStore.deleteEntry(ROOT_CA_ALIAS)
            } catch (_: Exception) {
            }
            return false
        }

        // 1. Persisted BC-built cert paired to this exact keypair wins over
        //    whatever bytes the keystore round-trip returns.
        loadPersistedCaCert()?.let { persisted ->
            if (persisted.publicKey == entryCert.publicKey && isRootCaUsable(persisted)) {
                rootPrivateKey = key
                rootCertificate = persisted
                return true
            }
        }

        // 2. Keystore entry itself usable → adopt it and persist its bytes.
        if (isRootCaUsable(entryCert)) {
            rootPrivateKey = key
            rootCertificate = entryCert
            persistCaCert(entryCert)
            return true
        }

        // 3. Unusable (DECISION-021): drop so the caller regenerates.
        try {
            keyStore.deleteEntry(ROOT_CA_ALIAS)
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Reads the independently-persisted BC-built Root CA. Returns null when
     * persistence is off (no filesDir), the file is absent, or its bytes do
     * not parse / are not a usable CA — never trust persisted bytes blindly.
     */
    private fun loadPersistedCaCert(): X509Certificate? {
        val dir = persistedFilesDir ?: return null
        try {
            val file = java.io.File(dir, PERSISTED_CA_FILENAME)
            if (!file.isFile) return null
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(bytes.inputStream()) as? X509Certificate
                ?: return null
            if (!isRootCaUsable(cert)) return null
            return cert
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Persists BC-built Root CA bytes to app-private storage. Best-effort and
     * silent: JVM/tests (no filesDir) and write failures simply skip — the
     * keystore remains the key holder either way.
     */
    private fun persistCaCert(cert: X509Certificate) {
        val dir = persistedFilesDir ?: return
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            java.io.File(dir, PERSISTED_CA_FILENAME).writeBytes(cert.encoded)
        } catch (e: Exception) {
        }
    }

    @Synchronized
    private fun loadFromKeyStoreOnly(): Boolean {
        try {
            val keyStore = try {
                KeyStore.getInstance("AndroidKeyStore")
            } catch (e: Exception) {
                // Fallback for JVM unit tests - use PKCS12 software keystore
                KeyStore.getInstance("PKCS12")
            }
            keyStore.load(null)

            // Authoritative resolution (DECISION-022), never raw keystore bytes.
            return ensureAuthoritativeCert(keyStore)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Dynamically generates a domain-specific leaf certificate signed by the Root CA.
     * If a certificate for the host is already in the cache, it returns it directly.
     *
     * @param hostname The target domain (e.g. "google.com")
     * @return The generated X509Certificate
     */
    @Synchronized
    fun generateLeafCert(hostname: String): X509Certificate {
        return generateLeafKeyAndCert(hostname).certificate
    }

    /**
     * Dynamically generates a domain-specific leaf certificate and private key signed by the Root CA.
     *
     * @param hostname The target domain (e.g. "google.com")
     * @return The generated KeyAndCert containing both the PrivateKey and X509Certificate
     */
    @Synchronized
    fun generateLeafKeyAndCert(hostname: String): KeyAndCert {
        // Check memory LRU cache first
        leafCertCache.get(hostname)?.let { cached ->
            try {
                cached.certificate.checkValidity()
                return cached
            } catch (e: Exception) {
                // Cert has expired, remove from cache and regenerate
                leafCertCache.remove(hostname)
            }
        }

        if (rootPrivateKey == null || rootCertificate == null) {
            loadFromKeyStoreOnly()
        }

        val rootPriv = rootPrivateKey ?: throw IllegalStateException("Root CA Private Key is not initialized")
        val rootCert = rootCertificate ?: throw IllegalStateException("Root CA Certificate is not initialized")

        val keyPair = generateKeyPairForLeaf()
        val subject = X500Name("CN=$hostname, O=RethinkDNS Local, C=US")
        
        val random = SecureRandom()
        val serial = BigInteger(159, random)
        
        val notBefore = Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30) // 30 days ago for massive clock skew / timezone buffer
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365) // 365 days in the future (total validity of 395 days, safely below the 398-day limit)

        val certBuilder = JcaX509v3CertificateBuilder(
            rootCert,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        // Basic Constraints (Not a CA)
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

        // Key Usage: Digital Signature & Key Encipherment
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        // Extended Key Usage: Server Authentication
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            true,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        // Subject Alternative Name (SAN) - CRITICAL for Chrome and modern HTTPS clients
        val san = GeneralNames(GeneralName(GeneralName.dNSName, hostname))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)

        // Subject Key Identifier & Authority Key Identifier - CRITICAL for modern TLS/HSTS validation in Chrome
        val extUtils = JcaX509ExtensionUtils()
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extUtils.createSubjectKeyIdentifier(keyPair.public)
        )
        certBuilder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extUtils.createAuthorityKeyIdentifier(rootCert)
        )

        // Sign using Root CA Private Key via AndroidKeyStore ContentSigner
        val signer = AndroidKeyStoreContentSigner(rootPriv)

        val holder = certBuilder.build(signer)
        val leafCert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(holder)

        val keyAndCert = KeyAndCert(keyPair.private, leafCert)
        // Store in Cache
        leafCertCache.put(hostname, keyAndCert)
        return keyAndCert
    }

    /**
     * Generates and securely stores the self-signed Root CA.
     *
     * On Android: generates keypair directly in AndroidKeyStore (non-extractable, hardware-backed when available)
     * On JVM (unit tests): uses software keystore (PKCS12)
     * We use a custom ContentSigner that delegates signing to the keystore's Signature implementation,
     * allowing BouncyCastle to build certificates without extracting the private key material.
     */
    private fun generateAndStoreRootCA(keyStore: KeyStore) {
        val issuer = X500Name("CN=RethinkDNS Root CA, O=RethinkDNS, C=US")
        val random = SecureRandom()
        val serial = BigInteger(159, random)
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years validity

        val isAndroidKeyStore = keyStore.provider.name == "AndroidKeyStore"
        val keyPair: KeyPair

        if (isAndroidKeyStore) {
            // Generate keypair directly in AndroidKeyStore (non-extractable, hardware-backed when available)
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply {
                time = start.time
                add(Calendar.YEAR, 10)
            }
            kpg.initialize(
                KeyGenParameterSpec.Builder(ROOT_CA_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=RethinkDNS Root CA, O=RethinkDNS, C=US"))
                    .setCertificateSerialNumber(serial)
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build()
            )
            keyPair = kpg.generateKeyPair()
        } else {
            // Software keystore for JVM unit tests
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            keyPair = kpg.generateKeyPair()
        }

        // Get the public key for certificate creation
        val publicKey = keyPair.public

        // Create the X509 Certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            publicKey
        )

        // Basic Constraints: isCA = true
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))

        // Key Usage: KeyCertSign and CRLSign
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        // Subject Key Identifier & Authority Key Identifier - CRITICAL for modern TLS/HSTS validation in Chrome
        val extUtils = JcaX509ExtensionUtils()
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extUtils.createSubjectKeyIdentifier(publicKey)
        )
        certBuilder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extUtils.createAuthorityKeyIdentifier(publicKey)
        )

        // Sign the certificate using our custom ContentSigner
        // This delegates to the keystore's Signature implementation without extracting the private key
        val signer = AndroidKeyStoreContentSigner(keyPair.private)

        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(holder)

        // Persist the BC-built bytes FIRST (DECISION-022 fixup): the keystore
        // setKeyEntry below is ROM-dependent — on some backends (field case:
        // Redmi 9T MIUI) storing a keystore-native key reference throws or is
        // ignored, which previously aborted init and left Save greyed out.
        // The persisted file + in-memory cert survive regardless; the keystore
        // remains the key holder (keygen already created its entry).
        persistCaCert(cert)

        // Store the key+cert in the keystore (best-effort on AndroidKeyStore).
        try {
            if (isAndroidKeyStore) {
                // Overwrite the existing KeyStore entry to associate the custom certificate with the private key.
                // On Android KeyStore, we pass the private key reference and the certificate chain.
                keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, null, arrayOf(cert))
            } else {
                // Software keystore - store both key and cert
                keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, "password".toCharArray(), arrayOf(cert))
            }
        } catch (e: Exception) {
            // ROM refused the overwrite — tolerated: memory + persisted file
            // already carry the BC-built cert, key stays usable in keystore.
        }

        rootPrivateKey = keyPair.private
        rootCertificate = cert
    }

    /**
     * Custom ContentSigner that wraps AndroidKeyStore-backed PrivateKey.
     * This allows BouncyCastle to sign certificates without extracting the private key.
     * The Signature instance is automatically routed to AndroidKeyStore provider.
     */
    private class AndroidKeyStoreContentSigner(
        private val privateKey: PrivateKey,
        private val sigAlgo: String = SIGNATURE_ALGORITHM
    ) : ContentSigner {
        private val buffer = ByteArrayOutputStream()
        // TIDAK set provider — biarkan JCA route otomatis ke AndroidKeyStore
        private val signature = Signature.getInstance(sigAlgo).apply { initSign(privateKey) }

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            DefaultSignatureAlgorithmIdentifierFinder().find(sigAlgo)

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray {
            signature.update(buffer.toByteArray())
            return signature.sign()
        }
    }

    /**
     * Exports the Root CA Certificate in standard DER-encoded byte format.
     * This is used for the flow where the user downloads/installs the CA to the device trust store.
     *
     * @return The DER-encoded bytes of the Root CA certificate
     */
    fun exportCaCert(): ByteArray {
        if (rootCertificate == null) {
            loadFromKeyStoreOnly()
        }
        val cert = rootCertificate
            ?: throw IllegalStateException("Root CA Certificate is not initialized")
        return cert.encoded
    }

    /**
     * Checks if the Root CA Certificate is installed in Android's trust store.
     * On Android devices, trusted user-installed and system CAs are stored in "AndroidCAStore".
     */
    fun isCaInstalled(): Boolean {
        try {
            if (rootCertificate == null) {
                loadFromKeyStoreOnly()
            }
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null, null)
            val cert = rootCertificate ?: return false
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val systemCert = keyStore.getCertificate(alias) as? X509Certificate
                if (systemCert != null && systemCert.subjectX500Principal == cert.subjectX500Principal) {
                    if (systemCert.publicKey == cert.publicKey) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback for non-Android environments / Unit tests
            return false
        }
        return false
    }

    private var leafKeyPair: KeyPair? = null

    /**
     * Generates or reuses a 2048-bit RSA keypair in-memory for the temporary leaf certificate.
     * Reusing a single keypair eliminates the extremely expensive 2048-bit RSA key generation 
     * overhead (100ms-800ms of 100% CPU) on every cache miss, making dynamic certificate 
     * generation near-instantaneous (under 2ms) and buttery smooth.
     */
    @Synchronized
    private fun generateKeyPairForLeaf(): KeyPair {
        var kp = leafKeyPair
        if (kp == null) {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            kp = kpg.generateKeyPair()
            leafKeyPair = kp
        }
        return kp
    }

    /**
     * Dynamic helper to safely extract Principal X500Name from X509 certificate.
     */
    private fun X505PrincipalUtil(cert: X509Certificate): X500Name {
        return X500Name(cert.subjectX500Principal.name)
    }

    /**
     * Gets the Root CA certificate.
     */
    fun getRootCertificate(): X509Certificate {
        if (rootCertificate == null) {
            loadFromKeyStoreOnly()
        }
        return rootCertificate ?: throw IllegalStateException("Root CA Certificate is not initialized")
    }

    /**
     * Clears the in-memory leaf certificate cache.
     * Call this after CA is reinstalled or when leaf certs need to be regenerated
     * (e.g. after validity period fix).
     */
    @Synchronized
    fun clearLeafCertCache() {
        leafCertCache.evictAll()
    }

    /**
     * For testing/debugging purposes only. Clears the CA keys from AndroidKeyStore.
     */
    @Synchronized
    fun resetCA() {
        try {
            val keyStore = try {
                KeyStore.getInstance("AndroidKeyStore")
            } catch (e: Exception) {
                // Fallback for JVM unit tests - use PKCS12 software keystore
                KeyStore.getInstance("PKCS12")
            }
            keyStore.load(null)
            if (keyStore.containsAlias(ROOT_CA_ALIAS)) {
                keyStore.deleteEntry(ROOT_CA_ALIAS)
            }
            try {
                persistedFilesDir?.let { dir ->
                    val file = java.io.File(dir, PERSISTED_CA_FILENAME)
                    if (file.isFile) file.delete()
                }
            } catch (_: Exception) {
            }
            rootPrivateKey = null
            rootCertificate = null
            leafCertCache.evictAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
