package dev.apalis.riviumchallenge

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data class Reader(val articleId: String) : NavKey
