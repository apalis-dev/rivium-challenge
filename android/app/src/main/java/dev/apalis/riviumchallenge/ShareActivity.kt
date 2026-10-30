package dev.apalis.riviumchallenge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Invisible share target: hands the shared text to the reader and returns to the other app. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
            ).joinToString(" ")
            if (shared.isNotBlank()) {
                ReaderService.save(applicationContext, shared)
                Toast.makeText(this, "Saving to Pocket Reader…", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
