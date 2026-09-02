package me.ashikoki.listea

import me.ashikoki.listea.data.ReviewItem

/**
 * Filtering and sorting a *review queue*, as opposed to a folder listing.
 *
 * FileArrange.kt answers "which files, in what order" for anything at all. This file is the one
 * place that says what those four facts are for a [ReviewItem], and what it means to start a round
 * over a narrowed one. Both review modes go through it, which is the point: a folder's Quick
 * Review and a List's Review differ in where their queue came from, not in what "pictures only,
 * newest first" does to it.
 *
 * Two rules hold everywhere below, and they are the whole contract with the rest of the app:
 *
 *  1. Narrowing changes *what is reviewed*, and nothing else. Checking, favouriting, actions, the
 *     completion of a list and every webhook are unaffected — a filtered round is a shorter round,
 *     not a different one.
 *  2. Narrowing never changes *what is counted*. Progress belongs to the whole list or the whole
 *     folder: reviewing fifty items out of a hundred leaves the page reading fifty of a hundred,
 *     never fifty of fifty. Nothing here is allowed anywhere near a total, which is why this file
 *     returns a queue and never a count.
 */

/**
 * The four facts a filter and a sort read off a queued item.
 *
 * [FileFacts.isChecked] is always an answer and never an absence, because a queued item always has
 * a decision behind it — a file's stored state, or a manual row's own columns. That is what lets
 * the Checked filter mean something on a list of manual entries and in a folder no List covers,
 * rather than degrading to "nobody knows, keep everything".
 *
 * Size and time can genuinely be unknown: a manual item is not a file, and a list whose folder has
 * not been scanned since these were introduced has not measured itself yet. Both are null, and by
 * the unknown rule in FileArrange.kt neither is ever filtered out for it.
 */
fun reviewFacts(item: ReviewItem): FileFacts = FileFacts(
    name = item.title,
    sizeBytes = item.sizeBytes,
    lastModified = item.lastModified,
    isChecked = item.decisions.isCompleted
)

/**
 * The subset of [items] one review round works on, in the order it walks them.
 *
 * Two independent narrowings, composed as an AND, because they answer different questions and
 * neither is allowed to override the other:
 *
 *  - [arrangement] is the page's own filter and sort — the subset the user picked out before
 *    pressing Review. It decides *which items* and *in what order*.
 *  - [uncheckedOnly] is the standing review setting, which decides whether a round revisits work
 *    already done. It only ever removes.
 *
 * So asking the page for Checked while the setting says unchecked-only yields nothing at all, and
 * that is the honest answer rather than a reason for either to win.
 */
fun reviewRound(
    items: List<ReviewItem>,
    arrangement: FileArrangement,
    uncheckedOnly: Boolean,
    now: Long
): List<ReviewItem> =
    arrangeFiles(items, arrangement, now, ::reviewFacts)
        .filter { !uncheckedOnly || !it.decisions.isCompleted }

/**
 * The identity a List's arrangement is remembered under, alongside the folders'.
 *
 * One namespace, deliberately: "same filter and sort everywhere" means everywhere, and a user who
 * sets "videos, newest first" from a folder expects a list of videos to open the same way. A
 * folder's key is a root URI and a path, and every root URI Listea holds is a `content://` tree
 * URI, so no list can ever collide with one.
 */
fun listArrangementKey(listId: Long): String = "list|$listId"
