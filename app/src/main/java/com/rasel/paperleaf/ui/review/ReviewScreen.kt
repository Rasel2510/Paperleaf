package com.rasel.paperleaf.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.App
import com.rasel.paperleaf.core.dueLabel
import com.rasel.paperleaf.core.nextReview
import com.rasel.paperleaf.data.ReviewCounts
import com.rasel.paperleaf.data.ReviewWord
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Message
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox
import com.rasel.paperleaf.ui.RetroButton
import com.rasel.paperleaf.ui.ScreenBar
import kotlinx.coroutines.launch

/** Flashcards over the saved words of every book. */
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val queue = remember { mutableStateListOf<ReviewWord>() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<ReviewCounts?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var known by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val now = System.currentTimeMillis()
            // At most 30 words in one sitting.
            queue.addAll(App.repo.wordsToReview(now, 30))
            counts = App.repo.reviewCounts(now)
            total = queue.size
        } catch (_: Exception) {
            failed = true
        }
        loaded = true
    }

    fun answer(knew: Boolean) {
        val word = queue.firstOrNull() ?: return
        val next = nextReview(word.box, knew, System.currentTimeMillis())
        queue.removeAt(0)
        // Not known: it comes round again before the sitting ends.
        if (knew) known++ else queue.add(word.copy(box = next.box))
        shown = false
        scope.launch { runCatching { App.repo.setWordReview(word.id, next.box, next.due) } }
    }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        ScreenBar("Word review", onBack)
        when {
            !loaded -> Unit
            failed -> Message(Icons.Default.ErrorOutline, "Could not load your words", "Close this screen and try again.")
            queue.isNotEmpty() -> Session(queue.first(), total, known, total - queue.size, shown, { shown = true }, ::answer)
            total > 0 -> Message(
                Icons.Default.EmojiEvents,
                "Done for now",
                if (known == 1) "You went through 1 word." else "You went through $known words.",
            ) { RetroButton("Back to the shelf", onClick = onBack) }
            else -> {
                val c = counts
                val next = c?.nextDue
                if (c != null && c.resting > 0 && next != null) {
                    val whenDue = dueLabel(next, System.currentTimeMillis())
                    Message(
                        Icons.Default.CheckCircleOutline,
                        "All caught up",
                        if (c.resting == 1) "Your word comes back $whenDue."
                        else "Your ${c.resting} words are resting. The next one comes back $whenDue.",
                    )
                } else {
                    Message(
                        Icons.Default.Style,
                        "No words to review yet",
                        if ((c?.withoutMeaning ?: 0) > 0) {
                            "Your saved words have no meaning yet. Open a book's notes and look them up (this needs internet)."
                        } else {
                            "Long-press a word while reading and save it. It will be waiting for you here."
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Session(
    word: ReviewWord,
    total: Int,
    known: Int,
    done: Int,
    shown: Boolean,
    show: () -> Unit,
    answer: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp)) {
        Text("$known OF $total KNOWN", style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.5.sp))
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.coerceIn(0, total).toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Poster.terracotta,
            trackColor = p.paperDark,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(20.dp))
        RetroBox(Modifier.weight(1f).fillMaxWidth(), onClick = if (shown) null else show) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(word.word, fontFamily = Fonts.display, fontSize = 34.sp, color = p.ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${word.bookTitle} · page ${word.page}",
                    style = MaterialTheme.typography.bodySmall.copy(color = p.ink.copy(alpha = 0.6f)),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(24.dp))
                if (shown) {
                    Text(
                        word.meaning,
                        Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "Think of the meaning, then tap the card.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = p.ink.copy(alpha = 0.6f)),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (shown) {
            Row {
                RetroButton("Not yet", { answer(false) }, Modifier.weight(1f), Icons.Default.Replay, Poster.terracotta, Poster.onDark)
                Spacer(Modifier.width(14.dp))
                RetroButton("I knew it", { answer(true) }, Modifier.weight(1f), Icons.Default.Check, Poster.teal, Poster.onDark)
            }
        } else {
            RetroButton("Show meaning", show, Modifier.fillMaxWidth(), Icons.Default.Visibility)
        }
    }
}
