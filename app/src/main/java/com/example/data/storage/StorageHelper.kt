package com.example.data.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Everything to do with the user-chosen download folder (Storage Access Framework):
 * remembering it, creating files in it, and opening/sharing/deleting saved files.
 */
class StorageHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences("media_downloader_prefs", Context.MODE_PRIVATE)

    private val _folderUri = MutableStateFlow(readSavedFolderUri())
    val folderUri: StateFlow<Uri?> = _folderUri.asStateFlow()

    private val _folderName = MutableStateFlow(readSavedFolderName())
    val folderName: StateFlow<String> = _folderName.asStateFlow()

    companion object {
        private const val KEY_FOLDER_URI = "saved_folder_tree_uri"
        private const val KEY_FOLDER_NAME = "saved_folder_name"
        private const val NO_FOLDER = "No folder selected"

        fun cleanFileName(name: String): String =
            name.replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_").trim().trim('.')

        fun getMimeType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    // region Download folder

    fun getSavedFolderUri(): Uri? = _folderUri.value

    private fun readSavedFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            // Make sure we still have permission (the user may have revoked it).
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPermission) uri else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readSavedFolderName(): String =
        prefs.getString(KEY_FOLDER_NAME, NO_FOLDER) ?: NO_FOLDER

    fun saveFolderUri(uri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            val doc = DocumentFile.fromTreeUri(context, uri)
            val displayName = doc?.name ?: uri.lastPathSegment ?: "Selected Folder"

            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
                putString(KEY_FOLDER_NAME, displayName)
            }
            _folderUri.value = uri
            _folderName.value = displayName
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // endregion

    // region Writing files

    /** Creates a new file in the download folder (inside [subFolderName] if given). */
    fun createFile(subFolderName: String?, fileName: String): DocumentFile? {
        val treeUri = getSavedFolderUri() ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!rootDoc.canWrite()) return null

        val targetDir = if (!subFolderName.isNullOrBlank()) {
            val cleanSub = cleanFileName(subFolderName)
            rootDoc.findFile(cleanSub)?.takeIf { it.isDirectory }
                ?: rootDoc.createDirectory(cleanSub)
                ?: rootDoc
        } else {
            rootDoc
        }

        val cleanedName = cleanFileName(fileName).ifBlank { "download" }
        // If a file with the same name exists the system adds " (1)" instead of overwriting it.
        return targetDir.createFile(getMimeType(cleanedName), cleanedName)
    }

    /** Re-opens a file created earlier, used to resume a paused download. */
    fun existingFile(uriString: String?): DocumentFile? {
        if (uriString.isNullOrBlank()) return null
        return try {
            DocumentFile.fromSingleUri(context, Uri.parse(uriString))?.takeIf { it.exists() }
        } catch (_: Exception) {
            null
        }
    }

    fun openOutputStream(file: DocumentFile, append: Boolean): OutputStream {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openOutputStream(file.uri, if (append) "wa" else "wt")
        } catch (_: Exception) {
            // Some providers do not support "wt"; fall back to plain write.
            resolver.openOutputStream(file.uri, if (append) "wa" else "w")
        }
        return stream ?: throw IOException("Unable to write to ${file.name}")
    }

    /** Copies a finished file (e.g. from yt-dlp's work folder) into the download folder. */
    fun copyIntoFolder(
        source: File,
        subFolderName: String?,
        onProgress: (copied: Long, total: Long) -> Unit
    ): DocumentFile {
        val target = createFile(subFolderName, source.name)
            ?: throw IOException("Unable to create ${source.name} in the download folder")
        val total = source.length()
        var copied = 0L
        var lastReport = 0L
        openOutputStream(target, append = false).use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 300) {
                        onProgress(copied, total)
                        lastReport = now
                    }
                }
                out.flush()
            }
        }
        onProgress(total, total)
        return target
    }

    fun deleteFile(uriString: String?): Boolean = existingFile(uriString)?.delete() ?: false

    // endregion

    // region Using saved files

    fun openFile(uriString: String, fileName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), getMimeType(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startSafely(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(uriString: String, fileName: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(fileName)
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startSafely(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startSafely(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // endregion
}
