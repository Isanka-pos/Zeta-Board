package com.zeta.board

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Notices when this copy of the app was not built from this project: someone opened the real APK in an "APK editor",
 * changed a file inside it (a photo, a sticker, an ad) and resigned it, since only this project has `zeta.keystore`.
 * Android requires every APK to be signed, and a tool that resigns one has to use its own key - it cannot reuse ours
 * without the keystore file and its password. So a changed-and-resigned copy always carries a different certificate,
 * and that is what this checks for.
 *
 * Limits, stated plainly: this is a lock, not a wall. The project currently ships without ProGuard/R8, so the code
 * here is as readable in the APK as it is in this file; a technical attacker who decompiles the app can find this
 * check and cut it out before resigning. It stops casual repacking (the common case: someone drags files around in
 * an APK editor and resigns), not a determined attacker willing to patch the code itself.
 */
object AntiTamper {

    // SHA-256 of the certificate in app/zeta.keystore (from: keytool -list -v -keystore zeta.keystore -storetype pkcs12
    // -storepass zetaboard -alias zeta). Update this only if the signing key is ever deliberately changed.
    private const val EXPECTED_CERT_SHA256 = "995168D94DD34E1C290BCF879768A9EDAAD452796ABC09B89E2B7FC3F2DCC16C"
    private const val EXPECTED_PACKAGE = "com.zeta.board"

    private var checked = false

    /** True until proven otherwise: a genuine, unmodified install. False once [check] finds a different signer. */
    @Volatile
    var ok: Boolean = true
        private set

    /** Runs the check once per process (cheap to call again; later calls are a no-op). Call this as early as possible. */
    fun check(context: Context) {
        if (checked) return
        checked = true
        ok = try {
            verify(context.applicationContext)
        } catch (e: Exception) {
            // could not read our own signature - treat that as our bug, not as proof of tampering
            true
        }
    }

    private fun verify(context: Context): Boolean {
        if (context.packageName != EXPECTED_PACKAGE) return false
        val pm = context.packageManager
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return false
            // the app has never used key rotation, so more than one *current* signer is already a bad sign
            if (si.hasMultipleSigners()) return false
            val history = si.signingCertificateHistory ?: return false
            history.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = info.signatures ?: return false
            sigs.map { it.toByteArray() }
        }
        if (certs.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        return certs.any { bytesToHex(digest.digest(it)) == EXPECTED_CERT_SHA256 }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
