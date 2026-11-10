package lt.faineyr.s3remote

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Fono paslauga: telefonas tampa „S3 Player“ įrenginiu YouTube perdavimo sąraše.
 * YouTube atsiunčia dainų ID -> gaunam nuorodas (NewPipeExtractor) -> siunčiam į ESP.
 */
class CastService : Service() {
    companion object {
        const val CH = "cast"
        @Volatile var isRunning = false
        fun start(c: Context) {
            val i = Intent(c, CastService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, CastService::class.java)) }
    }

    private val prefs by lazy { getSharedPreferences("s3", MODE_PRIVATE) }
    private val espExec = Executors.newSingleThreadExecutor()     // ESP veiksmai griežta eile
    private var dial: DialServer? = null
    private val sessions = mutableMapOf<String, LoungeSession>()
    @Volatile private var active: LoungeSession? = null
    private var mlock: WifiManager.MulticastLock? = null
    @Volatile private var alive = false
    @Volatile private var castIds: List<String> = emptyList()
    @Volatile private var gen = 0                                  // naujas setPlaylist atšaukia seną pildymą
    private var last: EspState? = null
    private var lastSentAt = 0L
    private var lastTitle = ""

    private fun esp(): Esp? {
        val h = prefs.getString("host", "") ?: ""
        return if (h.isBlank()) null else Esp(h, prefs.getString("key", "") ?: "")
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "YouTube perdavimas", NotificationManager.IMPORTANCE_LOW))
        val n = notif("Laukiama YouTube…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
        isRunning = true
        alive = true

        mlock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("s3cast").apply { setReferenceCounted(false); acquire() }

        val name = prefs.getString("castName", "S3 Player") ?: "S3 Player"
        val store: (String, String?) -> String? = { k, v ->
            if (v != null) { prefs.edit().putString("cast.$k", v).apply(); v } else prefs.getString("cast.$k", null)
        }
        val uuid = store("uuid", null) ?: UUID.randomUUID().toString().also { store("uuid", it) }
        val pid = store("pid", null) ?: UUID.randomUUID().toString().also { store("pid", it) }

        for (theme in listOf("cl", "m")) sessions[theme] = LoungeSession(theme, name, store, ::onMessage)
        thread(name = "cast-begin") {
            for ((t, s) in sessions) {
                var tries = 0
                while (alive && !s.running) {
                    try { s.begin() } catch (e: Exception) {
                        Log.w("S3Cast", "($t) begin: ${e.message}")
                        Thread.sleep(minOf(60_000L, 5_000L * ++tries))
                    }
                }
            }
            update("Paruošta – YouTube: „Pasirinkti įrenginį“ → $name")
        }
        dial = DialServer(name, uuid, pid) { theme, code ->
            val s = sessions[theme] ?: sessions["cl"]!!
            s.registerPairingCode(code)
            active = s
        }.also { it.start(::wifiIp) }

        thread(name = "cast-poll") { pollLoop() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        alive = false; isRunning = false
        dial?.stop()
        sessions.values.forEach { it.stop() }
        runCatching { mlock?.release() }
        espExec.shutdownNow()
        super.onDestroy()
    }

    private fun wifiIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun notif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("S3 Player – perdavimas įjungtas")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(text))
    }

    // ---------------- YouTube -> ESP ----------------
    private fun onMessage(s: LoungeSession, m: LoungeMsg) {
        Log.i("S3Cast", "<- ${m.name} ${m.payload}")
        when (m.name) {
            "loungeStatus" -> {
                // aktyvus tas seansas, prie kurio prisijungė telefonas (REMOTE_CONTROL)
                if (m.str("devices").contains("REMOTE_CONTROL")) active = s
                s.send(listOf(
                    "onHasPreviousNextChanged" to mapOf("hasPrevious" to true, "hasNext" to true),
                    "onAutoplayModeChanged" to mapOf("autoplayMode" to "UNSUPPORTED")) + nowPlaying(last))
            }
            "getNowPlaying" -> s.send(nowPlaying(last))
            "getVolume" -> s.send(listOf(volMsg(last)))
            "setPlaylist" -> {
                active = s
                val ids = m.str("videoIds").split(",").filter { it.isNotBlank() }
                val cur = m.str("videoId").ifBlank { ids.getOrNull(m.str("currentIndex").toIntOrNull() ?: 0) } ?: return
                val t = m.str("currentTime").toDoubleOrNull()?.toInt() ?: 0
                castIds = ids
                val g = ++gen
                espExec.execute { playNow(cur, t) }
                val rest = ids.dropWhile { it != cur }.drop(1)
                espExec.execute { appendIds(rest, g) }
            }
            "updatePlaylist" -> {
                val ids = m.str("videoIds").split(",").filter { it.isNotBlank() }
                val new = ids.filter { it !in castIds }
                castIds = ids
                val g = gen
                if (new.isNotEmpty()) espExec.execute { appendIds(new, g) }
            }
            "next" -> espExec.execute { runCatching { esp()?.cmd("next") } }
            "previous" -> {
                val cur = last?.id ?: return
                val i = castIds.indexOf(cur)
                if (i > 0) espExec.execute { playNow(castIds[i - 1], 0) }
            }
            "pause" -> espExec.execute { runCatching { esp()?.cmd("pause") } }
            "play" -> espExec.execute { runCatching { esp()?.cmd("resume") } }
            "stopVideo" -> espExec.execute { runCatching { esp()?.cmd("stop") } }
            "seekTo" -> {
                val t = m.str("newTime").toDoubleOrNull()?.toInt() ?: return
                espExec.execute { runCatching { esp()?.cmd("seek", t.toString()) } }
            }
            "setVolume" -> {
                val v = m.str("volume").toIntOrNull() ?: return
                espExec.execute { runCatching { esp()?.cmd("vol", ((v * 21 + 50) / 100).toString()) } }
            }
        }
    }

    private fun playNow(id: String, t: Int) {
        val e = esp() ?: return update("Nenurodytas ESP adresas")
        try {
            val tr = Yt.info(id)
            e.add(listOf(tr), play = true)
            if (t > 3) e.cmd("seek", t.toString())
        } catch (ex: Exception) {
            Log.e("S3Cast", "playNow", ex); update("Klaida: ${ex.message}")
        }
    }

    /** Likusias grojaraščio dainas pridedam po vieną (su pavadinimu ir nuoroda). */
    private fun appendIds(ids: List<String>, g: Int) {
        val e = esp() ?: return
        for (id in ids.take(49)) {
            if (g != gen || !alive) return
            val tr = runCatching { Yt.info(id) }.getOrElse { Track(id, "", 0) }
            runCatching { e.add(listOf(tr), play = false) }
        }
    }

    // ---------------- ESP -> YouTube (būsena) ----------------
    private fun pollLoop() {
        while (alive) {
            try {
                val st = esp()?.state()
                val s = active
                if (st != null) {
                    val p = last
                    val changed = p == null || p.id != st.id || p.playing != st.playing || p.paused != st.paused
                    if (s != null && (changed || (st.playing && System.currentTimeMillis() - lastSentAt > 10_000))) {
                        s.send(nowPlaying(st) + listOf("onStateChange" to stateMap(st)))
                        lastSentAt = System.currentTimeMillis()
                    }
                    if (s != null && p != null && p.vol != st.vol) s.send(listOf(volMsg(st)))
                    last = st
                    val title = if (st.id.isNotEmpty()) st.title else "Paruošta"
                    if (title != lastTitle) { lastTitle = title; update(title) }
                }
            } catch (_: Exception) { }
            Thread.sleep(2000)
        }
    }

    private fun playerState(st: EspState?) = when {
        st == null || st.id.isEmpty() -> -1
        st.paused -> 2
        st.playing -> 1
        else -> 3
    }

    private fun stateMap(st: EspState?): Map<String, Any?> = mapOf(
        "state" to playerState(st), "currentTime" to (st?.pos ?: 0), "duration" to (st?.dur ?: 0),
        "loadedTime" to (st?.dur ?: 0), "seekableStartTime" to 0, "seekableEndTime" to (st?.dur ?: 0),
        "cpn" to "s3player")

    private fun nowPlaying(st: EspState?): List<Pair<String, Map<String, Any?>>> {
        if (st == null || st.id.isEmpty()) return listOf("nowPlaying" to emptyMap())
        val m = stateMap(st).toMutableMap()
        m["videoId"] = st.id
        val i = castIds.indexOf(st.id)
        if (i >= 0) m["currentIndex"] = i
        return listOf("nowPlaying" to m)
    }

    private fun volMsg(st: EspState?) =
        "onVolumeChanged" to mapOf("volume" to ((st?.vol ?: 10) * 100 / 21), "muted" to false)
}
