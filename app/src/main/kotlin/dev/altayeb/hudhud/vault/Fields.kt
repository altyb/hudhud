package dev.altayeb.hudhud.vault

enum class FieldKind { USERNAME, PASSWORD, OTHER }

/** Decides what a form field is from what the app reports. No Android types, so it is unit-tested. */
object Fields {
    private const val CLASS_MASK = 0xF
    private const val VARIATION_MASK = 0xFF0
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2
    private val TEXT_PASSWORD = setOf(0x80, 0x90, 0xE0)   // password, visible password, web password
    private const val NUMBER_PASSWORD = 0x10
    private val TEXT_EMAIL = setOf(0x20, 0xD0)
    private val NOT_LOGIN = listOf("search", "captcha", "otp", "one-time", "2fa", "coupon", "promo")
    private val PASSWORD_WORDS = listOf("password", "passwd", "pass_word", "pwd", "كلمة السر", "كلمة المرور")
    private val USER_WORDS = listOf("username", "user_name", "userid", "user_id", "login", "email", "e-mail", "اسم المستخدم", "البريد")

    fun classify(hints: List<String>, inputType: Int, htmlType: String?, idEntry: String?, hint: String?): FieldKind {
        val h = hints.map { it.lowercase() }
        if (h.any { "password" in it && "new" !in it || it == "current-password" }) return FieldKind.PASSWORD
        if (h.any { it == "new-password" || "password" in it }) return FieldKind.PASSWORD
        if (h.any { it == "username" || it == "emailaddress" || it == "email" }) return FieldKind.USERNAME
        if (htmlType.equals("password", true)) return FieldKind.PASSWORD
        val cls = inputType and CLASS_MASK
        val variation = inputType and VARIATION_MASK
        if (cls == CLASS_TEXT && variation in TEXT_PASSWORD) return FieldKind.PASSWORD
        if (cls == CLASS_NUMBER && variation == NUMBER_PASSWORD) return FieldKind.PASSWORD
        if (htmlType.equals("email", true) || cls == CLASS_TEXT && variation in TEXT_EMAIL) return FieldKind.USERNAME
        val text = listOfNotNull(idEntry, hint).joinToString(" ").lowercase()
        if (NOT_LOGIN.any { it in text }) return FieldKind.OTHER
        if (PASSWORD_WORDS.any { it in text }) return FieldKind.PASSWORD
        if (USER_WORDS.any { it in text }) return FieldKind.USERNAME
        return FieldKind.OTHER
    }
}
