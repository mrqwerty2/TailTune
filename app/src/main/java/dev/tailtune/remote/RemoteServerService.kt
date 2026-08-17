package dev.tailtune.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Always-on foreground owner for the HTTP/WebSocket remote.
 *
 * Playback deliberately lives in [PlaybackService]. Keeping the remote listener
 * in a separate connected-device foreground service prevents Media3's idle
 * playback lifecycle from making the remote disappear simply because music has
 * been paused for a while.
 */
class RemoteServerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val serverStarting = AtomicBoolean(false)
    private val serverGeneration = AtomicInteger(0)
    private val serverExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-RemoteServer").apply { isDaemon = true }
    }

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var networkCallbackRegistered = false

    @Volatile private var playbackService: PlaybackService? = null
    private var webServer: TailTuneServer? = null
    private var nsdAdvertiser: TailTuneNsdAdvertiser? = null

    /** True after bindService() succeeds and until the matching unbindService(). */
    private var bindRequested = false
    private var retryAttempt = 0
    private var pendingFullRefresh = false
    private var pendingReload = false
    private var serverError: String? = null

    private val bindTimeoutRunnable = Runnable {
        if (destroyed.get() || !bindRequested || playbackService != null) return@Runnable
        releasePlaybackBinding()
        updateError("Playback service binding timed out; retrying…")
        scheduleRetry()
    }

    /**
     * onServiceDisconnected() normally leaves the binding itself alive. Give
     * Android a brief chance to reconnect it automatically; if it does not,
     * explicitly drop the old binding before starting another one. This avoids
     * accumulating duplicate bindings after repeated vendor/service failures.
     */
    private val disconnectedRebindRunnable = Runnable {
        if (destroyed.get() || playbackService != null || !bindRequested) return@Runnable
        releasePlaybackBinding()
        scheduleRetry()
    }

    private val retryRunnable = Runnable {
        if (destroyed.get() || !RemoteServicePreferences.isEnabled(this)) return@Runnable
        val service = playbackService
        when {
            service == null -> ensurePlaybackBound()
            service.isReady() -> onPlaybackReady(service)
            service.isInitializing() -> schedulePlaybackReadyPoll()
            else -> handlePlaybackUnavailable(
                service.initializationError() ?: "Playback engine is unavailable"
            )
        }
    }

    private val nsdRefreshRunnable = Runnable {
        if (destroyed.get() || webServer == null) return@Runnable
        nsdAdvertiser?.stop()
        nsdAdvertiser?.start(REMOTE_PORT)
    }

    /** Detects an unexpectedly dead NanoHTTPD listener and repairs it. */
    private val serverWatchdogRunnable = object : Runnable {
        override fun run() {
            if (destroyed.get()) return
            if (RemoteServicePreferences.isEnabled(this@RemoteServerService)) {
                val server = webServer
                val service = playbackService
                when {
                    server != null && !server.isAlive -> {
                        Log.w(TAG, "Web listener stopped unexpectedly; restarting")
                        updateError("Web listener stopped unexpectedly; restarting…")
                        stopServerOnly()
                        if (service?.isReady() == true) {
                            startServerIfNeeded(service)
                        } else {
                            scheduleRetry()
                        }
                    }
                    server == null && service?.isReady() == true && !serverStarting.get() -> {
                        startServerIfNeeded(service)
                    }
                }
            }
            mainHandler.postDelayed(this, SERVER_WATCHDOG_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNsdRefresh()
        override fun onLost(network: Network) = scheduleNsdRefresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            scheduleNsdRefresh()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            mainHandler.removeCallbacks(disconnectedRebindRunnable)

            val service = (binder as? PlaybackService.LocalBinder)?.service()
            playbackService = service
            if (service == null) {
                handlePlaybackUnavailable("Playback binder was unavailable")
                return
            }

            when {
                service.isReady() -> onPlaybackReady(service)
                service.isInitializing() -> schedulePlaybackReadyPoll()
                else -> handlePlaybackUnavailable(
                    service.initializationError() ?: "Playback engine did not initialize"
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            playbackService?.attachRemoteServer(null, "Playback service disconnected")
            playbackService = null
            stopServerOnly()
            updateError("Playback service disconnected; reconnecting…")

            // The existing binding is still logically active after this callback.
            // Wait briefly for Android's automatic reconnect before rebinding.
            mainHandler.removeCallbacks(disconnectedRebindRunnable)
            mainHandler.postDelayed(disconnectedRebindRunnable, DISCONNECTED_REBIND_GRACE_MS)
        }

        override fun onBindingDied(name: ComponentName?) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            mainHandler.removeCallbacks(disconnectedRebindRunnable)
            playbackService?.attachRemoteServer(null, "Playback binding died")
            playbackService = null
            releasePlaybackBinding()
            stopServerOnly()
            updateError("Playback binding died; reconnecting…")
            scheduleRetry()
        }

        override fun onNullBinding(name: ComponentName?) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            mainHandler.removeCallbacks(disconnectedRebindRunnable)
            playbackService = null
            releasePlaybackBinding()
            stopServerOnly()
            updateError("Playback service returned a null binder; retrying…")
            scheduleRetry()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // A service launched via startForegroundService() must promote itself
            // immediately, before any database, player or socket setup.
            startRemoteForegroundNotification("Starting TailTune remote…")
        } catch (failure: Throwable) {
            Log.e(TAG, "Could not enter foreground state", failure)
            stopSelf()
            return
        }

        nsdAdvertiser = TailTuneNsdAdvertiser(applicationContext)
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { Log.d(TAG, "Network change monitoring unavailable", it) }

        mainHandler.postDelayed(serverWatchdogRunnable, SERVER_WATCHDOG_INITIAL_DELAY_MS)
    }

    /** The remote service is start-only; clients communicate through HTTP/WebSocket. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RemoteServicePreferences.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_ALL -> {
                RemoteServicePreferences.setEnabled(this, true)
                pendingFullRefresh = true
                pendingReload = true
                ensureOrResumePlayback()
            }

            ACTION_START_OR_RELOAD -> {
                RemoteServicePreferences.setEnabled(this, true)
                pendingReload = true
                ensureOrResumePlayback()
            }

            null -> {
                if (!RemoteServicePreferences.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureOrResumePlayback()
            }

            else -> ensureOrResumePlayback()
        }
        return START_STICKY
    }

    private fun ensureOrResumePlayback() {
        val service = playbackService
        when {
            service?.isReady() == true -> onPlaybackReady(service)
            service?.isInitializing() == true -> schedulePlaybackReadyPoll()
            service != null -> handlePlaybackUnavailable(
                service.initializationError() ?: "Playback engine is unavailable"
            )
            else -> ensurePlaybackBound()
        }
    }

    private fun ensurePlaybackBound() {
        if (destroyed.get() || bindRequested) return

        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOCAL_BIND
        }
        val accepted = runCatching {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { failure ->
            updateError("Could not bind playback: ${ErrorSanitizer.message(failure)}")
            false
        }

        if (!accepted) {
            bindRequested = false
            scheduleRetry()
            return
        }

        bindRequested = true
        mainHandler.removeCallbacks(bindTimeoutRunnable)
        mainHandler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS)
    }

    private fun releasePlaybackBinding() {
        mainHandler.removeCallbacks(bindTimeoutRunnable)
        mainHandler.removeCallbacks(disconnectedRebindRunnable)
        if (bindRequested) {
            runCatching { unbindService(connection) }
                .onFailure { Log.d(TAG, "Playback binding was already gone", it) }
        }
        bindRequested = false
        playbackService = null
    }

    private fun schedulePlaybackReadyPoll() {
        if (destroyed.get() || !RemoteServicePreferences.isEnabled(this)) return
        serverError = null
        startRemoteForegroundNotification("Preparing playback and offline library…")
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, PLAYBACK_READY_POLL_MS)
    }

    private fun onPlaybackReady(service: PlaybackService) {
        if (destroyed.get() || playbackService !== service || !service.isReady()) return

        mainHandler.removeCallbacks(bindTimeoutRunnable)
        mainHandler.removeCallbacks(disconnectedRebindRunnable)
        mainHandler.removeCallbacks(retryRunnable)
        retryAttempt = 0
        serverError = null

        val fullRefresh = pendingFullRefresh
        val reload = pendingReload || fullRefresh
        pendingFullRefresh = false
        pendingReload = false
        if (reload) service.reloadConfiguration(fullRefresh = fullRefresh)

        // The remote can become available immediately from cached state while a
        // Navidrome refresh continues on the playback component executor.
        startServerIfNeeded(service)
    }

    private fun scheduleNsdRefresh() {
        if (destroyed.get()) return
        mainHandler.removeCallbacks(nsdRefreshRunnable)
        mainHandler.postDelayed(nsdRefreshRunnable, NSD_REFRESH_DEBOUNCE_MS)
    }

    private fun handlePlaybackUnavailable(message: String) {
        updateError(message)
        stopServerOnly()
        releasePlaybackBinding()
        scheduleRetry()
    }

    private fun startServerIfNeeded(service: PlaybackService) {
        if (destroyed.get() || webServer != null || !service.isReady()) return
        if (!RemoteServicePreferences.isEnabled(this)) return
        if (!serverStarting.compareAndSet(false, true)) return

        val generation = serverGeneration.get()
        try {
            serverExecutor.execute {
                var candidate: TailTuneServer? = null
                val result = runCatching {
                    val token = RemoteAccessToken.getOrCreate(applicationContext)
                    TailTuneServer(
                        service = service,
                        port = REMOTE_PORT,
                        accessToken = token
                    ).also {
                        candidate = it
                        it.start(SOCKET_READ_TIMEOUT_MS, false)
                        check(it.isAlive) { "Web listener did not remain alive after start" }
                    }
                }

                mainHandler.post {
                    serverStarting.set(false)
                    val stale = destroyed.get() ||
                        generation != serverGeneration.get() ||
                        playbackService !== service ||
                        !service.isReady() ||
                        !RemoteServicePreferences.isEnabled(this)

                    if (stale) {
                        candidate?.let(::stopServerAsync)
                        return@post
                    }

                    result.onSuccess { server ->
                        webServer = server
                        serverError = null
                        retryAttempt = 0
                        service.attachRemoteServer(server)
                        nsdAdvertiser?.start(REMOTE_PORT)
                        startRemoteForegroundNotification("TailTune remote ready on port $REMOTE_PORT")
                        Log.i(TAG, "TailTune remote listening on port $REMOTE_PORT")
                    }.onFailure { failure ->
                        candidate?.let(::stopServerAsync)
                        val message = ErrorSanitizer.message(failure)
                        service.attachRemoteServer(null, message)
                        updateError("TailTune remote failed: $message")
                        scheduleRetry()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            serverStarting.set(false)
            if (!destroyed.get()) {
                updateError("Web server worker is unavailable")
                scheduleRetry()
            }
        }
    }

    private fun stopServerOnly() {
        serverGeneration.incrementAndGet()
        nsdAdvertiser?.stop()
        val server = webServer
        webServer = null
        playbackService?.attachRemoteServer(null, serverError)
        if (server != null) stopServerAsync(server)
    }

    private fun stopServerAsync(server: TailTuneServer) {
        Thread({ runCatching { server.stop() } }, "TailTune-ServerStop").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleRetry() {
        if (destroyed.get() || !RemoteServicePreferences.isEnabled(this)) return
        mainHandler.removeCallbacks(retryRunnable)
        retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPT)
        val delay = RemoteRetryPolicy.delayForAttempt(
            attempt = retryAttempt,
            initialDelayMs = INITIAL_RETRY_DELAY_MS,
            maxDelayMs = MAX_RETRY_DELAY_MS,
            maxExponent = MAX_RETRY_EXPONENT
        )
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun updateError(message: String) {
        serverError = message
        Log.e(TAG, message)
        runCatching {
            startRemoteForegroundNotification(message.take(NOTIFICATION_TEXT_LIMIT))
        }.onFailure { Log.e(TAG, "Could not update foreground notification", it) }
    }

    private fun startRemoteForegroundNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "TailTune remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the TailTune device remote reachable"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("TailTune remote")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user explicitly enabled a headless remote. Removing only the
        // configuration activity from Recents is not a request to turn it off.
    }

    override fun onDestroy() {
        if (!destroyed.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)

        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }

        stopServerOnly()
        releasePlaybackBinding()
        serverExecutor.shutdownNow()
        playbackService = null
        nsdAdvertiser?.close()
        nsdAdvertiser = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val REMOTE_PORT = 8787
        const val ACTION_START_OR_RELOAD = "dev.tailtune.remote.action.REMOTE_START_OR_RELOAD"
        const val ACTION_REFRESH_ALL = "dev.tailtune.remote.action.REMOTE_REFRESH_ALL"
        const val ACTION_STOP = "dev.tailtune.remote.action.REMOTE_STOP"

        private const val TAG = "TailTune-Remote"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_CHANNEL = "tailtune_remote_v2"
        private const val SOCKET_READ_TIMEOUT_MS = 90_000
        private const val NSD_REFRESH_DEBOUNCE_MS = 1_000L
        private const val BIND_TIMEOUT_MS = 12_000L
        private const val DISCONNECTED_REBIND_GRACE_MS = 2_000L
        private const val PLAYBACK_READY_POLL_MS = 500L
        private const val SERVER_WATCHDOG_INITIAL_DELAY_MS = 15_000L
        private const val SERVER_WATCHDOG_INTERVAL_MS = 20_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val MAX_RETRY_EXPONENT = 6
        private const val MAX_RETRY_ATTEMPT = 1_000_000
        private const val NOTIFICATION_TEXT_LIMIT = 120
    }
}
