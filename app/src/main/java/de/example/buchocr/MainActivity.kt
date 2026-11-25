package de.example.buchocr

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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var runButton: Button
    private lateinit var chooseButton: Button
    private lateinit var cameraButton: Button
    private lateinit var clearButton: Button

    private val selected = mutableListOf<Uri>()
    private var pendingCameraUri: Uri? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
            selected.add(uri)
        }
        showSelection()
    }

    private val camera = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) selected.add(uri)
        showSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 16)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        val title = TextView(this).apply {
            text = "OCR"
            textSize = 28f
            setTextColor(0xFF17243A.toInt())
            gravity = Gravity.CENTER_VERTICAL
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
        clearButton = Button(this).apply {
            text = "Auswahl leeren"
            setOnClickListener { selected.clear(); showSelection() }
        }
        runButton = Button(this).apply {
            text = "Texterkennung starten"
            isEnabled = false
            setOnClickListener { recognizeAll() }
        }
        status = TextView(this).apply {
            text = "Noch keine Fotos ausgewählt."
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
        outer.addView(title)
        outer.addView(intro)
        outer.addView(cameraButton, match())
        outer.addView(chooseButton, match())
        outer.addView(clearButton, match())
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
        showSelection()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun showSelection() {
        status.text = if (selected.isEmpty()) "Noch keine Fotos ausgewählt."
        else "${selected.size} Foto(s) ausgewählt. Doppelte Fotos werden vor der Erkennung entfernt."
        runButton.isEnabled = selected.isNotEmpty()
    }

    private fun setBusy(busy: Boolean) {
        cameraButton.isEnabled = !busy
        chooseButton.isEnabled = !busy
        clearButton.isEnabled = !busy
        runButton.isEnabled = !busy && selected.isNotEmpty()
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

    // --- Doppelte Fotos erkennen ---------------------------------------------

    /** Liefert (SHA-256 des Dateiinhalts, dHash) oder (null, null), wenn das Bild nicht lesbar ist. */
    private fun fingerprint(uri: Uri): Pair<String?, Long?> {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Pair(null, null)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return Pair(sha, null)
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
            Pair(sha, hash)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun luma(c: Int) =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Gibt die eindeutigen Fotos zurück sowie die Nummern (1-basiert) der entfernten Duplikate. */
    private fun removeDuplicateImages(all: List<Uri>): Pair<List<Uri>, List<Int>> {
        val kept = mutableListOf<Uri>()
        val keptSha = mutableSetOf<String>()
        val keptHashes = mutableListOf<Long>()
        val removed = mutableListOf<Int>()
        all.forEachIndexed { i, uri ->
            val (sha, hash) = fingerprint(uri)
            val exact = sha != null && sha in keptSha
            val similar = hash != null &&
                keptHashes.any { java.lang.Long.bitCount(it xor hash) <= NEAR_DUPLICATE_BITS }
            if (exact || similar) {
                removed.add(i + 1)
            } else {
                kept.add(uri)
                if (sha != null) keptSha.add(sha)
                if (hash != null) keptHashes.add(hash)
            }
        }
        return Pair(kept, removed)
    }

    /** Vergleichsform für Seitentexte: Groß-/Kleinschreibung und Leerzeichen egal. */
    private fun normalize(s: String) = s.lowercase(Locale.GERMANY).replace(Regex("\\s+"), " ").trim()

    // --- Erkennung -------------------------------------------------------------

    private fun recognizeAll() {
        if (selected.isEmpty()) return
        setBusy(true)
        status.text = "Doppelte Fotos werden geprüft …"
        lifecycleScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = StringBuilder()
            try {
                val (unique, removedImages) = withContext(Dispatchers.IO) {
                    removeDuplicateImages(selected.toList())
                }
                val seenTexts = mutableSetOf<String>()
                var pageNo = 0
                var skippedTexts = 0

                unique.forEachIndexed { index, uri ->
                    status.text = "Seite ${index + 1} von ${unique.size} wird gelesen …"
                    val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val text = recognizer.process(image).await().text.trim()
                    bitmap.recycle()

                    // Gleicher Text trotz anderem Foto (z. B. zweimal fotografierte Seite) wird nur einmal übernommen.
                    val key = normalize(text)
                    if (key.isNotEmpty()) {
                        if (key in seenTexts) { skippedTexts++; return@forEachIndexed }
                        seenTexts.add(key)
                    }
                    pageNo++
                    result.append("\n\n--- Seite $pageNo ---\n\n").append(text)
                    editor.setText(result.toString().trimStart())
                }

                val parts = mutableListOf("Fertig: $pageNo Seite(n) erkannt.")
                if (removedImages.isNotEmpty()) {
                    parts += "${removedImages.size} doppelte Foto(s) entfernt (Nr. ${removedImages.joinToString()})."
                }
                if (skippedTexts > 0) {
                    parts += "$skippedTexts Seite(n) mit gleichem Text übersprungen."
                }
                parts += "Du kannst den Text jetzt korrigieren und speichern."
                status.text = parts.joinToString(" ")
                Toast.makeText(this@MainActivity, "Texterkennung abgeschlossen", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "Fehler bei der Texterkennung: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                recognizer.close()
                setBusy(false)
            }
        }
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

    // --- Speichern -------------------------------------------------------------

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

    private fun saveText() {
        if (editor.text.isNullOrBlank()) {
            Toast.makeText(this, "Es gibt noch keinen Text zum Speichern.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())
        createDocument.launch("Buchtext_$stamp.txt")
    }
}
