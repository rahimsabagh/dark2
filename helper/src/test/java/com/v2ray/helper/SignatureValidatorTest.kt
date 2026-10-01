package com.v2ray.helper

import android.content.pm.PackageManager
import android.os.Process
import com.v2ray.helper.security.SignatureChecker
import com.v2ray.helper.security.SignatureValidator
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSignatureChecker(
    var packagesByUid: Map<Int, Array<String>> = emptyMap(),
    var signatureMatchResult: Int = PackageManager.SIGNATURE_MATCH
) : SignatureChecker {
    override fun getPackagesForUid(uid: Int): Array<String>? = packagesByUid[uid]
    override fun checkSignatures(uid1: Int, uid2: Int): Int = signatureMatchResult
}

class SignatureValidatorTest {

    @Test
    fun testSameProcessAllowed() {
        val fakeChecker = FakeSignatureChecker()
        // Own process UID must always pass
        SignatureValidator.verifyCaller(null as android.content.Context?, Process.myUid(), fakeChecker)
    }

    @Test
    fun testUnauthorizedPackageRejected() {
        val foreignUid = 99999
        val fakeChecker = FakeSignatureChecker(
            packagesByUid = mapOf(foreignUid to arrayOf("com.malicious.attacker"))
        )

        val exception = assertThrows(SecurityException::class.java) {
            SignatureValidator.verifyCaller(null as android.content.Context?, foreignUid, fakeChecker)
        }
        assertTrue(exception.message!!.contains("not authorized"))
    }

    @Test
    fun testUnknownUidRejected() {
        val unknownUid = 88888
        val fakeChecker = FakeSignatureChecker(packagesByUid = emptyMap())

        val exception = assertThrows(SecurityException::class.java) {
            SignatureValidator.verifyCaller(null as android.content.Context?, unknownUid, fakeChecker)
        }
        assertTrue(exception.message!!.contains("Unknown caller UID"))
    }

    @Test
    fun testSignatureMismatchRejected() {
        val clientUid = 10123
        val fakeChecker = FakeSignatureChecker(
            packagesByUid = mapOf(clientUid to arrayOf(SignatureValidator.AUTHORIZED_CLIENT_PACKAGE)),
            signatureMatchResult = -3 // PackageManager.SIGNATURE_NO_MATCH
        )

        val exception = assertThrows(SecurityException::class.java) {
            SignatureValidator.verifyCaller(null as android.content.Context?, clientUid, fakeChecker)
        }
        assertTrue(exception.message!!.contains("Caller signature does not match"))
    }

    @Test
    fun testAuthorizedClientWithMatchingSignatureAllowed() {
        val clientUid = 10123
        val fakeChecker = FakeSignatureChecker(
            packagesByUid = mapOf(clientUid to arrayOf(SignatureValidator.AUTHORIZED_CLIENT_PACKAGE)),
            signatureMatchResult = PackageManager.SIGNATURE_MATCH
        )

        // Authorized package with matching certificate must pass cleanly
        SignatureValidator.verifyCaller(null as android.content.Context?, clientUid, fakeChecker)
    }
}
