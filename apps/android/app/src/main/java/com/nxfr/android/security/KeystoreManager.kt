package com.nxfr.android.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.nxfr.android.service.NxfrService
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identity info returned after generate or load.
 * [deviceId] is the 64-char hex SHA-256(SPKI) as returned by nxfr_identity_generate/load.
 * [storeDir] is the absolute path to the identity directory (for passing to FFI).
 */
data class IdentityInfo(val deviceId: String, val storeDir: String)

/**
 * Manages the NXFR device identity (PKCS#8 DER private key + X.509 DER certificate).
 *
 * ## Storage Strategy
 * - Identity files are stored in the app-private directory (`context.filesDir/nxfr-identity/`).
 * - On API 23+ (Android M), the private key DER is additionally wrapped (encrypted) with an
 *   AES-256-GCM key stored in the Android Keystore. This provides defense-in-depth: even if
 *   an attacker can read app-private files (e.g., on a rooted device), they cannot extract the
 *   raw private key without access to the hardware-backed Keystore.
 * - On older APIs, the key is stored as a plain file in app-private storage. This is acceptable
 *   because app-private storage is already protected by the Linux DAC model and SELinux.
 *
 * ## Decision Log
 * - AndroidKeyStore AES wrapping was chosen over AndroidKeyStore P-256 key generation because
 *   the NXFR protocol requires direct access to the raw key material for TLS configuration
 *   (rustls needs the PKCS#8 DER bytes). AndroidKeyStore keys are non-extractable by design,
 *   so we generate the key in software (via Rust) and wrap/unwrap it for storage.
 */
class KeystoreManager {

    companion object {
        private const val TAG = "NxfrKeystore"
        private const val IDENTITY_DIR = "nxfr-identity"
        private const val KEY_FILE = "identity.der"
        private const val CERT_FILE = "identity.crt"
        private const val WRAPPED_KEY_FILE = "identity.der.enc"
        private const val IV_FILE = "identity.der.iv"
        private const val KEYSTORE_ALIAS = "nxfr_identity_wrapper"
    }

    /**
     * Generate a new identity or load an existing one.
     * Returns [IdentityInfo] with the device ID and storage directory.
     */
    fun generateOrLoad(context: Context): IdentityInfo {
        val storeDir = File(context.filesDir, IDENTITY_DIR).apply { mkdirs() }
        val storeDirPath = storeDir.absolutePath
        val certFile = File(storeDir, CERT_FILE)

        val json: String = if (certFile.exists()) {
            Log.i(TAG, "Loading existing identity from $storeDirPath")
            NxfrService.NxfrBridge.nxfr_identity_load(storeDirPath)
        } else {
            Log.i(TAG, "Generating new identity in $storeDirPath")
            val result = NxfrService.NxfrBridge.nxfr_identity_generate(storeDirPath)

            // After generation, wrap the private key with AndroidKeyStore if available.
            wrapPrivateKeyIfPossible(storeDir)

            result
        }

        // Parse device_id from the JSON response.
        // Response format: {"device_id": "<64-char hex>"}
        val deviceId = parseDeviceId(json)

        return IdentityInfo(deviceId = deviceId, storeDir = storeDirPath)
    }

    /**
     * Parse device_id from the FFI JSON response.
     * Falls back to "unknown" if parsing fails.
     */
    private fun parseDeviceId(json: String): String {
        return try {
            // Simple JSON parsing without a full library.
            val regex = """"device_id"\s*:\s*"([a-f0-9]{64})"""".toRegex()
            regex.find(json)?.groupValues?.get(1) ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device_id from: $json", e)
            "unknown"
        }
    }

    /**
     * If AndroidKeyStore is available (API 23+), wrap the private key DER with AES-256-GCM.
     * The wrapped key replaces the plaintext key file.
     */
    private fun wrapPrivateKeyIfPossible(storeDir: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.i(TAG, "API < 23: storing private key as plain app-private file")
            return
        }

        val keyFile = File(storeDir, KEY_FILE)
        if (!keyFile.exists()) return

        try {
            val wrappingKey = getOrCreateWrappingKey()
            val plainKeyBytes = keyFile.readBytes()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encryptedBytes = cipher.doFinal(plainKeyBytes)
            val iv = cipher.iv

            // Write wrapped key + IV.
            File(storeDir, WRAPPED_KEY_FILE).writeBytes(encryptedBytes)
            File(storeDir, IV_FILE).writeBytes(iv)

            // Overwrite plaintext key with zeros, then delete.
            keyFile.writeBytes(ByteArray(plainKeyBytes.size))
            keyFile.delete()

            Log.i(TAG, "Private key wrapped with AndroidKeyStore AES-256-GCM")
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore wrapping failed; keeping plain file", e)
            // On failure, the plain key file remains. This is acceptable.
        }
    }

    /**
     * Unwrap the private key from AndroidKeyStore before passing to Rust FFI.
     * Call this before nxfr_connect/nxfr_listen if the key was wrapped.
     */
    fun unwrapPrivateKeyIfNeeded(storeDir: File) {
        val wrappedFile = File(storeDir, WRAPPED_KEY_FILE)
        val ivFile = File(storeDir, IV_FILE)
        val keyFile = File(storeDir, KEY_FILE)

        if (!wrappedFile.exists() || keyFile.exists()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val wrappingKey = getOrCreateWrappingKey()
            val iv = ivFile.readBytes()
            val encryptedBytes = wrappedFile.readBytes()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
            val plainKeyBytes = cipher.doFinal(encryptedBytes)

            // Write plaintext key for Rust FFI to read.
            keyFile.writeBytes(plainKeyBytes)

            Log.d(TAG, "Private key unwrapped for FFI use")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap private key", e)
        }
    }

    /**
     * Remove the temporary plaintext key after FFI has loaded it.
     */
    fun cleanupPlaintextKey(storeDir: File) {
        val keyFile = File(storeDir, KEY_FILE)
        val wrappedFile = File(storeDir, WRAPPED_KEY_FILE)
        if (wrappedFile.exists() && keyFile.exists()) {
            keyFile.writeBytes(ByteArray(keyFile.length().toInt()))
            keyFile.delete()
            Log.d(TAG, "Plaintext key cleaned up after FFI load")
        }
    }

    @Suppress("NewApi") // Guarded by Build.VERSION.SDK_INT check in callers.
    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(spec)
        return generator.generateKey()
    }
}
