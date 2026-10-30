package io.github.samuel0kello.mchoro.presentation

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Copy held in State without needing a Context: a string resource, or text from the engine. */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Resource -> stringResource(id, *args.toTypedArray())
        is Raw -> value
    }

    /** For event handlers (snackbars) that run outside composition. */
    fun asString(context: Context): String = when (this) {
        is Resource -> context.getString(id, *args.toTypedArray())
        is Raw -> value
    }
}
