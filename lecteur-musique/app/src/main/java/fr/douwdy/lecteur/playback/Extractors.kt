package fr.douwdy.lecteur.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/**
 * Extracteurs utilisés pour lire les fichiers, partagés par le lecteur et la lecture des tags :
 * ceux d'ExoPlayer, avec une recherche précise dans les MP3 à débit variable sans index, plus AIFF.
 */
@OptIn(UnstableApi::class)
fun audioExtractorsFactory(): ExtractorsFactory = WithAiffExtractorsFactory(
    DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
)
