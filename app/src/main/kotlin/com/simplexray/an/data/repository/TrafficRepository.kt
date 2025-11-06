package com.simplexray.an.data.repository

import com.simplexray.an.common.AppLogger
import com.simplexray.an.data.db.SpeedStats
import com.simplexray.an.data.db.TotalBytes
import com.simplexray.an.data.db.TrafficDao
import com.simplexray.an.data.db.toEntity
import com.simplexray.an.data.db.toSnapshot
import com.simplexray.an.domain.model.TrafficSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing traffic data.
 * Provides a clean API for accessing and storing traffic information.
 * 
 * TODO: Add caching layer to reduce database queries
 * TODO: Implement data pagination for large datasets
 * TODO: Add data validation before insertion
 * TODO: Consider adding transaction support for batch operations
 */
// ARCH-DEBT: Repository uses dependency injection but factory pattern also exists
// TEST-GAP: Repository not tested - database operations unverified
@Singleton
class TrafficRepository @Inject constructor(
    private val trafficDao: TrafficDao
) {

    /**
     * Insert a traffic snapshot into the database
     */
    suspend fun insert(snapshot: TrafficSnapshot) {
        trafficDao.insert(snapshot.toEntity())
    }

    /**
     * Insert multiple traffic snapshots
     */
    suspend fun insertAll(snapshots: List<TrafficSnapshot>) {
        trafficDao.insertAll(snapshots.map { it.toEntity() })
    }

    /**
     * Get all traffic logs as Flow
     * 
     * Note: For very large histories (100k+ entries), consider replacing with PagingSource
     * (Paging3) to enable incremental loading and better memory efficiency. Current approach
     * works well for typical use cases with moderate dataset sizes.
     * 
     * Performance optimizations:
     * - Mapping is done on IO dispatcher to avoid blocking main thread
     * - Consider using Paging3 for datasets > 10k entries
     */
    fun getAllLogs(): Flow<List<TrafficSnapshot>> {
        return trafficDao.getAllLogs().map { entities ->
            // Map entities to snapshots
            // Note: Room queries already run on background thread, mapping is lightweight
            entities.map { it.toSnapshot() }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    }

    /**
     * Get traffic logs for a specific time range
     */
    fun getLogsInRange(startTime: Long, endTime: Long): Flow<List<TrafficSnapshot>> {
        return trafficDao.getLogsInRange(startTime, endTime).map { entities ->
            entities.map { it.toSnapshot() }
        }
    }

    /**
     * Get traffic logs for the last N hours
     */
    fun getLogsForLastHours(hours: Int): Flow<List<TrafficSnapshot>> {
        val startTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        return trafficDao.getLogsForLast(startTime).map { entities ->
            entities.map { it.toSnapshot() }
        }
    }

    /**
     * Get traffic logs for today
     */
    fun getLogsForToday(): Flow<List<TrafficSnapshot>> {
        val startOfDay = getStartOfDayMillis()
        return trafficDao.getLogsForToday(startOfDay).map { entities ->
            entities.map { it.toSnapshot() }
        }
    }

    /**
     * Get the latest N traffic logs
     */
    fun getLatestLogs(limit: Int): Flow<List<TrafficSnapshot>> {
        return trafficDao.getLatestLogs(limit).map { entities ->
            entities.map { it.toSnapshot() }
        }
    }

    /**
     * Get total bytes transferred today
     */
    suspend fun getTotalBytesToday(): TotalBytes {
        val startOfDay = getStartOfDayMillis()
        return trafficDao.getTotalBytesToday(startOfDay) ?: TotalBytes(0L, 0L)
    }

    /**
     * Get speed statistics for a time range
     */
    suspend fun getSpeedStats(startTime: Long, endTime: Long): SpeedStats {
        return trafficDao.getSpeedStats(startTime, endTime)
            ?: SpeedStats(0f, 0f, 0f, 0f)
    }

    /**
     * Get speed statistics for today
     */
    suspend fun getSpeedStatsToday(): SpeedStats {
        val startOfDay = getStartOfDayMillis()
        val now = System.currentTimeMillis()
        return getSpeedStats(startOfDay, now)
    }

    /**
     * Get speed statistics for last 24 hours
     */
    suspend fun getSpeedStatsLast24Hours(): SpeedStats {
        val now = System.currentTimeMillis()
        val yesterday = now - (24 * 60 * 60 * 1000L)
        return getSpeedStats(yesterday, now)
    }

    /**
     * Get average latency for a time range
     */
    suspend fun getAverageLatency(startTime: Long, endTime: Long): Long {
        return trafficDao.getAverageLatency(startTime, endTime) ?: -1L
    }

    /**
     * Get average latency for today
     */
    suspend fun getAverageLatencyToday(): Long {
        val startOfDay = getStartOfDayMillis()
        val now = System.currentTimeMillis()
        return getAverageLatency(startOfDay, now)
    }

    /**
     * Delete logs older than N days
     * TODO: Add progress callback for large deletions
     * TODO: Consider implementing soft delete instead of hard delete
     */
    suspend fun deleteLogsOlderThanDays(days: Int): Int {
        // Validate days parameter
        if (days < 0) {
            throw IllegalArgumentException("Days must be non-negative, got: $days")
        }
        if (days > 3650) { // Max 10 years
            throw IllegalArgumentException("Days exceeds maximum (3650), got: $days")
        }
        
        val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        
        // For large deletions, check count first and batch if needed
        val countBefore = trafficDao.getCount()
        if (countBefore > 10000) {
            // Large dataset - deletion may take time, but Room handles it efficiently
            // Note: Room operations are already on background thread via suspend
            AppLogger.d("TrafficRepository: Deleting logs older than $days days (large dataset: $countBefore entries)")
        }
        
        return trafficDao.deleteLogsOlderThan(cutoffTime)
    }

    /**
     * Delete all logs
     */
    suspend fun deleteAll() {
        trafficDao.deleteAll()
    }

    /**
     * Get total count of logs
     */
    suspend fun getCount(): Int {
        return trafficDao.getCount()
    }

    /**
     * Get count of logs for today
     */
    suspend fun getCountToday(): Int {
        val startOfDay = getStartOfDayMillis()
        return trafficDao.getCountToday(startOfDay)
    }

    /**
     * Helper function to get start of day in milliseconds
     */
    private fun getStartOfDayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

/**
 * Simple version without dependency injection for easier initialization
 */
class TrafficRepositoryFactory {
    companion object {
        fun create(trafficDao: TrafficDao): TrafficRepository {
            return TrafficRepository(trafficDao)
        }
    }
}
