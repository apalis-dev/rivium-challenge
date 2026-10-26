package dev.apalis.riviumchallenge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.apalis.riviumchallenge.ui.main.MainScreen
import dev.apalis.riviumchallenge.ui.reader.ReaderScreen

@Composable
fun MainNavigation(articleToOpen: String?, onArticleOpened: () -> Unit) {
    val backStack = rememberNavBackStack(Main)

    LaunchedEffect(articleToOpen) {
        if (articleToOpen != null) {
            backStack.retainAll { it == Main }
            backStack.add(Reader(articleToOpen))
            onArticleOpened()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(onOpenArticle = { backStack.add(Reader(it)) }, modifier = Modifier.fillMaxSize())
            }
            entry<Reader> { key ->
                ReaderScreen(articleId = key.articleId, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}
