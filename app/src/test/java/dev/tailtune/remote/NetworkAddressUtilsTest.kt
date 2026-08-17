package dev.tailtune.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAddressUtilsTest {
    @Test fun privateLanRangesAreRecognized() {
        assertTrue(NetworkAddressUtils.isPrivateLan("10.0.0.1"))
        assertTrue(NetworkAddressUtils.isPrivateLan("172.16.0.1"))
        assertTrue(NetworkAddressUtils.isPrivateLan("172.31.255.254"))
        assertTrue(NetworkAddressUtils.isPrivateLan("192.168.43.1"))
        assertFalse(NetworkAddressUtils.isPrivateLan("172.32.0.1"))
        assertFalse(NetworkAddressUtils.isPrivateLan("8.8.8.8"))
        assertFalse(NetworkAddressUtils.isPrivateLan("not-an-ip"))
    }

    @Test fun onlyRfc6598TailscaleRangeIsRecognized() {
        assertTrue(NetworkAddressUtils.isTailscale("100.64.0.1"))
        assertTrue(NetworkAddressUtils.isTailscale("100.127.255.254"))
        assertFalse(NetworkAddressUtils.isTailscale("100.63.255.255"))
        assertFalse(NetworkAddressUtils.isTailscale("100.128.0.1"))
        assertFalse(NetworkAddressUtils.isTailscale("100.bad.0.1"))
    }
}
