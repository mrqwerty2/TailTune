package dev.tailtune.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SubsonicModelsTest {
    @Test fun safeSuffixWins() {
        assertEquals("flac", song(suffix = "FLAC").preferredExtension())
    }

    @Test fun unsafeSuffixFallsBackToMimeType() {
        assertEquals(
            "mp3",
            song(suffix = "../../evil", contentType = "audio/mpeg").preferredExtension()
        )
    }

    @Test fun unknownTypeUsesNeutralExtension() {
        assertEquals("audio", song(suffix = null, contentType = null).preferredExtension())
    }

    private fun song(
        suffix: String?,
        contentType: String? = null
    ) = RemoteSong(
        id = "id",
        title = "title",
        artist = "artist",
        album = "album",
        durationSeconds = 1,
        coverArtId = null,
        contentType = contentType,
        suffix = suffix,
        sizeBytes = 1
    )
}
