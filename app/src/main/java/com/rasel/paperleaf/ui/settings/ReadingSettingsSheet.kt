package com.rasel.paperleaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.App
import com.rasel.paperleaf.core.PageTint
import com.rasel.paperleaf.core.PageTurn
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox

/** The reader preferences. Apply to every book. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingSettingsSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val tint by App.prefs.tint.collectAsState()
    val turn by App.prefs.turn.collectAsState()
    val awake by App.prefs.keepAwake.collectAsState()
    val openLast by App.prefs.openLastBook.collectAsState()
    val inPdf by App.prefs.highlightsInPdf.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        // Opens all the way: nothing is hidden below the bottom edge.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text("Page color", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (t in PageTint.entries) {
                    Column(
                        Modifier.clickable { App.prefs.setTint(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RetroBox(color = Color(t.swatch), radius = 4.dp, shadow = if (t == tint) 4.dp else 0.dp) {
                            Box(Modifier.size(width = 64.dp, height = 80.dp), contentAlignment = Alignment.Center) {
                                Text("Aa", fontFamily = Fonts.display, fontSize = 24.sp, color = Color(t.textColor))
                                if (t == tint) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .background(Poster.terracotta, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t.label.uppercase(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("Page turn", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (turn == PageTurn.Flip) "Pages curl over like a real book." else "Pages slide sideways.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                for ((i, t) in PageTurn.entries.withIndex()) {
                    if (i > 0) Spacer(Modifier.width(14.dp))
                    val selected = t == turn
                    RetroBox(
                        Modifier.weight(1f),
                        color = if (selected) Poster.mustard else p.card,
                        shadow = if (selected) 3.dp else 0.dp,
                        onClick = { App.prefs.setTurn(t) },
                    ) {
                        Text(
                            t.label.uppercase(),
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            style = TextStyle(
                                fontFamily = Fonts.body,
                                fontSize = 13.sp,
                                letterSpacing = 1.2.sp,
                                color = if (selected) Poster.onLight else p.ink,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Applies to every book.", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on", style = MaterialTheme.typography.titleLarge)
                    Text("No dimming or lock while a book is open.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = awake,
                    onCheckedChange = { App.prefs.setKeepAwake(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Poster.terracotta, checkedThumbColor = Poster.onDark),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continue reading", style = MaterialTheme.typography.titleLarge)
                    Text("Open the last book when the app starts.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = openLast,
                    onCheckedChange = { App.prefs.setOpenLastBook(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Poster.terracotta, checkedThumbColor = Poster.onDark),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Highlights in the PDF", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (inPdf) "Saved inside the book's file too: kept if the app is removed, seen by other reader apps."
                        else "Kept only in the app: removed with the app; the PDF file is not changed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = inPdf,
                    onCheckedChange = { App.prefs.setHighlightsInPdf(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Poster.terracotta, checkedThumbColor = Poster.onDark),
                )
            }
        }
    }
}
