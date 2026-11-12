package dev.altayeb.hudhud.vault

data class CsvImport(val entries: List<VaultEntry>, val skippedRows: Int)

object Csv {
    private val TITLE = listOf("name", "title")
    private val URL = listOf("url", "login_uri", "website")
    private val USER = listOf("username", "login_username", "user", "email")
    private val PASS = listOf("password", "login_password")
    private val NOTE = listOf("note", "notes")
    private val GROUP = listOf("folder", "group")
    private val TOTP = listOf("login_totp", "otpauth")
    private val ANDROID = Regex("^android://[^@/]*@([^/]+)/?$", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var pending = false // something on this row, so the end of input closes it
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> { quoted = true; pending = true }
                ',' -> { row.add(cell.toString()); cell.clear(); pending = true }
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList(); pending = false
                }
                else -> { cell.append(c); pending = true }
            }
            i++
        }
        if (pending) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun import(text: String): CsvImport {
        val rows = parse(text).filterNot { it.size == 1 && it[0].isBlank() }
        require(rows.isNotEmpty()) { "empty file" }
        val head = rows[0].map { it.trim().lowercase() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } } ?: -1
        val pass = col(PASS)
        require(pass >= 0) { "no password column" }
        val title = col(TITLE); val url = col(URL); val user = col(USER)
        val note = col(NOTE); val group = col(GROUP); val totp = col(TOTP)
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<VaultEntry>()
        var skipped = 0
        for (r in rows.drop(1)) {
            if (r.size < head.size) { skipped++; continue }
            fun get(i: Int) = if (i >= 0) r[i] else ""
            val pw = get(pass); val un = get(user)
            if (pw.isEmpty() && un.isEmpty()) { skipped++; continue }
            var u = get(url).trim()
            var t = get(title)
            ANDROID.find(u)?.let { m -> u = ""; if (t.isEmpty()) t = m.groupValues[1] }
            out.add(VaultEntry(title = t, username = un, password = pw, url = u, notes = get(note), group = get(group),
                totp = get(totp), modified = now, passwordChanged = now))
        }
        return CsvImport(out, skipped)
    }

    fun exportGoogle(entries: List<VaultEntry>): String {
        val o = StringBuilder("name,url,username,password,note\r\n")
        for (e in entries) {
            o.append(q(safe(e.title))).append(',').append(q(e.url)).append(',').append(q(e.username)).append(',')
                .append(q(e.password)).append(',').append(q(safe(e.notes))).append("\r\n")
        }
        return o.toString()
    }

    private fun safe(s: String) = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
    private fun q(s: String) = if (s.any { it in ",\"\r\n" }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
