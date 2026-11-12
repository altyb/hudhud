package dev.altayeb.hudhud.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class PasskeyTest {

    private val rp = "example.com"
    private val handle = byteArrayOf(1, 2, 3, 4)

    private fun gen(user: String = "alice") = Passkey.generate(rp, handle, user)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun pub(p: PasskeyRecord): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Passkey.publicKeyX509(p)))

    private fun verify(pk: PublicKey, data: ByteArray, sig: ByteArray): Boolean {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pk)
        s.update(data)
        return s.verify(sig)
    }

    // Minimal CBOR reader: enough for the attestation map {text: text | {} | bytes}.
    private class Cbor(private val b: ByteArray) {
        private var i = 0

        fun item(): Any {
            val head = b[i++].toInt() and 0xFF
            val major = head shr 5
            val info = head and 0x1F
            val n: Int = when (info) {
                in 0..23 -> info
                24 -> b[i++].toInt() and 0xFF
                25 -> {
                    val v = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                    i += 2
                    v
                }
                else -> error("unsupported CBOR info $info")
            }
            return when (major) {
                2 -> b.copyOfRange(i, i + n).also { i += n }
                3 -> String(b, i, n, Charsets.UTF_8).also { i += n }
                5 -> {
                    val m = LinkedHashMap<String, Any>()
                    repeat(n) {
                        val k = item() as String
                        m[k] = item()
                    }
                    m
                }
                else -> error("unsupported CBOR major $major")
            }
        }
    }

    // ---- generate / keys ----

    @Test fun generateKeepsRpUserHandleAndUsername() {
        val p = gen("alice")
        assertEquals(rp, p.rpId)
        assertArrayEquals(handle, p.userHandle)
        assertEquals("alice", p.username)
        assertEquals(32, p.credentialId.size)
        assertTrue(p.privateKeyPem.startsWith("-----BEGIN"))
        assertTrue(p.privateKeyPem.contains("PRIVATE KEY"))
    }

    @Test fun credentialIdsAreRandom() {
        val a = gen().credentialId
        val b = gen().credentialId
        assertFalse(a.contentEquals(b))
    }

    @Test fun publicKeyIsP256() {
        val key = pub(gen())
        assertTrue(key is ECPublicKey)
        assertEquals(256, (key as ECPublicKey).params.curve.field.fieldSize)
    }

    // ---- sign ----

    @Test fun signatureVerifiesOverAuthDataPlusClientDataHash() {
        val p = gen()
        val ad = Passkey.authData(rp, true)
        val cdh = sha256("client data json".toByteArray())
        val sig = Passkey.sign(p, ad, cdh)
        assertEquals(0x30, sig[0].toInt()) // DER SEQUENCE
        assertTrue(verify(pub(p), ad + cdh, sig))
    }

    @Test fun changedClientDataHashFailsVerification() {
        val p = gen()
        val ad = Passkey.authData(rp, true)
        val cdh = sha256("client data json".toByteArray())
        val sig = Passkey.sign(p, ad, cdh)
        val other = cdh.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(verify(pub(p), ad + other, sig))
    }

    @Test fun changedAuthDataFailsVerification() {
        val p = gen()
        val ad = Passkey.authData(rp, true)
        val cdh = sha256("client data json".toByteArray())
        val sig = Passkey.sign(p, ad, cdh)
        val other = ad.copyOf().also { it[36] = (it[36].toInt() xor 0x01).toByte() }
        assertFalse(verify(pub(p), other + cdh, sig))
    }

    @Test fun signatureFailsUnderAnotherKey() {
        val p = gen()
        val ad = Passkey.authData(rp, true)
        val cdh = sha256("x".toByteArray())
        val sig = Passkey.sign(p, ad, cdh)
        assertFalse(verify(pub(gen()), ad + cdh, sig))
    }

    // ---- authData layout ----

    @Test fun authDataWithoutAttestedIsThirtySevenBytesWithBeBsFlags() {
        val ad = Passkey.authData(rp, userVerified = false)
        assertEquals(37, ad.size)
        assertArrayEquals(sha256(rp.toByteArray()), ad.copyOfRange(0, 32))
        assertEquals(0x19, ad[32].toInt() and 0xFF) // UP 0x01 | BE 0x08 | BS 0x10
        assertArrayEquals(ByteArray(4), ad.copyOfRange(33, 37)) // counter zero
    }

    @Test fun userVerifiedAddsUvFlag() {
        val ad = Passkey.authData(rp, userVerified = true)
        assertEquals(37, ad.size)
        assertEquals(0x1D, ad[32].toInt() and 0xFF) // 0x01 | 0x04 | 0x08 | 0x10
        assertArrayEquals(ByteArray(4), ad.copyOfRange(33, 37))
    }

    @Test fun attestedAuthDataHasExactLayoutAndCoseKey() {
        val p = gen()
        val ad = Passkey.authData(rp, userVerified = true, attested = p)

        // 37 header + 16 AAGUID + 2 length + 32 id + 77 COSE key
        assertEquals(164, ad.size)
        assertArrayEquals(sha256(rp.toByteArray()), ad.copyOfRange(0, 32))
        assertEquals(0x5D, ad[32].toInt() and 0xFF) // AT 0x40 added to 0x1D
        assertArrayEquals(ByteArray(4), ad.copyOfRange(33, 37))
        assertArrayEquals(ByteArray(16), ad.copyOfRange(37, 53))
        assertEquals(0x00, ad[53].toInt())
        assertEquals(0x20, ad[54].toInt())
        assertArrayEquals(p.credentialId, ad.copyOfRange(55, 87))

        // COSE_Key, CTAP2 canonical order: {1: 2, 3: -7, -1: 1, -2: x, -3: y}
        val x509 = Passkey.publicKeyX509(p)
        val raw = x509.copyOfRange(x509.size - 65, x509.size)
        assertEquals(0x04, raw[0].toInt())
        val x = raw.copyOfRange(1, 33)
        val y = raw.copyOfRange(33, 65)
        val cose = byteArrayOf(
            0xA5.toByte(), 0x01, 0x02,           // map(5), kty = 2
            0x03, 0x26,                          // alg = -7
            0x20, 0x01,                          // crv = 1 (key -1)
            0x21, 0x58, 0x20,                    // x = bstr(32) (key -2)
        ) + x + byteArrayOf(0x22, 0x58, 0x20) + y // y = bstr(32) (key -3)
        assertArrayEquals(cose, ad.copyOfRange(87, 164))
    }

    @Test fun attestedWithoutUserVerificationFlags0x59() {
        val ad = Passkey.authData(rp, userVerified = false, attested = gen())
        assertEquals(0x59, ad[32].toInt() and 0xFF)
    }

    // ---- attestationObject ----

    @Test fun attestationObjectIsNoneFormatWithAuthData() {
        val p = gen()
        val obj = Passkey.attestationObject(p)
        @Suppress("UNCHECKED_CAST")
        val m = Cbor(obj).item() as Map<String, Any>
        assertEquals(setOf("fmt", "attStmt", "authData"), m.keys)
        assertEquals("none", m["fmt"])
        assertEquals(emptyMap<String, Any>(), m["attStmt"])
        // assumption: the registration authData carries attested credential data (AT set)
        assertArrayEquals(Passkey.authData(rp, true, p), m["authData"] as ByteArray)
    }

    // ---- fields ----

    @Test fun toFieldsUsesKeePassXcNamesAndBase64UrlNoPadding() {
        val p = gen("bob")
        val f = Passkey.toFields(p)
        assertEquals(
            setOf(
                "KPEX_PASSKEY_CREDENTIAL_ID", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "KPEX_PASSKEY_RELYING_PARTY",
                "KPEX_PASSKEY_USERNAME", "KPEX_PASSKEY_USER_HANDLE",
            ),
            f.keys,
        )
        assertEquals(b64u(p.credentialId), f["KPEX_PASSKEY_CREDENTIAL_ID"])
        assertEquals(b64u(handle), f["KPEX_PASSKEY_USER_HANDLE"])
        assertEquals(rp, f["KPEX_PASSKEY_RELYING_PARTY"])
        assertEquals("bob", f["KPEX_PASSKEY_USERNAME"])
        assertEquals(p.privateKeyPem, f["KPEX_PASSKEY_PRIVATE_KEY_PEM"])
        assertFalse(f["KPEX_PASSKEY_CREDENTIAL_ID"]!!.contains('='))
    }

    @Test fun fieldsRoundTripAndStillSign() {
        val p = gen("carol")
        val back = Passkey.fromFields(Passkey.toFields(p))
        assertNotNull(back)
        assertArrayEquals(p.credentialId, back!!.credentialId)
        assertEquals(p.rpId, back.rpId)
        assertArrayEquals(p.userHandle, back.userHandle)
        assertEquals(p.username, back.username)
        assertEquals(p.privateKeyPem, back.privateKeyPem)

        val ad = Passkey.authData(rp, true)
        val cdh = sha256("z".toByteArray())
        assertTrue(verify(pub(p), ad + cdh, Passkey.sign(back, ad, cdh)))
    }

    @Test fun fromFieldsIsNullWhenAFieldIsMissing() {
        val full = Passkey.toFields(gen())
        assertNull(Passkey.fromFields(full - "KPEX_PASSKEY_PRIVATE_KEY_PEM"))
        assertNull(Passkey.fromFields(full - "KPEX_PASSKEY_RELYING_PARTY"))
        assertNull(Passkey.fromFields(emptyMap()))
    }

    @Test fun fromFieldsListKeepsOnlyExactRpIdMatches() {
        val handleBytes = handle
        val exact = VaultEntry(title = "exact", fields = Passkey.toFields(Passkey.generate("example.com", handleBytes, "a")))
        val sub = VaultEntry(title = "sub", fields = Passkey.toFields(Passkey.generate("login.example.com", handleBytes, "b")))
        val upper = VaultEntry(title = "upper", fields = Passkey.toFields(Passkey.generate("Example.com", handleBytes, "c")))
        val broken = VaultEntry(
            title = "broken",
            fields = Passkey.toFields(Passkey.generate("example.com", handleBytes, "d")) - "KPEX_PASSKEY_PRIVATE_KEY_PEM",
        )
        val plain = VaultEntry(title = "plain", password = "pw")

        val res = Passkey.fromFieldsList(listOf(exact, sub, upper, broken, plain), "example.com")
        assertEquals(1, res.size)
        assertEquals(exact.uuid, res[0].first.uuid)
        assertEquals("a", res[0].second.username)
        assertEquals(rp, res[0].second.rpId)
    }
}
