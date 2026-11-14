package com.tan.tocdonhac

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.color.MaterialColors
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var userSeeking = false
    private var pendingViewUri: Uri? = null
    private var lastRenderedIndex = -2

    private lateinit var tvTitle: TextView
    private lateinit var seekPos: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnRepeat: Button
    private lateinit var btnShuffle: Button
    private lateinit var tvSpeed: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvPitch: TextView
    private lateinit var seekPitch: SeekBar
    private lateinit var cbTape: CheckBox
    private lateinit var cbFade: CheckBox
    private lateinit var tvAB: TextView
    private lateinit var tvSleep: TextView
    private lateinit var playlistBox: LinearLayout

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            addUris(uris, playFirstAdded = false)
        }

    private val uiTick = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        tvTitle = findViewById(R.id.tvTitle)
        seekPos = findViewById(R.id.seekPos)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        btnPlay = findViewById(R.id.btnPlay)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnShuffle = findViewById(R.id.btnShuffle)
        tvSpeed = findViewById(R.id.tvSpeed)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvPitch = findViewById(R.id.tvPitch)
        seekPitch = findViewById(R.id.seekPitch)
        cbTape = findViewById(R.id.cbTape)
        cbFade = findViewById(R.id.cbFade)
        tvAB = findViewById(R.id.tvAB)
        tvSleep = findViewById(R.id.tvSleep)
        playlistBox = findViewById(R.id.playlistBox)

        // Khôi phục cài đặt
        seekSpeed.progress = prefs.getInt("speed", speedToProgress(1f))
        seekPitch.progress = prefs.getInt("pitch", 12)
        cbTape.isChecked = prefs.getBoolean("tape", false)
        cbFade.isChecked = prefs.getBoolean("fade", true)
        Shared.fadeOut = cbFade.isChecked

        setupButtons()
        updateLabels()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null) {
            pendingViewUri = i.data
            if (controller != null) {
                addUris(listOf(i.data!!), playFirstAdded = true)
                pendingViewUri = null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            controller = try { f.get() } catch (e: Exception) { null }
            onControllerReady()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        handler.removeCallbacks(uiTick)
        controller?.let {
            prefs.edit()
                .putInt("idx", it.currentMediaItemIndex)
                .putLong("posMs", it.currentPosition)
                .apply()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun onControllerReady() {
        val c = controller ?: return
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateUi()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                renderPlaylist()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                renderPlaylist()
                savePlaylist()
            }

            override fun onPlayerError(error: PlaybackException) {
                toast("Không phát được bài này (file bị xóa hoặc không hỗ trợ)")
                if (c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }
            }
        })

        if (c.mediaItemCount == 0) restorePlaylist(c)
        applyParams()

        pendingViewUri?.let {
            addUris(listOf(it), playFirstAdded = true)
            pendingViewUri = null
        }

        lastRenderedIndex = -2
        renderPlaylist()
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    // ================== Nút bấm ==================

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            controller?.let { it.seekTo(max(0L, it.currentPosition - 10_000)) }
        }
        findViewById<Button>(R.id.btnFwd).setOnClickListener {
            controller?.let {
                val d = it.duration
                val target = it.currentPosition + 10_000
                it.seekTo(if (d > 0) min(d, target) else target)
            }
        }
        btnPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.mediaItemCount == 0) { pickFiles(); return@setOnClickListener }
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        }
        btnRepeat.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            updateUi()
        }
        btnShuffle.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            updateUi()
        }

        seekPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = controller?.duration ?: 0
                    if (d > 0) tvPos.text = fmt(d * p / 1000)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
                val c = controller ?: return
                val d = c.duration
                if (d > 0) c.seekTo(d * seekPos.progress / 1000)
            }
        })

        // Tốc độ
        val paramListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = applyParams()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        seekSpeed.setOnSeekBarChangeListener(paramListener)
        seekPitch.setOnSeekBarChangeListener(paramListener)
        findViewById<Button>(R.id.btnSpeedMinus).setOnClickListener { seekSpeed.progress -= 1 }
        findViewById<Button>(R.id.btnSpeedPlus).setOnClickListener { seekSpeed.progress += 1 }
        findViewById<Button>(R.id.btnPitchMinus).setOnClickListener { seekPitch.progress -= 1 }
        findViewById<Button>(R.id.btnPitchPlus).setOnClickListener { seekPitch.progress += 1 }
        cbTape.setOnCheckedChangeListener { _, _ -> applyParams() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            seekSpeed.progress = speedToProgress(1f)
            seekPitch.progress = 12
            cbTape.isChecked = false
            applyParams()
        }

        val presetRow = findViewById<LinearLayout>(R.id.presetRow)
        for (v in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            val b = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = if (v == 1f) "1x" else "${v}x".replace(".0x", "x")
            b.textSize = 13f
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(0, 0, 0, 0)
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 2; marginEnd = 2 }
            b.setOnClickListener { seekSpeed.progress = speedToProgress(v) }
            presetRow.addView(b)
        }

        // A-B
        findViewById<Button>(R.id.btnA).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            Shared.abA = c.currentPosition
            if (Shared.abB in 0..Shared.abA) Shared.abB = -1L
            updateUi()
        }
        findViewById<Button>(R.id.btnB).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (Shared.abA < 0) { toast("Hãy đặt điểm A trước"); return@setOnClickListener }
            val p = c.currentPosition
            if (p <= Shared.abA) { toast("Điểm B phải sau điểm A"); return@setOnClickListener }
            Shared.abB = p
            c.seekTo(Shared.abA)
            updateUi()
        }
        findViewById<Button>(R.id.btnClearAB).setOnClickListener {
            Shared.abA = -1L; Shared.abB = -1L; updateUi()
        }

        // Hẹn giờ
        findViewById<Button>(R.id.btnSleep).setOnClickListener { showSleepDialog() }
        cbFade.setOnCheckedChangeListener { _, checked ->
            Shared.fadeOut = checked
            prefs.edit().putBoolean("fade", checked).apply()
        }

        // Danh sách
        findViewById<Button>(R.id.btnAdd).setOnClickListener { pickFiles() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.mediaItemCount == 0) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage("Xóa toàn bộ danh sách phát?")
                .setPositiveButton("Xóa") { _, _ -> c.stop(); c.clearMediaItems() }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun pickFiles() {
        try {
            picker.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            toast("Không mở được trình chọn file")
        }
    }

    private fun showSleepDialog() {
        val labels = arrayOf(
            "Tắt hẹn giờ", "5 phút", "10 phút", "15 phút", "20 phút", "30 phút",
            "45 phút", "60 phút", "90 phút", "Khi hết bài đang phát", "Tự nhập số phút…"
        )
        val minutes = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("Hẹn giờ tắt nhạc")
            .setItems(labels) { _, which ->
                when {
                    which == 0 -> { Shared.sleepEndAt = 0L; Shared.sleepEndOfTrack = false }
                    which < minutes.size -> setSleepMinutes(minutes[which])
                    which == 9 -> { Shared.sleepEndAt = 0L; Shared.sleepEndOfTrack = true }
                    else -> askCustomMinutes()
                }
                updateUi()
            }
            .show()
    }

    private fun askCustomMinutes() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Số phút"
        }
        AlertDialog.Builder(this)
            .setTitle("Tắt sau bao nhiêu phút?")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val m = input.text.toString().toIntOrNull()
                if (m != null && m > 0) setSleepMinutes(m) else toast("Số phút không hợp lệ")
                updateUi()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun setSleepMinutes(m: Int) {
        Shared.sleepEndOfTrack = false
        Shared.sleepEndAt = SystemClock.elapsedRealtime() + m * 60_000L
        toast("Nhạc sẽ tắt sau $m phút")
    }

    // ================== Tốc độ / cao độ ==================

    private fun progressToSpeed(p: Int) = 0.25f + p * 0.05f
    private fun speedToProgress(s: Float) = ((s - 0.25f) / 0.05f).roundToInt()

    private fun applyParams() {
        val speed = progressToSpeed(seekSpeed.progress)
        val semis = seekPitch.progress - 12
        val semiFactor = 2.0.pow(semis / 12.0).toFloat()
        val pitch = if (cbTape.isChecked) speed * semiFactor else semiFactor
        controller?.playbackParameters = PlaybackParameters(speed, pitch)
        prefs.edit()
            .putInt("speed", seekSpeed.progress)
            .putInt("pitch", seekPitch.progress)
            .putBoolean("tape", cbTape.isChecked)
            .apply()
        updateLabels()
    }

    private fun updateLabels() {
        tvSpeed.text = String.format(java.util.Locale.US, "%.2fx", progressToSpeed(seekSpeed.progress))
        val semis = seekPitch.progress - 12
        val sign = if (semis > 0) "+" else ""
        tvPitch.text = if (cbTape.isChecked) "$sign$semis nửa cung (+ theo tốc độ)" else "$sign$semis nửa cung"
    }

    // ================== Danh sách phát ==================

    private fun makeItem(uri: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(uri)).build())
            .build()

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (_: Exception) { }
        val n = name ?: Uri.decode(uri.lastPathSegment ?: "Bài hát").substringAfterLast('/')
        return n.substringBeforeLast('.', n)
    }

    private fun addUris(uris: List<Uri>, playFirstAdded: Boolean) {
        if (uris.isEmpty()) return
        val c = controller ?: run { toast("Trình phát chưa sẵn sàng, thử lại"); return }
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        val wasEmpty = c.mediaItemCount == 0
        val firstNew = c.mediaItemCount
        c.addMediaItems(uris.map { makeItem(it) })
        if (playFirstAdded || wasEmpty) {
            c.seekTo(firstNew, 0)
            c.prepare()
            c.play()
        }
    }

    private fun savePlaylist() {
        val c = controller ?: return
        val sb = StringBuilder()
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            val u = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                ?: Uri.parse(item.mediaId)
            sb.append(u.toString()).append('\n')
        }
        prefs.edit().putString("list", sb.toString()).apply()
    }

    private fun restorePlaylist(c: MediaController) {
        val saved = prefs.getString("list", "") ?: ""
        val uris = saved.split('\n').filter { it.isNotBlank() }.map { Uri.parse(it) }
        if (uris.isEmpty()) return
        val idx = prefs.getInt("idx", 0).coerceIn(0, uris.size - 1)
        val pos = prefs.getLong("posMs", 0L).coerceAtLeast(0L)
        c.setMediaItems(uris.map { makeItem(it) }, idx, pos)
        c.prepare()
    }

    private fun renderPlaylist() {
        val c = controller ?: return
        playlistBox.removeAllViews()
        val cur = c.currentMediaItemIndex
        val accent = MaterialColors.getColor(playlistBox, androidx.appcompat.R.attr.colorPrimary)
        val normal = MaterialColors.getColor(playlistBox, com.google.android.material.R.attr.colorOnSurface)
        if (c.mediaItemCount == 0) {
            playlistBox.addView(TextView(this).apply {
                text = "Danh sách trống."
                setPadding(8, 24, 8, 24)
            })
        }
        for (i in 0 until c.mediaItemCount) {
            val title = c.getMediaItemAt(i).mediaMetadata.title ?: "Bài ${i + 1}"
            val tv = TextView(this)
            tv.text = "${i + 1}. $title"
            tv.textSize = 16f
            tv.setPadding(12, 28, 12, 28)
            if (i == cur) {
                tv.setTypeface(null, Typeface.BOLD)
                tv.setTextColor(accent)
                tv.text = "▶ ${i + 1}. $title"
            } else {
                tv.setTextColor(normal)
            }
            tv.setOnClickListener { c.seekTo(i, 0); c.prepare(); c.play() }
            tv.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Xóa “$title” khỏi danh sách?")
                    .setPositiveButton("Xóa") { _, _ ->
                        if (i < c.mediaItemCount) c.removeMediaItem(i)
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
                true
            }
            playlistBox.addView(tv)
        }
        lastRenderedIndex = cur
    }

    // ================== Cập nhật giao diện ==================

    private fun updateUi() {
        val c = controller
        if (c == null) return

        val item = c.currentMediaItem
        tvTitle.text = item?.mediaMetadata?.title?.toString()
            ?: if (c.mediaItemCount == 0) "Chưa có bài nào – bấm “＋ Thêm bài”" else "…"

        val d = c.duration
        val p = c.currentPosition
        if (!userSeeking) {
            tvPos.text = fmt(p)
            seekPos.progress = if (d > 0) (p * 1000 / d).toInt() else 0
        }
        tvDur.text = if (d > 0) fmt(d) else "0:00"
        btnPlay.text = if (c.isPlaying) "⏸" else "▶"

        btnRepeat.text = when (c.repeatMode) {
            Player.REPEAT_MODE_ALL -> "Lặp: Tất cả"
            Player.REPEAT_MODE_ONE -> "Lặp: 1 bài"
            else -> "Lặp: Tắt"
        }
        btnShuffle.text = if (c.shuffleModeEnabled) "Trộn: Bật" else "Trộn: Tắt"

        val a = Shared.abA
        val b = Shared.abB
        tvAB.text = when {
            a >= 0 && b > a -> "Đang lặp  A ${fmt(a)}  →  B ${fmt(b)}"
            a >= 0 -> "A = ${fmt(a)}  (chờ đặt B)"
            else -> "Chưa đặt"
        }

        tvSleep.text = when {
            Shared.sleepEndAt > 0 -> {
                val remain = max(0L, Shared.sleepEndAt - SystemClock.elapsedRealtime())
                "Tắt sau ${fmt(remain)}"
            }
            Shared.sleepEndOfTrack -> "Tắt khi hết bài này"
            else -> "Chưa hẹn giờ"
        }

        if (c.currentMediaItemIndex != lastRenderedIndex) renderPlaylist()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
