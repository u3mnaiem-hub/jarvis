package com.voiceagent.automation.voice

import com.voiceagent.automation.model.Automation
import kotlin.math.max
import kotlin.math.min

sealed class MatchResult {
    data class ExactMatch(val automation: Automation) : MatchResult()
    data class HighConfidenceMatch(val automation: Automation, val score: Double) : MatchResult()
    data class AmbiguousMatch(val candidateAutomations: List<Automation>) : MatchResult()
    object NoMatch : MatchResult()
}

object CommandMatchingEngine {

    /**
     * Finds the best matching automation for a spoken user phrase.
     */
    fun matchCommand(spokenText: String, availableAutomations: List<Automation>): MatchResult {
        if (spokenText.isBlank() || availableAutomations.isEmpty()) {
            return MatchResult.NoMatch
        }

        val normalizedInput = normalizeText(spokenText)

        // 0. Parameterized template phrase match (e.g. "Jarvis block <device_name>", "اقفل النت عن <device_name>")
        for (automation in availableAutomations) {
            for (phrase in automation.voicePhrases) {
                if (phrase.contains("{device_name}")) {
                    val prefix = normalizeText(phrase.substringBefore("{device_name}"))
                    if (prefix.isNotBlank() && normalizedInput.startsWith(prefix)) {
                        val extractedParam = spokenText.trim()
                            .replaceFirst(Regex("(?i)^jarvis[,\\s]*"), "")
                            .let { raw ->
                                val rawPrefix = phrase.substringBefore("{device_name}").replace(Regex("(?i)^jarvis[,\\s]*"), "").trim()
                                if (raw.lowercase().startsWith(rawPrefix.lowercase())) {
                                    raw.substring(rawPrefix.length).trim()
                                } else {
                                    normalizedInput.removePrefix(prefix).trim()
                                }
                            }
                        if (extractedParam.isNotBlank()) {
                            val boundAutomation = bindDeviceParameter(automation, extractedParam)
                            return MatchResult.ExactMatch(boundAutomation)
                        }
                    }
                }
            }
        }

        // 1. Exact phrase match
        for (automation in availableAutomations) {
            val allPhrases = automation.voicePhrases + listOf(automation.name, automation.nameAr)
            for (phrase in allPhrases) {
                if (phrase.isBlank()) continue
                if (normalizeText(phrase) == normalizedInput) {
                    return MatchResult.ExactMatch(automation)
                }
            }
        }

        // 2. Fuzzy similarity scoring
        val scoredCandidates = mutableListOf<Pair<Automation, Double>>()

        for (automation in availableAutomations) {
            var highestScore = 0.0
            val allPhrases = automation.voicePhrases + listOf(automation.name, automation.nameAr, automation.description)
            
            for (phrase in allPhrases) {
                if (phrase.isBlank()) continue
                val normalizedPhrase = normalizeText(phrase)
                
                // Substring containment bonus
                if (normalizedInput.contains(normalizedPhrase) || normalizedPhrase.contains(normalizedInput)) {
                    highestScore = max(highestScore, 0.85)
                }

                // Word token overlap (Jaccard similarity)
                val tokenScore = calculateTokenOverlap(normalizedInput, normalizedPhrase)
                highestScore = max(highestScore, tokenScore)

                // Levenshtein similarity
                val editScore = calculateLevenshteinSimilarity(normalizedInput, normalizedPhrase)
                highestScore = max(highestScore, editScore)
            }

            if (highestScore >= 0.55) {
                scoredCandidates.add(automation to highestScore)
            }
        }

        // Sort descending by score
        scoredCandidates.sortByDescending { it.second }

        if (scoredCandidates.isEmpty()) {
            return MatchResult.NoMatch
        }

        val topCandidate = scoredCandidates.first()

        // Check for ambiguity if second candidate has a very close score (> 0.70 and within 0.08 of top)
        if (scoredCandidates.size > 1) {
            val secondCandidate = scoredCandidates[1]
            if (secondCandidate.second >= 0.70 && (topCandidate.second - secondCandidate.second) < 0.08) {
                return MatchResult.AmbiguousMatch(listOf(topCandidate.first, secondCandidate.first))
            }
        }

        return if (topCandidate.second >= 0.60) {
            MatchResult.HighConfidenceMatch(topCandidate.first, topCandidate.second)
        } else {
            MatchResult.NoMatch
        }
    }

    private fun bindDeviceParameter(automation: Automation, deviceName: String): Automation {
        val updatedSteps = automation.steps.map { step ->
            if (!step.indexedNamePattern.isNullOrBlank() || !step.rowContextText.isNullOrBlank()) {
                step.copy(
                    rowContextText = deviceName,
                    selectorText = deviceName,
                    title = step.title.replace(step.rowContextText ?: "", deviceName),
                    spokenMessage = step.spokenMessage?.replace(step.rowContextText ?: "", deviceName)
                )
            } else {
                step
            }
        }
        return automation.copy(
            name = "${automation.name} ($deviceName)",
            steps = updatedSteps
        )
    }

    /**
     * Normalizes Arabic and English text:
     * - Lowercase English
     * - Removes Arabic Tashkeel / diacritics
     * - Standardizes Alef (أ, إ, آ, ٱ -> ا)
     * - Standardizes Teh Marbuta (ة -> ه)
     * - Standardizes Alef Maksura (ى -> ي)
     * - Removes punctuation
     */
    fun normalizeText(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "") // Remove diacritics
            .replace(Regex("[أإآٱ]"), "ا")
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("[.,/#!$%^&*;:{}=\\-_`~()?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateTokenOverlap(textA: String, textB: String): Double {
        val tokensA = textA.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = textB.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return intersection.toDouble() / union.toDouble()
    }

    private fun calculateLevenshteinSimilarity(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen.toDouble()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
