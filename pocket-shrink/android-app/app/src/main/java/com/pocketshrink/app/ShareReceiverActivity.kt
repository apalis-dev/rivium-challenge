package com.pocketshrink.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File
import java.io.FileOutputStream

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                handleSharedImage(imageUri)
            } else {
                Toast.makeText(this, "No image found in share intent", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun handleSharedImage(uri: Uri) {
        try {
            val rootDir = try {
                File(applicationInfo.dataDir, "files/pocket-data").canonicalFile
            } catch (_: Exception) {
                File(filesDir, "pocket-data")
            }.apply { mkdirs() }

            // Copy shared file from ContentResolver to app internal storage
            val cacheFolder = File(cacheDir, "shared_incoming")
            cacheFolder.mkdirs()
            val tempFile = File(cacheFolder, "shared_${System.currentTimeMillis()}.jpg")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Enqueue job directly into SQLite queue via JNI
            val jobId = Bridge.nativeEnqueue(
                rootDir = rootDir.absolutePath,
                inputPath = tempFile.absolutePath,
                maxWidth = 1280
            )

            // Ensure background service is running to process the queue
            PocketBackendService.start(this)

            Toast.makeText(
                this,
                "⚡ Shared photo enqueued into PocketShrink! ($jobId)",
                Toast.LENGTH_LONG
            ).show()

            // Open MainActivity to show live queue & history
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to enqueue shared photo: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
