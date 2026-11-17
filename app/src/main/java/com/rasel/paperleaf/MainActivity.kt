package com.rasel.paperleaf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rasel.paperleaf.core.NotAPdfException
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.ui.DayPalette
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.NightPalette
import com.rasel.paperleaf.ui.PaperleafTheme
import com.rasel.paperleaf.ui.library.LibraryScreen
import com.rasel.paperleaf.ui.notes.NotesScreen
import com.rasel.paperleaf.ui.reader.ReaderScreen
import com.rasel.paperleaf.ui.review.ReviewScreen
import com.rasel.paperleaf.ui.stats.StatsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The screens; the last one in the stack is shown. */
sealed interface Screen {
    data object Library : Screen
    data class Reader(val book: Book, val startPage: Int? = null) : Screen
    data class Notes(val book: Book) : Screen
    data object Stats : Screen
    data object Review : Screen
}

/** Shows a short message at the bottom of the screen. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

class MainActivity : ComponentActivity() {
    /** A PDF another app asked to open ("Open with"). */
    private val incoming = MutableStateFlow<Uri?>(null)

    /** A cold start (not the system recreating the screen): the last book may reopen. */
    private var freshStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Not after the system recreated the screen: that one was handled.
        if (savedInstanceState == null) incoming.value = intent.pdfUri()
        freshStart = savedInstanceState == null
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.pdfUri()?.let { incoming.value = it }
    }

    private fun Intent?.pdfUri(): Uri? = if (this?.action == Intent.ACTION_VIEW) data else null

    @Composable
    private fun Root() {
        val tint by App.prefs.tint.collectAsState()
        // The whole app goes dark with the Night page.
        val palette = if (tint.isDark) NightPalette else DayPalette
        LaunchedEffect(palette.night) {
            val style = if (palette.night) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }

        var stack by remember { mutableStateOf(listOf<Screen>(Screen.Library)) }
        fun push(screen: Screen) {
            stack = stack + screen
        }
        fun pop() {
            if (stack.size > 1) stack = stack.dropLast(1) else finish()
        }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val toast: (String) -> Unit = { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }

        // Continue reading: start in the book that was open when the app was left.
        LaunchedEffect(Unit) {
            if (!freshStart || incoming.value != null || !App.prefs.openLastBook.value) return@LaunchedEffect
            freshStart = false
            val id = App.prefs.lastBookId
            if (id <= 0) return@LaunchedEffect
            val book = App.repo.book(id)
            if (book != null && java.io.File(book.filePath).exists()) {
                stack = listOf(Screen.Library, Screen.Reader(book))
            } else {
                App.prefs.lastBookId = -1
            }
        }

        val incomingUri by incoming.collectAsState()
        LaunchedEffect(incomingUri) {
            val uri = incomingUri ?: return@LaunchedEffect
            incoming.value = null
            // Not in this effect: clearing the request above restarts it,
            // which would cancel the import half way.
            scope.launch {
                try {
                    val book = App.library.importIncoming(uri)
                    // Back to the shelf first: never a reader on top of a reader.
                    stack = listOf(Screen.Library, Screen.Reader(book))
                } catch (_: NotAPdfException) {
                    toast("That file is not a PDF, or it is damaged.")
                } catch (_: Exception) {
                    toast("Could not open that PDF.")
                }
            }
        }

        PaperleafTheme(palette) {
            CompositionLocalProvider(LocalToast provides toast) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(LocalPalette.current.paper),
                ) {
                    BackHandler(enabled = stack.size > 1) { pop() }
                    when (val screen = stack.last()) {
                        Screen.Library -> LibraryScreen(open = ::push)
                        is Screen.Reader -> ReaderScreen(
                            book = screen.book,
                            startPage = screen.startPage,
                            onClose = ::pop,
                        )
                        is Screen.Notes -> NotesScreen(
                            book = screen.book,
                            onBack = ::pop,
                            onOpenPage = { page -> stack = listOf(Screen.Library, Screen.Reader(screen.book, page)) },
                        )
                        Screen.Stats -> StatsScreen(onBack = ::pop)
                        Screen.Review -> ReviewScreen(onBack = ::pop)
                    }
                    SnackbarHost(
                        snackbar,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(12.dp),
                    ) { data ->
                        Snackbar(containerColor = LocalPalette.current.ink, contentColor = LocalPalette.current.paper) {
                            Text(data.visuals.message, fontFamily = Fonts.body)
                        }
                    }
                    SplashOverlay()
                }
            }
        }
    }

    /** The book icon and the app's name, shown briefly at start, then faded out. */
    @Composable
    private fun SplashOverlay() {
        var visible by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(900)
            visible = false
        }
        AnimatedVisibility(visible, enter = EnterTransition.None, exit = fadeOut(tween(350))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(DayPalette.paper)
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.book_icon), null, Modifier.size(168.dp))
                Spacer(Modifier.height(20.dp))
                Text("Paperleaf", fontFamily = Fonts.display, fontSize = 30.sp, color = DayPalette.ink)
            }
        }
    }
}

/** Reloads [load] now and after every database change. */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, load: suspend () -> T): T? {
    val version by App.repo.changes.collectAsState()
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(version, *keys) {
        value = runCatching { load() }.getOrNull() ?: value
    }
    return value
}
