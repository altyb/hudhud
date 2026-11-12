package dev.altayeb.hudhud.vault

import org.bouncycastle.asn1.sec.SECNamedCurves
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class PasskeyRecord(
    val credentialId: ByteArray, val rpId: String, val userHandle: ByteArray, val username: String, val privateKeyPem: String,
)

/** WebAuthn ES256 credential stored in the vault, with KeePassXC's field names so the file stays portable. */
object Passkey {
    private const val CRED = "KPEX_PASSKEY_CREDENTIAL_ID"
    private const val KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
    private const val RP = "KPEX_PASSKEY_RELYING_PARTY"
    private const val USER = "KPEX_PASSKEY_USERNAME"
    private const val HANDLE = "KPEX_PASSKEY_USER_HANDLE"
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val unurl = Base64.getUrlDecoder()

    fun generate(rpId: String, userHandle: ByteArray, username: String): PasskeyRecord {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(g.generateKeyPair().private.encoded)
        val id = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PasskeyRecord(id, rpId, userHandle, username, "-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n")
    }

    fun toFields(p: PasskeyRecord): Map<String, String> = mapOf(
        CRED to url.encodeToString(p.credentialId), KEY to p.privateKeyPem, RP to p.rpId,
        USER to p.username, HANDLE to url.encodeToString(p.userHandle),
    )

    fun fromFields(f: Map<String, String>): PasskeyRecord? = runCatching {
        PasskeyRecord(unurl.decode(f.getValue(CRED)), f.getValue(RP), unurl.decode(f.getValue(HANDLE)), f[USER].orEmpty(), f.getValue(KEY))
    }.getOrNull()

    fun fromFieldsList(entries: List<VaultEntry>, rpId: String): List<Pair<VaultEntry, PasskeyRecord>> =
        entries.mapNotNull { e -> fromFields(e.fields)?.takeIf { it.rpId == rpId }?.let { e to it } }

    fun authData(rpId: String, userVerified: Boolean, attested: PasskeyRecord? = null): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray()))
        var flags = 0x01 or 0x08 or 0x10
        if (userVerified) flags = flags or 0x04
        if (attested != null) flags = flags or 0x40
        o.write(flags)
        o.write(ByteArray(4))
        if (attested != null) {
            o.write(ByteArray(16))
            val id = attested.credentialId
            o.write(id.size ushr 8); o.write(id.size)
            o.write(id)
            o.write(coseKey(attested))
        }
        return o.toByteArray()
    }

    fun attestationObject(p: PasskeyRecord, userVerified: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xA3)
        text(o, "fmt"); text(o, "none")
        text(o, "attStmt"); o.write(0xA0)
        text(o, "authData"); bytes(o, authData(p.rpId, userVerified, p))
        return o.toByteArray()
    }

    fun sign(p: PasskeyRecord, authData: ByteArray, clientDataHash: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(p)); update(authData); update(clientDataHash); sign()
        }

    fun publicKeyX509(p: PasskeyRecord): ByteArray {
        val k = privateKey(p)
        val (x, y) = point(k)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), k.params)).encoded
    }

    private fun privateKey(p: PasskeyRecord): ECPrivateKey {
        val body = p.privateKeyPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as ECPrivateKey
    }

    private fun point(k: ECPrivateKey): Pair<BigInteger, BigInteger> {
        val q = SECNamedCurves.getByName("secp256r1").g.multiply(k.s).normalize()
        return q.affineXCoord.toBigInteger() to q.affineYCoord.toBigInteger()
    }

    private fun coseKey(p: PasskeyRecord): ByteArray {
        val (x, y) = point(privateKey(p))
        val o = ByteArrayOutputStream()
        o.write(0xA5)
        o.write(0x01); o.write(0x02)                 // kty: EC2
        o.write(0x03); o.write(0x26)                 // alg: -7
        o.write(0x20); o.write(0x01)                 // crv: P-256
        o.write(0x21); bytes(o, fixed32(x))
        o.write(0x22); bytes(o, fixed32(y))
        return o.toByteArray()
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(b.size, 32)
        System.arraycopy(b, b.size - n, out, 32 - n, n)
        return out
    }

    private fun head(o: ByteArrayOutputStream, major: Int, n: Int) {
        when {
            n < 24 -> o.write((major shl 5) or n)
            n < 256 -> { o.write((major shl 5) or 24); o.write(n) }
            else -> { o.write((major shl 5) or 25); o.write(n ushr 8); o.write(n) }
        }
    }
    private fun text(o: ByteArrayOutputStream, s: String) { val b = s.toByteArray(); head(o, 3, b.size); o.write(b) }
    private fun bytes(o: ByteArrayOutputStream, b: ByteArray) { head(o, 2, b.size); o.write(b) }
}
