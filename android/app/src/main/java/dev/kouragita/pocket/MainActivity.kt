package dev.kouragita.pocket

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "starting…"
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        setContentView(status)

        Host.start(this) { result ->
            runOnUiThread {
                status.text = when (result) {
                    is Host.Result.Ready -> "running\n${result.version}"
                    is Host.Result.Failed -> "failed (${result.code})\n${result.message}"
                }
            }
        }
    }
}
