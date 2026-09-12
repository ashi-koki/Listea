package me.ashikoki.listea.data

import java.security.SecureRandom

/**
 * The identifier an item carries out of Listea and into whatever the webhook feeds.
 *
 * The row id it replaced on the wire was a SQLite sequence number, and three separate things were
 * wrong with that downstream. It restarts at 1 on a reinstall, so a receiver's records collide
 * with each other across the life of one phone. It is drawn from two different sequences — a List
 * review sends a `list_items` id, a folder Quick Review sends a `file_review_state` id — so `12`
 * from one mode and `12` from the other were different files wearing the same name. And it says
 * nothing: two ids next to each other tell a receiver nothing about when either arrived.
 *
 * So an item's public id is generated here, once, when the row that stands for the file is
 * created, and stored on that row. The same file entering Listea again — a new List over the same
 * folder, a re-sync that re-adds it after it went missing, a fresh registration under another root
 * — creates a new row, and a new row means a new id. That is the property being bought: an id
 * names one *arrival* of a file, not the file, so a receiver can keep both without either
 * overwriting the other.
 *
 * ```
 * 0AT9K3QWMB-4H7ZP2-XC5N0V
 * └────┬───┘ └──┬─┘ └──┬─┘
 *   time     file    random
 * ```
 *
 * **Time** (10 chars) is milliseconds since the epoch. Fixed width and big-endian, so sorting the
 * ids as plain strings sorts them by when they were created — no parsing, no date column, and it
 * holds until the year 10889.
 *
 * **File** (6 chars) is a fingerprint of what the row is about: a file's path under the SAF root,
 * or a manual item's list and title. Deterministic, so the same file always fingerprints the same
 * way and a receiver can group two arrivals of one file without comparing paths. It is a hint and
 * not a promise — 30 bits will eventually repeat, and nothing here depends on it not doing so.
 *
 * **Random** (6 chars) is what actually makes the id unique. Two rows for the same file in the
 * same millisecond need 30 bits of randomness to collide as well, which is the guarantee — the
 * other two fields are for reading and sorting, this one is for uniqueness.
 *
 * The alphabet is Crockford's base32: digits and upper-case letters with I, L, O and U left out,
 * so nothing in an id can be misread as something else when it is being copied out of a log by
 * hand. 24 characters in total, dashes included — shorter than a UUID, and unlike a UUID it is in
 * an order that means something.
 *
 * Deliberately *not* enforced unique by the database. A UNIQUE index would turn a one-in-a-billion
 * random collision into a failed insert, and an insert that fails is a file silently missing from
 * a list — a far worse outcome than the duplicate it would be preventing. Uniqueness is
 * established where the id is made.
 */

private const val Base32Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private const val TimeChars = 10
private const val FingerprintChars = 6
private const val RandomChars = 6

/** 30 bits, which is what six base32 characters hold exactly. */
private const val ThirtyBits = 0x3FFF_FFFFL

/** 50 bits, which is what ten of them hold — far more than milliseconds will need. */
private const val FiftyBits = 0x3_FFFF_FFFF_FFFFL

/** How long every id is, dashes included. Fixed: nothing here produces a shorter or longer one. */
const val ITEM_PUBLIC_ID_LENGTH = TimeChars + FingerprintChars + RandomChars + 2

/**
 * One id, for one row about to be written.
 *
 * [identity] is what the row is about, in whatever form the caller can name it stably — see
 * [fileIdentity] and [manualItemIdentity], which are the two forms actually used. It only feeds
 * the fingerprint, so passing something imperfect costs readability and never uniqueness.
 *
 * [now] and [random] are parameters rather than reads so the generation can be tested for what it
 * claims — that ids sort by time, that the fingerprint is stable, and that two calls in one
 * millisecond about one file still differ.
 */
fun newItemPublicId(
    identity: String,
    now: Long = System.currentTimeMillis(),
    random: Long = nextIdRandomBits()
): String = buildString(ITEM_PUBLIC_ID_LENGTH) {
    append(encodeBase32(now.coerceAtLeast(0L) and FiftyBits, TimeChars))
    append('-')
    append(encodeBase32(fingerprintOf(identity), FingerprintChars))
    append('-')
    append(encodeBase32(random and ThirtyBits, RandomChars))
}

/**
 * A source-backed row's identity: the file's path under the SAF root, which is the same name
 * every other part of Listea knows it by. The root itself is left out — the same file under the
 * same path is the same file whether or not the grant behind it was re-issued.
 */
fun fileIdentity(rootRelativePath: String): String = "file:$rootRelativePath"

/**
 * A manual item's identity. There is no file, so the only stable thing about it is the list it
 * was typed into and what it was typed as.
 */
fun manualItemIdentity(listId: Long, title: String): String = "item:$listId/$title"

/**
 * What a stored item row is about, chosen the way the insert sites choose it: a source-backed row
 * is its file, and a manual one — which has no file — is the list it was typed into and its title.
 *
 * One function rather than the same `if` written twice, because the second place that needs it is
 * the V10 backfill, and a backfill that fingerprinted rows differently from the inserts would
 * quietly produce two kinds of id for one kind of thing.
 */
fun itemIdentityOf(rootRelativePath: String?, listId: Long, title: String): String =
    if (rootRelativePath != null) {
        fileIdentity(rootRelativePath)
    } else {
        manualItemIdentity(listId, title)
    }

/**
 * When an id was made, read back out of it, or null if this is not one of ours.
 *
 * Nothing in the app needs this — the ids sort correctly without ever being decoded, which is the
 * point of the layout. It exists so that a receiver's operator, or a test, can check that claim
 * against a real id rather than taking it on faith.
 */
fun itemPublicIdTime(id: String): Long? {
    if (id.length != ITEM_PUBLIC_ID_LENGTH) return null
    var value = 0L
    for (index in 0 until TimeChars) {
        val digit = Base32Alphabet.indexOf(id[index])
        if (digit < 0) return null
        value = (value shl 5) or digit.toLong()
    }
    return value
}

/** Big-endian base32, always [width] characters, zero-padded on the left so ids sort as strings. */
private fun encodeBase32(value: Long, width: Int): String {
    val out = CharArray(width)
    var remaining = value
    for (index in width - 1 downTo 0) {
        out[index] = Base32Alphabet[(remaining and 31L).toInt()]
        remaining = remaining ushr 5
    }
    return String(out)
}

/**
 * FNV-1a over the identity's UTF-8 bytes, folded down to 30 bits.
 *
 * Chosen over [String.hashCode] because this value ends up in front of whoever is reading the
 * webhook: it has to mean the same thing in every version of the app and on every device, and a
 * hash that is specified here rather than by the platform is the only way to promise that. The
 * fold is what FNV needs before being truncated — its low bits alone are the weakest part of it,
 * since the multiply only ever carries upward.
 */
private fun fingerprintOf(identity: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (byte in identity.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L // FNV prime
    }
    return (hash xor (hash ushr 30)) and ThirtyBits
}

/**
 * One [SecureRandom] for the app, because the alternative is seeding a new one per file during a
 * folder scan that creates thousands of rows in a row.
 */
private val idRandom by lazy { SecureRandom() }

private fun nextIdRandomBits(): Long = idRandom.nextInt().toLong() and ThirtyBits
