package me.ashikoki.listea

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handing the file the user is looking at to whatever else is on the phone.
 *
 * The third thing in the app that touches storage, and the least consequential of them: it never
 * writes where the user can see it and never changes the reviewed file. What it produces is a
 * short-lived copy in Listea's own cache, purely so there is something an ordinary Android share
 * can be pointed at.
 *
 * That copy is the whole reason this file exists. A reviewed file is a SAF document belonging to
 * whichever provider the user granted, and handing that URI straight to another app works only if
 * that app is willing to read through a foreign provider on a one-off grant. Plenty are not — the
 * messengers people actually share photographs with are the usual offenders — and the failure
 * arrives inside the *other* app, as a blank picture or a silent refusal, where Listea can neither
 * detect it nor explain it. Copying into a [FileProvider] path of Listea's own means every
 * receiver reads the same kind of URI from the same kind of provider, which is the closest thing
 * to "works everywhere" that sharing has.
 *
 * What Listea does not do is decide who the file goes to. The chooser is Android's, what is in it
 * is whatever the phone has installed, and that differs from phone to phone — so nothing here
 * names an app, special-cases one, or promises any particular destination.
 *
 * Deliberately knows nothing about lists, items, tags or actions, exactly like [saveToListeaAlbum]:
 * sharing is a decision about a file, made in front of that file, and it is not recorded anywhere.
 * No webhook hears about it and no completion changes.
 */

/** Kept apart from the authority below so the manifest and the code can be compared at a glance. */
const val ShareProviderSuffix = ".fileprovider"

/** The authority of Listea's own provider, as the manifest declares it. */
fun shareAuthorityOf(context: Context): String = context.packageName + ShareProviderSuffix

/** The cache subdirectory the staged copies live in, matching `res/xml/share_paths.xml`. */
const val ShareCacheDirName = "shared"

/**
 * How long a staged copy is left alone before the next share sweeps it up.
 *
 * A copy cannot be deleted when the chooser closes: the receiving app reads it on its own
 * schedule, sometimes minutes later, and deleting it out from under them is what turns a share
 * into an empty file at the other end. So they are cleaned up on the way *in* instead, and a day
 * is long enough that no plausible receiver is still reading.
 */
private const val ShareCacheLifetimeMs = 24L * 60 * 60 * 1000

/** How a share ended, in the terms the Info sheet reports it. */
sealed interface ShareResult {
    /** The chooser is open. What happens next belongs to the app the user picks. */
    data object Opened : ShareResult

    /** Nothing installed offered to take this kind of file. Not a failure of Listea's. */
    data object NoApp : ShareResult

    /** The file could not be read, or the copy could not be written. */
    data class Failed(val reason: String) : ShareResult
}

/**
 * One sentence under the button, or null when there is nothing worth saying.
 *
 * A share that opened the chooser reports nothing: the chooser is on screen saying it, and
 * whether the user then picks an app, backs out, or the other app makes a mess of the file is not
 * Listea's to narrate — the system never tells us how a share ended.
 */
fun shareOutcomeMessage(result: ShareResult): String? = when (result) {
    ShareResult.Opened -> null
    ShareResult.NoApp -> "Nothing on this phone offered to take this file."
    is ShareResult.Failed -> "Could not share: ${result.reason}"
}

/**
 * What the copy is called, which is the name the receiving app shows and saves.
 *
 * The name is carried over rather than generated, because it is the one piece of the original a
 * receiver gets to keep. Only the characters that would break a path are replaced, and the
 * extension survives untouched — it is what a receiver with no MIME type to go on falls back to.
 */
fun shareFileName(name: String): String {
    val cleaned = name.map { character ->
        if (character.isISOControl() || character in IllegalNameCharacters) '_' else character
    }.joinToString("").trim()
    return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.take(MaxNameLength)
        ?: "shared-file"
}

private const val IllegalNameCharacters = "\\/:*?\"<>|"

/** Well under any real filesystem's limit, and longer than any name worth reading. */
private const val MaxNameLength = 120

/** What an unrecognised file is offered as, so the chooser is wide rather than empty. */
const val AnyShareMimeType = "*/*"

/**
 * The type the chooser filters on, from the provider's answer if there is one and from the
 * extension if not.
 *
 * Falls back to [AnyShareMimeType] rather than refusing. Unlike a save, sharing has no reason to
 * insist on media: a receiver that will take a text file is as valid a destination as one that
 * takes a photograph, and all an unknown type costs is a slightly wider chooser.
 */
fun shareMimeType(mimeType: String?, name: String): String = mimeType
    ?: MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    ?: AnyShareMimeType

/** Whether a staged copy is old enough that nothing could still be reading it. */
fun isStaleShare(lastModified: Long, now: Long): Boolean = now - lastModified > ShareCacheLifetimeMs

/**
 * Copies [sourceUri] into Listea's share cache and opens the system chooser on it.
 *
 * The copy happens on IO and the chooser is started back on the caller's dispatcher, which is the
 * main thread: starting an activity is a main-thread job, and a whole-file copy is emphatically
 * not.
 *
 * Nothing here waits for an answer. Android does not report which app the user picked, or whether
 * they picked one at all, and pretending otherwise would mean showing an outcome Listea cannot
 * know.
 */
suspend fun shareMediaFile(context: Context, sourceUri: String, name: String): ShareResult {
    val parsed = runCatching { sourceUri.toUri() }.getOrNull()
        ?: return ShareResult.Failed("the file could not be located")
    val mimeType = runCatching { context.contentResolver.getType(parsed) }.getOrNull()

    val staged = withContext(Dispatchers.IO) { stageForSharing(context, parsed, name) }
    val shareUri = staged.getOrElse { failure ->
        return ShareResult.Failed(failure.message ?: "the file could not be prepared")
    }

    return startShareChooser(context, shareUri, shareMimeType(mimeType, name))
}

/**
 * Writes the copy and returns the URI a receiver can read it through. Blocking: IO only.
 *
 * Failure comes back as a [Result] rather than as an exception, so the caller reports it in the
 * same sentence every other outcome is reported in.
 */
private fun stageForSharing(context: Context, source: Uri, name: String): Result<Uri> =
    runCatching {
        val directory = File(context.cacheDir, ShareCacheDirName)
        if (!directory.exists() && !directory.mkdirs()) {
            error("the share folder could not be created")
        }
        pruneShareCache(directory, System.currentTimeMillis())

        // Overwritten rather than uniquified: sharing the same file twice is the same copy, and a
        // cache that grows a "name (1)" per tap is a cache that never stops growing.
        val target = File(directory, shareFileName(name))
        context.contentResolver.openInputStream(source).use { input ->
            if (input == null) error("the file could not be read")
            target.outputStream().use { output -> input.copyTo(output) }
        }
        FileProvider.getUriForFile(context, shareAuthorityOf(context), target)
    }

/** Drops the copies old enough that no receiver could still be reading them. */
private fun pruneShareCache(directory: File, now: Long) {
    directory.listFiles()?.forEach { file ->
        if (isStaleShare(file.lastModified(), now)) runCatching { file.delete() }
    }
}

/**
 * The share itself: one [Intent.ACTION_SEND] through the system chooser.
 *
 * The read grant is put on the intent *and* in a [ClipData], because receivers disagree about
 * which of the two they honour and an app that reads neither is an app handed a URI it cannot
 * open.
 *
 * Started from the activity the composable's context belongs to, found by unwrapping rather than
 * assumed: a Compose `LocalContext` is often a wrapper, and starting a chooser from a bare
 * application context needs a task of its own — which is why the flag is added exactly when no
 * activity was found, and not otherwise.
 */
private fun startShareChooser(context: Context, uri: Uri, mimeType: String): ShareResult {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val host = context.findActivity()
    val chooser = Intent.createChooser(send, null).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (host == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        (host ?: context).startActivity(chooser)
        ShareResult.Opened
    } catch (_: ActivityNotFoundException) {
        ShareResult.NoApp
    } catch (error: Exception) {
        ShareResult.Failed(error.message ?: "the share could not be opened")
    }
}
