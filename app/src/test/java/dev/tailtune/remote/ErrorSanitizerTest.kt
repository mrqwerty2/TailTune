package dev.tailtune.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSanitizerTest {
    @Test fun redactsSubsonicQuerySecrets() {
        val result = ErrorSanitizer.message(
            "GET http://host/rest/ping.view?u=alice&t=secret&s=salt&p=password"
        )
        assertFalse(result.contains("alice"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("salt"))
        assertFalse(result.contains("password"))
        assertTrue(result.contains("<redacted>"))
    }

    @Test fun redactsRemoteTokenHeaderAndFlattensLines() {
        val result = ErrorSanitizer.message("X-TailTune-Token: abc123\nsecond line")
        assertFalse(result.contains("abc123"))
        assertFalse(result.contains('\n'))
    }

    @Test fun redactsCompleteBearerAuthorizationValue() {
        val result = ErrorSanitizer.message("Authorization: Bearer secret-token\nnext")
        assertFalse(result.contains("Bearer"))
        assertFalse(result.contains("secret-token"))
        assertTrue(result.contains("<redacted>"))
    }

    @Test fun negativeMaximumLengthCannotCrash() {
        val result = ErrorSanitizer.message("message", -1)
        assertTrue(result.isEmpty())
    }
}
