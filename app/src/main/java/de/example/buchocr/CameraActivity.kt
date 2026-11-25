package de.example.buchocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Eigener Kamera-Bildschirm: jedes Foto wird sofort erkannt, bei Erfolg erscheint ein grüner Haken. */
class CameraActivity : AppCompatActivity() {

    private enum class Style { GOOD, WARN, BAD }

    private lateinit var preview: PreviewView
    private lateinit var counter: TextView
    private lateinit var last: TextView
    private lateinit var badge: TextView
    private var imageCapture: ImageCapture? = null
    private var processing = 0

    private val hideBadge = Runnable { badge.visibility = View.GONE }

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Ohne Kamera-Berechtigung geht es nicht.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
        updateCounter()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(8))
        }
        counter = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        last = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        info.addView(counter)
        info.addView(last)
        root.addView(info, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        badge = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))

        val shutter = Button(this).apply {
            text = "Foto"
            textSize = 16f
            setTextColor(Color.BLACK)
            background = circle(Color.WHITE)
            setOnClickListener { takePhoto() }
        }
        val done = Button(this).apply {
            text = "Fertig"
            setOnClickListener { finish() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(24))
            addView(shutter, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(12) })
            addView(done, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        ViewCompat.setOnApplyWindowInsetsListener(bottom) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, dp(24) + bars.bottom)
            insets
        }

        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, capture)
            imageCapture = capture
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val dir = File(cacheDir, "scans").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri: Uri = FileProvider.getUriForFile(this, "de.example.buchocr.fileprovider", file)
        processing++
        updateCounter()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch { recognize(uri) }
                }

                override fun onError(exception: ImageCaptureException) {
                    processing--
                    updateCounter()
                    showBadge(Style.BAD, "Foto fehlgeschlagen")
                }
            }
        )
    }

    private suspend fun recognize(uri: Uri) {
        try {
            when (ScanStore.process(this, uri)) {
                ScanStore.Outcome.NEW_PAGE -> showBadge(Style.GOOD, "Seite erkannt")
                ScanStore.Outcome.DUPLICATE_PHOTO -> showBadge(Style.WARN, "Doppeltes Foto übersprungen")
                ScanStore.Outcome.DUPLICATE_TEXT -> showBadge(Style.WARN, "Seite mit gleichem Text übersprungen")
            }
        } catch (e: Exception) {
            showBadge(Style.BAD, "Erkennung fehlgeschlagen")
        } finally {
            processing--
            updateCounter()
        }
    }

    private fun showBadge(style: Style, message: String) {
        badge.background = circle(
            when (style) {
                Style.GOOD -> 0xFF2E7D32.toInt()
                Style.WARN -> 0xFFEF8F00.toInt()
                Style.BAD -> 0xFFC62828.toInt()
            }
        )
        badge.text = when (style) {
            Style.GOOD -> "✓"
            Style.WARN -> "!"
            Style.BAD -> "✗"
        }
        last.text = message
        badge.visibility = View.VISIBLE
        badge.scaleX = 0.6f
        badge.scaleY = 0.6f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        badge.removeCallbacks(hideBadge)
        badge.postDelayed(hideBadge, 900)
    }

    private fun updateCounter() {
        val busy = if (processing > 0) " · wird erkannt: $processing" else ""
        counter.text = "Seiten: ${ScanStore.pageCount}$busy"
    }
}
