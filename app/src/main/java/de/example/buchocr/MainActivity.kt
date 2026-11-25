package de.example.buchocr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Farben aus dem Design
private val BG = 0xFF0B0F12.toInt()
private val CARD = 0xFF161C22.toInt()
private val CARD_BORDER = 0xFF262E37.toInt()
private val GREEN = 0xFF3DE070.toInt()
private val TEXT = 0xFFFFFFFF.toInt()
private val MUTED = 0xFF8B95A1.toInt()
private val DANGER = 0xFFFF6B6B.toInt()

class MainActivity : AppCompatActivity() {

    private lateinit var countView: TextView
    private lateinit var statusHint: TextView
    private lateinit var editor: EditText
    private lateinit var runButton: Button
    private lateinit var chooseButton: Button
    private lateinit var cameraButton: Button
    private lateinit var panels: List<View>
    private lateinit var navTabs: List<TextView>

    private val pending = mutableListOf<Uri>()
    private var busy = false
    private var spellSession: SpellCheckerSession? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
            pending.add(uri)
        }
        processPending()
    }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(editor.text.toString())
            }
            Toast.makeText(this, "Textdatei gespeichert.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        // Seiten aus der Kamera-Ansicht übernehmen, ohne eigene Änderungen am Text zu verlieren.
        if (editor.text.toString() != ScanStore.text) editor.setText(ScanStore.text)
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        ScanStore.text = editor.text.toString()
    }

    // --- Aufbau ----------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float, strokeDp: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(20).toFloat(), 1, CARD_BORDER)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun lp(bottomDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottomDp) }

    /** Pillenform wie im Design: gefüllt (Akzent) oder umrandet. */
    private fun pill(text: String, filled: Boolean, color: Int = GREEN, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            elevation = 0f
            minHeight = dp(54)
            setTextColor(if (filled) Color.BLACK else color)
            background = if (filled) rounded(GREEN, dp(27).toFloat())
            else rounded(Color.TRANSPARENT, dp(27).toFloat(), 1, color)
            setOnClickListener { onClick() }
        }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val content = FrameLayout(this)
        panels = listOf(buildHome(), buildText(), buildSettings())
        panels.forEach { content.addView(it, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        // Ab Android 15 (targetSdk 35) zeichnet sich die App hinter Status- und Navigationsleiste.
        // Unten Platz für Navigationsleiste oder Bildschirmtastatur lassen.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun buildHome(): View {
        val col = column().apply { setPadding(dp(22), dp(20), dp(22), dp(16)) }
        col.addView(label("OCR", 34f, GREEN, true))
        col.addView(label(
            SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date()).uppercase(Locale.GERMANY),
            13f, GREEN
        ))
        col.addView(label("Texte aus Fotos", 28f, TEXT, true).apply { setPadding(0, dp(4), 0, dp(18)) })

        val statusCard = card()
        statusCard.addView(label("ERKANNTE SEITEN", 12f, MUTED))
        countView = label("0", 40f, TEXT, true)
        statusCard.addView(countView)
        statusHint = label("", 14f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        statusCard.addView(statusHint)
        col.addView(statusCard, lp(18))

        cameraButton = pill("Kamera öffnen", true) { startActivity(Intent(this, CameraActivity::class.java)) }
        chooseButton = pill("JPG-/PNG-Fotos auswählen", false) { picker.launch(arrayOf("image/jpeg", "image/png")) }
        runButton = pill("Ausgewählte Fotos erkennen", false) { processPending() }
        col.addView(cameraButton, lp(12))
        col.addView(chooseButton, lp(12))
        col.addView(runButton, lp(0))

        return ScrollView(this).apply { addView(col) }
    }

    private fun buildText(): View {
        val col = column().apply { setPadding(dp(22), dp(20), dp(22), dp(16)) }
        col.addView(label("Erkannter Text", 26f, TEXT, true), lp(14))
        editor = EditText(this).apply {
            hint = "Der erkannte Text erscheint hier …"
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            gravity = Gravity.TOP
            background = rounded(CARD, dp(20).toFloat(), 1, CARD_BORDER)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(14)
        })
        col.addView(pill("Als TXT speichern", true) { saveText() }, lp(12))
        col.addView(pill("Teilen", false) { shareText() }, lp(0))
        return col
    }

    private fun buildSettings(): View {
        val col = column().apply { setPadding(dp(22), dp(20), dp(22), dp(16)) }
        col.addView(label("Einstellungen", 26f, TEXT, true), lp(16))

        val spell = card()
        spell.addView(label("Rechtschreibung", 18f, TEXT, true))
        spell.addView(label(
            "Prüft den Text mit der Rechtschreibprüfung deines Handys und zeigt Vorschläge.",
            14f, MUTED
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        spell.addView(pill("Rechtschreibung prüfen", false) { spellCheck() }, lp(0))
        col.addView(spell, lp(14))

        val clear = card()
        clear.addView(label("Text löschen", 18f, TEXT, true))
        clear.addView(label(
            "Entfernt den erkannten Text und die Seitenzählung. Deine Fotos bleiben erhalten.",
            14f, MUTED
        ).apply { setPadding(0, dp(6), 0, dp(12)) })
        clear.addView(pill("Text löschen …", false, DANGER) { confirmClear() }, lp(0))
        col.addView(clear, lp(14))

        val privacy = card()
        privacy.addView(label("Datenschutz", 18f, TEXT, true))
        privacy.addView(label(
            "Die Texterkennung läuft offline auf deinem Gerät. Die App lädt keine Fotos hoch.",
            14f, MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(privacy)

        return ScrollView(this).apply { addView(col) }
    }

    private fun buildNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER
        }
        navTabs = listOf("Scannen", "Text", "Einstellungen").mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { showTab(i) }
            }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        return bar
    }

    private fun showTab(index: Int) {
        panels.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        navTabs.forEachIndexed { i, t ->
            val active = i == index
            t.background = if (active) rounded(GREEN, dp(22).toFloat()) else null
            t.setTextColor(if (active) Color.BLACK else MUTED)
            t.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun updateStatus() {
        countView.text = ScanStore.pageCount.toString()
        statusHint.text = when {
            pending.isNotEmpty() -> "${pending.size} Foto(s) warten auf Erkennung."
            ScanStore.pageCount == 0 -> "Noch keine Seiten erkannt."
            else -> "Du kannst den Text korrigieren, die Rechtschreibung prüfen und speichern."
        }
        runButton.isEnabled = !busy && pending.isNotEmpty()
        runButton.alpha = if (runButton.isEnabled) 1f else 0.4f
    }

    private fun setBusy(value: Boolean) {
        busy = value
        cameraButton.isEnabled = !value
        chooseButton.isEnabled = !value
        updateStatus()
    }

    // --- Erkennung -----------------------------------------------------------------

    /** Ausgewählte JPG/PNG-Dateien nacheinander erkennen. */
    private fun processPending() {
        if (busy || pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        setBusy(true)
        ScanStore.text = editor.text.toString()
        lifecycleScope.launch {
            var newPages = 0
            var skipped = 0
            try {
                batch.forEachIndexed { index, uri ->
                    statusHint.text = "Foto ${index + 1} von ${batch.size} wird erkannt …"
                    when (ScanStore.process(this@MainActivity, uri)) {
                        ScanStore.Outcome.NEW_PAGE -> newPages++
                        else -> skipped++
                    }
                    editor.setText(ScanStore.text)
                }
                statusHint.text = "$newPages Seite(n) neu erkannt" +
                    (if (skipped > 0) ", $skipped doppelt übersprungen" else "") + "."
            } catch (e: Exception) {
                statusHint.text = "Fehler bei der Texterkennung: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                setBusy(false)
                if (pending.isNotEmpty()) processPending()
                else updateStatus()
            }
        }
    }

    // --- Rechtschreibung --------------------------------------------------------------

    private fun spellCheck() {
        val text = editor.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Es gibt noch keinen Text zum Prüfen.", Toast.LENGTH_SHORT).show()
            return
        }
        val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
        val session = tsm?.newSpellCheckerSession(null, Locale.GERMAN, object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<SuggestionsInfo>?) {}

            override fun onGetSentenceSuggestions(results: Array<SentenceSuggestionsInfo>?) {
                spellSession?.close()
                spellSession = null
                val found = LinkedHashMap<String, List<String>>()
                results?.forEachIndexed { si, sent ->
                    val sentence = sentences.getOrNull(si) ?: return@forEachIndexed
                    for (i in 0 until sent.suggestionsCount) {
                        val info = sent.getSuggestionsInfoAt(i)
                        if (info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) continue
                        val off = sent.getOffsetAt(i)
                        val len = sent.getLengthAt(i)
                        if (off < 0 || off + len > sentence.length) continue
                        val word = sentence.substring(off, off + len)
                        if (word.length < 2 || word.any { it.isDigit() }) continue
                        val suggestions = (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }
                        found.putIfAbsent(word, suggestions)
                    }
                }
                val items = found.entries.map { it.key to it.value }
                runOnUiThread {
                    if (items.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Keine Rechtschreibfehler gefunden.", Toast.LENGTH_LONG).show()
                    } else {
                        reviewSpelling(items, 0)
                    }
                }
            }
        }, true)

        if (session == null || sentences.isEmpty()) {
            Toast.makeText(
                this,
                "Keine Rechtschreibprüfung verfügbar. Bitte eine deutsche Rechtschreibprüfung in den Tastatur-Einstellungen aktivieren.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        spellSession = session
        session.getSentenceSuggestions(sentences.map { TextInfo(it) }.toTypedArray(), 5)
    }

    private fun reviewSpelling(items: List<Pair<String, List<String>>>, index: Int) {
        if (index >= items.size) {
            Toast.makeText(this, "Rechtschreibprüfung abgeschlossen.", Toast.LENGTH_SHORT).show()
            return
        }
        val (word, suggestions) = items[index]
        val builder = AlertDialog.Builder(this)
            .setTitle("„$word“ (${index + 1} von ${items.size})")
        if (suggestions.isEmpty()) {
            builder.setMessage("Kein Vorschlag verfügbar.")
        } else {
            builder.setItems(suggestions.toTypedArray()) { _, which ->
                replaceWord(word, suggestions[which])
                reviewSpelling(items, index + 1)
            }
        }
        builder.setNeutralButton("Ignorieren") { _, _ -> reviewSpelling(items, index + 1) }
        builder.setNegativeButton("Abbrechen", null)
        builder.show()
    }

    private fun replaceWord(word: String, replacement: String) {
        val regex = Regex("(?<!\\p{L})" + Regex.escape(word) + "(?!\\p{L})")
        editor.setText(regex.replace(editor.text.toString(), Regex.escapeReplacement(replacement)))
    }

    // --- Löschen, speichern, teilen -------------------------------------------------

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Text löschen?")
            .setMessage("Der erkannte Text und die Seitenzählung werden entfernt. Deine Fotos auf dem Gerät bleiben erhalten.")
            .setPositiveButton("Löschen") { _, _ -> clearAll() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun clearAll() {
        ScanStore.reset()
        pending.clear()
        editor.setText("")
        updateStatus()
        Toast.makeText(this, "Text gelöscht.", Toast.LENGTH_SHORT).show()
    }

    private fun saveText() {
        if (editor.text.isNullOrBlank()) {
            Toast.makeText(this, "Es gibt noch keinen Text zum Speichern.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())
        createDocument.launch("Buchtext_$stamp.txt")
    }

    private fun shareText() {
        val text = editor.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Es gibt noch keinen Text zum Teilen.", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Text teilen"))
    }
}
