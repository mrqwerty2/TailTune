package dev.tailtune.standalone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Navidrome-only control center. Cache controls never modify files on the Navidrome server. */
class MainActivity : Activity() {
    private val bg = Color.rgb(14, 19, 32)
    private val cardColor = Color.rgb(27, 34, 52)
    private val muted = Color.rgb(171, 181, 205)
    private val accent = Color.rgb(111, 215, 201)
    private lateinit var status: TextView
    private lateinit var battery: TextView
    private lateinit var cacheDetail: TextView
    private lateinit var speed: TextView
    private lateinit var transferTitle: TextView
    private lateinit var transferProgress: ProgressBar
    private lateinit var downloadedItems: LinearLayout
    private lateinit var playlistPicker: Spinner
    private lateinit var trackPicker: Spinner
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var count: EditText
    private lateinit var cache: NavidromeCache
    private lateinit var library: MusicLibrary
    private var indexVersion = ""
    private var loadingIndex = false
    private var selectedPlaylist = "navidrome"
    private var selectedTrack = ""
    private var renderedCachedSongs = ""
    private val handler = Handler(Looper.getMainLooper())
    private val requestCode = 801
    private val poll = object : Runnable {
        override fun run() {
            val preferences = getSharedPreferences("tailtune_status", MODE_PRIVATE)
            status.text = preferences.getString("status", "Start player, save server, then Sync Navidrome")
            val stats = cache.usage()
            cacheDetail.text = "${stats.count} saved tracks  ·  ${formatBytes(stats.bytes)} used  ·  ${formatBytes(stats.freeBytes)} free"
            val busy = preferences.getBoolean("downloadBusy", false)
            val done = preferences.getLong("downloadBytes", 0L)
            val total = preferences.getLong("downloadTotal", -1L)
            val mbps = preferences.getFloat("downloadSpeed", 0f)
            speed.text = if (busy) "${String.format(java.util.Locale.US, "%.2f", mbps / 1_048_576f)} MB/s" else "Idle"
            transferTitle.text = if (busy) preferences.getString("downloadTitle", "Downloading...") else "No active download"
            transferProgress.isIndeterminate = busy && total <= 0
            transferProgress.progress = if (busy && total > 0) ((done * 100 / total).coerceIn(0, 100)).toInt() else 0
            val sticky = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            battery.text = if (level >= 0 && scale > 0) "Samsung battery  ${100 * level / scale}%" else "Samsung battery  unavailable"
            refreshIndexIfChanged()
            renderDownloadedIfChanged()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        cache = NavidromeCache(this)
        library = MusicLibrary(this, cache)
        indexVersion = cache.indexVersion()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }
        scroll.addView(root)
        root.addView(label("TAILTUNE", 12f, accent, true))
        root.addView(label("Your music, untethered.", 26f, Color.WHITE, true))
        root.addView(label("Samsung player  •  Navidrome library  •  iPhone BLE remote", 12f, muted))
        battery = label("Samsung battery  —", 14f, accent, true)
        root.addView(battery)

        val syncCard = section(root, "NAVIDROME ACCOUNT", "Connect to your private music server")
        server = input("Server URL — https://… or private Tailscale / LAN address")
        user = input("Username")
        pass = input("Password (leave blank to keep saved password)", true)
        syncCard.addView(server); syncCard.addView(user); syncCard.addView(pass)
        runCatching { NavidromeSecrets(this).load() }.getOrNull()?.let {
            server.setText(it.url); user.setText(it.username)
        }
        syncCard.addView(button("Save account settings") { saveCredentials() })
        syncCard.addView(button("Start player + Bluetooth remote", 1) { startOrRequestPermissions() })
        syncCard.addView(button("Sync Navidrome songs and playlists", 2) { startAction(MusicService.ACTION_SYNC) })

        val transfer = section(root, "DOWNLOADS", "Only songs from your Navidrome server")
        cacheDetail = label("Loading local storage…", 13f, muted)
        transfer.addView(cacheDetail)
        transferTitle = label("No active download", 14f, Color.WHITE, true)
        transfer.addView(transferTitle)
        speed = label("Idle", 19f, accent, true)
        transfer.addView(speed)
        transferProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(55, 66, 87))
        }
        transfer.addView(transferProgress, LinearLayout.LayoutParams(-1, dp(7)).apply { bottomMargin = dp(15) })
        transfer.addView(label("Choose a Navidrome playlist", 12f, muted))
        playlistPicker = Spinner(this).apply { setBackgroundColor(Color.rgb(50, 64, 81)) }
        transfer.addView(playlistPicker)
        transfer.addView(button("Download selected playlist", 1) {
            startAction(MusicService.ACTION_DOWNLOAD_PLAYLIST, id = selectedPlaylist)
        }, 1)
        transfer.addView(label("Or download the next uncached songs from your library", 12f, muted))
        count = input("Number of songs (1–100)").apply {
            setText("25"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        transfer.addView(count)
        transfer.addView(button("Download next songs", 2) {
            startAction(MusicService.ACTION_DOWNLOAD, amount = (count.text.toString().toIntOrNull() ?: 25).coerceIn(1, 100))
        })
        transfer.addView(label("Download one song from selected playlist", 12f, muted))
        trackPicker = Spinner(this).apply { setBackgroundColor(Color.rgb(50, 64, 81)) }
        transfer.addView(trackPicker)
        transfer.addView(button("Download selected song", 2) {
            if (selectedTrack.isNotEmpty()) startAction(MusicService.ACTION_DOWNLOAD_TRACK, id = selectedTrack)
        })
        transfer.addView(button("Stop queued playlist downloads") { startAction(MusicService.ACTION_CANCEL_DOWNLOAD) })
        
        val saved = section(root, "OFFLINE MUSIC", "Remove old tracks to make space; Navidrome originals stay safe")
        saved.addView(button("Delete ALL cached tracks", 3) {
            AlertDialog.Builder(this)
                .setTitle("Clear TailTune offline music?")
                .setMessage("This deletes only music downloaded inside TailTune on this Samsung. Your Navidrome server music and playlists will not be changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete downloads") { _, _ -> startAction(MusicService.ACTION_DELETE_ALL) }
                .show()
        })
        downloadedItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saved.addView(downloadedItems)

        status = label("Ready", 13f, muted)
        val footer = section(root, "STATUS", "Your music plays on the Samsung / paired Bluetooth speaker")
        footer.addView(status)
        footer.addView(button("Stop Samsung player") {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_STOP))
        })
        root.addView(label("Keep the Samsung connected to Wi-Fi or Tailscale while downloading. Bluetooth audio is separate from BLE iPhone controls.", 12f, muted))
        setContentView(scroll)
        fillSelectors()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun section(parent: LinearLayout, title: String, subtitle: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(18).toFloat() }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        parent.addView(card, lp)
        card.addView(label(title, 13f, accent, true))
        card.addView(label(subtitle, 12f, muted))
        return card
    }
    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(muted); setTextColor(Color.WHITE)
        textSize = 14f; setSingleLine(true)
        setPadding(dp(11), dp(10), dp(11), dp(10))
        background = GradientDrawable().apply { setColor(Color.rgb(40, 49, 69)); cornerRadius = dp(10).toFloat() }
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }
    private fun button(text: String, kind: Int = 0, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false
        setTextColor(if (kind == 1) bg else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(when (kind) { 1 -> accent; 2 -> Color.rgb(59, 99, 132); 3 -> Color.rgb(126, 54, 74); else -> Color.rgb(50, 61, 82) })
            cornerRadius = dp(11).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) }
        setOnClickListener { action() }
    }

    private fun saveCredentials() {
        val raw = server.text.toString().trim()
        val username = user.text.toString().trim()
        val password = pass.text.toString()
        if (raw.isBlank() || username.isBlank()) { status.text = "Enter a server address and username"; return }
        Thread {
            try {
                val secrets = NavidromeSecrets(this)
                val old = secrets.load()
                val validPassword = password.ifEmpty {
                    old?.takeIf { it.url == NavidromeWire.normalizeBase(raw) && it.username == username }?.password
                        ?: throw IllegalArgumentException("Password needed for a new server")
                }
                secrets.save(NavidromeSettings(raw, username, validPassword))
                runOnUiThread { pass.text.clear(); status.text = "Saved account. Tap Sync Navidrome." }
            } catch (e: Exception) { runOnUiThread { status.text = "Save failed: ${e.message?.take(100)}" } }
        }.start()
    }

    private fun pickerAdapter(options: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, options
    ) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            (view as? TextView)?.setTextColor(Color.WHITE)
            return view
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent)
            (view as? TextView)?.setTextColor(Color.BLACK)
            return view
        }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun fillSelectors() {
        val names = mutableListOf("All Navidrome songs" to "navidrome")
        library.navidromePlaylists.forEach { names += it.name to "navplaylist:${it.id}" }
        val selected = names.indexOfFirst { it.second == selectedPlaylist }.coerceAtLeast(0)
        playlistPicker.adapter = pickerAdapter(names.map { it.first })
        playlistPicker.setSelection(selected)
        playlistPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPlaylist = names.getOrNull(position)?.second ?: "navidrome"
                fillTrackPicker()
            }
        }
        fillTrackPicker()
    }
    private fun fillTrackPicker() {
        val all = library.playlist(selectedPlaylist).take(3000)
        val cachedIndex = all.indexOfFirst { it.id == selectedTrack }.coerceAtLeast(0)
        trackPicker.adapter = pickerAdapter(all.map { "${it.title} — ${it.artist}${if (it.downloaded) "  ✓" else ""}" })
        trackPicker.setSelection(cachedIndex)
        selectedTrack = all.getOrNull(cachedIndex)?.id ?: ""
        trackPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTrack = all.getOrNull(position)?.id ?: ""
            }
        }
    }

    private fun refreshIndexIfChanged() {
        val version = cache.indexVersion()
        if (version == indexVersion || loadingIndex) return
        loadingIndex = true
        Thread {
            val songs = cache.loadCatalog()
            val playlists = cache.loadPlaylists()
            runOnUiThread {
                library.setRemote(songs, playlists)
                indexVersion = version
                loadingIndex = false
                renderedCachedSongs = ""
                fillSelectors()
            }
        }.start()
    }
    private fun renderDownloadedIfChanged() {
        val cached = library.songs.filter { it.downloaded }.take(150)
        val signature = cached.joinToString("|") { it.id }
        if (signature == renderedCachedSongs) return
        renderedCachedSongs = signature
        downloadedItems.removeAllViews()
        if (cached.isEmpty()) downloadedItems.addView(label("No offline tracks yet. Download a playlist above.", 13f, muted))
        for (song in cached) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label("${song.title}  ·  ${formatBytes(song.cacheFile.length())}", 12f, Color.WHITE),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Remove", 3) {
                startAction(MusicService.ACTION_DELETE_TRACK, id = song.id)
            }.apply { textSize = 12f }, LinearLayout.LayoutParams(dp(90), dp(43)))
            downloadedItems.addView(row)
        }
        if (library.songs.count { it.downloaded } > cached.size) downloadedItems.addView(label("Showing first 150 cached tracks. Use Clear All to remove everything.", 12f, muted))
    }
    private fun formatBytes(n: Long): String = if (n >= 1024 * 1024 * 1024) String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0)
        else String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }

    private fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_ADVERTISE) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    private fun startOrRequestPermissions() {
        val missing = required().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), requestCode)
        else startAction(MusicService.ACTION_START)
    }
    private fun startAction(action: String, amount: Int? = null, id: String? = null) {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        if (amount != null) intent.putExtra(MusicService.EXTRA_COUNT, amount)
        if (id != null) intent.putExtra(MusicService.EXTRA_ID, id)
        startForegroundService(intent)
        status.text = "Requested: ${action.substringAfterLast('.')}"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == this.requestCode) {
            val denied = required().filter { it != Manifest.permission.POST_NOTIFICATIONS }
                .any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (denied) status.text = "Allow Bluetooth permissions in Android Settings" else startAction(MusicService.ACTION_START)
        }
    }
}
