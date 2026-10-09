package fr.douwdy.lecteur.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.douwdy.lecteur.data.Tags
import fr.douwdy.lecteur.data.Track
import java.io.File

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
            .setArtworkUri(artworkUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()

/**
 * Fichier ouvert depuis une autre app ou le sélecteur de fichiers, décrit par ses propres tags.
 * Sans tags, le nom du fichier sert de titre.
 */
fun externalMediaItem(uri: Uri, displayName: String?, tags: Tags?, artworkFile: File?): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(tags?.title ?: displayName?.substringBeforeLast('.'))
                .setArtist(tags?.artist)
                .setAlbumTitle(tags?.album)
                .setAlbumArtist(tags?.albumArtist)
                .setTrackNumber(tags?.trackNumber)
                .setArtworkUri(artworkFile?.let(Uri::fromFile))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
