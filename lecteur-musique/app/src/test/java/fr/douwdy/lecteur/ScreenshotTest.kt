package fr.douwdy.lecteur

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import fr.douwdy.lecteur.data.Album
import fr.douwdy.lecteur.data.Artist
import fr.douwdy.lecteur.data.Folder
import fr.douwdy.lecteur.data.Library
import fr.douwdy.lecteur.data.Track
import fr.douwdy.lecteur.playback.PlaybackService
import fr.douwdy.lecteur.playback.PlayerUiState
import fr.douwdy.lecteur.playback.QueueEntry
import fr.douwdy.lecteur.ui.LibraryState
import fr.douwdy.lecteur.ui.MusicViewModel
import fr.douwdy.lecteur.ui.components.MiniPlayer
import fr.douwdy.lecteur.ui.screens.CollectionScreen
import fr.douwdy.lecteur.ui.screens.LibraryScreen
import fr.douwdy.lecteur.ui.screens.PlayerScreen
import fr.douwdy.lecteur.ui.theme.LecteurTheme
import fr.douwdy.lecteur.ui.theme.Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendu des écrans principaux avec une fausse bibliothèque, enregistré dans build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewModel by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Robolectric ne sait pas lier le service Media3 : on le déclare injoignable.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        MusicViewModel(app)
    }

    private val tracks = listOf(
        track(1, "Les nuits blanches", "Mona Ferrand", "Rivages", 1, 214_000),
        track(2, "Sous la pluie de juin", "Mona Ferrand", "Rivages", 2, 187_000),
        track(3, "Boulevard", "Les Ondes Courtes", "Fréquences", 1, 251_000),
        track(4, "Minuit passé", "Mona Ferrand", "Rivages", 3, 302_000),
        track(5, "Grand large", "Ilan Varga", "Marées", 1, 276_000),
        track(6, "Électricité", "Les Ondes Courtes", "Fréquences", 2, 199_000),
        track(7, "Une ville en hiver", "Ilan Varga", "Marées", 2, 233_000),
        track(8, "Le dernier métro", "Ilan Varga", "Marées", 3, 265_000),
        track(9, "Zinc", "Les Ondes Courtes", "Fréquences", 3, 172_000),
    )

    private val library = Library(
        tracks = tracks,
        albums = tracks.groupBy { it.album }.map { (title, t) -> Album(title, title, t.first().artist, t) },
        artists = tracks.groupBy { it.artist }.map { (name, t) -> Artist(name, 1, t) },
        folders = listOf(Folder("Music/Rivages", "Rivages", tracks.take(3))),
    )

    private val playing = PlayerUiState(
        hasMedia = true,
        mediaId = tracks[3].mediaId,
        mediaUri = tracks[3].uri,
        title = tracks[3].title,
        artist = tracks[3].artist,
        album = tracks[3].album,
        isPlaying = true,
        durationMs = tracks[3].durationMs,
        shuffle = true,
        repeatMode = Player.REPEAT_MODE_ONE,
        queue = tracks.mapIndexed { i, t -> QueueEntry(i, t.title, t.artist, i == 3) },
    )

    @Test
    fun library() {
        compose.setContent {
            Screen {
                LibraryScreen(
                    viewModel = viewModel,
                    state = LibraryState.Loaded(library),
                    query = "",
                    currentMediaId = playing.mediaId,
                    isPlaying = true,
                    bottomPadding = 80.dp,
                    onOpenAlbum = {},
                    onOpenArtist = {},
                    onOpenFolder = {},
                    onOpenFiles = {},
                )
                MiniPlayer(
                    state = playing,
                    connection = viewModel.player,
                    onOpen = {},
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/1-bibliotheque.png")

        compose.onNodeWithText("Albums").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/2-albums.png")
    }

    @Test
    fun album() {
        val album = library.albums.first()
        compose.setContent {
            Screen {
                CollectionScreen(
                    kind = "Album",
                    title = album.title,
                    subtitle = album.artist,
                    tracks = album.tracks,
                    currentMediaId = playing.mediaId,
                    isPlaying = true,
                    bottomPadding = 0.dp,
                    onPlay = {},
                    onShuffle = {},
                    onBack = {},
                    coverUri = album.tracks.first().coverUri,
                    isAlbum = true,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/3-album.png")
    }

    @Test
    fun player() {
        compose.setContent {
            Screen { PlayerScreen(state = playing, connection = viewModel.player, onCollapse = {}) }
        }
        compose.onRoot().captureRoboImage("build/screenshots/4-lecteur.png")

        compose.onNodeWithContentDescription("File d'attente").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/5-file-attente.png")
    }

    /** Icône de l'app, avec le masque rond le plus courant et le cercle de la zone sûre (66 dp). */
    @Test
    fun launcherIcon() {
        compose.setContent {
            Box(
                Modifier
                    .size(216.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.ic_launcher_background)),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(132.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/0-icone.png")
    }

    @Composable
    private fun Screen(content: @Composable BoxScope.() -> Unit) {
        LecteurTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Theme.colors.background),
                content = content,
            )
        }
    }

    private fun track(id: Long, title: String, artist: String, album: String, number: Int, duration: Long) = Track(
        id = id,
        uri = "content://media/external/audio/media/$id".toUri(),
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        albumId = album.hashCode().toLong(),
        durationMs = duration,
        trackNumber = number,
        discNumber = 0,
        folderPath = "Music/$album",
        fileName = "$title.flac",
    )
}
