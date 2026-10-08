package fr.douwdy.lecteur.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fr.douwdy.lecteur.MainActivity

/**
 * Service de lecture : garde la musique en route écran éteint, affiche la notification
 * et répond aux commandes du casque, de la montre, d'Android Auto, etc.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Les décodeurs du téléphone d'abord (économes en batterie), puis FFmpeg pour tout
        // ce qu'ils ne savent pas lire : ALAC, Opus, Vorbis, FLAC, AC-3, E-AC-3, DTS, TrueHD, AMR…
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // Recherche précise dans les MP3 à débit variable sans en-tête d'index, et AIFF en plus.
        val extractorsFactory = WithAiffExtractorsFactory(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
        )

        val player = ExoPlayer.Builder(
            this,
            renderersFactory,
            DefaultMediaSourceFactory(this, extractorsFactory),
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause quand on débranche le casque
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(SkipUnplayableListener(player))

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L'app est fermée depuis les applis récentes : on continue seulement si ça joue.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Un fichier illisible ou corrompu ne doit pas bloquer toute la file : on passe au suivant. */
    private class SkipUnplayableListener(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private object SessionCallback : MediaSession.Callback {
        /**
         * Les éléments reçus d'un contrôleur peuvent arriver sans leur URI (elle n'est pas toujours
         * transmise d'un processus à l'autre) : on la reconstruit depuis l'identifiant du média.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.mapTo(ArrayList()) { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    item.buildUpon()
                        .setUri(item.requestMetadata.mediaUri ?: item.mediaId.toUri())
                        .build()
                }
            },
        )
    }
}
