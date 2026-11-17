package com.rasel.paperleaf.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rasel.paperleaf.pdf.PageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** One sentence (or a part of a long one) of a page: what is said, and where it is on the page. */
data class Spoken(val text: String, val range: IntRange)

/**
 * Splits the text of a page into pieces the voice reads one by one: at the
 * end of a sentence, or after about 280 characters at a space. Words broken
 * over two lines ("won-\nderful") are joined again.
 */
fun sentencesOf(page: PageText): List<Spoken> {
    val text = page.text
    val out = mutableListOf<Spoken>()
    var start = 0
    fun cut(end: Int) {
        if (end < start) return
        val said = text.substring(start, end + 1)
            .replace(Regex("""-\n"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (said.any { it.isLetterOrDigit() }) out += Spoken(said, start..end)
        start = end + 1
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        val endOfSentence = (c == '.' || c == '!' || c == '?' || c == '…') &&
            (next == null || next.isWhitespace() || next == '"' || next == '”' || next == '’')
        if (endOfSentence) {
            // Take a closing quote with the sentence.
            var end = i
            while (text.getOrNull(end + 1) == '"' || text.getOrNull(end + 1) == '”' || text.getOrNull(end + 1) == '’') end++
            cut(end)
            i = end + 1
            continue
        }
        if (i - start >= 280 && c.isWhitespace()) cut(i)
        i++
    }
    cut(text.length - 1)
    return out
}

/**
 * Reads pages aloud with the phone's own voice (it works offline). Reads one
 * piece at a time, so the piece being read can be shown on the page, and
 * calls [onPageDone] when the page is finished.
 */
class ReadAloud(context: Context, private val scope: CoroutineScope, private val onPageDone: () -> Unit) {

    enum class Status { Off, Starting, Reading, Paused }

    var status by mutableStateOf(Status.Off)
        private set

    /** What is being read now: the page (1-based) and the characters on it. */
    var current by mutableStateOf<Pair<Int, IntRange>?>(null)
        private set

    var rate by mutableFloatStateOf(1f)
        private set

    /** Set when the voice cannot be used; the screen shows it once. */
    var problem by mutableStateOf<String?>(null)

    private var ready = false
    private var page = 0
    private var pieces: List<Spoken> = emptyList()
    private var index = 0
    private var session = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { result ->
        scope.launch(Dispatchers.Main) {
            if (result != TextToSpeech.SUCCESS) {
                fail("This phone has no voice for reading aloud.")
                return@launch
            }
            val language = tts.setLanguage(Locale.US)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            ready = true
            if (status == Status.Starting) speakNext()
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                scope.launch(Dispatchers.Main) { finished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch(Dispatchers.Main) { finished(utteranceId) }
            }
        })
    }

    private fun fail(message: String) {
        problem = message
        stop()
    }

    /** Reads page [pageNumber] from its first sentence. */
    fun readPage(pageNumber: Int, text: PageText) {
        session++
        page = pageNumber
        pieces = sentencesOf(text)
        index = 0
        if (status == Status.Off || status == Status.Paused) status = Status.Starting
        tts.stop()
        if (pieces.isEmpty()) {
            // A page with no text (a picture): go on to the next one.
            current = null
            if (ready) scope.launch(Dispatchers.Main) { onPageDone() }
            return
        }
        if (ready) speakNext()
    }

    private fun speakNext() {
        val piece = pieces.getOrNull(index)
        if (piece == null) {
            current = null
            onPageDone()
            return
        }
        status = Status.Reading
        current = page to piece.range
        tts.setSpeechRate(rate)
        tts.speak(piece.text, TextToSpeech.QUEUE_FLUSH, null, "$session:$index")
    }

    private fun finished(id: String?) {
        // Only the piece being read now counts (not one stopped by a pause or a new page).
        if (status != Status.Reading || id != "$session:$index") return
        index++
        speakNext()
    }

    fun pause() {
        if (status != Status.Reading) return
        status = Status.Paused
        session++
        tts.stop()
    }

    fun resume() {
        if (status != Status.Paused) return
        session++
        if (ready) speakNext() else status = Status.Starting
    }

    fun stop() {
        session++
        tts.stop()
        status = Status.Off
        current = null
        pieces = emptyList()
    }

    /** Goes through the speeds: 1×, 1.25×, 1.5×, 0.75×. Takes effect from the next sentence. */
    fun nextRate() {
        rate = when (rate) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 0.75f
            else -> 1f
        }
    }

    val reading: Boolean get() = status != Status.Off

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
