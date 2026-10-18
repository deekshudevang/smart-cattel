package com.example.smartcattle.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CowDao {
    @Query("SELECT * FROM cows WHERE isDeleted = 0 ORDER BY cattleId ASC")
    fun getAllActiveCows(): Flow<List<CowEntity>>

    @Query("SELECT * FROM cows WHERE isDeleted = 0 ORDER BY cattleId ASC")
    suspend fun getActiveCowsList(): List<CowEntity>

    @Query("SELECT * FROM cows WHERE cattleId = :id LIMIT 1")
    suspend fun getCowById(id: String): CowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cow: CowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cows: List<CowEntity>)

    @Query("UPDATE cows SET isDeleted = 1 WHERE cattleId = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM cows WHERE cattleId = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM cows")
    suspend fun clearAll()
}
