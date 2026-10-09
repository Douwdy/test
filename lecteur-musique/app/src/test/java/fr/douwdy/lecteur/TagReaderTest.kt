package fr.douwdy.lecteur

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import fr.douwdy.lecteur.data.TagReader
import fr.douwdy.lecteur.data.Tags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lecture des tags de fichiers réels (src/test/resources/tags), générés avec ffmpeg :
 * titre, artiste, album, artiste de l'album, piste 3/12, disque 2/2 et une pochette JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TagReaderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun mp3() = assertFullTags("mp3.mp3")

    @Test fun flac() = assertFullTags("flac.flac")

    @Test fun m4aAac() = assertFullTags("m4a-aac.m4a")

    @Test fun m4aAlac() = assertFullTags("m4a-alac.m4a")

    @Test fun oggVorbis() = assertFullTags("vorbis.ogg")

    @Test fun opus() = assertFullTags("opus.opus")

    @Test fun wavId3() = assertFullTags("wav-id3.wav")

    /** Liste INFO : pas d'artiste d'album ni de disque dans ce format. */
    @Test
    fun wavInfo() {
        val tags = read("wav.wav")
        assertEquals("Titre de test", tags.title)
        assertEquals("Artiste de test", tags.artist)
        assertEquals("Album de test", tags.album)
        assertEquals(3, tags.trackNumber)
    }

    @Test fun aiff() = assertTextTags(read("aiff.aiff"))

    /** ExoPlayer ne lit pas les tags Matroska : pas de tags, mais pas de plantage non plus. */
    @Test
    fun mka() {
        assertNull(runBlocking { TagReader.read(context, copy("mka.mka")) })
    }

    @Test
    fun notAudio() {
        val file = File(context.cacheDir, "texte.mp3").apply { writeText("pas de la musique") }
        assertNull(runBlocking { TagReader.read(context, Uri.fromFile(file)) })
    }

    private fun assertFullTags(name: String) {
        val tags = read(name)
        assertTextTags(tags)
        assertNotNull("pochette de $name", tags.artwork)
    }

    private fun assertTextTags(tags: Tags) {
        assertEquals("Titre de test", tags.title)
        assertEquals("Artiste de test", tags.artist)
        assertEquals("Album de test", tags.album)
        assertEquals("Artiste de l'album", tags.albumArtist)
        assertEquals(3, tags.trackNumber)
        assertEquals(2, tags.discNumber)
        assertNotNull(tags.durationMs)
    }

    private fun read(name: String): Tags =
        assertNotNull(runBlocking { TagReader.read(context, copy(name)) }, name)

    private fun copy(name: String): Uri {
        val file = File(context.cacheDir, name)
        javaClass.getResourceAsStream("/tags/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun <T> assertNotNull(value: T?, name: String): T {
        assertNotNull("tags de $name", value)
        return value!!
    }
}
