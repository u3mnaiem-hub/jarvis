package com.voiceagent.automation.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.model.StepExecutionDetail

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return gson.toJson(value ?: emptyList<String>())
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromAutomationStepList(value: List<AutomationStep>?): String {
        return gson.toJson(value ?: emptyList<AutomationStep>())
    }

    @TypeConverter
    fun toAutomationStepList(value: String?): List<AutomationStep> {
        if (value.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<AutomationStep>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromStepExecutionDetailList(value: List<StepExecutionDetail>?): String {
        return gson.toJson(value ?: emptyList<StepExecutionDetail>())
    }

    @TypeConverter
    fun toStepExecutionDetailList(value: String?): List<StepExecutionDetail> {
        if (value.isNullOrEmpty()) return emptyList()
        val type = object : TypeToken<List<StepExecutionDetail>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }
}
