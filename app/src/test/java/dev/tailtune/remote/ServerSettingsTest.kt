package dev.tailtune.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {
    @Test fun normalizesHostAndRestSuffix() {
        val settings = ServerSettings(" 192.168.1.2:4533/rest/ ", "user", "password")
        assertEquals("http://192.168.1.2:4533", settings.normalizedBaseUrl())
        assertNull(settings.validationError())
    }

    @Test fun acceptsHttpsAndRejectsUnsupportedSchemes() {
        assertNull(ServerSettings("https://music.example.test", "u", "p").validationError())
        assertNotNull(ServerSettings("ftp://music.example.test", "u", "p").validationError())
    }

    @Test fun rejectsCredentialsInsideUrl() {
        assertNotNull(ServerSettings("https://u:p@example.test", "u", "p").validationError())
    }

    @Test fun rejectsPortZeroAndOutOfRangePorts() {
        assertNotNull(ServerSettings("http://localhost:0", "u", "p").validationError())
        assertNotNull(ServerSettings("http://localhost:65536", "u", "p").validationError())
        assertNull(ServerSettings("http://localhost:65535", "u", "p").validationError())
    }

    @Test fun rejectsControlCharactersAndUrlFragments() {
        assertNotNull(ServerSettings("http://local\nhost", "u", "p").validationError())
        assertNotNull(ServerSettings("http://localhost/#fragment", "u", "p").validationError())
        assertNotNull(ServerSettings("http://localhost/?x=1", "u", "p").validationError())
    }

    @Test fun requiresAllFields() {
        assertNotNull(ServerSettings("", "u", "p").validationError())
        assertNotNull(ServerSettings("http://localhost", "", "p").validationError())
        assertNotNull(ServerSettings("http://localhost", "u", "").validationError())
    }

    @Test fun distinguishesBlankOfflineModeFromPartialConfiguration() {
        assertFalse(ServerSettings("", "", "").hasAnyValue)
        assertTrue(ServerSettings("http://localhost", "", "").hasAnyValue)
    }
}
