package com.calmapps.calmmusic.data

/**
 * Groups credits such as "Baco Exu do Blues, Tuyo" or "Artist feat. Someone" under the primary
 * artist ("Baco Exu do Blues") for the Artists tab and artist pages.
 *
 * The decision is data driven so that real names containing separators ("Tyler, The Creator",
 * "Earth, Wind & Fire") are left alone: a credit is only reduced to the part before a separator
 * when that part is itself the album artist of something in the library.
 */
object ArtistGrouping {
    private val separator = Regex("(?i)(, | feat\\.? | ft\\.? | featuring | with | & | x | \u00d7 |; | / | • | · )")
    private val whitespace = Regex("\\s+")

    fun normalize(name: String): String = name.trim().replace(whitespace, " ").lowercase()

    /** Normalised names of every album artist in the library. */
    fun knownNames(albumArtists: Collection<String?>): Set<String> =
        albumArtists.filterNotNull().map(::normalize).filter { it.isNotEmpty() }.toSet()

    /** A key shared by all credits that belong to the same primary artist. */
    fun groupKey(name: String, knownAlbumArtists: Set<String>): String {
        val trimmed = name.trim()
        for (match in separator.findAll(trimmed)) {
            val prefix = trimmed.substring(0, match.range.first).trim()
            if (prefix.isNotEmpty() && normalize(prefix) in knownAlbumArtists) return normalize(prefix)
        }
        return normalize(trimmed)
    }
}
