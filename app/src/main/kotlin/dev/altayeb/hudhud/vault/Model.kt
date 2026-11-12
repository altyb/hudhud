package dev.altayeb.hudhud.vault

import java.util.UUID

data class VaultEntry(
    val uuid: UUID = UUID.randomUUID(),
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp: String = "",
    val group: String = "",
    val apps: List<AppLink> = emptyList(),
    val fields: Map<String, String> = emptyMap(),
    val modified: Long = 0L,
    val passwordChanged: Long = 0L,
)

/** An Android app allowed to fill an entry. [certSha256] is lowercase hex without separators. */
data class AppLink(val pkg: String, val certSha256: String)

data class Vault(val name: String = "Hudhud", val entries: List<VaultEntry> = emptyList())

class BadPasswordException : Exception()
class CorruptVaultException(msg: String) : Exception(msg)
