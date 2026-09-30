package rida.pour.les.pros.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import rida.pour.les.pros.data.repo.MailDraft
import java.io.File

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private fun fileUri(context: Context, path: String): Uri? {
    val f = File(path)
    if (!f.exists()) return null
    return FileProvider.getUriForFile(context, context.packageName + ".files", f)
}

/**
 * Ouvre le client mail de l'utilisateur, prérempli (destinataires, copie, objet, corps, fichier joint).
 * L'utilisateur n'a plus qu'à appuyer sur Envoyer. Renvoie un message d'erreur éventuel.
 */
fun openMail(context: Context, draft: MailDraft, cc: List<String>, attachmentPath: String?): String? {
    val uri = attachmentPath?.let { fileUri(context, it) }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (uri != null) XLSX_MIME else "text/plain"
        putExtra(Intent.EXTRA_EMAIL, draft.to.toTypedArray())
        if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())
        putExtra(Intent.EXTRA_SUBJECT, draft.subject)
        putExtra(Intent.EXTRA_TEXT, draft.body)
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "Envoyer l'extrait RIDA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "Aucune application de messagerie trouvée."
    }
}

/** Ouvre le fichier joint (Excel, Sheets…). */
fun openFile(context: Context, path: String): String? {
    val uri = fileUri(context, path) ?: return "Fichier introuvable (il a peut-être été purgé)."
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, XLSX_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "Aucune application ne sait ouvrir un fichier Excel sur ce téléphone."
    }
}
