package dev.altayeb.hudhud.vault

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import dev.altayeb.hudhud.BrandDark
import dev.altayeb.hudhud.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

private val b64u = Base64.getUrlEncoder().withoutPadding()
private val unb64u = Base64.getUrlDecoder()

/** Android 14+ passkey provider. Lists only by what the caller asks; every use opens [PasskeyActivity], which demands a fresh unlock. */
@RequiresApi(34)
class PasskeyService : CredentialProviderService() {
    private var code = 100

    private fun open(action: String, extra: Intent.() -> Unit = {}): PendingIntent = PendingIntent.getActivity(
        this, code++, Intent(this, PasskeyActivity::class.java).setAction(action).apply(extra),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) { callback.onError(CreateCredentialUnknownException("only passkeys")); return }
        callback.onResult(BeginCreateCredentialResponse(listOf(
            CreateEntry.Builder(getString(R.string.app_name), open(PasskeyActivity.CREATE)).build(),
        )))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val store = VaultStore(this)
        if (!store.exists() || request.beginGetCredentialOptions.none { it is BeginGetPublicKeyCredentialOption }) {
            callback.onResult(BeginGetCredentialResponse()); return
        }
        if (!VaultSession.unlocked) {
            // Locked: nothing about the vault is known yet, so the only choice offered is to unlock.
            callback.onResult(BeginGetCredentialResponse.Builder().addAuthenticationAction(
                androidx.credentials.provider.AuthenticationAction(getString(R.string.vault_autofill_unlock), open(PasskeyActivity.UNLOCK)),
            ).build())
            return
        }
        callback.onResult(listPasskeys(request))
    }

    private fun listPasskeys(request: BeginGetCredentialRequest) = listEntries(this, request) { cred, o ->
        PublicKeyCredentialEntry.Builder(this, cred.username, open(PasskeyActivity.GET) {
            putExtra(PasskeyActivity.CRED, b64u.encodeToString(cred.credentialId))
        }, o).build()
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) = callback.onResult(null)
}

@RequiresApi(34)
private fun listEntries(
    context: android.content.Context, request: BeginGetCredentialRequest,
    make: (PasskeyRecord, BeginGetPublicKeyCredentialOption) -> androidx.credentials.provider.CredentialEntry,
): BeginGetCredentialResponse {
    val out = ArrayList<androidx.credentials.provider.CredentialEntry>()
    for (o in request.beginGetCredentialOptions) {
        if (o !is BeginGetPublicKeyCredentialOption) continue
        val rp = runCatching { JSONObject(o.requestJson).getString("rpId") }.getOrNull() ?: continue
        for ((_, p) in Passkey.fromFieldsList(VaultSession.vault?.entries.orEmpty(), rp)) out.add(make(p, o))
    }
    return BeginGetCredentialResponse(out)
}

@RequiresApi(34)
class PasskeyActivity : ComponentActivity() {
    private val store by lazy { VaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
                setContent {
            MaterialTheme(colorScheme = BrandDark) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    UnlockGate(store, forceAuth = true) {
                        var failed by remember { mutableStateOf<String?>(null) }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(Unit) {
                            scope.launch {
                                val r = withContext(Dispatchers.Default) { runCatching {
                                    when (intent.action) { CREATE -> finishCreate(); UNLOCK -> finishUnlock(); else -> finishGet() }
                                } }
                                r.onFailure { failed = it.message ?: it.javaClass.simpleName }.onSuccess { finish() }
                            }
                        }
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val f = failed
                            if (f == null) CircularProgressIndicator()
                            else {
                                Text(stringResource(R.string.vault_passkey_failed, f), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The website the browser says it is on, believed only for browsers on the signed list; an app gets its own certificate as origin. */
    private fun origin(info: CallingAppInfo): String {
        val web = runCatching { info.getOrigin(AppIdentity.allowlistJson(this)) }.getOrNull()
        if (web != null) return web.trimEnd('/')
        val cert = AppIdentity.certs(this, info.packageName).firstOrNull() ?: error("unknown caller")
        val raw = ByteArray(cert.length / 2) { cert.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return "android:apk-key-hash:" + b64u.encodeToString(raw)
    }

    /** A website may only use a passkey of its own domain or a parent of it. */
    private fun checkRp(origin: String, rp: String) {
        if (origin.startsWith("android:")) return // the relying party's server checks this origin against its asset links
        check(origin.startsWith("https://") || origin.startsWith("http://localhost")) { "insecure origin" }
        check(Match.domainMatches(rp, origin)) { "site does not own this passkey" }
    }

    private fun clientData(type: String, challenge: String, origin: String) =
        JSONObject().put("type", type).put("challenge", challenge).put("origin", origin).put("crossOrigin", false).toString().toByteArray()

    private fun finishCreate() {
        val req = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: error("no request")
        val option = req.callingRequest as CreatePublicKeyCredentialRequest
        val json = JSONObject(option.requestJson)
        val rp = json.getJSONObject("rp").getString("id")
        val user = json.getJSONObject("user")
        val params = json.getJSONArray("pubKeyCredParams")
        check((0 until params.length()).any { params.getJSONObject(it).optInt("alg") == -7 }) { "ES256 not allowed by this site" }
        val origin = origin(req.callingAppInfo)
        checkRp(origin, rp)
        val existing = Passkey.fromFieldsList(VaultSession.vault?.entries.orEmpty(), rp).map { b64u.encodeToString(it.second.credentialId) }
        val exclude = json.optJSONArray("excludeCredentials") ?: JSONArray()
        check((0 until exclude.length()).none { exclude.getJSONObject(it).getString("id") in existing }) { "already registered" }

        val username = user.optString("name")
        val p = Passkey.generate(rp, unb64u.decode(user.getString("id")), username)
        val data = if (option.clientDataHash != null) null else clientData("webauthn.create", json.getString("challenge"), origin)
        val now = nowEpoch()
        VaultSession.update(this, store) { v ->
            v.copy(entries = v.entries + VaultEntry(title = rp, username = username, url = "https://$rp", fields = Passkey.toFields(p), modified = now))
        }
        val id = b64u.encodeToString(p.credentialId)
        val att = Passkey.attestationObject(p)
        val out = JSONObject().put("id", id).put("rawId", id).put("type", "public-key").put("authenticatorAttachment", "platform")
            .put("response", JSONObject()
                .put("clientDataJSON", data?.let(b64u::encodeToString) ?: "")
                .put("attestationObject", b64u.encodeToString(att))
                .put("authenticatorData", b64u.encodeToString(Passkey.authData(rp, true, p)))
                .put("publicKey", b64u.encodeToString(Passkey.publicKeyX509(p)))
                .put("publicKeyAlgorithm", -7)
                .put("transports", JSONArray(listOf("internal", "hybrid"))))
            .put("clientExtensionResults", JSONObject().put("credProps", JSONObject().put("rk", true)))
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(out.toString()))
        setResult(RESULT_OK, result)
    }

    /** Answers the "unlock" choice: the same list the service would give, now that the vault is open. */
    private fun finishUnlock() {
        val req = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent) ?: error("no request")
        var code = 500
        val response = listEntries(this, req) { cred, o ->
            PublicKeyCredentialEntry.Builder(this, cred.username, PendingIntent.getActivity(this, code++,
                Intent(this, PasskeyActivity::class.java).setAction(GET).putExtra(CRED, b64u.encodeToString(cred.credentialId)),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), o).build()
        }
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, response)
        setResult(RESULT_OK, result)
    }

    private fun finishGet() {
        val req = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: error("no request")
        val option = req.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: error("no passkey option")
        val json = JSONObject(option.requestJson)
        val rp = json.getString("rpId")
        val origin = origin(req.callingAppInfo)
        checkRp(origin, rp)
        val mine = Passkey.fromFieldsList(VaultSession.vault?.entries.orEmpty(), rp).map { it.second }
        val wanted = intent.getStringExtra(CRED)
        val allow = json.optJSONArray("allowCredentials")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.orEmpty()
        val p = mine.firstOrNull { c ->
            val id = b64u.encodeToString(c.credentialId)
            (wanted == null || id == wanted) && (allow.isEmpty() || id in allow)
        } ?: error("no passkey for $rp")
        val auth = Passkey.authData(rp, true)
        val given = option.clientDataHash
        val data = if (given == null) clientData("webauthn.get", json.getString("challenge"), origin) else null
        val hash = given ?: MessageDigest.getInstance("SHA-256").digest(data!!)
        val sig = Passkey.sign(p, auth, hash)
        val id = b64u.encodeToString(p.credentialId)
        val out = JSONObject().put("id", id).put("rawId", id).put("type", "public-key").put("authenticatorAttachment", "platform")
            .put("response", JSONObject()
                .put("clientDataJSON", data?.let(b64u::encodeToString) ?: "")
                .put("authenticatorData", b64u.encodeToString(auth))
                .put("signature", b64u.encodeToString(sig))
                .put("userHandle", b64u.encodeToString(p.userHandle)))
            .put("clientExtensionResults", JSONObject())
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(out.toString())))
        setResult(RESULT_OK, result)
    }

    companion object {
        const val CREATE = "dev.altayeb.hudhud.passkey.CREATE"
        const val GET = "dev.altayeb.hudhud.passkey.GET"
        const val UNLOCK = "dev.altayeb.hudhud.passkey.UNLOCK"
        const val CRED = "cred"
    }
}
