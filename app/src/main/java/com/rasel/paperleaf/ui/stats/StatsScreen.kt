package com.rasel.paperleaf.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.App
import com.rasel.paperleaf.core.formatDuration
import com.rasel.paperleaf.core.readingStats
import com.rasel.paperleaf.rememberLoaded
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox
import com.rasel.paperleaf.ui.ScreenBar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val stats = rememberLoaded { readingStats(App.repo.readingDays(), LocalDate.now()) }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        ScreenBar("Reading stats", onBack)
        val s = stats ?: return@Column
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Row {
                Tile(Modifier.weight(1f), Poster.terracotta, Poster.onDark, "STREAK", "${s.streak}", if (s.streak == 1) "day" else "days")
                Spacer(Modifier.width(16.dp))
                Tile(
                    Modifier.weight(1f), Poster.mustard, Poster.onLight, "TODAY", "${s.today.pages}",
                    "pages · ${formatDuration(s.today.seconds)}",
                )
            }
            Spacer(Modifier.height(20.dp))

            // The last seven days.
            RetroBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("LAST 7 DAYS", style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(12.dp))
                    val most = s.lastSeven.maxOf { it.pages }
                    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in s.lastSeven) {
                            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${d.pages}", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                val h = if (most == 0) 3f else 3f + 70f * d.pages / most
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(h.dp)
                                        .background(if (d.pages == 0) p.paperDark else Poster.terracotta)
                                        .border(2.dp, p.ink),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    d.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).uppercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row {
                Tile(Modifier.weight(1f), Poster.teal, Poster.onDark, "ALL TIME", "${s.totalPages}", "pages")
                Spacer(Modifier.width(16.dp))
                Tile(
                    Modifier.weight(1f), Poster.navy, Poster.onDark, "TIME READ", formatDuration(s.totalSeconds),
                    "on ${s.activeDays} days", small = true,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "A turn of one to three pages forward counts as pages read. Time counts while you turn pages, " +
                    "at most two minutes between turns.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, color: Color, on: Color, label: String, value: String, unit: String, small: Boolean = false) {
    RetroBox(modifier, color = color) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontFamily = Fonts.body, fontSize = 12.sp, letterSpacing = 1.5.sp, color = on)
            Spacer(Modifier.height(6.dp))
            Text(value, fontFamily = Fonts.display, fontSize = if (small) 24.sp else 40.sp, color = on, maxLines = 1)
            Text(unit, fontFamily = Fonts.body, fontSize = 12.sp, color = on)
        }
    }
}
