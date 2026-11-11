package com.voiceagent.automation.data.db

import androidx.room.*
import com.voiceagent.automation.model.ExpenseTransaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseTransactionDao {
    @Query("SELECT * FROM expense_transactions ORDER BY timestamp DESC")
    fun getAllTransactionsFlow(): Flow<List<ExpenseTransaction>>

    @Query("SELECT * FROM expense_transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactions(): List<ExpenseTransaction>

    @Query("SELECT SUM(amount) FROM expense_transactions WHERE type = 'expense'")
    fun getTotalExpensesFlow(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM expense_transactions WHERE type = 'income'")
    fun getTotalIncomeFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: ExpenseTransaction)

    @Delete
    suspend fun deleteTransaction(transaction: ExpenseTransaction)

    @Query("DELETE FROM expense_transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM expense_transactions")
    suspend fun clearAll()
}
