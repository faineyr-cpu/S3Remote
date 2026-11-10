package lt.faineyr.s3remote

import android.content.Intent
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("s3", MODE_PRIVATE) }
    private var esp: Esp? = null
    private var results: List<Track> = emptyList()
    private var state: EspState? = null
    private var pollJob: Job? = null
    private var resolving = false
    private var userSeeking = false
    private var userVol = false
    private var tab = 0                              // 0 = paieška, 1 = eilė
    private val failed = mutableSetOf<String>()   // ID, kurių nepavyko gauti – nebandyti kas 2 s

    private lateinit var status: TextView
    private lateinit var dot: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var seek: Slider
    private lateinit var vol: Slider
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var tabs: TabLayout
    private lateinit var listInfo: TextView
    private lateinit var listAction: MaterialButton
    private lateinit var busy: View
    private lateinit var resAdapter: TrackAdapter
    private lateinit var queueAdapter: TrackAdapter
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Android 15: turinys piešiamas po sistemos juostomis -> pridedam atitraukimus
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }
        status = findViewById(R.id.status)
        dot = findViewById(R.id.dot)
        nowTitle = findViewById(R.id.nowTitle)
        seek = findViewById(R.id.seek)
        vol = findViewById(R.id.vol)
        posText = findViewById(R.id.posText)
        durText = findViewById(R.id.durText)
        tabs = findViewById(R.id.tabs)
        listInfo = findViewById(R.id.listInfo)
        listAction = findViewById(R.id.btnListAction)
        busy = findViewById(R.id.busy)
        list = findViewById(R.id.list)
        setDot(false)

        // Rezultatai: paspaudus – groti dabar, "+" – į eilės galą
        resAdapter = TrackAdapter(R.drawable.ic_add,
            onClick = { t -> addTracks(listOf(t), play = true) },
            onAction = { t -> addTracks(listOf(t), play = false) })
        // Eilė: paspaudus – groti šią, "✕" – pašalinti
        queueAdapter = TrackAdapter(R.drawable.ic_close,
            onClick = { t -> espCmd("play", t.id) },
            onAction = { t -> espCmd("del", t.id) })
        list.layoutManager = LinearLayoutManager(this)

        tabs.addTab(tabs.newTab().setText("Paieška"))
        tabs.addTab(tabs.newTab().setText("Eilė"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { showTab(t.position) }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        showTab(0)

        val query = findViewById<EditText>(R.id.query)
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                search(query.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(query.windowToken, 0)
                true
            } else false
        }
        listAction.setOnClickListener {
            if (tab == 0) { if (results.isNotEmpty()) addTracks(results, play = true, replace = true) }
            else AlertDialog.Builder(this).setMessage("Išvalyti eilę?")
                .setPositiveButton("Taip") { _, _ -> espCmd("clear") }
                .setNegativeButton("Ne", null).show()
        }
        findViewById<View>(R.id.btnStop).setOnClickListener { espCmd("stop") }
        findViewById<View>(R.id.btnNext).setOnClickListener { espCmd("next") }
        findViewById<View>(R.id.btnSettings).setOnClickListener { settingsDialog() }

        seek.setLabelFormatter { fmt(it.toLong()) }
        seek.addOnChangeListener { _, v, fromUser -> if (fromUser) posText.text = fmt(v.toLong()) }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { userSeeking = true }
            override fun onStopTrackingTouch(s: Slider) {
                userSeeking = false; espCmd("seek", s.value.toInt().toString())
            }
        })
        vol.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { userVol = true }
            override fun onStopTrackingTouch(s: Slider) {
                userVol = false; espCmd("vol", s.value.toInt().toString())
            }
        })

        connect()
        if (esp == null) settingsDialog()
        applyCast()
        handleShare(intent)
    }

    /** Įjungia/išjungia YouTube perdavimo paslaugą pagal nustatymą. */
    private fun applyCast() {
        if (prefs.getBoolean("cast", false)) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            CastService.start(this)
        } else if (CastService.isRunning) CastService.stop(this)
    }

    private fun showTab(i: Int) {
        tab = i
        list.adapter = if (i == 0) resAdapter else queueAdapter
        updateListBar()
    }

    private fun updateListBar() {
        if (tab == 0) {
            listInfo.text = if (results.isEmpty()) "Įvesk paiešką viršuje" else "Rasta: ${results.size}"
            listAction.text = "Groti visus"; listAction.setIconResource(R.drawable.ic_play)
            listAction.isEnabled = results.isNotEmpty()
        } else {
            val n = state?.queue?.size ?: 0
            listInfo.text = if (n == 0) "Eilė tuščia" else "Laukia: $n"
            listAction.text = "Valyti"; listAction.setIconResource(R.drawable.ic_close)
            listAction.isEnabled = n > 0
        }
        tabs.getTabAt(1)?.text = "Eilė" + (state?.queue?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: "")
    }

    private fun setDot(ok: Boolean) {
        dot.setColorFilter(if (ok) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); handleShare(intent)
    }

    /** "Bendrinti" iš YouTube -> pridėti į eilę */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        lifecycleScope.launch {
            try {
                busy.visibility = View.VISIBLE
                val tracks = withContext(Dispatchers.IO) { Yt.search(text) }
                if (tracks.isEmpty()) toast("Nuorodoje nerasta kūrinių")
                else addTracks(tracks, play = state?.playing != true)
            } catch (e: Exception) { toast("Klaida: ${e.message}") }
            finally { busy.visibility = View.INVISIBLE }
        }
    }

    override fun onResume() {
        super.onResume()
        pollJob = lifecycleScope.launch {
            while (isActive) { refresh(); delay(2000) }
        }
    }

    override fun onPause() { super.onPause(); pollJob?.cancel() }

    private fun connect() {
        val host = prefs.getString("host", "") ?: ""
        esp = if (host.isBlank()) null else Esp(host, prefs.getString("key", "") ?: "")
    }

    // ---------- ESP būsena ir nuorodų atnaujinimas ----------
    private suspend fun refresh() {
        val e = esp ?: run { status.text = "Nenurodytas ESP adresas (⋮)"; setDot(false); return }
        try {
            val s = withContext(Dispatchers.IO) { e.state() }
            state = s
            setDot(true)
            status.text = when {
                s.need.isNotEmpty() -> "ESP prisijungęs · atnaujinamos nuorodos (${s.need.size})"
                s.playing -> "ESP prisijungęs · groja"
                else -> "ESP prisijungęs"
            }
            nowTitle.text = s.title.ifBlank { "Niekas negroja" }
            if (!userSeeking) {
                val max = s.dur.coerceAtLeast(1).toFloat()
                seek.valueTo = max
                seek.value = s.pos.toFloat().coerceIn(0f, max)
                seek.isEnabled = s.dur > 0
                posText.text = fmt(s.pos); durText.text = fmt(s.dur)
            }
            if (!userVol) vol.value = s.vol.coerceIn(0, 21).toFloat()
            queueAdapter.submit(s.queue, s.id)
            updateListBar()
            refreshUrls(e, s.need)
        } catch (ex: Exception) {
            setDot(false)
            status.text = "ESP nepasiekiamas: ${ex.message}"
        }
    }

    /** ESP praneša, kurių kūrinių nuorodos pasibaigė/nėra – gauname naujas (po 5). */
    private fun refreshUrls(e: Esp, need: List<String>) {
        val todo = need.filter { it !in failed }.take(5)
        if (resolving || todo.isEmpty()) return
        resolving = true
        lifecycleScope.launch {
            try {
                val got = withContext(Dispatchers.IO) {
                    todo.mapNotNull { id ->
                        val t = Track(id, "", 0)
                        try { if (Yt.resolve(t)) t else { failed += id; null } }
                        catch (_: Exception) { failed += id; null }
                    }.also { e.urls(it) }
                }
                if (got.size < todo.size) toast("Kai kurių kūrinių gauti nepavyko (gal privatūs)")
            } catch (ex: Exception) {
                toast("Nuorodų atnaujinimo klaida: ${ex.message}")
            } finally { resolving = false }
        }
    }

    // ---------- Paieška ir pridėjimas ----------
    private fun search(q: String) {
        if (q.isBlank()) return
        tabs.getTabAt(0)?.select()
        busy.visibility = View.VISIBLE
        listInfo.text = "Ieškoma…"
        lifecycleScope.launch {
            try {
                results = withContext(Dispatchers.IO) { Yt.search(q.trim()) }
                resAdapter.submit(results, null)
                list.scrollToPosition(0)
            } catch (e: Exception) {
                toast("Paieškos klaida: ${e.message}")
            } finally { busy.visibility = View.INVISIBLE; updateListBar() }
        }
    }

    /** Pirmo kūrinio nuorodą gauname iš karto (kad grotų greitai), kitas – ESP paprašys pats. */
    private fun addTracks(tracks: List<Track>, play: Boolean, replace: Boolean = false) {
        val e = esp ?: return toast("Nustatyk ESP adresą (⋮)")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val list = tracks.map { it.copy() }
                    list.take(if (play) 1 else 0).forEach { runCatching { Yt.resolve(it) } }
                    if (replace) e.cmd("clear")
                    e.add(list, play)
                }
                toast(if (play) "Grojama" else "Pridėta į eilę: ${tracks.size}")
                refresh()
            } catch (ex: Exception) { toast("Klaida: ${ex.message}") }
        }
    }

    private fun espCmd(c: String, v: String = "") {
        val e = esp ?: return toast("Nustatyk ESP adresą (⋮)")
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { e.cmd(c, v) }; refresh() }
            catch (ex: Exception) { toast("Klaida: ${ex.message}") }
        }
    }

    // ---------- Nustatymai + automatinė ESP paieška tinkle (mDNS) ----------
    private fun settingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val host = EditText(this).apply {
            hint = "ESP adresas, pvz. 192.168.0.35"; setText(prefs.getString("host", ""))
        }
        // Raktas paslėptas (••••); akutės mygtukas leidžia laikinai parodyti
        val keyBox = com.google.android.material.textfield.TextInputLayout(this).apply {
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val key = com.google.android.material.textfield.TextInputEditText(keyBox.context).apply {
            hint = "API raktas"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        keyBox.addView(key)
        val find = Button(this).apply { text = "Rasti ESP tinkle" }
        val cast = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Rodyti YouTube „Pasirinkti įrenginį“ sąraše"
            isChecked = prefs.getBoolean("cast", false)
            setPadding(0, 30, 0, 0)
        }
        val castName = EditText(this).apply {
            hint = "Pavadinimas sąraše"; setText(prefs.getString("castName", "S3 Player"))
        }
        box.addView(host); box.addView(keyBox); box.addView(find); box.addView(cast); box.addView(castName)
        find.setOnClickListener {
            find.text = "Ieškoma..."
            discover { ip ->
                runOnUiThread {
                    if (ip != null) { host.setText(ip); find.text = "Rasta: $ip" }
                    else find.text = "Nerasta – įvesk IP ranka"
                }
            }
        }
        AlertDialog.Builder(this).setTitle("ESP nustatymai").setView(box)
            .setPositiveButton("Išsaugoti") { _, _ ->
                val newName = castName.text.toString().trim().ifBlank { "S3 Player" }
                val restart = cast.isChecked && CastService.isRunning && newName != prefs.getString("castName", "S3 Player")
                prefs.edit().putString("host", host.text.toString().trim())
                    .putString("key", key.text.toString().trim())
                    .putBoolean("cast", cast.isChecked).putString("castName", newName).apply()
                failed.clear(); connect()
                if (restart) CastService.stop(this)
                applyCast()
            }
            .setNegativeButton("Atšaukti", null).show()
    }

    /** ESP skelbia paslaugą "_s3player._tcp" (MDNS.addService). */
    private fun discover(done: (String?) -> Unit) {
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        var finished = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(si: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        if (!finished) { finished = true; done(r.host?.hostAddress) }
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, err: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, err: Int) {
                if (!finished) { finished = true; done(null) }
            }
            override fun onStopDiscoveryFailed(t: String, err: Int) {}
        }
        nsd.discoverServices("_s3player._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        lifecycleScope.launch {
            delay(8000)
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (!finished) { finished = true; done(null) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        fun fmt(s: Long) = if (s <= 0) "0:00" else "%d:%02d".format(s / 60, s % 60)
    }
}

/** Bendras sąrašo adapteris rezultatams ir eilei. */
class TrackAdapter(
    private val actionIcon: Int,
    private val onClick: (Track) -> Unit,
    private val onAction: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {
    private var items: List<Track> = emptyList()
    private var currentId: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val title: TextView = v.findViewById(R.id.title)
        val info: TextView = v.findViewById(R.id.info)
        val action: ImageButton = v.findViewById(R.id.action)
    }

    fun submit(list: List<Track>, current: String?) {
        if (list == items && current == currentId) return
        items = list; currentId = current; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.row, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = items[i]
        val playing = t.id == currentId
        h.num.text = if (playing) "▶" else "${i + 1}"
        h.title.text = t.title.ifBlank { t.id }
        h.title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
        h.info.text = MainActivity.fmt(t.dur)
        h.action.setImageResource(actionIcon)
        h.itemView.setOnClickListener { onClick(t) }
        h.action.setOnClickListener { onAction(t) }
    }
}
