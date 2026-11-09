package com.voiceagent.automation.data.db

import androidx.room.*
import com.voiceagent.automation.model.Credential
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY label ASC")
    fun getAllCredentialsFlow(): Flow<List<Credential>>

    @Query("SELECT * FROM credentials WHERE `key` = :key LIMIT 1")
    suspend fun getCredentialByKey(key: String): Credential?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(credential: Credential)

    @Delete
    suspend fun delete(credential: Credential)

    @Query("DELETE FROM credentials WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("UPDATE credentials SET lastUsedAt = :time WHERE `key` = :key")
    suspend fun updateLastUsed(key: String, time: Long)
}
