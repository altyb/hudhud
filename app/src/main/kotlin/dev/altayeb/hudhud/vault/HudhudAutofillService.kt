package dev.altayeb.hudhud.vault

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.altayeb.hudhud.R

/** What a screen is asking for. A web domain is kept only when a verified browser reported it. */
class ParsedForm(val pkg: String, val domain: String?, val username: AutofillId?, val password: AutofillId?,
                 val usernameValue: String?, val passwordValue: String?)

object FormParser {
    fun parse(context: Context, s: AssistStructure): ParsedForm? {
        val pkg = s.activityComponent?.packageName ?: return null
        if (pkg == context.packageName) return null
        var user: AutofillId? = null; var pass: AutofillId? = null
        var userV: String? = null; var passV: String? = null
        var passDomain: String? = null; var userDomain: String? = null
        var lastText: AssistStructure.ViewNode? = null
        var lastBeforePass: AssistStructure.ViewNode? = null
        // Each field belongs to the frame it sits in. An iframe naming another site must not decide what the password field gets.
        fun walk(n: AssistStructure.ViewNode, inherited: String?) {
            val frame = n.webDomain?.takeIf { it.isNotEmpty() } ?: inherited
            val id = n.autofillId
            val textField = n.autofillType == android.view.View.AUTOFILL_TYPE_TEXT && id != null &&
                (n.className?.contains("EditText") == true || n.htmlInfo?.tag.equals("input", true) || n.autofillHints != null)
            if (textField) {
                val htmlType = n.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second
                when (Fields.classify(n.autofillHints?.toList().orEmpty(), n.inputType, htmlType, n.idEntry, n.hint)) {
                    FieldKind.PASSWORD -> if (pass == null) {
                        pass = id; passDomain = frame; passV = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                        lastBeforePass = lastText
                    }
                    FieldKind.USERNAME -> if (user == null) { user = id; userDomain = frame; userV = n.autofillValue?.takeIf { it.isText }?.textValue?.toString() }
                    FieldKind.OTHER -> if (!htmlType.equals("hidden", true) && !htmlType.equals("submit", true)) lastText = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), frame)
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, null)
        // A plain text box right above the password is the username on most login forms.
        if (user == null && pass != null) lastBeforePass?.let { n ->
            user = n.autofillId; userV = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()
        }
        if (user == null && pass == null) return null
        val domain = if (pass != null) passDomain else userDomain
        // A username box in a different frame from the password is not filled.
        if (pass != null && userDomain != null && userDomain != passDomain) { user = null; userV = null }
        val trusted = domain?.takeIf { AppIdentity.isTrustedBrowser(context, pkg) }
        return ParsedForm(pkg, trusted, user, pass, userV, passV)
    }
}

class HudhudAutofillService : AutofillService() {
    private val store by lazy { VaultStore(this) }

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val form = request.fillContexts.lastOrNull()?.structure?.let { FormParser.parse(this, it) }
        if (form == null || !store.exists()) { callback.onSuccess(null); return }
        val response = FillResponse.Builder()
        val specs = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty() else emptyList()
        var used = 0
        val session = VaultSession.vault
        val matches = when {
            session == null -> emptyList()
            form.domain != null -> Match.forWeb(session.entries, form.domain)
            AppIdentity.isBrowser(this, form.pkg) -> emptyList() // a browser whose page we cannot verify gets no direct offer
            else -> Match.forApp(session.entries, form.pkg, AppIdentity.certs(this, form.pkg))
        }
        for (e in matches.take(4)) {
            val b = Dataset.Builder(row(e.title.ifEmpty { e.username }, e.username))
            if (Build.VERSION.SDK_INT >= 30) specs.getOrNull(used)?.let { inline(it, e.title.ifEmpty { e.username }, e.username)?.let(b::setInlinePresentation) }
            FillActivity.values(b, form, e)
            response.addDataset(b.build())
            used++
        }
        // Always one way in: unlock when locked, or search the whole vault when nothing matched.
        val label = getString(if (session == null) R.string.vault_autofill_unlock else R.string.vault_autofill_search)
        val open = Dataset.Builder(row(label, getString(R.string.app_name)))
        if (Build.VERSION.SDK_INT >= 30) specs.getOrNull(used)?.let { inline(it, label, getString(R.string.app_name))?.let(open::setInlinePresentation) }
        form.username?.let { open.setValue(it, null) }
        form.password?.let { open.setValue(it, null) }
        open.setAuthentication(PendingIntent.getActivity(this, form.hashCode(), FillActivity.autofillIntent(this, form),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
        response.addDataset(open.build())

        val ids = listOfNotNull(form.username, form.password)
        if (form.password != null) response.setSaveInfo(
            SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0), ids.toTypedArray())
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build())
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val form = request.fillContexts.lastOrNull()?.structure?.let { FormParser.parse(this, it) }
        val pass = form?.passwordValue
        if (form == null || pass.isNullOrEmpty()) { callback.onSuccess(); return }
        val intent = Intent(this, SaveLoginActivity::class.java)
            .putExtra(SaveLoginActivity.PKG, form.pkg).putExtra(SaveLoginActivity.DOMAIN, form.domain)
            .putExtra(SaveLoginActivity.USER, form.usernameValue).putExtra(SaveLoginActivity.PASS, pass)
        if (Build.VERSION.SDK_INT >= 28) callback.onSuccess(
            PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE).intentSender)
        else callback.onSuccess()
    }

    private fun row(title: String, sub: String) = RemoteViews(packageName, R.layout.vault_autofill_row).apply {
        setTextViewText(R.id.vault_row_title, title)
        setTextViewText(R.id.vault_row_sub, sub)
    }

    private fun inline(spec: InlinePresentationSpec, title: String, sub: String): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30) return null
        val attribution = PendingIntent.getActivity(this, 2, Intent(this, VaultActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val slice = InlineSuggestionUi.newContentBuilder(attribution).setTitle(title).setSubtitle(sub)
            .setStartIcon(Icon.createWithResource(this, R.mipmap.ic_launcher)).build().slice
        return InlinePresentation(slice, spec, false)
    }
}
