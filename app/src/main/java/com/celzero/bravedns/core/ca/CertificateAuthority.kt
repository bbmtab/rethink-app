package com.celzero.bravedns.core.ca

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
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
    private const val KEYSTORE_PROVIDER_ANDROID = "AndroidKeyStore"
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
        // Register BouncyCastle Provider dynamically if not already registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        initializeCA()
    }

    /**
     * Initializes the Root Certificate Authority.
     * Loads the existing Root CA from the keystore, or generates a new one if it doesn't exist.
     */
    @Synchronized
    fun initializeCA() {
        try {
            val keyStore = getKeystoreInstance()
            
            if (keyStore.containsAlias(ROOT_CA_ALIAS)) {
                rootPrivateKey = keyStore.getKey(ROOT_CA_ALIAS, null) as? PrivateKey
                rootCertificate = keyStore.getCertificate(ROOT_CA_ALIAS) as? X509Certificate
            }

            if (rootPrivateKey == null || rootCertificate == null) {
                generateAndStoreRootCA(keyStore)
            }
        } catch (e: Exception) {
            // Log fallback or handle initialization errors
            e.printStackTrace()
        }
    }

    /**
     * Dynamically generates a domain-specific leaf certificate signed by the Root CA.
     * If a certificate for the host is already in the cache, it returns it directly.
     *
     * @param hostname The target domain (e.g. "google.com")
     * @return The generated X509Certificate
     */
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

        // Sign using Root CA Private Key
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(rootPriv)
        
        val holder = certBuilder.build(signer)
        val leafCert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)

        val keyAndCert = KeyAndCert(keyPair.private, leafCert)
        // Store in Cache
        leafCertCache.put(hostname, keyAndCert)
        return keyAndCert
    }

    /**
     * Exports the Root CA Certificate in standard DER-encoded byte format.
     * This is used for the flow where the user downloads/installs the CA to the device trust store.
     *
     * @return The DER-encoded bytes of the Root CA certificate
     */
    fun exportCaCert(): ByteArray {
        val cert = rootCertificate ?: throw IllegalStateException("Root CA Certificate is not initialized")
        return cert.encoded
    }

    /**
     * Checks if the Root CA Certificate is installed in Android's trust store.
     * On Android devices, trusted user-installed and system CAs are stored in "AndroidCAStore".
     */
    fun isCaInstalled(): Boolean {
        try {
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
     * Generates and securely stores the self-signed Root CA in the keystore.
     */
    private fun generateAndStoreRootCA(keyStore: KeyStore) {
        val issuer = X500Name("CN=RethinkDNS Root CA, O=RethinkDNS, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years validity (Constraint)

        val keyPair: KeyPair
        if (isAndroidKeyStoreAvailable()) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE_PROVIDER_ANDROID
            )
            val spec = KeyGenParameterSpec.Builder(
                ROOT_CA_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .build()
            kpg.initialize(spec)
            keyPair = kpg.generateKeyPair()
        } else {
            // Fallback for Unit Tests/JVM Environment
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            keyPair = kpg.generateKeyPair()
        }

        // Create the X509 Certificate
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        // Basic Constraints: isCA = true
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))

        // Key Usage: KeyCertSign and CRLSign
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        // Sign the certificate using Root CA Private Key
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)

        // Store back into KeyStore
        if (isAndroidKeyStoreAvailable()) {
            keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, null, arrayOf(cert))
        } else {
            // For unit test fallback keystore, save key + cert chain
            keyStore.setKeyEntry(ROOT_CA_ALIAS, keyPair.private, null, arrayOf(cert))
        }

        rootPrivateKey = keyPair.private
        rootCertificate = cert
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
     * Gets the appropriate KeyStore instance. Supports fallback for JUnit tests.
     */
    private fun getKeystoreInstance(): KeyStore {
        return if (isAndroidKeyStoreAvailable()) {
            KeyStore.getInstance(KEYSTORE_PROVIDER_ANDROID).apply { load(null) }
        } else {
            // Standard JKS/PKCS12 keystore fallback for non-Android / JUnit environments
            KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        }
    }

    /**
     * Helper to check if the Android KeyStore provider is accessible.
     */
    private fun isAndroidKeyStoreAvailable(): Boolean {
        return try {
            KeyStore.getInstance(KEYSTORE_PROVIDER_ANDROID)
            true
        } catch (e: Exception) {
            false
        }
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
        return rootCertificate ?: throw IllegalStateException("Root CA Certificate is not initialized")
    }

    /**
     * For testing/debugging purposes only. Clears the CA keys from the KeyStore.
     */
    @Synchronized
    fun resetCA() {
        try {
            val keyStore = getKeystoreInstance()
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
