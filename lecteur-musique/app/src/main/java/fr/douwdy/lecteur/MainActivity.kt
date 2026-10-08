package fr.douwdy.lecteur

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import fr.douwdy.lecteur.ui.components.EmptyMessage
import fr.douwdy.lecteur.ui.components.MiniPlayer
import fr.douwdy.lecteur.ui.screens.CollectionScreen
import fr.douwdy.lecteur.ui.screens.LibraryScreen
import fr.douwdy.lecteur.ui.screens.PlayerScreen
import fr.douwdy.lecteur.ui.theme.LecteurTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleViewIntent(intent)

        setContent {
            LecteurTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(viewModel)
                }
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
    val snackbar = remember { SnackbarHostState() }

    val unplayable = stringResource(R.string.error_unplayable)
    val unplayableUnknown = stringResource(R.string.error_unplayable_unknown)
    LaunchedEffect(Unit) {
        viewModel.player.errors.collect { title ->
            snackbar.showSnackbar(if (title.isBlank()) unplayableUnknown else unplayable.format(title))
        }
    }

    val onPlayer = backStack?.destination?.route == Routes.PLAYER
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = playerState.hasMedia && !onPlayer,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                MiniPlayer(
                    state = playerState,
                    connection = viewModel.player,
                    onOpen = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            AppNavHost(
                navController = navController,
                viewModel = viewModel,
                currentMediaId = playerState.mediaId,
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
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: MusicViewModel,
    currentMediaId: String?,
    openFiles: () -> Unit,
    /** Écran affiché à la place de la bibliothèque tant que la permission n'est pas accordée. */
    permissionGate: (@Composable () -> Unit)?,
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    val loaded = (library as? LibraryState.Loaded)?.library

    NavHost(navController, startDestination = Routes.LIBRARY) {
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
                title = album.title,
                subtitle = album.artist,
                tracks = album.tracks,
                coverUri = album.tracks.first().uri,
                isAlbum = true,
                currentMediaId = currentMediaId,
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
                title = artist.name,
                subtitle = null,
                tracks = artist.tracks,
                currentMediaId = currentMediaId,
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
                title = folder.name,
                subtitle = folder.path,
                tracks = folder.tracks,
                currentMediaId = currentMediaId,
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
    EmptyMessage(
        title = stringResource(R.string.permission_title),
        body = stringResource(R.string.permission_text),
        modifier = Modifier.padding(top = 96.dp),
        action = {
            Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Button(
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
            ) {
                Text(
                    stringResource(if (permanentlyDenied) R.string.permission_settings else R.string.permission_grant),
                )
            }
            TextButton(onClick = onOpenFiles) {
                Text(stringResource(R.string.action_open_files))
            }
        },
    )
}
