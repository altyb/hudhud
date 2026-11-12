package qa.login

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Stand-in for a real app: a login form for autofill, and a relying party that checks passkey signatures. Logs under tag QA. */
class Main : ComponentActivity() {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val un = Base64.getUrlDecoder()
    private var publicKey: ByteArray? = null
    private lateinit var user: EditText
    private lateinit var pass: EditText

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        user = EditText(this).apply { hint = "username"; setAutofillHints("username"); contentDescription = "user"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        pass = EditText(this).apply { hint = "password"; setAutofillHints("password"); contentDescription = "pass"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(user); root.addView(pass)
        root.addView(Button(this).apply { text = "SHOW"; setOnClickListener { Log.i("QA", "FORM user=" + user.text + " pass=" + pass.text) } })
        root.addView(Button(this).apply { text = "COPYPLAIN"; isFocusable = false; setOnClickListener {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("x", "qa-plain-copy"))
        } })
        root.addView(Button(this).apply { text = "COPYSECRET"; isFocusable = false; setOnClickListener {
            val clip = android.content.ClipData.newPlainText("x", "qa-secret-copy")
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        } })
        root.addView(Button(this).apply { text = "SUBMIT"; setOnClickListener { getSystemService(AutofillManager::class.java).commit(); Log.i("QA", "SUBMIT"); finish() } })
        root.addView(Button(this).apply { text = "REGISTER"; setOnClickListener { register() } })
        root.addView(Button(this).apply { text = "ASSERT"; setOnClickListener { assertion() } })
        setContentView(root)
    }

    private fun register() = lifecycleScope.launch {
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val json = JSONObject("""{"rp":{"id":"qa.example.com","name":"QA"},"user":{"id":"${b64.encodeToString("uid1".toByteArray())}","name":"qa-user","displayName":"QA"},
            "challenge":"${b64.encodeToString(challenge)}","pubKeyCredParams":[{"type":"public-key","alg":-7}],"authenticatorSelection":{"residentKey":"required","userVerification":"required"}}""").toString()
        runCatching {
            val r = CredentialManager.create(this@Main).createCredential(this@Main, CreatePublicKeyCredentialRequest(json)) as CreatePublicKeyCredentialResponse
            val resp = JSONObject(r.registrationResponseJson).getJSONObject("response")
            val client = JSONObject(String(un.decode(resp.getString("clientDataJSON"))))
            check(client.getString("type") == "webauthn.create" && client.getString("challenge") == b64.encodeToString(challenge)) { "client data $client" }
            val auth = un.decode(resp.getString("authenticatorData"))
            check(auth.copyOf(32).contentEquals(MessageDigest.getInstance("SHA-256").digest("qa.example.com".toByteArray()))) { "rp hash" }
            check(auth[32].toInt() and 0x45 == 0x45) { "flags ${auth[32]}" }
            publicKey = un.decode(resp.getString("publicKey"))
            getSharedPreferences("q", 0).edit().putString("pk", resp.getString("publicKey")).apply()
            Log.i("QA", "REGISTER OK origin=" + client.getString("origin").take(24))
        }.onFailure { Log.e("QA", "REGISTER FAIL $it") }
    }

    private fun assertion() = lifecycleScope.launch {
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val json = """{"rpId":"qa.example.com","challenge":"${b64.encodeToString(challenge)}","userVerification":"required"}"""
        runCatching {
            val r = CredentialManager.create(this@Main).getCredential(this@Main, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(json))))
            val c = r.credential as PublicKeyCredential
            val resp = JSONObject(c.authenticationResponseJson).getJSONObject("response")
            val clientRaw = un.decode(resp.getString("clientDataJSON"))
            val client = JSONObject(String(clientRaw))
            check(client.getString("challenge") == b64.encodeToString(challenge)) { "challenge" }
            val auth = un.decode(resp.getString("authenticatorData"))
            val pk = publicKey ?: un.decode(getSharedPreferences("q", 0).getString("pk", ""))
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pk))
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(auth); update(MessageDigest.getInstance("SHA-256").digest(clientRaw)); verify(un.decode(resp.getString("signature")))
            }
            Log.i("QA", "ASSERT signature=$ok user=" + String(un.decode(resp.getString("userHandle"))))
        }.onFailure { Log.e("QA", "ASSERT FAIL $it") }
    }
}
