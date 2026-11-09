package com.voiceagent.automation.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.Credential
import com.voiceagent.automation.model.ExecutionLog

@Database(
    entities = [Automation::class, ExecutionLog::class, Credential::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VoiceAgentDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        @Volatile
        private var INSTANCE: VoiceAgentDatabase? = null

        fun getInstance(context: Context): VoiceAgentDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VoiceAgentDatabase::class.java,
                    "voice_agent_automation.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
