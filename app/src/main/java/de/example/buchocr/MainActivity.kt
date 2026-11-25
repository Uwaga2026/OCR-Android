package de.example.buchocr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var runButton: Button
    private var selected: List<Uri> = emptyList()

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selected = uris
        status.text = if (uris.isEmpty()) "Noch keine Fotos ausgewählt."
        else "${uris.size} JPG-Bild(er) ausgewählt."
        runButton.isEnabled = uris.isNotEmpty()
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
        }
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
            text = "JPG-Seiten auswählen, Text offline erkennen und als Datei speichern."
            textSize = 15f
            setTextColor(0xFF526078.toInt())
            setPadding(0, 8, 0, 18)
        }
        val choose = Button(this).apply {
            text = "JPG-Fotos auswählen"
            setOnClickListener { picker.launch(arrayOf("image/jpeg", "image/png")) }
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
        outer.addView(choose, match())
        outer.addView(runButton, match())
        outer.addView(status)
        outer.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        outer.addView(save, match())
        setContentView(outer)
        // Ab Android 15 (targetSdk 35) zeichnet sich die App hinter Statusleiste und
        // Navigationsleiste. Ohne Abstand würde der Speichern-Button verdeckt, und die
        // Bildschirmtastatur würde ihn bei der Textkorrektur überdecken.
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

    private fun recognizeAll() {
        if (selected.isEmpty()) return
        runButton.isEnabled = false
        status.text = "Texterkennung läuft …"
        lifecycleScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = StringBuilder()
            try {
                selected.forEachIndexed { index, uri ->
                    status.text = "Seite ${index + 1} von ${selected.size} wird gelesen …"
                    val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val text = recognizer.process(image).await()
                    result.append("\n\n--- Seite ${index + 1} ---\n\n")
                    result.append(text.text)
                    bitmap.recycle()
                    editor.setText(result.toString())
                }
                status.text = "Fertig: ${selected.size} Seite(n) erkannt. Du kannst den Text jetzt korrigieren und speichern."
                Toast.makeText(this@MainActivity, "Texterkennung abgeschlossen", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "Fehler bei der Texterkennung: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                recognizer.close()
                runButton.isEnabled = selected.isNotEmpty()
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
