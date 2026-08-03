package dev.tailtune.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.tailtune.remote.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startOrReloadService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val saved = ServerSettings.load(this)
        binding.serverUrlInput.setText(saved.baseUrl)
        binding.usernameInput.setText(saved.username)
        binding.passwordInput.setText(saved.password)
        showRemoteAddresses()
        showOfflineStorage()

        binding.testButton.setOnClickListener { testConnection() }
        binding.startButton.setOnClickListener { saveAndStart() }
    }

    private fun currentSettings(): ServerSettings = ServerSettings(
        baseUrl = binding.serverUrlInput.text.toString(),
        username = binding.usernameInput.text.toString(),
        password = binding.passwordInput.text.toString()
    )

    private fun saveAndStart() {
        val settings = currentSettings()
        val hasCachedPlaylists = OfflineStore(this).let { store ->
            try {
                store.playlistCount() > 0
            } finally {
                store.close()
            }
        }
        if (!settings.configured && !hasCachedPlaylists) {
            binding.statusText.text = "Enter the Navidrome URL, username and password."
            return
        }
        if (settings.configured) ServerSettings.save(this, settings)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOrReloadService()
        }
    }

    private fun startOrReloadService() {
        val running = PlaybackService.instance
        if (running != null) {
            running.reloadConfiguration()
        } else {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        }
        binding.statusText.text =
            "Web remote started immediately. Navidrome synchronization now runs in the background."
        showRemoteAddresses()
        showOfflineStorage()
    }

    private fun testConnection() {
        val settings = currentSettings()
        if (!settings.configured) {
            binding.statusText.text = "Enter all three Navidrome settings first."
            return
        }
        binding.statusText.text = "Testing Navidrome…"
        Thread {
            val result = runCatching {
                SubsonicClient(settings).ping()
                "Connection successful."
            }.getOrElse { "Connection failed: ${it.message}" }
            runOnUiThread { binding.statusText.text = result }
        }.start()
    }

    private fun showOfflineStorage() {
        val store = OfflineStore(this)
        val storage = try {
            store.storageJson()
        } finally {
            store.close()
        }
        val location = if (storage.optBoolean("removable", false)) "microSD card" else "phone storage"
        binding.storageText.text = "Offline storage: $location · SQLite library cache enabled"
    }

    private fun showRemoteAddresses() {
        val addresses = ipv4Addresses()
        val local = addresses.firstOrNull { (_, ip) -> isPrivateLan(ip) }?.second
        val tailscale = addresses.firstOrNull { (_, ip) -> ip.startsWith("100.") }?.second

        binding.urlText.text = buildString {
            if (local != null) append("Home Wi-Fi: http://$local:${PlaybackService.REMOTE_PORT}")
            if (tailscale != null) {
                if (isNotEmpty()) append('\n')
                append("Tailscale: http://$tailscale:${PlaybackService.REMOTE_PORT}")
            }
            if (isEmpty()) append("Network address unavailable")
        }
    }

    private fun ipv4Addresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in network.inetAddresses.toList()) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { result += network.name to it }
                }
            }
        }
        return result.sortedBy { (name, ip) ->
            when {
                name.startsWith("wlan") -> 0
                isPrivateLan(ip) -> 1
                ip.startsWith("100.") -> 2
                else -> 3
            }
        }
    }

    private fun isPrivateLan(ip: String): Boolean =
        ip.startsWith("192.168.") || ip.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(ip)
}
