package com.voiceagent.automation.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials")
data class Credential(
    @PrimaryKey
    val key: String, // Unique identifier e.g. "router_password"
    val label: String, // User readable name e.g. "Router Admin Password"
    val encryptedData: String, // Keystore-encrypted Base64 payload
    val iv: String, // AES-GCM Initialization Vector Base64
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
)
