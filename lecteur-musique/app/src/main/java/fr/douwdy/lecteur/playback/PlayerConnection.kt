package fr.douwdy.lecteur.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** Ce que l'interface a besoin de savoir sur la lecture en cours. */
data class PlayerUiState(
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val mediaUri: Uri? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    /** Vrai si la lecture est demandée (y compris pendant le chargement) : pilote l'icône lecture/pause. */
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** File d'attente dans l'ordre réel de lecture (tient compte du mode aléatoire). */
    val queue: List<QueueEntry> = emptyList(),
)

data class QueueEntry(
    /** Position dans la playlist du lecteur, à passer à [PlayerConnection.skipTo]. */
    val index: Int,
    val title: String,
    val artist: String?,
    val isCurrent: Boolean,
)

/**
 * Relie l'interface au [PlaybackService] via un [MediaController].
 * Les commandes envoyées avant la fin de la connexion sont jouées dès qu'elle est établie.
 */
class PlayerConnection(context: Context, scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Titre des morceaux qui n'ont pas pu être lus. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var released = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.mediaMetadata?.title?.toString()
            _errors.tryEmit(title.orEmpty())
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        scope.launch {
            val connected = future.await()
            if (released) {
                connected.release()
                return@launch
            }
            controller = connected
            connected.addListener(listener)
            refresh(connected)
            while (pending.isNotEmpty()) pending.removeFirst()(connected)
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pending.addLast(action)
    }

    val currentPositionMs: Long get() = controller?.currentPosition ?: 0L

    fun play(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        withController {
            it.shuffleModeEnabled = shuffle
            it.setMediaItems(items, startIndex.coerceIn(items.indices), C.TIME_UNSET)
            it.prepare()
            it.play()
        }
    }

    fun togglePlayPause() = withController {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE) {
            it.pause()
        } else {
            when (it.playbackState) {
                Player.STATE_IDLE -> it.prepare()
                Player.STATE_ENDED -> it.seekToDefaultPosition()
                else -> Unit
            }
            it.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun skipTo(index: Int) = withController {
        it.seekToDefaultPosition(index)
        if (it.playbackState == Player.STATE_IDLE) it.prepare()
        it.play()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeatMode() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun release() {
        released = true
        pending.clear()
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
    }

    private fun refresh(player: Player) {
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        val playing = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        _state.value = PlayerUiState(
            hasMedia = item != null,
            mediaId = item?.mediaId,
            mediaUri = item?.let(::uriOf),
            title = metadata.title?.toString()
                ?: metadata.displayTitle?.toString()
                ?: item?.mediaId?.let { it.toUri().lastPathSegment }
                .orEmpty(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            isPlaying = playing,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queue = queueOf(player),
        )
    }

    private fun queueOf(player: Player): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val window = Timeline.Window()
        val current = player.currentMediaItemIndex
        val entries = ArrayList<QueueEntry>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET) {
            val item = timeline.getWindow(index, window).mediaItem
            entries += QueueEntry(
                index = index,
                title = item.mediaMetadata.title?.toString() ?: item.mediaId,
                artist = item.mediaMetadata.artist?.toString(),
                isCurrent = index == current,
            )
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return entries
    }

    private fun uriOf(item: MediaItem): Uri =
        item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: item.mediaId.toUri()
}
