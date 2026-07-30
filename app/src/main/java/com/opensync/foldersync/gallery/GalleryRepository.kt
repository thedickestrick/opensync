package com.opensync.foldersync.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.opensync.foldersync.files.Clipboard
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.files.ExplorerRepository
import com.opensync.foldersync.provider.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Provides gallery data from the device MediaStore and from folder-based providers. */
class GalleryRepository(private val context: Context) {

    private val explorer = ExplorerRepository(context)

    // ---- Device (MediaStore) ----

    private data class Row(
        val id: Long, val name: String, val bucketId: String, val bucketName: String,
        val date: Long, val uri: Uri, val isVideo: Boolean, val path: String, val size: Long
    )

    suspend fun deviceAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val rows = queryDevice(null)
        rows.groupBy { it.bucketId }
            .map { (bucketId, items) ->
                val sorted = items.sortedByDescending { it.date }
                val dir = sorted.firstOrNull { it.path.isNotEmpty() }
                    ?.let { File(it.path).parent?.trim('/') } ?: ""
                Album(
                    id = bucketId,
                    name = sorted.first().bucketName,
                    count = sorted.size,
                    coverModel = sorted.first().uri,
                    latestDate = sorted.first().date,
                    directory = dir
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun deviceMedia(bucketId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        queryDevice(bucketId)
            .sortedByDescending { it.date }
            .map { row ->
                // Carry the real file (path/size/date from the MediaStore row, no extra stat) so the
                // gallery's file controls (copy/cut/delete/rename) work on device photos too.
                val rf = row.path.takeIf { it.isNotEmpty() }?.let {
                    RemoteFile(
                        relPath = it.trimStart('/'),
                        name = row.name,
                        isDirectory = false,
                        size = row.size,
                        modifiedTime = row.date * 1000L
                    )
                }
                MediaItem(
                    key = row.uri.toString(),
                    name = row.name,
                    isVideo = row.isVideo,
                    deviceUri = row.uri,
                    remoteFile = rf,
                    thumbModel = row.uri
                )
            }
    }

    private fun queryDevice(bucketId: String?): List<Row> {
        val rows = ArrayList<Row>()
        rows += queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, bucketId)
        rows += queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, bucketId)
        return rows
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean, bucketId: String?): List<Row> {
        val hasBuckets = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            @Suppress("DEPRECATION")
            add(MediaStore.MediaColumns.DATA)
            if (hasBuckets) {
                add(MediaStore.MediaColumns.BUCKET_ID)
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            }
        }.toTypedArray()

        val selection = if (hasBuckets && bucketId != null) "${MediaStore.MediaColumns.BUCKET_ID} = ?" else null
        val args = if (selection != null) arrayOf(bucketId) else null
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val out = ArrayList<Row>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val bIdCol = if (hasBuckets) c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID) else -1
            val bNameCol = if (hasBuckets) c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val bId = if (bIdCol >= 0 && !c.isNull(bIdCol)) c.getString(bIdCol) else "0"
                val bName = if (bNameCol >= 0 && !c.isNull(bNameCol)) c.getString(bNameCol) else "Device"
                val filePath = if (dataCol >= 0 && !c.isNull(dataCol)) c.getString(dataCol) else ""
                val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
                out += Row(
                    id = id,
                    name = c.getString(nameCol) ?: "",
                    bucketId = bId,
                    bucketName = bName,
                    date = c.getLong(dateCol),
                    uri = ContentUris.withAppendedId(collection, id),
                    isVideo = isVideo,
                    path = filePath,
                    size = size
                )
            }
        }
        return out
    }

    // ---- Provider (folder-based) ----

    suspend fun setProviderLocation(location: ExplorerLocation) = explorer.setLocation(location)

    /** Returns (subfolders, media) for [relDir] on the current provider location. */
    suspend fun listFolder(relDir: String): Pair<List<RemoteFile>, List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val local = explorer.location == ExplorerLocation.LocalRoot
            val entries = explorer.list(relDir)
            val folders = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val media = entries
                .filter { !it.isDirectory && isMediaName(it.name) }
                .sortedByDescending { it.modifiedTime }
                .map { rf ->
                    MediaItem(
                        key = rf.relPath,
                        name = rf.name,
                        isVideo = isVideoName(rf.name),
                        remoteFile = rf,
                        thumbModel = if (local) File("/", rf.relPath) else null
                    )
                }
            folders to media
        }

    suspend fun materialize(item: MediaItem): File {
        val rf = item.remoteFile ?: throw IllegalStateException("Not a provider item")
        return explorer.materialize(rf)
    }

    // ---- File operations (folder-based sources) — same engine as the Files explorer ----

    val providerLocation: ExplorerLocation get() = explorer.location

    suspend fun createFolder(parentRel: String, name: String) = explorer.createFolder(parentRel, name)

    suspend fun rename(item: RemoteFile, newName: String) = explorer.rename(item, newName)

    suspend fun delete(items: List<RemoteFile>) = explorer.delete(items)

    suspend fun paste(
        clip: Clipboard,
        destDir: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean
    ) = explorer.paste(clip, destDir, onProgress, isCancelled)

    fun release() = explorer.release()
}
