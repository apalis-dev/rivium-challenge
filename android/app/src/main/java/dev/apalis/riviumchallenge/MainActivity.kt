package dev.apalis.riviumchallenge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dev.apalis.riviumchallenge.theme.RiviumPocketTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ARTICLE_ID = "article_id"
    }

    // Set when a "Saved" notification is tapped; consumed by the navigation.
    private val articleToOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) articleToOpen.value = intent.getStringExtra(EXTRA_ARTICLE_ID)

        enableEdgeToEdge()
        setContent {
            RiviumPocketTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainNavigation(
                        articleToOpen = articleToOpen.value,
                        onArticleOpened = { articleToOpen.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ARTICLE_ID)?.let { articleToOpen.value = it }
    }
}
