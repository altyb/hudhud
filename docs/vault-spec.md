# Password vault: core API and rules

Pure Kotlin in package `dev.altayeb.hudhud.vault`, no Android imports in these files, so JVM unit tests cover them.
The password manager has no network access. The file format is standard KDBX 4.x, so any KeePass app can open an export.

## Model.kt

```kotlin
data class VaultEntry(
    val uuid: java.util.UUID = java.util.UUID.randomUUID(),
    val title: String = "", val username: String = "", val password: String = "",
    val url: String = "", val notes: String = "", val totp: String = "",   // otpauth URI or bare base32
    val group: String = "",                 // "" = root, "Work/Mail" = nested
    val apps: List<AppLink> = emptyList(),  // Android apps allowed to fill this entry
    val fields: Map<String, String> = emptyMap(), // other KDBX custom strings, kept as they are
    val modified: Long = 0L,                // epoch seconds
    val passwordChanged: Long = 0L,         // epoch seconds
)
data class AppLink(val pkg: String, val certSha256: String)  // lowercase hex without separators
data class Vault(val name: String = "Hudhud", val entries: List<VaultEntry> = emptyList())
class BadPasswordException : Exception()    // wrong password, or header HMAC failed
class CorruptVaultException(msg: String) : Exception(msg) // anything else wrong with the file
```

## Kdbx.kt

```kotlin
data class KdfParams(val memoryBytes: Long = 64L shl 20, val iterations: Long = 3, val parallelism: Int = 2)
object Kdbx {
    fun write(v: Vault, password: CharArray, kdf: KdfParams = KdfParams()): ByteArray
    fun read(file: ByteArray, password: CharArray): Vault
}
```

- Writes KDBX 4.0: outer cipher AES-256-CBC, KDF Argon2id, gzip, inner stream ChaCha20, with fresh random seeds and IVs on every write.
- Reads KDBX 4.0 and 4.1 with outer cipher AES-256-CBC or ChaCha20, KDF Argon2id or Argon2d, gzip or none. KDBX 3 or AES-KDF: `CorruptVaultException`.
- Verifies the header SHA-256 and header HMAC before decrypting, and every HMAC block. Wrong password: `BadPasswordException`. Any changed byte, truncation or garbage: `BadPasswordException` or `CorruptVaultException`, never another exception type, never partial data.
- KDF limits on read, to stop a hostile file using up the phone: memory at most 1 GiB, iterations at most 100, parallelism at most 16, block at most 64 MiB, file at most 64 MiB. Above: `CorruptVaultException`.
- XML: external entities and DOCTYPE are refused.
- KDBX string keys: `Title`, `UserName`, `Password`, `URL`, `Notes`, `otp`. `Password` and `otp` are written Protected.
- `AppLink`s are stored in custom string `HudhudApps` as `pkg:cert,pkg:cert`. Other custom strings go to `fields`, unchanged.
- Times are KDBX 4 base64 of little-endian seconds since 0001-01-01. `passwordChanged` is stored in custom string `HudhudPasswordChanged`.
- Text with Arabic, emoji and XML characters (`<>&"'`) round-trips exactly.

## Totp.kt

```kotlin
data class TotpConfig(val secret: ByteArray, val digits: Int = 6, val period: Int = 30, val algorithm: String = "SHA1")
object Totp {
    fun parse(text: String): TotpConfig?       // otpauth://totp/... or bare base32 (spaces, lowercase, no padding ok). Bad input: null. hotp: null
    fun code(c: TotpConfig, epochSeconds: Long): String // RFC 6238, zero-padded. SHA1, SHA256, SHA512
    fun secondsLeft(c: TotpConfig, epochSeconds: Long): Int
}
```

`parse` refuses digits outside 6..8, period outside 1..300, and an empty secret.

## Csv.kt

```kotlin
data class CsvImport(val entries: List<VaultEntry>, val skippedRows: Int)
object Csv {
    fun parse(text: String): List<List<String>>  // RFC 4180: quotes, "" escape, commas and newlines in quotes, CRLF, leading BOM
    fun import(text: String): CsvImport          // header decides the columns; throws IllegalArgumentException if no password column
    fun exportGoogle(entries: List<VaultEntry>): String // header exactly name,url,username,password,note
}
```

- Headers are case-insensitive. Google: `name,url,username,password,note`. Bitwarden: `name,login_uri,login_username,login_password,notes,folder,login_totp`. Also accepted: `title`, `website`, `user`, `email`, `otpauth`, `group`.
- A row with empty password and empty username is skipped and counted. A row with fewer columns than the header is skipped and counted.
- Google `android://<hash>@<pkg>/` URLs: `url` stays empty, `title` becomes `pkg` when name is empty. `AppLink` is not created, because the certificate is not known.
- `exportGoogle` neutralises spreadsheet formulas only in `name` and `note`: a value starting with `=`, `+`, `-` or `@` gets a leading `'`. `password`, `username` and `url` are never changed.

## Generator.kt

```kotlin
data class GenOptions(val length: Int = 20, val lower: Boolean = true, val upper: Boolean = true,
    val digits: Boolean = true, val symbols: Boolean = true, val noLookalikes: Boolean = false)
object Generator {
    fun password(o: GenOptions = GenOptions(), r: java.security.SecureRandom = java.security.SecureRandom()): String
    fun passphrase(words: List<String>, count: Int = 6, sep: String = "-", r: java.security.SecureRandom = java.security.SecureRandom()): String
    fun entropyBits(password: String): Double  // length * log2(size of character classes used); 0 for empty
}
```

`password` includes at least one character of each enabled class, has no modulo bias, and throws `IllegalArgumentException` when no class is enabled or length is below the number of enabled classes or above 256. `noLookalikes` removes `O0oIl1|`. `passphrase` throws `IllegalArgumentException` for count below 1 or an empty list.

## Audit.kt

```kotlin
enum class Issue { WEAK, REUSED, OLD, NO_PASSWORD }
data class Finding(val entry: VaultEntry, val issues: Set<Issue>)
object Audit { fun run(entries: List<VaultEntry>, nowEpoch: Long): List<Finding> } // entries with no issue are left out
```

WEAK: `Generator.entropyBits` below 50. REUSED: the same non-empty password on another entry, exact match. OLD: `passwordChanged` above 0 and over 365 days before now. NO_PASSWORD: empty password.

## Match.kt

```kotlin
object Match {
    fun host(url: String): String?  // lowercase host; "example.com/x" and "https://Example.com:8443/x" both give example.com; bad input null
    fun domainMatches(saved: String, visited: String): Boolean
    fun forWeb(entries: List<VaultEntry>, visitedHost: String): List<VaultEntry>
    fun forApp(entries: List<VaultEntry>, pkg: String, certs: Set<String>): List<VaultEntry>
}
```

- `domainMatches`: true for the same host, or when `visited` is a subdomain of `saved` (saved `example.com`, visited `login.example.com`). False the other way round. False for lookalikes: `example.com.evil.net`, `notexample.com`, `example.com@evil.net` (`host` gives `evil.net`). A saved value that is a single label (`com`) or an IP address matches only itself. Case does not matter; a trailing dot is ignored.
- `forApp` needs an `AppLink` with the same `pkg` and a `certSha256` found in `certs`. The same package with another certificate gets nothing: that is the anti-phishing rule. A package name never matches by `url` or `title`.

## Passkey.kt

```kotlin
data class PasskeyRecord(val credentialId: ByteArray, val rpId: String, val userHandle: ByteArray, val username: String, val privateKeyPem: String)
object Passkey {
    fun generate(rpId: String, userHandle: ByteArray, username: String): PasskeyRecord  // ES256, 32-byte random credential id
    fun toFields(p: PasskeyRecord): Map<String, String>  // KeePassXC names: KPEX_PASSKEY_CREDENTIAL_ID, _PRIVATE_KEY_PEM, _RELYING_PARTY, _USERNAME, _USER_HANDLE (base64url without padding)
    fun fromFields(f: Map<String, String>): PasskeyRecord?
    fun authData(rpId: String, userVerified: Boolean, attested: PasskeyRecord? = null): ByteArray
    fun attestationObject(p: PasskeyRecord, userVerified: Boolean = true): ByteArray // fmt "none", CBOR
    fun sign(p: PasskeyRecord, authData: ByteArray, clientDataHash: ByteArray): ByteArray // DER ECDSA SHA-256 over authData + clientDataHash
    fun publicKeyX509(p: PasskeyRecord): ByteArray
    fun fromFieldsList(entries: List<VaultEntry>, rpId: String): List<Pair<VaultEntry, PasskeyRecord>>
}
```

`authData`: SHA-256 of rpId (32 bytes), flags, 4-byte counter of zero. Flags: UP 0x01 always, UV 0x04 if verified, BE 0x08 and BS 0x10 always (the file can be copied, so it is a synced passkey), AT 0x40 when `attested` is given. With `attested`: 16 zero AAGUID bytes, 2-byte big-endian id length, id, then the COSE key (kty 2, alg -7, crv 1, x, y, each coordinate 32 bytes). `fromFieldsList` matches `rpId` exactly.
