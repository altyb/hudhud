package dev.altayeb.hudhud.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldsTest {

    private fun classify(
        hints: List<String> = emptyList(), inputType: Int = 0, htmlType: String? = null,
        idEntry: String? = null, hint: String? = null,
    ) = Fields.classify(hints, inputType, htmlType, idEntry, hint)

    // ---- password ----

    @Test fun autofillHintPasswordIsPassword() {
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("password")))
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("current-password")))
        assertEquals(FieldKind.PASSWORD, classify(hints = listOf("new-password")))
    }

    @Test fun htmlTypePasswordIsPassword() {
        assertEquals(FieldKind.PASSWORD, classify(htmlType = "password"))
        assertEquals(FieldKind.PASSWORD, classify(htmlType = "PASSWORD"))
    }

    @Test fun passwordInputTypesArePassword() {
        assertEquals(FieldKind.PASSWORD, classify(inputType = 0x81))  // text + password
        assertEquals(FieldKind.PASSWORD, classify(inputType = 0x91))  // text + visible password
        assertEquals(FieldKind.PASSWORD, classify(inputType = 0xE1))  // text + web password
        assertEquals(FieldKind.PASSWORD, classify(inputType = 0x12))  // number + password
    }

    @Test fun idEntryUserPasswordIsPassword() {
        assertEquals(FieldKind.PASSWORD, classify(idEntry = "user_password"))
    }

    @Test fun arabicPasswordHintIsPassword() {
        assertEquals(FieldKind.PASSWORD, classify(hint = "كلمة المرور"))
    }

    // ---- username ----

    @Test fun usernameHintsAreUsername() {
        assertEquals(FieldKind.USERNAME, classify(hints = listOf("username")))
        assertEquals(FieldKind.USERNAME, classify(hints = listOf("emailAddress")))
    }

    @Test fun emailInputTypeIsUsername() {
        assertEquals(FieldKind.USERNAME, classify(inputType = 0x21))  // text + email
    }

    @Test fun idEntryLoginNameIsUsername() {
        assertEquals(FieldKind.USERNAME, classify(idEntry = "login_name"))
    }

    // ---- other ----

    @Test fun searchAndOtpIdsAreOther() {
        assertEquals(FieldKind.OTHER, classify(idEntry = "search_box"))
        assertEquals(FieldKind.OTHER, classify(idEntry = "otp_code"))
    }

    @Test fun plainTextWithNoSignalIsOther() {
        assertEquals(FieldKind.OTHER, classify(inputType = 0x01))
    }
}
