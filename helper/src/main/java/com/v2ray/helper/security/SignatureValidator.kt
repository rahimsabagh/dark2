package com.v2ray.helper.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log

interface SignatureChecker {
    fun getPackagesForUid(uid: Int): Array<String>?
    fun checkSignatures(uid1: Int, uid2: Int): Int
}

class DefaultSignatureChecker(private val packageManager: PackageManager) : SignatureChecker {
    override fun getPackagesForUid(uid: Int): Array<String>? = packageManager.getPackagesForUid(uid)
    override fun checkSignatures(uid1: Int, uid2: Int): Int = packageManager.checkSignatures(uid1, uid2)
}

/**
 * Validates that inter-process communication (IPC) callers are authorized.
 * Enforces:
 * 1. Caller UID package verification (authorized client package: com.v2ray.ang).
 * 2. Cryptographic signature verification ensuring the caller is signed with the identical developer certificate.
 */
object SignatureValidator {
    private const val TAG = "HelperSecurity"
    const val AUTHORIZED_CLIENT_PACKAGE = "com.v2ray.ang"

    /**
     * Verifies that the caller associated with [callingUid] is authorized.
     * Throws [SecurityException] if authorization fails.
     */
    fun verifyCaller(
        context: Context?,
        callingUid: Int,
        checker: SignatureChecker? = null
    ) {
        // Allow internal calls from own process
        if (callingUid == Process.myUid()) {
            return
        }

        val effectiveChecker = checker ?: (context?.packageManager?.let { DefaultSignatureChecker(it) }
            ?: throw IllegalStateException("Context required when checker is not provided"))

        val callingPackages = effectiveChecker.getPackagesForUid(callingUid)

        if (callingPackages.isNullOrEmpty()) {
            Log.e(TAG, "Security check failed: No packages found for UID $callingUid")
            throw SecurityException("Security check failed: Unknown caller UID $callingUid")
        }

        // Verify package identity
        val isAuthorizedPackage = callingPackages.contains(AUTHORIZED_CLIENT_PACKAGE)
        if (!isAuthorizedPackage) {
            Log.e(TAG, "Security check failed: UID $callingUid (${callingPackages.joinToString()}) is not $AUTHORIZED_CLIENT_PACKAGE")
            throw SecurityException("Caller package is not authorized: ${callingPackages.joinToString()}")
        }

        // Verify cryptographic signing certificate match
        val signatureMatch = effectiveChecker.checkSignatures(callingUid, Process.myUid())
        if (signatureMatch != PackageManager.SIGNATURE_MATCH) {
            Log.e(TAG, "Security check failed: Signature mismatch between caller UID $callingUid and Helper UID ${Process.myUid()} (result=$signatureMatch)")
            throw SecurityException("Caller signature does not match Helper signing key. Result=$signatureMatch")
        }

        Log.d(TAG, "Security check passed for UID $callingUid ($AUTHORIZED_CLIENT_PACKAGE)")
    }
}
