package com.rasel.paperleaf.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Short English meanings from the free dictionaryapi.dev. Every failure gives null. */
object Dictionary {
    private val single = Regex("""^[A-Za-z][A-Za-z'’-]{0,39}$""")

    /** One lookup-ready word from a selection, without the punctuation around it; null for anything else. */
    fun lookupWord(text: String): String? {
        val word = text.trim().replace(Regex("""^[^A-Za-z0-9]+|[^A-Za-z0-9]+$"""), "")
        return if (single.matches(word)) word else null
    }

    suspend fun lookup(text: String): String? = withContext(Dispatchers.IO) {
        val word = lookupWord(text)?.lowercase()?.replace('’', '\'') ?: return@withContext null
        runCatching {
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8"))
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            try {
                if (connection.responseCode != 200) null
                else parseDefinition(connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }.onFailure { android.util.Log.w("Paperleaf", "Dictionary lookup failed", it) }.getOrNull()
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
