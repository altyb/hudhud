package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvTest {

    private val googleHeader = "name,url,username,password,note"

    // ---- parse: RFC 4180 ----

    @Test fun parsesPlainRows() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a,b\n1,2"))
    }

    @Test fun quotedFieldKeepsCommas() {
        assertEquals(listOf(listOf("a,b", "c")), Csv.parse("\"a,b\",c"))
    }

    @Test fun doubledQuoteIsOneQuote() {
        assertEquals(listOf(listOf("say \"hi\"", "x")), Csv.parse("\"say \"\"hi\"\"\",x"))
    }

    @Test fun newlineInsideQuotesStaysInField() {
        assertEquals(listOf(listOf("l1\nl2", "x"), listOf("y", "z")), Csv.parse("\"l1\nl2\",x\ny,z"))
    }

    @Test fun crlfInsideQuotesIsKept() {
        assertEquals(listOf(listOf("x\r\ny")), Csv.parse("\"x\r\ny\""))
    }

    @Test fun crlfLineEndings() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a,b\r\n1,2\r\n"))
    }

    @Test fun leadingBomIsIgnored() {
        assertEquals(listOf(listOf("name", "url")), Csv.parse("﻿name,url"))
    }

    @Test fun emptyFieldsAreEmptyStrings() {
        assertEquals(listOf(listOf("a", "", "c")), Csv.parse("a,,c"))
    }

    @Test fun trailingNewlineDoesNotAddRow() {
        assertEquals(listOf(listOf("a")), Csv.parse("a\n"))
    }

    // ---- import: header decides the columns ----

    @Test fun googleImportMapsEveryColumn() {
        val r = Csv.import("$googleHeader\nGitHub,https://github.com,me,pw1,hello\n")
        assertEquals(0, r.skippedRows)
        assertEquals(1, r.entries.size)
        val e = r.entries[0]
        assertEquals("GitHub", e.title)
        assertEquals("https://github.com", e.url)
        assertEquals("me", e.username)
        assertEquals("pw1", e.password)
        assertEquals("hello", e.notes)
        assertEquals("", e.group)
        assertEquals("", e.totp)
    }

    @Test fun bitwardenImportMapsFolderAndTotp() {
        val text = "name,login_uri,login_username,login_password,notes,folder,login_totp\n" +
            "Mail,https://mail.example,u@x.com,pw,note,Work,otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP\n"
        val e = Csv.import(text).entries.single()
        assertEquals("Mail", e.title)
        assertEquals("https://mail.example", e.url)
        assertEquals("u@x.com", e.username)
        assertEquals("pw", e.password)
        assertEquals("note", e.notes)
        assertEquals("Work", e.group)
        assertEquals("otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP", e.totp)
    }

    @Test fun headersAreCaseInsensitive() {
        val e = Csv.import("NAME,URL,USERNAME,PASSWORD,NOTE\nA,https://a,u,p,n\n").entries.single()
        assertEquals("A", e.title)
        assertEquals("https://a", e.url)
        assertEquals("u", e.username)
        assertEquals("p", e.password)
        assertEquals("n", e.notes)
    }

    @Test fun alternateHeaderNamesAreAccepted() {
        val e = Csv.import("title,website,email,password,otpauth,group\n" +
            "T,https://w,mail@x,pw,otpauth://totp/t?secret=JBSWY3DPEHPK3PXP,Dir\n").entries.single()
        assertEquals("T", e.title)
        assertEquals("https://w", e.url)
        assertEquals("mail@x", e.username)
        assertEquals("pw", e.password)
        assertEquals("otpauth://totp/t?secret=JBSWY3DPEHPK3PXP", e.totp)
        assertEquals("Dir", e.group)
    }

    @Test fun userHeaderIsUsername() {
        val e = Csv.import("name,user,password\nA,bob,pw\n").entries.single()
        assertEquals("bob", e.username)
    }

    @Test fun rowWithEmptyPasswordAndEmptyUsernameIsSkipped() {
        val r = Csv.import("$googleHeader\nGood,,me,pw,\nSkip,,,,\n")
        assertEquals(1, r.entries.size)
        assertEquals("Good", r.entries[0].title)
        assertEquals(1, r.skippedRows)
    }

    @Test fun rowWithOnlyOneOfUsernameOrPasswordIsKept() {
        val r = Csv.import("$googleHeader\nNoPw,,me,,\nNoUser,,,secret,\n")
        assertEquals(2, r.entries.size)
        assertEquals(0, r.skippedRows)
        assertEquals("", r.entries[0].password)
        assertEquals("", r.entries[1].username)
    }

    @Test fun rowWithFewerColumnsThanHeaderIsSkipped() {
        val r = Csv.import("$googleHeader\nShort,https://x\nGood,,me,pw,\nAlsoShort,,me\n")
        assertEquals(1, r.entries.size)
        assertEquals("Good", r.entries[0].title)
        assertEquals(2, r.skippedRows)
    }

    @Test fun skippedRowsCountBothKinds() {
        val r = Csv.import("$googleHeader\n,,,,\nA,b\n,,,,\n")
        assertEquals(0, r.entries.size)
        assertEquals(3, r.skippedRows)
    }

    @Test fun headerOnlyGivesNoEntries() {
        val r = Csv.import("$googleHeader\n")
        assertEquals(0, r.entries.size)
        assertEquals(0, r.skippedRows)
    }

    @Test fun googleAndroidUrlWithoutNameBecomesPackageTitle() {
        val e = Csv.import("$googleHeader\n,android://Abc123==@com.example.app/,me,pw,\n").entries.single()
        assertEquals("com.example.app", e.title)
        assertEquals("", e.url)
        assertEquals(emptyList<AppLink>(), e.apps)
    }

    @Test fun googleAndroidUrlKeepsNameAsTitle() {
        val e = Csv.import("$googleHeader\nExample App,android://Abc123==@com.example.app/,me,pw,\n").entries.single()
        assertEquals("Example App", e.title)
        assertEquals("", e.url)
        assertEquals(emptyList<AppLink>(), e.apps)
    }

    @Test fun ordinaryUrlIsKept() {
        val e = Csv.import("$googleHeader\nA,https://a.example/login,u,p,\n").entries.single()
        assertEquals("https://a.example/login", e.url)
    }

    @Test fun quotedNoteWithCommaAndNewlineImports() {
        val e = Csv.import("$googleHeader\nA,,u,p,\"line1, line2\nline3\"\n").entries.single()
        assertEquals("line1, line2\nline3", e.notes)
    }

    @Test fun bomAndCrlfHeaderImports() {
        val r = Csv.import("﻿$googleHeader\r\nA,https://a,u,p,\r\n")
        assertEquals("A", r.entries.single().title)
    }

    @Test fun noPasswordColumnThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            Csv.import("name,url,username\nA,b,c\n")
        }
    }

    @Test fun emptyInputThrows() {
        assertThrows(IllegalArgumentException::class.java) { Csv.import("") }
    }

    // ---- exportGoogle ----

    private fun entry(
        title: String = "GitHub", url: String = "https://github.com", user: String = "me",
        pw: String = "pw", note: String = "hello",
    ) = VaultEntry(title = title, url = url, username = user, password = pw, notes = note)

    private fun exportedRows(vararg es: VaultEntry) = Csv.parse(Csv.exportGoogle(es.toList()))

    @Test fun exportHeaderIsExact() {
        assertEquals(googleHeader, Csv.exportGoogle(emptyList()).lines().first())
        assertEquals(googleHeader, Csv.exportGoogle(listOf(entry())).lines().first())
    }

    @Test fun exportWritesGoogleColumnsInOrder() {
        val rows = exportedRows(entry())
        assertEquals(listOf(googleHeader.split(",")), listOf(rows[0]))
        assertEquals(listOf("GitHub", "https://github.com", "me", "pw", "hello"), rows[1])
    }

    @Test fun exportRoundTripsSpecialCharactersInPassword() {
        val pw = "a,b\"c\nd=e"
        val rows = exportedRows(entry(pw = pw))
        assertEquals(pw, rows[1][3])
    }

    @Test fun formulaTriggersAreNeutralisedInNameOnly() {
        for (p in listOf("=", "+", "-", "@")) {
            val rows = exportedRows(entry(title = "${p}cmd(1)"))
            assertEquals("'${p}cmd(1)", rows[1][0])
        }
    }

    @Test fun formulaTriggersAreNeutralisedInNoteOnly() {
        for (p in listOf("=", "+", "-", "@")) {
            val rows = exportedRows(entry(note = "${p}SUM(A1)"))
            assertEquals("'${p}SUM(A1)", rows[1][4])
        }
    }

    @Test fun passwordStartingWithFormulaTriggerIsUntouched() {
        for (p in listOf("=secret", "+123", "-x", "@abc")) {
            val rows = exportedRows(entry(pw = p))
            assertEquals(p, rows[1][3])
        }
    }

    @Test fun usernameAndUrlStartingWithFormulaTriggerAreUntouched() {
        val rows = exportedRows(entry(url = "=HYPERLINK(1)", user = "=me"))
        assertEquals("=HYPERLINK(1)", rows[1][1])
        assertEquals("=me", rows[1][2])
    }

    @Test fun plainNameAndNoteAreUnchanged() {
        val rows = exportedRows(entry(title = "Plain Name", note = "Plain note"))
        assertEquals("Plain Name", rows[1][0])
        assertEquals("Plain note", rows[1][4])
    }

    @Test fun exportOfEntryWithoutNoteKeepsEmptyNote() {
        val rows = exportedRows(entry(note = ""))
        assertEquals("", rows[1][4])
    }

    @Test fun exportThenImportKeepsCredentials() {
        val src = entry(title = "Bank", url = "https://bank.example", user = "ali", pw = "p,w\"1", note = "n")
        val back = Csv.import(Csv.exportGoogle(listOf(src))).entries.single()
        assertEquals(src.title, back.title)
        assertEquals(src.url, back.url)
        assertEquals(src.username, back.username)
        assertEquals(src.password, back.password)
        assertEquals("n", back.notes)
    }
}
