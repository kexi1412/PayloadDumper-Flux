package com.flux.payload.dumper.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/** Helpers for turning SAF document/tree URIs into real filesystem paths (MANAGE_EXTERNAL_STORAGE). */
object PathUtil {

    fun isAllFilesAccessGranted(): Boolean = Environment.isExternalStorageManager()

    fun realPathFromUri(context: Context, uri: Uri): String? {
        val id = when {
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
            runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false) ->
                DocumentsContract.getTreeDocumentId(uri)
            else -> return null
        }
        val split = id.split(":")
        if (split.size == 2 && split[0] == "primary") {
            return Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
        }
        return null
    }
}
