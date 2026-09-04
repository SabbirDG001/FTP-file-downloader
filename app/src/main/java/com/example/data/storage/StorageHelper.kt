package com.example.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.util.Locale

class StorageHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences("media_downloader_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FOLDER_URI = "saved_folder_tree_uri"
        private const val KEY_FOLDER_NAME = "saved_folder_name"

        fun cleanFileName(name: String): String {
            return name.replace(Regex("""[<>:"/\\|?*]"""), "_").trim()
        }

        fun getMimeType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun getSavedFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            // Verify access still exists
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPermission) uri else null
        } catch (e: Exception) {
            null
        }
    }

    fun getSavedFolderName(): String {
        return prefs.getString(KEY_FOLDER_NAME, "No folder selected") ?: "No folder selected"
    }

    fun saveFolderUri(uri: Uri): Boolean {
        return try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            val doc = DocumentFile.fromTreeUri(context, uri)
            val displayName = doc?.name ?: uri.lastPathSegment ?: "Selected Folder"

            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
                putString(KEY_FOLDER_NAME, displayName)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun clearSavedFolder() {
        prefs.edit {
            remove(KEY_FOLDER_URI)
            remove(KEY_FOLDER_NAME)
        }
    }

    fun getOrCreateTargetFile(
        treeUri: Uri,
        subFolderName: String?,
        fileName: String,
        overwrite: Boolean = false
    ): DocumentFile? {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!rootDoc.canWrite()) return null

        val targetDir = if (!subFolderName.isNullOrBlank()) {
            val cleanSub = cleanFileName(subFolderName)
            rootDoc.findFile(cleanSub) ?: rootDoc.createDirectory(cleanSub) ?: rootDoc
        } else {
            rootDoc
        }

        val cleanedName = cleanFileName(fileName)
        val mimeType = getMimeType(cleanedName)

        val existing = targetDir.findFile(cleanedName)
        if (existing != null) {
            if (overwrite) {
                existing.delete()
                return targetDir.createFile(mimeType, cleanedName)
            }
            return existing
        }

        return targetDir.createFile(mimeType, cleanedName)
    }

    fun openOutputStream(file: DocumentFile, append: Boolean = false): OutputStream? {
        val mode = if (append) "wa" else "wt"
        return try {
            context.contentResolver.openOutputStream(file.uri, mode)
        } catch (e: Exception) {
            // Fallback to "w" if "wt" is not supported by provider
            try {
                context.contentResolver.openOutputStream(file.uri, if (append) "wa" else "w")
            } catch (e2: Exception) {
                e2.printStackTrace()
                null
            }
        }
    }
}
