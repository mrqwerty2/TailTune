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
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Configuration screen. No database or network work is performed on the UI thread. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-Configuration").apply { isDaemon = true }
    }
    private val remoteVerificationGeneration = AtomicInteger(0)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Android can run the foreground service even when notification display
        // permission is declined; start it either way.
        startOrReloadService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.testButton.setOnClickListener { testConnection() }
        binding.startButton.setOnClickListener { saveAndStart() }
        binding.stopButton.setOnClickListener { stopRemote() }

        loadSavedSettings()
        showRemoteAddresses()
        showOfflineStorageState()
    }

    private fun loadSavedSettings() {
        setBusy(true, "Loading saved settings…")
        execute {
            val result = runCatching { ServerSettings.load(applicationContext) }
            runOnUiThreadSafely {
                setBusy(false)
                result.onSuccess { saved ->
                    binding.serverUrlInput.setText(saved.baseUrl)
                    binding.usernameInput.setText(saved.username)
                    binding.passwordInput.setText(saved.password)
                    setStatus("Ready.")
                }.onFailure {
                    setStatus("Could not read saved settings: ${safeMessage(it)}")
                }
            }
        }
    }

    private fun currentSettings(): ServerSettings = ServerSettings(
        baseUrl = binding.serverUrlInput.text?.toString().orEmpty(),
        username = binding.usernameInput.text?.toString().orEmpty(),
        password = binding.passwordInput.text?.toString().orEmpty()
    )

    private fun saveAndStart() {
        val settings = currentSettings()
        setBusy(true, "Validating settings…")
        execute {
            val result = runCatching {
                val hasCache = OfflineStore.hasExistingDatabase(applicationContext)
                when {
                    settings.configured -> {
                        settings.validationError()?.let { throw IllegalArgumentException(it) }
                        ServerSettings.save(applicationContext, settings)
                    }
                    settings.hasAnyValue -> throw IllegalArgumentException(
                        "Complete all three Navidrome fields, or clear all three for offline-only use."
                    )
                    !hasCache -> throw IllegalArgumentException(
                        "Enter Navidrome settings, or restore an existing TailTune cache."
                    )
                    else -> ServerSettings.clear(applicationContext)
                }
            }
            runOnUiThreadSafely {
                setBusy(false)
                result.onSuccess { requestNotificationThenStart() }
                    .onFailure { setStatus("Could not start TailTune: ${safeMessage(it)}") }
            }
        }
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOrReloadService()
        }
    }

    private fun startOrReloadService() {
        val intent = Intent(this, RemoteServerService::class.java).apply {
            action = RemoteServerService.ACTION_START_OR_RELOAD
        }
        runCatching {
            RemoteServicePreferences.setEnabled(applicationContext, true)
            ContextCompat.startForegroundService(this, intent)
        }
            .onSuccess {
                setStatus("Starting the TailTune remote…")
                verifyRemoteStarted(remoteVerificationGeneration.incrementAndGet())
                showRemoteAddresses()
                showOfflineStorageState()
            }
            .onFailure { setStatus("Could not start TailTune: ${safeMessage(it)}") }
    }

    private fun verifyRemoteStarted(generation: Int) {
        execute {
            val token = runCatching {
                RemoteAccessToken.getOrCreate(applicationContext)
            }.getOrNull()
            var ready = false
            if (token != null) {
                for (attempt in 0 until REMOTE_START_ATTEMPTS) {
                    ready = runCatching {
                        val connection = URL(
                            "http://127.0.0.1:${RemoteServerService.REMOTE_PORT}/api/health"
                        ).openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = REMOTE_HEALTH_TIMEOUT_MS
                            connection.readTimeout = REMOTE_HEALTH_TIMEOUT_MS
                            connection.useCaches = false
                            connection.setRequestProperty("X-TailTune-Token", token)
                            connection.responseCode == HttpURLConnection.HTTP_OK
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrDefault(false)
                    if (ready) break
                    if (attempt + 1 < REMOTE_START_ATTEMPTS) {
                        try {
                            Thread.sleep(REMOTE_START_RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }
            runOnUiThreadSafely {
                if (generation != remoteVerificationGeneration.get()) return@runOnUiThreadSafely
                if (ready) {
                    setStatus(
                        "TailTune remote is ready. Cached playlists are available immediately; " +
                            "Navidrome refresh runs in the background."
                    )
                } else {
                    setStatus(
                        "The service was requested, but port ${RemoteServerService.REMOTE_PORT} " +
                            "did not become ready. Reopen TailTune or capture Logcat."
                    )
                }
            }
        }
    }

    private fun stopRemote() {
        remoteVerificationGeneration.incrementAndGet()
        setBusy(true, "Stopping TailTune remote…")
        execute {
            val result = runCatching {
                RemoteServicePreferences.setEnabled(applicationContext, false)
                stopService(Intent(this, RemoteServerService::class.java))
            }
            runOnUiThreadSafely {
                setBusy(false)
                result.onSuccess { setStatus("TailTune remote stopped.") }
                    .onFailure { setStatus("Could not stop TailTune: ${safeMessage(it)}") }
            }
        }
    }

    private fun testConnection() {
        val settings = currentSettings()
        settings.validationError()?.let {
            setStatus(it)
            return
        }

        setBusy(true, "Testing Navidrome…")
        execute {
            val message = runCatching {
                SubsonicClient(settings).ping()
                "Connection successful."
            }.getOrElse { "Connection failed: ${safeMessage(it)}" }
            runOnUiThreadSafely {
                setBusy(false)
                setStatus(message)
            }
        }
    }

    private fun showOfflineStorageState() {
        binding.storageText.text = "Offline storage: checking…"
        execute {
            val hasCache = OfflineStore.hasExistingDatabase(applicationContext)
            val removable = getExternalFilesDirs(null)
                .filterNotNull()
                .any { directory ->
                    runCatching { android.os.Environment.isExternalStorageRemovable(directory) }
                        .getOrDefault(false)
                }
            val location = if (removable) "microSD preferred" else "phone storage"
            val text = buildString {
                append("Offline storage: ").append(location)
                append(" · SQLite cache ")
                append(if (hasCache) "found" else "will be created on first start")
            }
            runOnUiThreadSafely { binding.storageText.text = text }
        }
    }

    private fun showRemoteAddresses() {
        binding.urlText.text = "Finding network addresses…"
        execute {
            val token = runCatching {
                RemoteAccessToken.getOrCreate(applicationContext)
            }.getOrNull()
            val addresses = runCatching { ipv4Addresses() }.getOrDefault(emptyList())
            val local = addresses.firstOrNull { (_, ip) -> NetworkAddressUtils.isPrivateLan(ip) }?.second
            val tailscale = addresses.firstOrNull { (_, ip) -> NetworkAddressUtils.isTailscale(ip) }?.second
            val text = if (token == null) {
                "Could not create a remote-access token"
            } else {
                buildString {
                    local?.let {
                        append("Home/local: ")
                        append(remoteUrl(it, token))
                    }
                    tailscale?.let {
                        if (isNotEmpty()) append('\n')
                        append("Tailscale: ")
                        append(remoteUrl(it, token))
                    }
                    if (isEmpty()) {
                        append("Connect this Samsung to Wi-Fi or Tailscale, then reopen TailTune.")
                    }
                }
            }
            runOnUiThreadSafely { binding.urlText.text = text }
        }
    }

    private fun remoteUrl(ip: String, token: String): String =
        "http://$ip:${RemoteServerService.REMOTE_PORT}/#token=$token"

    private fun ipv4Addresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (network in interfaces) {
            val usable = runCatching { network.isUp && !network.isLoopback }
                .getOrDefault(false)
            if (!usable) continue
            val addresses = runCatching { network.inetAddresses.toList() }
                .getOrDefault(emptyList())
            for (address in addresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { result += network.name to it }
                }
            }
        }
        return result.sortedBy { (name, ip) ->
            when {
                name.startsWith("wlan") -> 0
                NetworkAddressUtils.isPrivateLan(ip) -> 1
                NetworkAddressUtils.isTailscale(ip) -> 2
                else -> 3
            }
        }
    }

    private fun execute(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Activity is closing.
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        binding.testButton.isEnabled = !busy
        binding.startButton.isEnabled = !busy
        binding.stopButton.isEnabled = !busy
        if (message != null) setStatus(message)
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    private fun runOnUiThreadSafely(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun safeMessage(error: Throwable): String = ErrorSanitizer.message(error)

    override fun onDestroy() {
        remoteVerificationGeneration.incrementAndGet()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        // Playback/database initialization happens off the Android lifecycle thread.
        // Give a cold/slow microSD device enough time before declaring startup failed.
        const val REMOTE_START_ATTEMPTS = 40
        const val REMOTE_HEALTH_TIMEOUT_MS = 600
        const val REMOTE_START_RETRY_DELAY_MS = 500L
    }
}
