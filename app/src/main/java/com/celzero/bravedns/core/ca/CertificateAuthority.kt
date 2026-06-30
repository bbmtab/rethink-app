package com.celzero.bravedns.core.ca

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
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
        initializeCA()
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

            if (keyStore.containsAlias(ROOT_CA_ALIAS)) {
                val key = keyStore.getKey(ROOT_CA_ALIAS, null)
                val cert = keyStore.getCertificate(ROOT_CA_ALIAS)
                if (key is PrivateKey && cert is X509Certificate) {
                    rootPrivateKey = key
                    rootCertificate = cert
                    return
                }
            }

            // No valid CA found — generate a new one and persist it in AndroidKeyStore
            generateAndStoreRootCA(keyStore)
        } catch (e: Exception) {
            e.printStackTrace()
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

            if (keyStore.containsAlias(ROOT_CA_ALIAS)) {
                val key = keyStore.getKey(ROOT_CA_ALIAS, null)
                val cert = keyStore.getCertificate(ROOT_CA_ALIAS)
                if (key is PrivateKey && cert is X509Certificate) {
                    rootPrivateKey = key
                    rootCertificate = cert
                    return true
                }
            }
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
        val issuer = X505PrincipalUtil(rootCert)
        val subject = X500Name("CN=$hostname, O=RethinkDNS Local, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        
        val notBefore = Date(System.currentTimeMillis() - 1000 * 60 * 60) // 1 hour ago for clock skew
        val notAfter = Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24) // 1 day validity (Constraint)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
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
        val serial = BigInteger.valueOf(System.currentTimeMillis())
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

        // Sign the certificate using our custom ContentSigner
        // This delegates to the keystore's Signature implementation without extracting the private key
        val signer = AndroidKeyStoreContentSigner(keyPair.private)

        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(holder)

        // Store the key+cert in the keystore
        if (isAndroidKeyStore) {
            // Overwrite the existing KeyStore entry to associate the custom certificate with the private key.
            // On Android KeyStore, we pass the private key reference and the certificate chain.
            keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, null, arrayOf(cert))
        } else {
            // Software keystore - store both key and cert
            keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, "password".toCharArray(), arrayOf(cert))
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

    /**
     * Generates a 2048-bit RSA keypair in-memory for the temporary leaf certificate.
     */
    private fun generateKeyPairForLeaf(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
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
            rootPrivateKey = null
            rootCertificate = null
            leafCertCache.evictAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
