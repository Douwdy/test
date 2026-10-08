package fr.douwdy.lecteur

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.douwdy.lecteur.ui.LibraryState
import fr.douwdy.lecteur.ui.MusicViewModel
import fr.douwdy.lecteur.ui.components.Message
import fr.douwdy.lecteur.ui.components.MiniPlayer
import fr.douwdy.lecteur.ui.components.PillButton
import fr.douwdy.lecteur.ui.components.ToastHost
import fr.douwdy.lecteur.ui.components.ToastState
import fr.douwdy.lecteur.ui.screens.CollectionScreen
import fr.douwdy.lecteur.ui.screens.LibraryScreen
import fr.douwdy.lecteur.ui.screens.PlayerScreen
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.LecteurTheme
import fr.douwdy.lecteur.ui.theme.Theme

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Interface toujours sombre : icônes claires dans les barres système.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleViewIntent(intent)

        setContent {
            LecteurTheme {
                App(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** Fichier ouvert depuis une autre application (« Ouvrir avec… »). */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.playUris(listOf(it)) }
        }
    }
}

private object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{name}"
    const val FOLDER = "folder/{path}"

    fun album(id: Long) = "album/$id"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
}

/** Hauteur occupée par le mini-lecteur flottant, marges comprises. */
private val MINI_PLAYER_HEIGHT = 80.dp

/** Types de fichiers proposés par le sélecteur : l'audio, plus les conteneurs souvent mal étiquetés. */
private val PICKABLE_TYPES = arrayOf(
    "audio/*",
    "application/ogg",
    "application/x-flac",
    "video/x-matroska",
    "video/webm",
    "application/octet-stream",
)

private val audioPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
private fun App(viewModel: MusicViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun isGranted() =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        // Après un refus « définitif », Android n'affiche plus la demande : il faut passer par les paramètres.
        permanentlyDenied = !it && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
    }
    // Une seule demande automatique, même si l'écran tourne ensuite.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permissionLauncher.launch(audioPermission)
        }
    }
    // Retour depuis les paramètres système : la permission a peut-être été accordée entre-temps.
    LifecycleResumeEffect(Unit) {
        granted = isGranted()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) {
        if (granted) viewModel.loadLibrary()
    }

    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Garde l'accès aux fichiers choisis même après un redémarrage de l'app.
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.playUris(uris)
    }
    val openFiles = { filesLauncher.launch(PICKABLE_TYPES) }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val toast = remember { ToastState() }

    val unplayable = stringResource(R.string.error_unplayable)
    val unplayableUnknown = stringResource(R.string.error_unplayable_unknown)
    LaunchedEffect(Unit) {
        viewModel.player.errors.collect { title ->
            toast.show(if (title.isBlank()) unplayableUnknown else unplayable.format(title))
        }
    }

    val showMiniPlayer = playerState.hasMedia && backStack?.destination?.route != Routes.PLAYER
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = navBar + if (playerState.hasMedia) MINI_PLAYER_HEIGHT else 0.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme.colors.background),
    ) {
        AppNavHost(
            navController = navController,
            viewModel = viewModel,
            bottomPadding = bottomPadding,
            openFiles = openFiles,
            permissionGate = if (granted) {
                null
            } else {
                {
                    PermissionScreen(
                        permanentlyDenied = permanentlyDenied,
                        onRequest = { permissionLauncher.launch(audioPermission) },
                        onOpenFiles = openFiles,
                    )
                }
            },
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) {
            ToastHost(toast)
            AnimatedVisibility(
                visible = showMiniPlayer,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(
                    state = playerState,
                    connection = viewModel.player,
                    onOpen = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: MusicViewModel,
    bottomPadding: Dp,
    openFiles: () -> Unit,
    /** Écran affiché à la place de la bibliothèque tant que la permission n'est pas accordée. */
    permissionGate: (@Composable () -> Unit)?,
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val loaded = (library as? LibraryState.Loaded)?.library
    val currentMediaId = playerState.mediaId
    val isPlaying = playerState.isPlaying

    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        enterTransition = { fadeIn() + slideInVertically { it / 12 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() + slideOutVertically { it / 12 } },
    ) {
        composable(Routes.LIBRARY) {
            if (permissionGate != null) {
                permissionGate()
                return@composable
            }
            LibraryScreen(
                viewModel = viewModel,
                state = filtered,
                query = query,
                currentMediaId = currentMediaId,
                isPlaying = isPlaying,
                bottomPadding = bottomPadding,
                onOpenAlbum = { navController.navigate(Routes.album(it)) },
                onOpenArtist = { navController.navigate(Routes.artist(it)) },
                onOpenFolder = { navController.navigate(Routes.folder(it)) },
                onOpenFiles = openFiles,
            )
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val album = loaded?.albums?.find { it.id == entry.arguments?.getLong("id") }
            if (album == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            CollectionScreen(
                kind = stringResource(R.string.kind_album),
                title = album.title,
                subtitle = album.artist,
                tracks = album.tracks,
                coverUri = album.tracks.first().uri,
                isAlbum = true,
                currentMediaId = currentMediaId,
                isPlaying = isPlaying,
                bottomPadding = bottomPadding,
                onPlay = { viewModel.playTracks(album.tracks, it) },
                onShuffle = { viewModel.shuffle(album.tracks) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.ARTIST) { entry ->
            val name = entry.arguments?.getString("name")?.let(Uri::decode)
            val artist = loaded?.artists?.find { it.name == name }
            if (artist == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            CollectionScreen(
                kind = stringResource(R.string.kind_artist),
                title = artist.name,
                subtitle = null,
                tracks = artist.tracks,
                currentMediaId = currentMediaId,
                isPlaying = isPlaying,
                bottomPadding = bottomPadding,
                onPlay = { viewModel.playTracks(artist.tracks, it) },
                onShuffle = { viewModel.shuffle(artist.tracks) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.FOLDER) { entry ->
            val path = entry.arguments?.getString("path")?.let(Uri::decode)
            val folder = loaded?.folders?.find { it.path == path }
            if (folder == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            CollectionScreen(
                kind = stringResource(R.string.kind_folder),
                title = folder.name,
                subtitle = folder.path,
                tracks = folder.tracks,
                currentMediaId = currentMediaId,
                isPlaying = isPlaying,
                bottomPadding = bottomPadding,
                onPlay = { viewModel.playTracks(folder.tracks, it) },
                onShuffle = { viewModel.shuffle(folder.tracks) },
                onBack = navController::popBackStack,
            )
        }
        composable(
            Routes.PLAYER,
            enterTransition = { slideInVertically { it } },
            exitTransition = { slideOutVertically { it } },
            popExitTransition = { slideOutVertically { it } },
        ) {
            if (!playerState.hasMedia) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            PlayerScreen(
                state = playerState,
                connection = viewModel.player,
                onCollapse = navController::popBackStack,
            )
        }
    }
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Message(
            title = stringResource(R.string.permission_title),
            body = stringResource(R.string.permission_text),
            actions = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    PillButton(
                        text = stringResource(
                            if (permanentlyDenied) R.string.permission_settings else R.string.permission_grant,
                        ),
                        icon = null,
                        onClick = {
                            if (permanentlyDenied) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            } else {
                                onRequest()
                            }
                        },
                    )
                    PillButton(
                        text = stringResource(R.string.action_open_files),
                        icon = Icons.Open,
                        onClick = onOpenFiles,
                        filled = false,
                    )
                }
            },
        )
    }
}
