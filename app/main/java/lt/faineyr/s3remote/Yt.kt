package lt.faineyr.s3remote

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/** Vienas kūrinys. url/exp – laikina googlevideo nuoroda ir jos galiojimo laikas (unix s). */
data class Track(
    val id: String,
    val title: String,
    val dur: Long,
    var url: String? = null,
    var exp: Long = 0
)

/**
 * YouTube dalis (NewPipeExtractor). Visi kvietimai blokuojantys – kviesti iš Dispatchers.IO.
 * SVARBU: naudojamas tik IPv4. googlevideo nuoroda pririšta prie IP (ip=...),
 * o ESP32 jungiasi per IPv4 – jei telefonas gautų nuorodą per IPv6, ESP gautų 403.
 */
object Yt {
    private val ipv4Dns = object : Dns {
        override fun lookup(hostname: String) =
            Dns.SYSTEM.lookup(hostname).filterIsInstance<Inet4Address>().ifEmpty {
                Dns.SYSTEM.lookup(hostname)
            }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4Dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private object Dl : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", UA)
            request.headers().forEach { (k, vs) ->
                b.removeHeader(k); vs.forEach { b.addHeader(k, it) }
            }
            http.newCall(b.build()).execute().use { r ->
                return Response(
                    r.code, r.message, r.headers.toMultimap(),
                    r.body?.string() ?: "", r.request.url.toString()
                )
            }
        }
    }

    @Volatile private var inited = false
    private fun init() {
        if (!inited) synchronized(this) {
            if (!inited) { NewPipe.init(Dl); inited = true }
        }
    }

    private val yt get() = ServiceList.YouTube

    /** Vaizdo ID iš bet kokios YouTube nuorodos (watch, youtu.be, shorts, music). */
    fun videoId(text: String): String? {
        val re = Regex("""(?:v=|youtu\.be/|shorts/|/live/)([A-Za-z0-9_-]{11})""")
        return re.find(text)?.groupValues?.get(1)
            ?: text.trim().takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
    }

    private fun listId(text: String) = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(text)?.groupValues?.get(1)

    private fun idOf(url: String) = videoId(url) ?: url

    /** Paieška arba grojaraštis/Mix (jei įklijuota nuoroda su list=). */
    fun search(query: String, max: Int = 30): List<Track> {
        init()
        val list = listId(query)
        if (list != null) return playlist(list, max)
        val vid = videoId(query)
        if (vid != null && query.contains("youtu")) return listOf(info(vid))
        val ex = yt.getSearchExtractor(
            yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        )
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>()
            .filter { it.duration > 0 }               // be tiesioginių transliacijų
            .take(max)
            .map { Track(idOf(it.url), it.name, it.duration) }
    }

    fun playlist(listId: String, max: Int = 50): List<Track> {
        init()
        val url = if (listId.startsWith("RD"))
            "https://www.youtube.com/watch?v=${listId.removePrefix("RD")}&list=$listId"
        else "https://www.youtube.com/playlist?list=$listId"
        val pl = PlaylistInfo.getInfo(yt, url)
        return pl.relatedItems
            .filter { it.duration > 0 && !it.name.startsWith("[") && it.name != "NA" }
            .take(max)
            .map { Track(idOf(it.url), it.name, it.duration) }
    }

    /** Vienas vaizdas su pavadinimu ir nuoroda. */
    fun info(id: String): Track {
        init()
        val i = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        val t = Track(id, i.name, i.duration)
        pick140(i)?.let { t.url = it; t.exp = expireOf(it) }
        return t
    }

    /** Gauna itag 140 (M4A/AAC) nuorodą. null – jei nėra. */
    fun resolve(t: Track): Boolean {
        init()
        val i = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=${t.id}")
        val u = pick140(i) ?: return false
        t.url = u; t.exp = expireOf(u)
        return true
    }

    private fun pick140(i: StreamInfo): String? =
        i.audioStreams.firstOrNull { it.itag == 140 && it.isUrl }?.content

    fun expireOf(url: String): Long =
        Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0
}
