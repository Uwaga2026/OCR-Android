package de.example.buchocr

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var runButton: Button
    private lateinit var chooseButton: Button
    private lateinit var cameraButton: Button

    private val pending = mutableListOf<android.net.Uri>()
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

    private fun buildScreen() {
        toolbar = Toolbar(this).apply {
            title = "OCR"
            setTitleTextColor(0xFF17243A.toInt())
            setBackgroundColor(0xFFF4F6FA.toInt())
            inflateMenu(R.menu.main_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_spell -> { spellCheck(); true }
                    R.id.menu_clear -> { confirmClear(); true }
                    else -> false
                }
            }
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 16)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        val intro = TextView(this).apply {
            text = "Seiten fotografieren – der Text wird sofort erkannt. Oder JPG/PNG auswählen und als Datei speichern."
            textSize = 15f
            setTextColor(0xFF526078.toInt())
            setPadding(0, 8, 0, 18)
        }
        cameraButton = Button(this).apply {
            text = "Kamera öffnen"
            setOnClickListener { startActivity(Intent(this@MainActivity, CameraActivity::class.java)) }
        }
        chooseButton = Button(this).apply {
            text = "JPG-/PNG-Fotos auswählen"
            setOnClickListener { picker.launch(arrayOf("image/jpeg", "image/png")) }
        }
        runButton = Button(this).apply {
            text = "Ausgewählte Fotos erkennen"
            isEnabled = false
            setOnClickListener { processPending() }
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF315C9B.toInt())
            setPadding(0, 10, 0, 10)
        }
        editor = EditText(this).apply {
            hint = "Der erkannte Text erscheint hier …"
            textSize = 16f
            gravity = Gravity.TOP
            minLines = 12
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val save = Button(this).apply {
            text = "Text als TXT speichern"
            setOnClickListener { saveText() }
        }
        outer.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        outer.addView(intro)
        outer.addView(cameraButton, match())
        outer.addView(chooseButton, match())
        outer.addView(runButton, match())
        outer.addView(status)
        outer.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        outer.addView(save, match())
        setContentView(outer)

        // Ab Android 15 (targetSdk 35) zeichnet sich die App hinter Status- und Navigationsleiste.
        // Der Abstand unten berücksichtigt außerdem die Bildschirmtastatur.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                22 + bars.left, 24 + bars.top, 22 + bars.right,
                16 + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun setBusy(value: Boolean) {
        busy = value
        cameraButton.isEnabled = !value
        chooseButton.isEnabled = !value
        runButton.isEnabled = !value && pending.isNotEmpty()
    }

    private fun updateStatus() {
        status.text = when {
            pending.isNotEmpty() -> "${pending.size} Foto(s) warten auf Erkennung."
            ScanStore.pageCount == 0 -> "Noch keine Seiten erkannt."
            else -> "${ScanStore.pageCount} Seite(n) erkannt. Du kannst den Text korrigieren, die Rechtschreibung prüfen und speichern."
        }
        runButton.isEnabled = !busy && pending.isNotEmpty()
    }

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
                    status.text = "Foto ${index + 1} von ${batch.size} wird erkannt …"
                    when (ScanStore.process(this@MainActivity, uri)) {
                        ScanStore.Outcome.NEW_PAGE -> newPages++
                        else -> skipped++
                    }
                    editor.setText(ScanStore.text)
                }
                status.text = "$newPages Seite(n) neu erkannt" +
                    (if (skipped > 0) ", $skipped doppelt übersprungen" else "") + "."
            } catch (e: Exception) {
                status.text = "Fehler bei der Texterkennung: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                setBusy(false)
                if (pending.isNotEmpty()) processPending() else updateStatus()
            }
        }
    }

    // --- Rechtschreibung ----------------------------------------------------------

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

    // --- Text löschen und speichern ------------------------------------------------

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
}
