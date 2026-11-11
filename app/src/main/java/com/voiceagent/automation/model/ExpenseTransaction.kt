package com.voiceagent.automation.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "expense_transactions")
data class ExpenseTransaction(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val type: String = "expense", // "expense" or "income"
    val category: String = "عام",
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
