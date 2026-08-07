package com.opensync.foldersync.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** A ready-to-send set of content URIs plus the MIME type that describes them. */
data class ShareBundle(
    val uris: List<Uri>,
    val mime: String,
    /** Used as the subject / clip label — usually the file name when sharing a single item. */
    val subject: String? = null
)

/**
 * Hands files, media and note text to the phone's normal share sheet (ACTION_SEND /
 * ACTION_SEND_MULTIPLE inside a chooser), so every app that can receive the content shows up —
 * messaging, mail, cloud drives, Bluetooth, Nearby Share, printing, and so on.
 *
 * Everything leaves the app as a `content://` URI from our FileProvider (or a MediaStore URI for
 * device photos), never a `file://` path, so receivers can actually read what they are handed.
 */
object ShareUtil {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Android's MIME table doesn't know Markdown or most config/log extensions, and typing those
     * as octet-stream hides text-friendly apps from the share sheet — so fill the gaps here.
     */
    private val TEXT_EXTS = setOf(
        "md", "markdown", "log", "yml", "yaml", "ini", "conf", "properties", "csv"
    )

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in TEXT_EXTS) "text/plain" else "application/octet-stream"
    }

    /** The narrowest type that covers everything: an exact type, then "image/*", else "*/*". */
    fun commonMime(mimes: List<String>): String {
        val distinct = mimes.distinct()
        return when {
            distinct.isEmpty() -> "*/*"
            distinct.size == 1 -> distinct.first()
            distinct.map { it.substringBefore('/') }.distinct().size == 1 ->
                distinct.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
    }

    fun bundleOfFiles(context: Context, files: List<File>): ShareBundle {
        val usable = files.filter { it.isFile }
        return ShareBundle(
            uris = usable.map { uriFor(context, it) },
            mime = commonMime(usable.map { mimeOf(it.name) }),
            subject = usable.singleOrNull()?.name
        )
    }

    /** Share local files (they must exist on disk — download remote ones first). */
    fun shareFiles(context: Context, files: List<File>) = share(context, bundleOfFiles(context, files))

    fun share(context: Context, bundle: ShareBundle) {
        if (bundle.uris.isEmpty()) {
            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (bundle.uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, bundle.uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(bundle.uris))
        }
        intent.type = bundle.mime
        bundle.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        // The ClipData carries the read grant to every receiver — some apps read the clip rather
        // than EXTRA_STREAM, and multi-item sends need it for the grant to cover all the URIs.
        intent.clipData = ClipData
            .newUri(context.contentResolver, bundle.subject ?: "Shared", bundle.uris.first())
            .apply { bundle.uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchChooser(context, intent)
    }

    /** Share note contents as plain text (what messaging / note apps expect). */
    fun shareText(context: Context, text: String, subject: String? = null) {
        if (text.isBlank()) {
            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        launchChooser(context, intent)
    }

    private fun launchChooser(context: Context, intent: Intent) {
        try {
            context.startActivity(
                Intent.createChooser(intent, "Share")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            // Includes TransactionTooLarge on a huge text share, and "no app can handle this".
            Toast.makeText(context, "Couldn't open the share sheet", Toast.LENGTH_SHORT).show()
        }
    }
}
