package dev.tailtune.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Advertises the local TailTune remote over DNS-SD/Bonjour. */
class TailTuneNsdAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val registered = AtomicBoolean(false)
    private var listener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(port: Int) {
        if (registered.get() || listener != null) return
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "TailTune ${Build.MODEL}".take(MAX_SERVICE_NAME_LENGTH)
            serviceType = SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute("version", BuildConfig.VERSION_NAME) }
            runCatching { setAttribute("protocol", PROTOCOL_VERSION) }
            runCatching { setAttribute("auth", "token") }
            preferredLocalIpv4()?.let { host ->
                runCatching { setAttribute("host", host) }
            }
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered.set(true)
                Log.i(TAG, "Bonjour registered: ${info.serviceName} on ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                listener = null
                releaseMulticastLock()
                Log.w(TAG, "Bonjour registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered.set(false)
                Log.i(TAG, "Bonjour unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                Log.w(TAG, "Bonjour unregister failed: $errorCode")
            }
        }
        listener = registration
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure {
            listener = null
            registered.set(false)
            releaseMulticastLock()
            Log.w(TAG, "Bonjour registration unavailable", it)
        }
    }

    fun stop() {
        val current = listener
        listener = null
        if (current != null) {
            runCatching { manager.unregisterService(current) }
                .onFailure { Log.d(TAG, "Bonjour was already stopped", it) }
        }
        registered.set(false)
        releaseMulticastLock()
    }

    fun close() = stop()

    private fun acquireMulticastLock() {
        val existing = multicastLock
        if (existing?.isHeld == true) return
        runCatching {
            wifiManager.createMulticastLock("TailTune:Bonjour").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onSuccess { multicastLock = it }
            .onFailure { Log.d(TAG, "Could not acquire multicast lock", it) }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        multicastLock = null
        if (lock?.isHeld == true) runCatching { lock.release() }
    }

    private fun preferredLocalIpv4(): String? {
        val candidates = buildList {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
                .getOrNull().orEmpty()
            for (network in interfaces) {
                if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
                val addresses = runCatching { network.inetAddresses.toList() }.getOrDefault(emptyList())
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        address.hostAddress?.let { add(network.name to it) }
                    }
                }
            }
        }
        return candidates.sortedBy { (name, ip) ->
            when {
                name.startsWith("wlan") -> 0
                NetworkAddressUtils.isPrivateLan(ip) -> 1
                NetworkAddressUtils.isTailscale(ip) -> 2
                else -> 3
            }
        }.firstOrNull()?.second
    }

    companion object {
        const val SERVICE_TYPE = "_tailtune._tcp."
        const val PROTOCOL_VERSION = "1"
        private const val TAG = "TailTune-NSD"
        private const val MAX_SERVICE_NAME_LENGTH = 60
    }
}
