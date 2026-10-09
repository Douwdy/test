package fr.douwdy.lecteur

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import fr.douwdy.lecteur.data.MusicRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Bibliothèque de bout en bout, avec un faux MediaStore qui se comporte comme celui des téléphones
 * qui ne lisent les tags que des MP3 : nom de fichier comme titre, artiste inconnu, dossier comme album.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        FakeMediaStore.dir = File(context.cacheDir, "Music").apply { mkdirs() }
        for ((_, name) in FakeMediaStore.FILES) {
            javaClass.getResourceAsStream("/tags/$name")!!.use { input ->
                File(FakeMediaStore.dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun tagsAreReadWhenMediaStoreHasNone() = runBlocking {
        val repository = MusicRepository(context)
        val before = repository.load()
        assertEquals(FakeMediaStore.FILES.size, before.tracks.size)
        assertTrue(before.tracks.all { it.artist == "Artiste inconnu" })

        val after = repository.enrich(before).toList().last()
        for (track in after.tracks) {
            assertEquals(track.fileName, "Titre de test", track.title)
            assertEquals(track.fileName, "Artiste de test", track.artist)
            assertEquals(track.fileName, "Album de test", track.album)
            assertEquals(track.fileName, 3, track.trackNumber)
        }
        // Pochettes extraites pour les formats qui en ont une.
        val withArtwork = after.tracks.filter { it.artworkFile != null }.map { it.fileName }.toSet()
        assertEquals(setOf("flac.flac", "vorbis.ogg", "opus.opus", "m4a-alac.m4a", "wav-id3.wav"), withArtwork)
        // Tous les morceaux ont le même album et le même artiste d'album : un seul album.
        assertEquals(1, after.albums.size)
        assertEquals("Artiste de l'album", after.albums.single().artist)
        assertEquals(1, after.artists.size)
    }

    @Test
    fun tagsAreCachedBetweenLaunches() = runBlocking {
        val first = MusicRepository(context)
        first.enrich(first.load()).toList()

        // Nouveau lancement : les tags viennent du cache, sans relire les fichiers.
        val second = MusicRepository(context)
        val library = second.load()
        assertTrue(library.tracks.all { it.title == "Titre de test" })
        assertNotNull(library.tracks.first { it.fileName == "flac.flac" }.artworkFile)
        assertTrue("rien à relire", second.enrich(library).toList().isEmpty())
    }
}

class FakeMediaStore : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection)
        for ((id, name) in FILES) {
            val values = mapOf(
                MediaStore.Audio.Media._ID to id,
                MediaStore.Audio.Media.TITLE to name.substringBeforeLast('.'),
                MediaStore.Audio.Media.ARTIST to MediaStore.UNKNOWN_STRING,
                MediaStore.Audio.Media.ALBUM to "Music",
                MediaStore.Audio.Media.ALBUM_ID to 1L,
                MediaStore.Audio.Media.DURATION to 1000L,
                MediaStore.Audio.Media.TRACK to 0,
                MediaStore.Audio.Media.DISPLAY_NAME to name,
                MediaStore.Audio.Media.DATE_MODIFIED to 1_700_000_000L,
                MediaStore.Audio.Media.SIZE to File(dir, name).length(),
                MediaStore.Audio.Media.RELATIVE_PATH to "Music/",
                MediaStore.Audio.Media.ALBUM_ARTIST to null,
            )
            cursor.addRow(projection!!.map { values[it] })
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = FILES.first { it.first == uri.lastPathSegment!!.toLong() }.second
        return ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        lateinit var dir: File

        /** Formats dont les tags complets (dont l'artiste d'album) sont lisibles. */
        val FILES = listOf(
            1L to "flac.flac",
            2L to "vorbis.ogg",
            3L to "opus.opus",
            4L to "m4a-alac.m4a",
            5L to "wav-id3.wav",
            6L to "aiff.aiff",
        )
    }
}
