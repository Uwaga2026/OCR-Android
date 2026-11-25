package de.example.buchocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val FILE_PROVIDER = "de.example.buchocr.fileprovider"
        // Höchstens so viele abweichende Bits (von 64) gelten bei dHash als gleiches Foto.
        private const val NEAR_DUPLICATE_BITS = 6
    }

    /** Fingerabdruck eines bereits verarbeiteten Fotos. */
    private class Photo(val sha: String?, val hash: Long?)

    private lateinit var toolbar: Toolbar
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var runButton: Button
    private lateinit var chooseButton: Button
    private lateinit var cameraButton: Button

    private val pending = mutableListOf<Uri>()          // Fotos, die noch erkannt werden müssen
    private val processed = mutableListOf<Photo>()      // bereits erkannte Fotos (für Duplikat-Prüfung)
    private val seenTexts = mutableSetOf<String>()      // bereits übernommene Seitentexte
    private var pageNo = 0
    private var liveMode = false                        // Sofort erkennen beim Fotografieren
    private var busy = false
    private var pendingCameraUri: Uri? = null
    private var spellSession: SpellCheckerSession? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
            pending.add(uri)
        }
        onNewPhotos()
    }

    private val camera = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            pending.add(uri)
            onNewPhotos()
        } else {
            updateStatus()
        }
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

    private fun buildScreen() {
        toolbar = Toolbar(this).apply {
            title = "OCR"
            setTitleTextColor(0xFF17243A.toInt())
            setBackgroundColor(0xFFF4F6FA.toInt())
            inflateMenu(R.menu.main_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_live -> {
                        item.isChecked = !item.isChecked
                        liveMode = item.isChecked
                        Toast.makeText(
                            this@MainActivity,
                            if (liveMode) "Sofort-Erkennung an" else "Sofort-Erkennung aus",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
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
            text = "Seiten fotografieren oder JPG/PNG auswählen, Text offline erkennen und als Datei speichern."
            textSize = 15f
            setTextColor(0xFF526078.toInt())
            setPadding(0, 8, 0, 18)
        }
        cameraButton = Button(this).apply {
            text = "Seite mit Kamera fotografieren"
            setOnClickListener { launchCamera() }
        }
        chooseButton = Button(this).apply {
            text = "JPG-/PNG-Fotos auswählen"
            setOnClickListener { picker.launch(arrayOf("image/jpeg", "image/png")) }
        }
        runButton = Button(this).apply {
            text = "Texterkennung starten"
            isEnabled = false
            setOnClickListener { processPending() }
        }
        status = TextView(this).apply {
            text = "Noch keine Fotos aufgenommen."
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
            processed.isEmpty() -> "Noch keine Fotos aufgenommen."
            else -> "${processed.size} Foto(s) erkannt. Du kannst weitere Seiten hinzufügen."
        }
        runButton.isEnabled = !busy && pending.isNotEmpty()
    }

    private fun onNewPhotos() {
        if (liveMode) processPending() else updateStatus()
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "scans").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, FILE_PROVIDER, file)
        pendingCameraUri = uri
        try {
            camera.launch(uri)
        } catch (_: Exception) {
            Toast.makeText(this, "Keine Kamera-App gefunden.", Toast.LENGTH_LONG).show()
        }
    }

    // --- Erkennung ---------------------------------------------------------------

    private fun processPending() {
        if (busy || pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        setBusy(true)
        lifecycleScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            var newPages = 0
            var duplicatePhotos = 0
            var skippedTexts = 0
            try {
                batch.forEachIndexed { index, uri ->
                    status.text = "Foto ${index + 1} von ${batch.size} wird geprüft …"
                    val photo = withContext(Dispatchers.IO) { fingerprint(uri) }
                    if (isDuplicate(photo)) { duplicatePhotos++; return@forEachIndexed }

                    status.text = "Foto ${index + 1} von ${batch.size} wird gelesen …"
                    val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
                    val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
                    bitmap.recycle()
                    processed.add(photo)

                    // Gleicher Text trotz anderem Foto (z. B. zweimal fotografierte Seite) nur einmal übernehmen.
                    val key = normalize(text)
                    if (key.isNotEmpty()) {
                        if (key in seenTexts) { skippedTexts++; return@forEachIndexed }
                        seenTexts.add(key)
                    }
                    pageNo++
                    newPages++
                    appendPage(pageNo, text)
                }
                val parts = mutableListOf("$newPages Seite(n) neu erkannt.")
                if (duplicatePhotos > 0) parts += "$duplicatePhotos doppeltes Foto(s) übersprungen."
                if (skippedTexts > 0) parts += "$skippedTexts Seite(n) mit gleichem Text übersprungen."
                parts += "Insgesamt ${processed.size} Foto(s). Du kannst den Text korrigieren, die Rechtschreibung prüfen und speichern."
                status.text = parts.joinToString(" ")
            } catch (e: Exception) {
                status.text = "Fehler bei der Texterkennung: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                recognizer.close()
                setBusy(false)
                if (pending.isNotEmpty()) processPending()
                else updateStatus()
            }
        }
    }

    private fun appendPage(n: Int, text: String) {
        val current = editor.text.toString()
        val block = "--- Seite $n ---\n\n$text"
        editor.setText(if (current.isBlank()) block else current.trimEnd() + "\n\n" + block)
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    // --- Doppelte Fotos erkennen ------------------------------------------------

    /** SHA-256 des Dateiinhalts und Bild-Hash (dHash); fehlende Werte bleiben null. */
    private fun fingerprint(uri: Uri): Photo {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Photo(null, null)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return Photo(sha, null)
            val g = Bitmap.createScaledBitmap(small, 9, 8, true)
            small.recycle()

            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(g.getPixel(x, y)) > luma(g.getPixel(x + 1, y))) hash = hash or (1L shl bit)
                    bit++
                }
            }
            g.recycle()
            Photo(sha, hash)
        } catch (_: Exception) {
            Photo(null, null)
        }
    }

    private fun isDuplicate(photo: Photo): Boolean =
        processed.any { old ->
            (photo.sha != null && old.sha == photo.sha) ||
                (photo.hash != null && old.hash != null &&
                    java.lang.Long.bitCount(old.hash xor photo.hash) <= NEAR_DUPLICATE_BITS)
        }

    private fun luma(c: Int) =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Vergleichsform für Seitentexte: Groß-/Kleinschreibung und Leerzeichen egal. */
    private fun normalize(s: String) = s.lowercase(Locale.GERMANY).replace(Regex("\\s+"), " ").trim()

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
            .setMessage("Der erkannte Text und die Liste der erkannten Fotos werden entfernt. Die Fotos selbst bleiben unverändert.")
            .setPositiveButton("Löschen") { _, _ -> clearAll() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun clearAll() {
        editor.setText("")
        processed.clear()
        seenTexts.clear()
        pageNo = 0
        pending.clear()
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
