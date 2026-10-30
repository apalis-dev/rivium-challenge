
package com.example.linkqueue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLinkDao {
    @Query("SELECT * FROM saved_links ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedLink>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: SavedLink): Long

    @Query("DELETE FROM saved_links WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_links WHERE normalizedUrl = :url LIMIT 1")
    suspend fun findByNormalizedUrl(url: String): SavedLink?

    @Query("SELECT * FROM saved_links WHERE originalUrl = :url LIMIT 1")
    suspend fun findByOriginalUrl(url: String): SavedLink?

    @Query("SELECT * FROM saved_links WHERE status IN ('queued', 'processing')")
    suspend fun findPending(): List<SavedLink>

    @Query("UPDATE OR IGNORE saved_links SET normalizedUrl = :normalizedUrl, sha256 = :sha256, status = :status, error = :error WHERE jobId = :jobId")
    suspend fun updateJobResult(
        jobId: String,
        normalizedUrl: String,
        sha256: String,
        status: String,
        error: String?
    ): Int

    @Query("UPDATE saved_links SET status = 'failed', error = :error WHERE jobId = :jobId")
    suspend fun markJobFailed(jobId: String, error: String)

    @Query("UPDATE saved_links SET status = :status, error = :error WHERE jobId = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, error: String?)

    @Query("SELECT COUNT(*) FROM saved_links")
    suspend fun count(): Int
}
