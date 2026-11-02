
package com.example.linkqueue

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_links",
    indices = [
        Index(value = ["normalizedUrl"], unique = true),
        Index(value = ["jobId"], unique = true)
    ]
)
data class SavedLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val originalUrl: String,
    val normalizedUrl: String,
    val sha256: String,
    val jobId: String? = null,
    val status: String = "completed",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
