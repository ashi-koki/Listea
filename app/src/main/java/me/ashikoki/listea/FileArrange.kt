package me.ashikoki.listea

import java.util.Calendar

/**
 * Filtering and sorting a set of files, as one pure value and one pure function.
 *
 * Deliberately knows nothing about folders, lists, Room, SAF or Compose. It takes a list of
 * *anything*, a way to read four facts off each element, and returns the ones that survive in the
 * order asked for. That is the whole of it, and it is why the same code serves the folder browser,
 * a list's items and both narrowed review queues: those differ in what they hold, not in what
 * "newest first" or "pictures only" means.
 *
 * The unknown rule, applied everywhere: a fact nobody knows never excludes anything. A file with
 * no timestamp survives every freshness filter, and a file whose checked state is not being
 * tracked survives every checked filter. Excluding on an absence would quietly hide files for a
 * reason the user could not see, and hiding files is the one thing a filter must be trusted about.
 * Sorting takes the opposite line: unknowns sink to the bottom in both directions, because
 * somewhere consistent beats somewhere defensible.
 */

/** The kinds of file the media-type filter can name. Anything else is only matched by "All". */
enum class MediaKind(val key: String, val label: String, val extensions: Set<String>) {
    PICTURE(
        "picture",
        "Pictures",
        setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "avif", "tif", "tiff", "jfif", "dng"
        )
    ),
    VIDEO(
        "video",
        "Videos",
        setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "wmv",
            "flv", "ts", "mpg", "mpeg", "mts"
        )
    ),
    TEXT(
        "text",
        "Text",
        setOf(
            "txt", "md", "log", "csv", "tsv", "json", "xml",
            "yaml", "yml", "ini", "rtf", "srt", "vtt"
        )
    )
}

/** What the checked-status filter can ask for. */
enum class CheckedState(val key: String, val label: String) {
    CHECKED("checked", "Checked"),
    UNCHECKED("unchecked", "Unchecked")
}

/**
 * How recently a file was last written, in whole local calendar days.
 *
 * Calendar days rather than elapsed hours, because "yesterday" should mean yesterday whether it
 * is now nine in the morning or eleven at night. Ranges are half-open and stated by their
 * labels: 1d-3d is the day before yesterday and the one before that, and 3d-7d picks up where it
 * stops.
 *
 * Nothing covers files older than a month. That is the point of the word freshness — anything
 * further back is reached by asking for All, which is also the default.
 */
enum class Freshness(val key: String, val label: String, val daysAgo: IntRange) {
    TODAY("today", "Today", 0..0),
    DAYS_1_3("d1_3", "1d - 3d", 1..2),
    DAYS_3_7("d3_7", "3d - 7d", 3..6),
    DAYS_7_14("d7_14", "7d - 2w", 7..13),
    DAYS_14_30("d14_30", "2w - 1m", 14..29)
}

/**
 * The orders a listing can be put in.
 *
 * [NATURAL] is the default and is not a sort at all: it leaves whatever it is given in the order
 * it arrived. That is the only default that can serve both places this is used. A folder's own
 * order is already its names ascending, so nothing moves there; a List's own order is the order
 * its items are stored in — the scan order for a folder-backed list, the order they were typed
 * for a manual one — and re-alphabetising that by default would rearrange a list the user built
 * by hand. Asking for [NAME_ASC] explicitly is still a real sort, and still means what it says.
 *
 * [NAME_ASC] is every other order's tie-breaker, so no two files ever swap places between one
 * listing and the next for no reason.
 */
enum class FileSort(val key: String, val label: String) {
    NATURAL("natural", "Default order"),
    NAME_ASC("name_asc", "Name (A - Z)"),
    NAME_DESC("name_desc", "Name (Z - A)"),
    TIME_DESC("time_desc", "Newest first"),
    TIME_ASC("time_asc", "Oldest first"),
    SIZE_DESC("size_desc", "Largest first"),
    SIZE_ASC("size_asc", "Smallest first"),
    FORMAT("format", "File type"),
    CHECKED("checked", "Unchecked first")
}

/**
 * One folder's — or later, one list's — filter and order, together.
 *
 * They travel as a pair because they are set from the same header, stored under the same key and
 * remembered by the same switch. An empty set means "All": there is no separate All member to
 * keep consistent with the others, so no state exists in which All and Pictures are both
 * selected.
 */
data class FileArrangement(
    val mediaKinds: Set<MediaKind> = emptySet(),
    val checked: Set<CheckedState> = emptySet(),
    val freshness: Set<Freshness> = emptySet(),
    val sort: FileSort = FileSort.NATURAL
) {
    /** Nothing is being filtered out: what the header's filter icon reports as off. */
    val isFilterDefault: Boolean
        get() = mediaKinds.isEmpty() && checked.isEmpty() && freshness.isEmpty()

    /** Files are in their ordinary order: what the header's sort icon reports as off. */
    val isSortDefault: Boolean get() = sort == FileSort.NATURAL

    val isDefault: Boolean get() = isFilterDefault && isSortDefault

    companion object {
        val Default = FileArrangement()
    }
}

/**
 * The four things a filter and a sort need to know about a file, whatever the file is being
 * carried around as.
 *
 * A null [sizeBytes], [lastModified] or [isChecked] means nobody knows, not zero and not false —
 * see the unknown rule at the top of this file.
 */
data class FileFacts(
    val name: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val isChecked: Boolean? = null
)

/**
 * [items] narrowed to what [arrangement] keeps, in the order it asks for.
 *
 * [facts] is read once per element rather than per comparison, so an expensive lookup behind it —
 * a checked state resolved out of a map, say — costs one pass and not n log n of them.
 *
 * [now] is passed in rather than read here, which is what makes freshness testable and what stops
 * a listing from re-sorting itself halfway down the screen because the clock ticked.
 */
fun <T> arrangeFiles(
    items: List<T>,
    arrangement: FileArrangement,
    now: Long,
    facts: (T) -> FileFacts
): List<T> {
    val paired = items.map { it to facts(it) }
    val kept = paired.filter { (_, fact) -> matchesFilter(fact, arrangement, now) }
    // Not "sort by nothing", which would still be a sort: the input order is passed through
    // untouched, because it is the one order this file has no way to reconstruct.
    if (arrangement.sort == FileSort.NATURAL) return kept.map { it.first }
    return kept.sortedWith(comparatorFor(arrangement.sort)).map { it.first }
}

/** Whether one file survives [arrangement]'s filter. Every group is an AND of the others. */
fun matchesFilter(facts: FileFacts, arrangement: FileArrangement, now: Long): Boolean =
    matchesMediaKind(facts, arrangement.mediaKinds) &&
        matchesChecked(facts, arrangement.checked) &&
        matchesFreshness(facts, arrangement.freshness, now)

private fun matchesMediaKind(facts: FileFacts, wanted: Set<MediaKind>): Boolean =
    wanted.isEmpty() || mediaKindOf(facts.name) in wanted

private fun matchesChecked(facts: FileFacts, wanted: Set<CheckedState>): Boolean {
    if (wanted.isEmpty()) return true
    // Not tracked here at all, so there is nothing to exclude it on.
    val checked = facts.isChecked ?: return true
    return (if (checked) CheckedState.CHECKED else CheckedState.UNCHECKED) in wanted
}

private fun matchesFreshness(facts: FileFacts, wanted: Set<Freshness>, now: Long): Boolean {
    if (wanted.isEmpty()) return true
    val modified = facts.lastModified ?: return true
    val days = calendarDaysAgo(modified, now)
    return wanted.any { days in it.daysAgo }
}

/**
 * Which kind of media [name] looks like, by extension, or null for everything else.
 *
 * By extension and not by asking the content provider: this runs over every file in a listing on
 * every keystroke-equivalent change of the filter, and a provider round trip per file would make
 * the header's filter icon the most expensive control in the app. The provider's opinion is still
 * what decides how a file is *rendered* — see `previewKindOf` — and the two disagreeing only
 * means a misnamed file is filed under its name.
 */
fun mediaKindOf(name: String): MediaKind? {
    val extension = fileExtension(name)
    if (extension.isEmpty()) return null
    return MediaKind.entries.firstOrNull { extension in it.extensions }
}

/** Lower-cased extension without the dot, or "" when the name has none. */
fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase()

/**
 * Whole local calendar days between [millis] and [now]: 0 is today, 1 is yesterday.
 *
 * Both ends are floored to local midnight before subtracting, so the answer does not depend on
 * the time of day and a daylight-saving change cannot make a day count as two. A timestamp in the
 * future — a clock skew, a provider reporting nonsense — is reported as today rather than as a
 * negative, so it lands in a bucket that exists.
 */
fun calendarDaysAgo(millis: Long, now: Long): Int {
    val elapsed = startOfDay(now) - startOfDay(millis)
    if (elapsed <= 0L) return 0
    return (elapsed / MillisPerDay).toInt()
}

private const val MillisPerDay = 24L * 60L * 60L * 1000L

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The comparator behind one [FileSort], always ending in the name so the order is total.
 *
 * Unknown sizes and times sort to the bottom whichever direction is asked for, which is why each
 * of those four is written out rather than derived by reversing its opposite: reversing would
 * float the unknowns to the top of one of them.
 */
private fun <T> comparatorFor(sort: FileSort): Comparator<Pair<T, FileFacts>> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { pair: Pair<T, FileFacts> ->
        pair.second.name
    }
    return when (sort) {
        // Never reached: [arrangeFiles] returns before sorting. Answered as the ordinary order
        // rather than left to throw, so this stays total if a second caller ever appears.
        FileSort.NATURAL,
        FileSort.NAME_ASC -> byName
        FileSort.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) {
            pair: Pair<T, FileFacts> -> pair.second.name
        }

        FileSort.TIME_DESC ->
            compareByDescending<Pair<T, FileFacts>> { it.second.lastModified ?: Long.MIN_VALUE }
                .then(byName)

        FileSort.TIME_ASC ->
            compareBy<Pair<T, FileFacts>> { it.second.lastModified ?: Long.MAX_VALUE }
                .then(byName)

        FileSort.SIZE_DESC ->
            compareByDescending<Pair<T, FileFacts>> { it.second.sizeBytes ?: Long.MIN_VALUE }
                .then(byName)

        FileSort.SIZE_ASC ->
            compareBy<Pair<T, FileFacts>> { it.second.sizeBytes ?: Long.MAX_VALUE }
                .then(byName)

        FileSort.FORMAT ->
            compareBy<Pair<T, FileFacts>> { fileExtension(it.second.name) }.then(byName)

        // Unchecked first: what is left to do comes before what is done, and an untracked file
        // counts as not done rather than being stranded in a third group of its own.
        FileSort.CHECKED ->
            compareBy<Pair<T, FileFacts>> { it.second.isChecked == true }.then(byName)
    }
}

/**
 * Every arrangement Listea is remembering: the one shared setting, and the per-listing ones.
 *
 * [byFolder] is keyed by whatever was being arranged — a folder under a root, or a List. One map,
 * because "same filter and sort everywhere" has to be able to mean everywhere; the two kinds of
 * key cannot collide, since a folder's begins with a `content://` root URI.
 *
 * Both are kept whichever mode is active, so switching "same everywhere" on and back off returns
 * each of them to the arrangement it had rather than resetting it. See [resolveArrangement].
 */
data class FolderArrangements(
    val global: FileArrangement = FileArrangement.Default,
    val byFolder: Map<String, FileArrangement> = emptyMap()
)

/**
 * The identity a folder's arrangement is filed under: a root, and a path inside it. A List's
 * equivalent is [listArrangementKey], which shares this map.
 */
fun folderArrangementKey(rootUri: String, folderPath: String): String = "$rootUri|$folderPath"

/** Which arrangement a listing is actually showing under, given the "same everywhere" switch. */
fun resolveArrangement(
    arrangements: FolderArrangements,
    folderKey: String,
    sharedEverywhere: Boolean
): FileArrangement = if (sharedEverywhere) {
    arrangements.global
} else {
    arrangements.byFolder[folderKey] ?: FileArrangement.Default
}

/**
 * How many folders' arrangements are kept before the least recently set one is dropped.
 *
 * There is a cap because a per-folder setting on an unbounded number of folders is an unbounded
 * amount of storage, re-read on every settings emission. Two hundred is far more than anyone
 * curates by hand and small enough to stay free.
 */
private const val MaxRememberedFolders = 200

/**
 * [existing] with [folderKey] set to [arrangement], most recently set last, capped.
 *
 * An arrangement that is back to its default is removed rather than stored: it is what an absent
 * key already means, and keeping it would spend one of the remembered slots saying nothing.
 */
fun rememberArrangement(
    existing: Map<String, FileArrangement>,
    folderKey: String,
    arrangement: FileArrangement
): Map<String, FileArrangement> {
    val without = existing.filterKeys { it != folderKey }
    if (arrangement.isDefault) return without
    val updated = without + (folderKey to arrangement)
    if (updated.size <= MaxRememberedFolders) return updated
    return updated.entries.drop(updated.size - MaxRememberedFolders)
        .associate { it.key to it.value }
}

/**
 * One arrangement as a line of text: sort, then the three filter groups.
 *
 * Members are written by their own stable [MediaKind.key]-style names rather than by ordinal, so
 * adding a media kind or reordering an enum cannot silently turn a stored "Pictures" into
 * "Videos". Anything unrecognised on the way back in is dropped, which makes a downgrade lose a
 * filter rather than fail to start.
 */
fun encodeArrangement(arrangement: FileArrangement): String = listOf(
    arrangement.sort.key,
    arrangement.mediaKinds.joinToString(",") { it.key },
    arrangement.checked.joinToString(",") { it.key },
    arrangement.freshness.joinToString(",") { it.key }
).joinToString("|")

fun decodeArrangement(text: String): FileArrangement {
    val parts = text.split("|")
    fun group(index: Int): List<String> =
        parts.getOrNull(index)?.split(",")?.filter { it.isNotEmpty() }.orEmpty()

    return FileArrangement(
        mediaKinds = group(1).mapNotNull { key ->
            MediaKind.entries.firstOrNull { it.key == key }
        }.toSet(),
        checked = group(2).mapNotNull { key ->
            CheckedState.entries.firstOrNull { it.key == key }
        }.toSet(),
        freshness = group(3).mapNotNull { key ->
            Freshness.entries.firstOrNull { it.key == key }
        }.toSet(),
        sort = FileSort.entries.firstOrNull { it.key == parts.firstOrNull() }
            ?: FileSort.NATURAL
    )
}

/**
 * The whole per-folder map as one string: one line each, the arrangement first and the folder key
 * second so a tab inside a folder name cannot shift the fields.
 *
 * A folder whose name contains a newline is not stored. It still filters and sorts perfectly well
 * for as long as the app is running — only the remembering is given up, for a folder name that no
 * file manager will produce and no encoding of this shape can survive.
 */
fun encodeFolderArrangements(byFolder: Map<String, FileArrangement>): String =
    byFolder.entries
        .filterNot { it.key.contains('\n') }
        .joinToString("\n") { (key, arrangement) -> encodeArrangement(arrangement) + "\t" + key }

fun decodeFolderArrangements(text: String): Map<String, FileArrangement> =
    text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            parts[1] to decodeArrangement(parts[0])
        }
        .toMap()
