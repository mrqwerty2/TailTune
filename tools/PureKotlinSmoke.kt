package dev.tailtune.remote

fun main() {
    val delays = (1..8).map { RemoteRetryPolicy.delayForAttempt(it) }
    check(delays == listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)) {
        "Unexpected retry schedule: $delays"
    }
    check(RemoteRetryPolicy.delayForAttempt(Int.MAX_VALUE) == 30_000L)

    check(NetworkAddressUtils.isPrivateLan("10.0.0.1"))
    check(NetworkAddressUtils.isPrivateLan("172.16.0.1"))
    check(NetworkAddressUtils.isPrivateLan("172.31.255.255"))
    check(NetworkAddressUtils.isPrivateLan("192.168.1.2"))
    check(!NetworkAddressUtils.isPrivateLan("172.32.0.1"))
    check(!NetworkAddressUtils.isPrivateLan("8.8.8.8"))
    check(!NetworkAddressUtils.isPrivateLan("999.1.1.1"))

    check(NetworkAddressUtils.isTailscale("100.64.0.1"))
    check(NetworkAddressUtils.isTailscale("100.127.255.254"))
    check(!NetworkAddressUtils.isTailscale("100.128.0.1"))

    val sanitized = ErrorSanitizer.message(
        "GET http://host/rest/ping.view?u=alice&t=secret&s=salt&token=abcdef password=hello"
    )
    check("secret" !in sanitized)
    check("abcdef" !in sanitized)
    check("hello" !in sanitized)
    check("<redacted>" in sanitized)

    println("PURE_KOTLIN_SMOKE_OK")
}
