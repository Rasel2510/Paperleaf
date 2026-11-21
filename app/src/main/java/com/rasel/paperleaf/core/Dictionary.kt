package com.rasel.paperleaf.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Short English meanings from the free dictionaryapi.dev. */
object Dictionary {
    private val single = Regex("""^[A-Za-z][A-Za-z'’-]{0,39}$""")

    /** One lookup-ready word from a selection, without the punctuation around it; null for anything else. */
    fun lookupWord(text: String): String? {
        val word = text.trim().replace(Regex("""^[^A-Za-z0-9]+|[^A-Za-z0-9]+$"""), "")
        return if (single.matches(word)) word else null
    }

    /** What a lookup found: a meaning, no entry for the word, or no answer at all. */
    sealed interface Result {
        data class Found(val meaning: String) : Result
        /** The dictionary answered: it has no such word (a name, a rare word). */
        data object NotFound : Result
        /** No answer (no connection, or the dictionary is down). */
        data object Unreachable : Result
    }

    suspend fun lookup(text: String): String? = (define(text) as? Result.Found)?.meaning

    suspend fun define(text: String): Result = withContext(Dispatchers.IO) {
        val word = lookupWord(text)?.lowercase()?.replace('’', '\'') ?: return@withContext Result.NotFound
        val first = fetch(word)
        if (first != Result.NotFound) return@withContext first
        // "boy's", "camels'": the word itself is in the dictionary.
        val base = when {
            word.endsWith("'s") -> word.dropLast(2)
            word.endsWith("s'") -> word.dropLast(1)
            else -> return@withContext first
        }
        if (base.length < 2) first else fetch(base)
    }

    private fun fetch(word: String): Result = runCatching {
        val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8"))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        try {
            when (connection.responseCode) {
                200 -> parseDefinition(connection.inputStream.bufferedReader().readText())?.let { Result.Found(it) } ?: Result.NotFound
                // The dictionary's answer for a word it does not have.
                404 -> Result.NotFound
                else -> Result.Unreachable
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure { android.util.Log.w("Paperleaf", "Dictionary lookup failed", it) }.getOrDefault(Result.Unreachable)

    /** What to say when [result] has no meaning for [word]. */
    fun missingMessage(word: String, result: Result): String = when {
        result == Result.Unreachable -> "Could not reach the dictionary. Check your internet connection."
        word.firstOrNull()?.isUpperCase() == true -> "No meaning found for “$word”. It may be a name."
        else -> "No meaning found for “$word”."
    }

    /** First usable definition, as "(noun) the definition". */
    fun parseDefinition(body: String): String? = runCatching {
        val entries = JSONArray(body)
        for (i in 0 until entries.length()) {
            val meanings = entries.optJSONObject(i)?.optJSONArray("meanings") ?: continue
            for (j in 0 until meanings.length()) {
                val meaning = meanings.optJSONObject(j) ?: continue
                val defs = meaning.optJSONArray("definitions") ?: continue
                val text = defs.optJSONObject(0)?.optString("definition")?.trim().orEmpty()
                if (text.isEmpty()) continue
                val pos = meaning.optString("partOfSpeech")
                return@runCatching if (pos.isNotEmpty()) "($pos) $text" else text
            }
        }
        null
    }.getOrNull()
}
