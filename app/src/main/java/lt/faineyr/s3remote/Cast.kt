package lt.faineyr.s3remote

import android.util.Log
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * YouTube perdavimo („Pasirinkti įrenginį“) imtuvas: DIAL (SSDP + HTTP) + YouTube Lounge API.
 * Neoficialus protokolas, perkeltas iš atviro projekto yt-cast-receiver (patrickkfkan).
 * Jei YouTube jį pakeis – žr. README „Perdavimas nebeveikia“.
 */
private const val TAG = "S3Cast"
private const val YT = "https://www.youtube.com"
private const val BIND = "$YT/api/lounge/bc/bind"

data class LoungeMsg(val aid: Int, val name: String, val payload: Any?) {
    fun str(k: String): String = (payload as? JSONObject)?.optString(k, "") ?: ""
}

/** Vienas Lounge seansas (theme "cl" = YouTube, "m" = YouTube Music). */
class LoungeSession(
    private val theme: String,
    private val screenName: String,
    private val store: (String, String?) -> String?,          // (raktas, nauja reikšmė/null=skaityti)
    private val onMessage: (LoungeSession, LoungeMsg) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)                      // ilga apklausa
        .build()

    private var screenId = store("screenId.$theme", null) ?: ""
    private val deviceId = store("deviceId.$theme", null) ?: UUID.randomUUID().toString().also { store("deviceId.$theme", it) }
    private var loungeToken = ""
    private var sid = ""
    private var gsid = ""
    private var rid = 0
    private var aid = 3
    private var ofs = 0
    @Volatile var running = false; private set
    private var rpcCall: okhttp3.Call? = null

    private val deviceInfo = JSONObject()
        .put("brand", "Faineyr").put("model", "S3Player").put("year", 0)
        .put("os", "Android").put("osVersion", "").put("chipset", "")
        .put("clientName", "TVHTML5").put("dialAdditionalDataSupportLevel", "unsupported")
        .put("mdxDialServerType", "MDX_DIAL_SERVER_TYPE_UNKNOWN").toString()

    private fun zx() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun bindUrl(type: String): String {
        val b = BIND.toHttpUrl().newBuilder()
        listOf(
            "device" to "LOUNGE_SCREEN", "id" to deviceId, "obfuscatedGaiaId" to "",
            "name" to screenName, "app" to "ytcr", "theme" to theme,
            "capabilities" to "dsp,mic,dpa,ntb", "cst" to "m", "mdxVersion" to "2",
            "loungeIdToken" to loungeToken, "VER" to "8", "v" to "2", "zx" to zx(), "t" to "1"
        ).forEach { (k, v) -> b.addQueryParameter(k, v) }
        when (type) {
            "init" -> { b.addQueryParameter("deviceInfo", deviceInfo)
                b.addQueryParameter("RID", (rid++).toString()); b.addQueryParameter("CVER", "1") }
            "send" -> { b.addQueryParameter("deviceInfo", deviceInfo)
                b.addQueryParameter("SID", sid); b.addQueryParameter("RID", (rid++).toString())
                b.addQueryParameter("AID", aid.toString()); b.addQueryParameter("gsessionid", gsid) }
            "rpc" -> { b.addQueryParameter("RID", "rpc"); b.addQueryParameter("SID", sid)
                b.addQueryParameter("CI", "0"); b.addQueryParameter("AID", aid.toString())
                b.addQueryParameter("gsessionid", gsid); b.addQueryParameter("TYPE", "xmlhttp") }
        }
        return b.build().toString()
    }

    private fun post(url: String, form: Map<String, String>): String {
        val fb = FormBody.Builder(); form.forEach { (k, v) -> fb.add(k, v) }
        http.newCall(Request.Builder().url(url).post(fb.build()).build()).execute().use {
            val body = it.body?.string() ?: ""
            if (!it.isSuccessful) throw Exception("HTTP ${it.code} $url")
            return body
        }
    }

    /** Pradeda seansą: screenId -> loungeToken -> SID/gsessionid -> ilga apklausa. */
    @Synchronized fun begin() {
        if (screenId.isEmpty()) {
            http.newCall(Request.Builder().url("$YT/api/lounge/pairing/generate_screen_id").build())
                .execute().use { screenId = it.body!!.string().trim() }
            store("screenId.$theme", screenId)
        }
        val tok = JSONObject(post("$YT/api/lounge/pairing/get_lounge_token_batch", mapOf("screen_ids" to screenId)))
        loungeToken = tok.getJSONArray("screens").getJSONObject(0).getString("loungeToken")
        sid = ""; gsid = ""; aid = 3; ofs = 0
        rid = (41000..49999).random()
        val init = post(bindUrl("init"), mapOf("count" to "0"))
        for (m in parse(init)) handle(m)
        if (sid.isEmpty() || gsid.isEmpty()) throw Exception("Lounge: negautas SID")
        if (!running) { running = true; thread(name = "lounge-$theme") { rpcLoop() } }
        Log.i(TAG, "($theme) seansas paruoštas, screenId=$screenId")
    }

    private fun rpcLoop() {
        var fails = 0
        while (running) {
            try {
                val call = http.newCall(Request.Builder().url(bindUrl("rpc")).build())
                rpcCall = call
                call.execute().use { r ->
                    if (!r.isSuccessful) throw Exception("rpc HTTP ${r.code}")
                    fails = 0
                    val rd = BufferedReader(InputStreamReader(r.body!!.byteStream()))
                    while (running) {
                        val line = rd.readLine() ?: break
                        for (m in parse(line)) handle(m)
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "($theme) rpc: ${e.message}")
                fails++
                Thread.sleep(minOf(30_000L, 1000L * fails * fails))
                if (fails >= 3) runCatching { begin() }     // seanso/žetono atnaujinimas
            }
        }
    }

    private fun handle(m: LoungeMsg) {
        aid = maxOf(aid, m.aid)
        when (m.name) {
            "c" -> sid = (m.payload as? JSONArray)?.optString(0) ?: sid
            "S" -> gsid = m.payload?.toString() ?: gsid
            "noop" -> {}
            else -> runCatching { onMessage(this, m) }.onFailure { Log.e(TAG, "handler", it) }
        }
    }

    fun stop() {
        running = false
        rpcCall?.cancel()
        thread { runCatching { send(listOf("loungeScreenDisconnected" to emptyMap())) } }
    }

    fun registerPairingCode(code: String) {
        post("$YT/api/lounge/pairing/register_pairing_code", mapOf(
            "access_type" to "permanent", "app" to "ytcr", "pairing_code" to code,
            "screen_id" to screenId, "screen_name" to screenName, "device_id" to deviceId))
        Log.i(TAG, "($theme) susieta su telefonu")
    }

    /** Siunčia žinutes siuntėjui (YouTube programėlei). */
    @Synchronized fun send(msgs: List<Pair<String, Map<String, Any?>>>) {
        if (sid.isEmpty()) return
        val form = linkedMapOf("count" to msgs.size.toString(), "ofs" to ofs.toString())
        msgs.forEachIndexed { i, (name, p) ->
            form["req${i}__sc"] = name
            p.forEach { (k, v) -> if (v != null) form["req${i}_$k"] = v.toString() }
        }
        ofs += msgs.size
        try { post(bindUrl("send"), form) } catch (e: Exception) { Log.w(TAG, "send: ${e.message}") }
        aid++
    }

    companion object {
        private val RE = Regex("""\[(\d+),\["(.+?)"(?:,(.*?))?\]\]""")
        fun parse(data: String): List<LoungeMsg> = RE.findAll(data.replace("\n", "")).mapNotNull { m ->
            val p = m.groups[3]?.value
            val payload: Any? = if (p == null) null else try {
                val a = JSONArray("[$p]"); if (a.length() == 1) a.get(0) else a
            } catch (_: Exception) { null }
            m.groupValues[2].let { LoungeMsg(m.groupValues[1].toInt(), it, payload) }
        }.toList()
    }
}

/** DIAL HTTP serveris + SSDP atsakiklis, kad YouTube matytų „S3 Player“ tinkle. */
class DialServer(
    private val friendlyName: String,
    private val uuid: String,
    private val pid: String,
    private val onLaunch: (theme: String, pairingCode: String) -> Unit
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    var port = 0; private set

    fun start(ip: () -> String?) {
        running = true
        server = (runCatching { ServerSocket(36866) }.getOrNull() ?: ServerSocket(0)).also { port = it.localPort }
        thread(name = "dial-http") {
            while (running) {
                val s = try { server!!.accept() } catch (_: Exception) { break }
                thread { runCatching { serve(s, ip) }; runCatching { s.close() } }
            }
        }
        thread(name = "dial-ssdp") { ssdpLoop(ip) }
        Log.i(TAG, "DIAL serveris prievadas $port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { ssdp?.close() }
    }

    private fun base(ip: String) = "http://$ip:$port/ytcr"

    private fun serve(s: Socket, ip: () -> String?) {
        s.soTimeout = 10_000
        val inp = s.getInputStream().buffered()
        val reqLine = readLine(inp) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len in 1..8192) String(ByteArray(len).also { var o = 0; while (o < len) { val r = inp.read(it, o, len - o); if (r < 0) break; o += r } }) else ""
        val (method, path) = reqLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val myIp = ip() ?: s.localAddress.hostAddress
        val out = s.getOutputStream()
        fun reply(code: String, type: String? = null, data: String = "", extra: Map<String, String> = emptyMap()) {
            val b = data.toByteArray()
            val h = StringBuilder("HTTP/1.1 $code\r\nConnection: close\r\nContent-Length: ${b.size}\r\n")
            if (type != null) h.append("Content-Type: $type\r\n")
            extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
            out.write((h.toString() + "\r\n").toByteArray()); out.write(b); out.flush()
        }
        when {
            method == "GET" && path.startsWith("/ytcr/ssdp/device-desc.xml") -> reply("200 OK", "application/xml", """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<URLBase>${base(myIp)}</URLBase><device><deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
<friendlyName>$friendlyName</friendlyName><manufacturer>Faineyr</manufacturer><modelName>S3Player</modelName>
<UDN>uuid:$uuid</UDN><serviceList><service><serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
<serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId><controlURL>/ssdp/notfound</controlURL>
<eventSubURL>/ssdp/notfound</eventSubURL><SCPDURL>/ssdp/notfound</SCPDURL></service></serviceList></device></root>""",
                mapOf("Application-URL" to "${base(myIp)}/apps"))
            method == "GET" && path.startsWith("/ytcr/apps/YouTube") -> reply("200 OK", "application/xml", """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7"><name>YouTube</name>
<options allowStop="false"/><state>running</state><link rel="run" href="$pid"/></service>""")
            method == "POST" && path.startsWith("/ytcr/apps/YouTube") -> {
                val form = body.split("&").mapNotNull { kv ->
                    kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") }
                }.toMap()
                val code = form["pairingCode"]
                if (code == null) { reply("400 Bad Request"); return }
                try {
                    onLaunch(form["theme"] ?: "cl", code)
                    reply("201 Created", extra = mapOf("LOCATION" to "${base(myIp)}/apps/YouTube/$pid"))
                } catch (e: Exception) {
                    Log.e(TAG, "launch", e); reply("503 Service Unavailable")
                }
            }
            method == "GET" && path == "/ytcr/apps" -> reply("204 No Content")
            else -> reply("404 Not Found")
        }
    }

    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private val sts = listOf(
        "urn:dial-multiscreen-org:service:dial:1", "urn:dial-multiscreen-org:device:dial:1",
        "upnp:rootdevice", "ssdp:all", "uuid:$uuid")

    private fun ssdpLoop(ip: () -> String?) {
        val group = InetAddress.getByName("239.255.255.250")
        try {
            val sock = MulticastSocket(null).apply {
                reuseAddress = true; bind(InetSocketAddress(1900))
                @Suppress("DEPRECATION") joinGroup(group)
            }
            ssdp = sock
            val buf = ByteArray(2048)
            thread(name = "ssdp-notify") {       // retkarčiais skelbiamės patys
                while (running) {
                    ip()?.let { myIp -> sts.take(3).forEach { st ->
                        val msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
                            "LOCATION: ${base(myIp)}/ssdp/device-desc.xml\r\nNT: $st\r\nNTS: ssdp:alive\r\n" +
                            "SERVER: Android UPnP/1.1 S3Player/1.0\r\nUSN: uuid:$uuid::$st\r\n\r\n"
                        runCatching { sock.send(DatagramPacket(msg.toByteArray(), msg.length, group, 1900)) }
                    } }
                    Thread.sleep(60_000)
                }
            }
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val txt = String(p.data, 0, p.length)
                if (!txt.startsWith("M-SEARCH")) continue
                val st = Regex("""(?im)^ST:\s*(.+?)\s*$""").find(txt)?.groupValues?.get(1) ?: continue
                if (st !in sts) continue
                val myIp = ip() ?: continue
                val resp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
                    "LOCATION: ${base(myIp)}/ssdp/device-desc.xml\r\nSERVER: Android UPnP/1.1 S3Player/1.0\r\n" +
                    "ST: $st\r\nUSN: uuid:$uuid::$st\r\nBOOTID.UPNP.ORG: 7337\r\nCONFIGID.UPNP.ORG: 7337\r\n\r\n"
                DatagramSocket().use { it.send(DatagramPacket(resp.toByteArray(), resp.length, p.socketAddress)) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "SSDP klaida", e)
        }
    }
}
