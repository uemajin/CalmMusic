package com.calmapps.calmmusic.data

enum class StreamingProvider {
    APPLE_MUSIC,
    YOUTUBE,
    NAVIDROME,
    ;

    companion object {
        fun fromStored(value: String?): StreamingProvider = when (value) {
            "APPLE_MUSIC" -> APPLE_MUSIC
            "NAVIDROME" -> NAVIDROME
            else -> YOUTUBE
        }

        fun toStored(provider: StreamingProvider): String = when (provider) {
            APPLE_MUSIC -> "APPLE_MUSIC"
            YOUTUBE -> "YOUTUBE"
            NAVIDROME -> "NAVIDROME"
        }
    }
}
