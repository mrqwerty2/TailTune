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
        showRemoteAddress()

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
        if (!settings.configured) {
            binding.statusText.text = "Enter the Navidrome URL, username and password."
            return
        }
        ServerSettings.save(this, settings)

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
        binding.statusText.text = "Web remote started. Loading your Navidrome playlists."
        showRemoteAddress()
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

    private fun showRemoteAddress() {
        val ip = localIpv4Address()
        binding.urlText.text = if (ip != null) "http://$ip:8787" else "Wi-Fi IP unavailable"
    }

    private fun localIpv4Address(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in network.inetAddresses.toList()) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val ip = address.hostAddress ?: continue
                    val priority = when {
                        network.name.startsWith("wlan") -> 0
                        ip.startsWith("192.168.") -> 1
                        ip.startsWith("10.") -> 2
                        ip.startsWith("172.") -> 3
                        else -> 4
                    }
                    candidates += priority to ip
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
