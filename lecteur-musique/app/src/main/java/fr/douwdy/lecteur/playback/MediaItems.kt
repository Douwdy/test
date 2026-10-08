package fr.douwdy.lecteur.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.douwdy.lecteur.data.Track

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setArtworkUri(albumArtUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()

/**
 * Fichier ouvert depuis une autre app ou le sélecteur de fichiers : on ne connaît que son nom,
 * le reste (titre, artiste, pochette) sera lu dans ses tags au moment de la lecture.
 */
fun externalMediaItem(uri: Uri, displayName: String?): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayName?.substringBeforeLast('.'))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()
