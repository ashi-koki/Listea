package me.ashikoki.listea

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/** Remembers which folder the user picked, so we can restore it on next launch. */
class FolderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("listea_folder", Context.MODE_PRIVATE)

    fun read(): Uri? = prefs.getString(KEY_TREE_URI, null)?.toUri()

    fun save(uri: Uri) = prefs.edit { putString(KEY_TREE_URI, uri.toString()) }

    fun clear() = prefs.edit { remove(KEY_TREE_URI) }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
    }
}
