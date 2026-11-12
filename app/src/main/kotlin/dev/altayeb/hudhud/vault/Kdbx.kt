package dev.altayeb.hudhud.vault

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

data class KdfParams(val memoryBytes: Long = 64L shl 20, val iterations: Long = 3, val parallelism: Int = 2)

/** KDBX 4 reader and writer. Any failure while reading is one of the two vault exceptions, never partial data. */
object Kdbx {
    private const val SIG1 = 0x9AA2D903.toInt()
    private const val SIG2 = 0xB54BFB67.toInt()
    private const val MAX_FILE = 64 * 1024 * 1024
    private const val MAX_BLOCK = 64 * 1024 * 1024
    private const val MAX_XML = 256 * 1024 * 1024
    private const val MAX_KDF_MEMORY = 1L shl 30
    private const val MAX_KDF_ITER = 100L
    private const val MAX_KDF_PAR = 16L
    private const val WRITE_BLOCK = 1 shl 20
    private const val EPOCH_0001 = 62135596800L
    private const val APPS = "HudhudApps"
    private const val PW_CHANGED = "HudhudPasswordChanged"
    private val AES = hex("31c1f2e6bf714350be5805216afc5aff")
    private val CHACHA = hex("d6038a2b8b6f4cb5a524339a31dbb59a")
    private val ARGON2D = hex("ef636ddf8c29444b91f7a9a403e30a0c")
    private val ARGON2ID = hex("9e298b1956db4773b23dfc3ec6f0a1e6")
    private val STANDARD = setOf("Title", "UserName", "Password", "URL", "Notes", "otp", APPS, PW_CHANGED)
    private val rng = SecureRandom()

    fun write(v: Vault, password: CharArray, kdf: KdfParams = KdfParams()): ByteArray {
        val seed = random(32); val iv = random(16); val salt = random(32)
        val head = ByteArrayOutputStream()
        val le = Le(head)
        le.i32(SIG1); le.i32(SIG2); le.i32(0x00040000)
        fun field(id: Int, data: ByteArray) { head.write(id); le.i32(data.size); head.write(data) }
        field(2, AES)
        field(3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        field(4, seed)
        field(7, iv)
        field(11, kdfDict(salt, kdf))
        field(0, "\r\n\r\n".toByteArray())
        val header = head.toByteArray()

        val transformed = transform(composite(password), ARGON2ID, salt, kdf.memoryBytes, kdf.iterations, kdf.parallelism)
        val hmacBase = sha512(seed, transformed, byteArrayOf(1))
        val encKey = sha256(seed, transformed)
        transformed.fill(0)

        val streamKey = random(64)
        val inner = ByteArrayOutputStream()
        val ile = Le(inner)
        inner.write(1); ile.i32(4); ile.i32(3)
        inner.write(2); ile.i32(streamKey.size); inner.write(streamKey)
        inner.write(0); ile.i32(0)
        inner.write(toXml(v, InnerStream(streamKey), sha256(header)))
        streamKey.fill(0)

        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(inner.toByteArray()) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val body = c.doFinal(gz.toByteArray())
        encKey.fill(0)

        val out = ByteArrayOutputStream(body.size + 4096)
        out.write(header)
        out.write(sha256(header))
        out.write(headerHmac(header, hmacBase))
        var index = 0L
        var pos = 0
        while (pos < body.size) {
            val n = minOf(WRITE_BLOCK, body.size - pos)
            writeBlock(out, index++, body, pos, n, hmacBase)
            pos += n
        }
        writeBlock(out, index, body, 0, 0, hmacBase)
        hmacBase.fill(0)
        return out.toByteArray()
    }

    fun read(file: ByteArray, password: CharArray): Vault = try {
        parse(file, password)
    } catch (e: BadPasswordException) {
        throw e
    } catch (e: CorruptVaultException) {
        throw e
    } catch (e: Exception) {
        throw CorruptVaultException(e.javaClass.simpleName)
    } catch (e: OutOfMemoryError) {
        throw CorruptVaultException("too large")
    } catch (e: StackOverflowError) {
        throw CorruptVaultException("nested too deep")
    }

    private fun parse(file: ByteArray, password: CharArray): Vault {
        if (file.size > MAX_FILE) bad("file too large")
        val b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 12 || b.int != SIG1 || b.int != SIG2) bad("not a KeePass file")
        val version = b.int
        if (version ushr 16 != 4) bad("only KDBX 4 is supported")

        var cipher: ByteArray? = null; var compressed = false
        var seed: ByteArray? = null; var iv: ByteArray? = null; var dict: Map<String, Any>? = null
        while (true) {
            val id = b.get().toInt() and 0xFF
            val n = b.int
            if (n < 0 || n > b.remaining()) bad("bad header")
            val data = ByteArray(n).also { b.get(it) }
            when (id) {
                0 -> break
                2 -> cipher = data
                3 -> compressed = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int == 1
                4 -> seed = data
                7 -> iv = data
                11 -> dict = readDict(data)
            }
        }
        val headerLen = b.position()
        val header = file.copyOfRange(0, headerLen)
        if (b.remaining() < 64) bad("truncated")
        val hash = ByteArray(32).also { b.get(it) }
        val mac = ByteArray(32).also { b.get(it) }
        if (!MessageDigest.isEqual(hash, sha256(header))) bad("header damaged")

        val s = seed?.takeIf { it.size == 32 } ?: bad("bad seed")
        val nonce = iv ?: bad("no IV")
        val d = dict ?: bad("no KDF")
        val uuid = d["\$UUID"] as? ByteArray ?: bad("no KDF")
        if (!uuid.contentEquals(ARGON2ID) && !uuid.contentEquals(ARGON2D)) bad("unsupported KDF")
        val mem = (d["M"] as? Long) ?: bad("bad KDF")
        val iter = (d["I"] as? Long) ?: bad("bad KDF")
        val par = (d["P"] as? Long) ?: bad("bad KDF")
        val salt = d["S"] as? ByteArray ?: bad("bad KDF")
        if (mem !in 8192..MAX_KDF_MEMORY || iter !in 1..MAX_KDF_ITER || par !in 1..MAX_KDF_PAR) bad("KDF settings out of range")

        val transformed = transform(composite(password), uuid, salt, mem, iter, par.toInt())
        val hmacBase = sha512(s, transformed, byteArrayOf(1))
        val encKey = sha256(s, transformed)
        transformed.fill(0)
        if (!MessageDigest.isEqual(mac, headerHmac(header, hmacBase))) throw BadPasswordException()

        val body = ByteArrayOutputStream(b.remaining())
        var index = 0L
        while (true) {
            if (b.remaining() < 36) bad("truncated")
            val blockMac = ByteArray(32).also { b.get(it) }
            val n = b.int
            if (n < 0 || n > MAX_BLOCK || n > b.remaining()) bad("bad block")
            val data = ByteArray(n).also { b.get(it) }
            if (!MessageDigest.isEqual(blockMac, blockHmac(index++, data, 0, n, hmacBase))) bad("block damaged")
            if (n == 0) break
            body.write(data)
        }
        if (b.hasRemaining()) bad("data after the end")

        val plain = when {
            cipher.contentEquals(AES) -> Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(nonce)); doFinal(body.toByteArray())
            }
            cipher.contentEquals(CHACHA) -> body.toByteArray().also { chacha(encKey, nonce).processBytes(it, 0, it.size, it, 0) }
            else -> bad("unsupported cipher")
        }
        encKey.fill(0)
        val content = if (compressed) gunzip(plain) else plain

        val inner = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN)
        var streamId = 0; var streamKey: ByteArray? = null
        while (true) {
            val id = inner.get().toInt() and 0xFF
            val n = inner.int
            if (n < 0 || n > inner.remaining()) bad("bad inner header")
            val data = ByteArray(n).also { inner.get(it) }
            when (id) {
                0 -> break
                1 -> streamId = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
                2 -> streamKey = data
            }
        }
        if (streamId != 3) bad("unsupported inner stream")
        val xml = content.copyOfRange(inner.position(), content.size)
        val key = streamKey ?: bad("no stream key")
        return fromXml(xml, InnerStream(key))
    }

    // Keys

    private fun composite(password: CharArray): ByteArray {
        val buf = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        if (buf.hasArray()) buf.array().fill(0)
        val once = sha256(bytes)
        bytes.fill(0)
        return sha256(once).also { once.fill(0) }
    }

    private fun transform(composite: ByteArray, type: ByteArray, salt: ByteArray, mem: Long, iter: Long, par: Int): ByteArray {
        val p = Argon2Parameters.Builder(if (type.contentEquals(ARGON2D)) Argon2Parameters.ARGON2_d else Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt).withMemoryAsKB((mem / 1024).toInt()).withIterations(iter.toInt()).withParallelism(par).build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(p) }.generateBytes(composite, out)
        composite.fill(0)
        return out
    }

    private fun blockKey(index: Long, base: ByteArray) =
        sha512(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(index).array(), base)

    private fun headerHmac(header: ByteArray, base: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(blockKey(-1L, base), "HmacSHA256"))
        return m.doFinal(header)
    }

    private fun blockHmac(index: Long, data: ByteArray, off: Int, n: Int, base: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(blockKey(index, base), "HmacSHA256"))
        m.update(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(index).putInt(n).array())
        m.update(data, off, n)
        return m.doFinal()
    }

    private fun writeBlock(out: ByteArrayOutputStream, index: Long, data: ByteArray, off: Int, n: Int, base: ByteArray) {
        out.write(blockHmac(index, data, off, n, base))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array())
        out.write(data, off, n)
    }

    // Variant dictionary

    private fun kdfDict(salt: ByteArray, k: KdfParams): ByteArray {
        val o = ByteArrayOutputStream()
        val le = Le(o)
        o.write(0x00); o.write(0x01)
        fun item(type: Int, name: String, value: ByteArray) {
            o.write(type); le.i32(name.length); o.write(name.toByteArray()); le.i32(value.size); o.write(value)
        }
        fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        item(0x42, "\$UUID", ARGON2ID)
        item(0x42, "S", salt)
        item(0x04, "P", u32(k.parallelism))
        item(0x05, "M", u64(k.memoryBytes))
        item(0x05, "I", u64(k.iterations))
        item(0x04, "V", u32(0x13))
        o.write(0)
        return o.toByteArray()
    }

    private fun readDict(data: ByteArray): Map<String, Any> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (b.short.toInt() and 0xFF00 != 0x0100) bad("bad KDF dictionary")
        val out = HashMap<String, Any>()
        while (true) {
            val type = b.get().toInt() and 0xFF
            if (type == 0) return out
            val kn = b.int
            if (kn !in 0..256 || kn > b.remaining()) bad("bad KDF dictionary")
            val name = String(ByteArray(kn).also { b.get(it) })
            val vn = b.int
            if (vn < 0 || vn > b.remaining()) bad("bad KDF dictionary")
            val v = ByteArray(vn).also { b.get(it) }
            val w = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
            out[name] = when {
                type == 0x04 && vn == 4 -> w.int.toLong() and 0xFFFFFFFFL
                // A u64 above Long.MAX_VALUE reads negative and fails the range check.
                type == 0x05 && vn == 8 -> w.long
                else -> v
            }
        }
    }

    // XML

    private class InnerStream(key: ByteArray) {
        private val engine: ChaCha7539Engine
        init {
            val h = MessageDigest.getInstance("SHA-512").digest(key)
            engine = chacha(h.copyOfRange(0, 32), h.copyOfRange(32, 44))
            h.fill(0)
        }
        fun xor(b: ByteArray): ByteArray = ByteArray(b.size).also { engine.processBytes(b, 0, b.size, it, 0) }
    }

    private fun toXml(v: Vault, stream: InnerStream, headerHash: ByteArray): ByteArray {
        val x = StringBuilder(4096)
        x.append("""<?xml version="1.0" encoding="utf-8" standalone="yes"?>""").append("<KeePassFile><Meta><Generator>Hudhud</Generator>")
        x.append("<DatabaseName>").append(esc(v.name)).append("</DatabaseName></Meta><Root>")
        class Node(val name: String) { val kids = LinkedHashMap<String, Node>(); val entries = ArrayList<VaultEntry>() }
        val root = Node(v.name)
        for (e in v.entries) {
            var n = root
            for (part in e.group.split('/').filter { it.isNotEmpty() }) n = n.kids.getOrPut(part) { Node(part) }
            n.entries.add(e)
        }
        fun str(key: String, value: String, protect: Boolean) {
            x.append("<String><Key>").append(esc(key)).append("</Key>")
            if (protect) {
                x.append("""<Value Protected="True">""")
                x.append(Base64.getEncoder().encodeToString(stream.xor(value.toByteArray())))
            } else x.append("<Value>").append(esc(value))
            x.append("</Value></String>")
        }
        fun group(n: Node) {
            x.append("<Group><UUID>").append(uuidB64(UUID.randomUUID())).append("</UUID><Name>").append(esc(n.name)).append("</Name>")
            for (e in n.entries) {
                x.append("<Entry><UUID>").append(uuidB64(e.uuid)).append("</UUID><Times><LastModificationTime>")
                x.append(timeB64(e.modified)).append("</LastModificationTime></Times>")
                str("Title", e.title, false); str("UserName", e.username, false); str("Password", e.password, true)
                str("URL", e.url, false); str("Notes", e.notes, false)
                if (e.totp.isNotEmpty()) str("otp", e.totp, true)
                if (e.apps.isNotEmpty()) str(APPS, e.apps.joinToString(",") { it.pkg + ":" + it.certSha256 }, false)
                if (e.passwordChanged != 0L) str(PW_CHANGED, e.passwordChanged.toString(), false)
                for ((k, value) in e.fields) if (k !in STANDARD) str(k, value, true)
                x.append("</Entry>")
            }
            for (k in n.kids.values) group(k)
            x.append("</Group>")
        }
        group(root)
        x.append("</Root></KeePassFile>")
        headerHash.fill(0)
        return x.toString().toByteArray()
    }

    private fun fromXml(xml: ByteArray, stream: InnerStream): Vault {
        // Android's parser does not offer the secure-processing switches, so a DOCTYPE is refused by hand. NUL rules out UTF-16 tricks.
        val raw = String(xml, Charsets.ISO_8859_1)
        if ('\u0000' in raw || raw.contains("<!DOCTYPE", true) || raw.contains("<!ENTITY", true)) bad("DOCTYPE refused")
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val top = doc.documentElement
        if (top.tagName != "KeePassFile") bad("not a KeePass database")
        val meta = child(top, "Meta")
        val name = meta?.let { child(it, "DatabaseName")?.textContent } ?: "Hudhud"
        val recycleId = meta?.let { child(it, "RecycleBinUUID")?.textContent }.orEmpty()

        // The stream runs over every protected value in document order, History included, so read them all first.
        val decrypted = HashMap<Node, String>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    if (c.tagName == "Value" && c.getAttribute("Protected").equals("True", true)) {
                        decrypted[c] = String(stream.xor(Base64.getDecoder().decode(c.textContent.trim())))
                    }
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(top)

        val root = child(top, "Root") ?: bad("no root")
        val rootGroup = child(root, "Group") ?: bad("no root group")
        val out = ArrayList<VaultEntry>()
        fun read(g: Element, path: String) {
            for (c in children(g)) when (c.tagName) {
                "Entry" -> out.add(entry(c, path, decrypted))
                "Group" -> {
                    if (recycleId.isNotEmpty() && child(c, "UUID")?.textContent == recycleId) continue
                    val n = child(c, "Name")?.textContent.orEmpty()
                    read(c, if (path.isEmpty()) n else "$path/$n")
                }
            }
        }
        read(rootGroup, "")
        return Vault(name, out)
    }

    private fun entry(e: Element, group: String, decrypted: Map<Node, String>): VaultEntry {
        val strings = LinkedHashMap<String, String>()
        for (s in children(e)) if (s.tagName == "String") {
            val k = child(s, "Key")?.textContent ?: continue
            val v = child(s, "Value") ?: continue
            strings[k] = decrypted[v] ?: v.textContent
        }
        val id = child(e, "UUID")?.textContent?.trim().orEmpty().let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val uuid = if (id != null && id.size == 16) ByteBuffer.wrap(id).run { UUID(long, long) } else UUID.randomUUID()
        val modified = child(e, "Times")?.let { child(it, "LastModificationTime")?.textContent }?.let(::parseTime) ?: 0L
        return VaultEntry(
            uuid = uuid,
            title = strings["Title"].orEmpty(), username = strings["UserName"].orEmpty(),
            password = strings["Password"].orEmpty(), url = strings["URL"].orEmpty(),
            notes = strings["Notes"].orEmpty(), totp = strings["otp"].orEmpty(), group = group,
            apps = strings[APPS].orEmpty().split(',').mapNotNull { a ->
                val i = a.indexOf(':'); if (i <= 0) null else AppLink(a.substring(0, i), a.substring(i + 1))
            },
            fields = strings.filterKeys { it !in STANDARD },
            modified = modified, passwordChanged = strings[PW_CHANGED]?.toLongOrNull() ?: 0L,
        )
    }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) { if (c is Element) out.add(c); c = c.nextSibling }
        return out
    }
    private fun child(e: Element, tag: String): Element? = children(e).firstOrNull { it.tagName == tag }

    private fun esc(s: String): String {
        val o = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> o.append("&amp;")
                c == '<' -> o.append("&lt;")
                c == '>' -> o.append("&gt;")
                c == '"' -> o.append("&quot;")
                c == '\'' -> o.append("&apos;")
                c == '\r' -> o.append("&#13;")
                c == '\n' || c == '\t' -> o.append(c)
                c < ' ' || c == '￾' || c == '￿' -> Unit // not legal in XML 1.0
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { o.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> Unit
                else -> o.append(c)
            }
            i++
        }
        return o.toString()
    }

    private fun uuidB64(u: UUID) = Base64.getEncoder().encodeToString(
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array())

    private fun timeB64(epoch: Long) = Base64.getEncoder().encodeToString(
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(epoch + EPOCH_0001).array())

    private fun parseTime(s: String): Long = runCatching {
        val b = Base64.getDecoder().decode(s.trim())
        if (b.size == 8) ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long - EPOCH_0001
        else java.time.Instant.parse(s.trim()).epochSecond
    }.getOrElse { runCatching { java.time.Instant.parse(s.trim()).epochSecond }.getOrDefault(0L) }

    // Helpers

    private fun chacha(key: ByteArray, nonce: ByteArray) = ChaCha7539Engine().apply {
        init(true, ParametersWithIV(KeyParameter(key), nonce))
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(65536)
        GZIPInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val n = z.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                if (o.size() > MAX_XML) bad("database too large")
            }
        }
        return o.toByteArray()
    }

    private fun bad(msg: String): Nothing = throw CorruptVaultException(msg)
    private fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    private fun sha256(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
    private fun sha512(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-512").run { parts.forEach(::update); digest() }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Le(val o: ByteArrayOutputStream) {
        fun i32(v: Int) { o.write(v); o.write(v ushr 8); o.write(v ushr 16); o.write(v ushr 24) }
    }
}
