package com.example.cdplayer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private val ACTION_PERM = "com.example.cdplayer.USB_PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var now: TextView
    private lateinit var listView: ListView
    private lateinit var playBtn: Button
    private var scsi: UsbScsi? = null
    private var tracks: List<Track> = emptyList()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var paused = false
    private var current = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else status.text = "USB 권한이 거부되었습니다."
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    stopPlayback()
                    scsi?.close(); scsi = null
                    tracks = emptyList(); showTracks()
                    status.text = "드라이브가 분리되었습니다."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val f = IntentFilter().apply {
            addAction(ACTION_PERM); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        connect()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); connect() }

    override fun onDestroy() {
        stopPlayback()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        scsi?.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }
        status = TextView(this).apply { textSize = 14f; text = "CD 드라이브를 연결하세요." }
        now = TextView(this).apply { textSize = 20f; text = "-"; setPadding(0, dp(12), 0, dp(12)) }
        val scan = Button(this).apply { text = "드라이브/디스크 다시 읽기"; setOnClickListener { connect() } }
        listView = ListView(this)
        listView.setOnItemClickListener { _, _, pos, _ -> play(pos) }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        playBtn = btn("▶") { togglePause() }
        bar.addView(btn("◀◀") { if (current > 0) play(current - 1) })
        bar.addView(playBtn)
        bar.addView(btn("■") { stopPlayback(); now.text = "정지" })
        bar.addView(btn("▶▶") { if (current + 1 < tracks.size) play(current + 1) })

        root.addView(status); root.addView(scan); root.addView(now)
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar)
        setContentView(root)
    }

    private fun connect() {
        if (scsi != null && tracks.isNotEmpty() && current >= 0) return
        val dev: UsbDevice? = usb.deviceList.values.firstOrNull { UsbScsi.hasMassStorage(it) }
        if (dev == null) { status.text = "USB 드라이브를 찾지 못했습니다. (OTG 연결/전원 확인)"; return }
        if (!usb.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(dev, pi)
            status.text = "USB 권한을 허용해 주세요."
            return
        }
        status.text = "드라이브 여는 중..."
        Thread {
            scsi?.close()
            val s = UsbScsi.open(usb, dev)
            if (s == null) { ui.post { status.text = "드라이브를 열 수 없습니다." }; return@Thread }
            scsi = s
            var ready = false
            for (i in 1..20) {
                ui.post { status.text = "디스크 준비 대기 중... ($i/20)" }
                if (s.testUnitReady()) { ready = true; break }
                Thread.sleep(1000)
            }
            if (!ready) { ui.post { status.text = "디스크가 준비되지 않았습니다. (디스크/전원 확인)" }; return@Thread }
            val t = s.readToc()
            ui.post {
                tracks = t; showTracks()
                status.text = if (t.isEmpty()) "오디오 트랙이 없습니다." else "트랙 ${t.size}개 • 곡을 눌러 재생"
            }
        }.start()
    }

    private fun showTracks() {
        val items = tracks.map {
            val sec = (it.endLba - it.startLba) / 75
            "트랙 %02d   %d:%02d".format(it.number, sec / 60, sec % 60)
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun togglePause() {
        if (current < 0) { if (tracks.isNotEmpty()) play(0); return }
        paused = !paused
        playBtn.text = if (paused) "▶" else "❚❚"
    }

    private fun stopPlayback() { gen++; current = -1; paused = false; playBtn.text = "▶" }

    private fun play(index: Int) {
        val s = scsi ?: return
        val track = tracks.getOrNull(index) ?: return
        gen++
        val g = gen
        current = index; paused = false; playBtn.text = "❚❚"
        now.text = "트랙 ${track.number}"
        Thread {
            val minBuf = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, 44100 * 4))
                .build()
            at.play()
            val chunk = 26
            val buf = ByteArray(chunk * 2352)
            var lba = track.startLba
            var failed = false
            var wasPaused = false
            while (g == gen && lba < track.endLba) {
                if (paused) {
                    if (!wasPaused) { at.pause(); wasPaused = true }
                    Thread.sleep(100); continue
                }
                if (wasPaused) { at.play(); wasPaused = false }
                val n = minOf(chunk, track.endLba - lba)
                var ok = false
                for (attempt in 1..3) { if (s.readCdda(lba, n, buf)) { ok = true; break } }
                if (!ok) { failed = true; break }
                at.write(buf, 0, n * 2352)
                lba += n
                val el = (lba - track.startLba) / 75
                ui.post { if (g == gen) now.text = "트랙 ${track.number}   %d:%02d".format(el / 60, el % 60) }
            }
            val finished = g == gen && !failed
            if (g == gen) { at.stop() } else { at.pause(); at.flush() }
            at.release()
            if (failed && g == gen) ui.post { status.text = "읽기 오류 (디스크 상태/전원 확인)"; stopPlayback() }
            else if (finished) ui.post {
                if (index + 1 < tracks.size) play(index + 1) else { stopPlayback(); now.text = "재생 끝" }
            }
        }.start()
    }
}
