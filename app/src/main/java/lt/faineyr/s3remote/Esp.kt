package lt.faineyr.s3remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** ESP būsena iš /app/state */
data class EspState(
    val title: String, val id: String, val pos: Long, val dur: Long,
    val playing: Boolean, val vol: Int, val paused: Boolean,
    val queue: List<Track>, val need: List<String>
)

/**
 * ESP32 API (S3Player /app/...). Raktas siunčiamas antrašte X-Key.
 *   GET  /app/state                         -> EspState JSON
 *   POST /app/add   {play, items:[{id,t,d,u,e}]}
 *   POST /app/urls  {items:[{id,u,e}]}       – atnaujintos nuorodos
 *   GET  /app/cmd?c=next|stop|clear|seek|vol|play|del&v=...
 */
class Esp(private val host: String, private val key: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun base() = "http://" + host.trim().removePrefix("http://").trimEnd('/')

    private fun call(r: Request.Builder): String {
        http.newCall(r.header("X-Key", key).build()).execute().use {
            val body = it.body?.string() ?: ""
            if (it.code == 401) throw Exception("Neteisingas API raktas")
            if (!it.isSuccessful) throw Exception("ESP klaida ${it.code}: $body")
            return body
        }
    }

    private fun post(path: String, json: JSONObject) = call(
        Request.Builder().url(base() + path)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
    )

    fun state(): EspState {
        val o = JSONObject(call(Request.Builder().url(base() + "/app/state")))
        val q = o.optJSONArray("queue") ?: JSONArray()
        val need = o.optJSONArray("need") ?: JSONArray()
        return EspState(
            o.optString("title"), o.optString("id"), o.optLong("pos"), o.optLong("dur"),
            o.optBoolean("playing"), o.optInt("vol"), o.optBoolean("paused"),
            (0 until q.length()).map { q.getJSONObject(it) }.map {
                Track(it.getString("id"), it.optString("t"), it.optLong("d"),
                    exp = it.optLong("e"))
            },
            (0 until need.length()).map { need.getString(it) }
        )
    }

    fun add(items: List<Track>, play: Boolean) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.dur)
                .put("u", it.url ?: "").put("e", it.exp))
        }
        post("/app/add", JSONObject().put("play", play).put("items", a))
    }

    fun urls(items: List<Track>) {
        val a = JSONArray()
        items.filter { it.url != null }.forEach {
            a.put(JSONObject().put("id", it.id).put("u", it.url).put("e", it.exp))
        }
        if (a.length() > 0) post("/app/urls", JSONObject().put("items", a))
    }

    fun cmd(c: String, v: String = "") {
        call(Request.Builder().url(
            base() + "/app/cmd?c=$c&v=" + URLEncoder.encode(v, "UTF-8")))
    }
}
