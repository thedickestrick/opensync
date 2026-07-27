package com.opensync.foldersync.gallery

import android.net.Uri
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.provider.RemoteFile

/** Where the gallery draws media from. */
sealed interface GallerySource {
    data object Device : GallerySource
    data class Provider(val location: ExplorerLocation) : GallerySource
}

/** A group of media (a MediaStore bucket, or a folder). */
data class Album(
    val id: String,
    val name: String,
    val count: Int,
    val coverModel: Any?,
    val latestDate: Long = 0L,
    /** The album's directory relative to the device root "/" (empty if unknown). */
    val directory: String = ""
)

enum class AlbumSort(val label: String) {
    NAME("Name"),
    COUNT("Item count"),
    DATE("Most recent")
}

/** A single photo or video. [thumbModel] and full content are Coil-compatible (Uri or File). */
data class MediaItem(
    val key: String,
    val name: String,
    val isVideo: Boolean,
    val deviceUri: Uri? = null,
    val remoteFile: RemoteFile? = null,
    val thumbModel: Any? = null
)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")

fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
fun isMediaName(name: String): Boolean = isImageName(name) || isVideoName(name)
