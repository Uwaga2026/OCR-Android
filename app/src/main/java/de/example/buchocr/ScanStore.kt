package de.example.buchocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

/**
 * Gemeinsamer Stand der Sitzung, von Kamera- und Hauptansicht genutzt:
 * erkannter Text, bereits verarbeitete Fotos und Seitenzähler.
 */
object ScanStore {
    enum class Outcome { NEW_PAGE, DUPLICATE_PHOTO, DUPLICATE_TEXT }

    private class Photo(val sha: String?, val hash: Long?)

    // Höchstens so viele abweichende Bits (von 64) gelten bei dHash als gleiches Foto.
    private const val NEAR_DUPLICATE_BITS = 6

    // Verhindert, dass zwei Fotos gleichzeitig verarbeitet werden und sich die Seitennummern vermischen.
    private val lock = Mutex()
    private val photos = mutableListOf<Photo>()
    private val seenTexts = mutableSetOf<String>()

    @Volatile var text: String = ""
    @Volatile var pageCount: Int = 0
        private set

    fun reset() {
        text = ""
        pageCount = 0
        photos.clear()
        seenTexts.clear()
    }

    /** Erkennt ein Foto und hängt den Text an. Wirft eine Exception, wenn das Bild nicht gelesen werden kann. */
    suspend fun process(context: Context, uri: Uri): Outcome = lock.withLock {
        val photo = withContext(Dispatchers.IO) { fingerprint(context, uri) }
        if (photos.any { isSame(it, photo) }) return@withLock Outcome.DUPLICATE_PHOTO

        val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val raw = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
        photos.add(photo)

        // Gleicher Text trotz anderem Foto (z. B. zweimal fotografierte Seite) wird nur einmal übernommen.
        val key = normalize(raw)
        if (key.isNotEmpty()) {
            if (key in seenTexts) return@withLock Outcome.DUPLICATE_TEXT
            seenTexts.add(key)
        }
        pageCount++
        val block = "--- Seite $pageCount ---\n\n$raw"
        text = if (text.isBlank()) block else text.trimEnd() + "\n\n" + block
        Outcome.NEW_PAGE
    }

    private fun isSame(old: Photo, new: Photo): Boolean =
        (new.sha != null && old.sha == new.sha) ||
            (new.hash != null && old.hash != null &&
                java.lang.Long.bitCount(old.hash xor new.hash) <= NEAR_DUPLICATE_BITS)

    /** SHA-256 des Dateiinhalts und Bild-Hash (dHash); fehlende Werte bleiben null. */
    private fun fingerprint(context: Context, uri: Uri): Photo {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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

    private fun luma(c: Int) =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun loadBitmap(context: Context, uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = false }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }

    /** Vergleichsform für Seitentexte: Groß-/Kleinschreibung und Leerzeichen egal. */
    private fun normalize(s: String) = s.lowercase(Locale.GERMANY).replace(Regex("\\s+"), " ").trim()
}
