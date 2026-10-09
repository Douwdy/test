package fr.douwdy.lecteur

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import fr.douwdy.lecteur.data.ArtworkLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * La pochette s'affiche même quand Android ne sait pas l'extraire (WAV, Opus…),
 * y compris pour un morceau dont les tags n'ont pas eu besoin d'être relus.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtworkLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun wav() = assertArtwork("wav-id3.wav")

    @Test fun opus() = assertArtwork("opus.opus")

    @Test fun flac() = assertArtwork("flac.flac")

    private fun assertArtwork(name: String) {
        val file = File(context.cacheDir, name)
        javaClass.getResourceAsStream("/tags/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        assertNotNull("pochette de $name", runBlocking { ArtworkLoader.load(context, Uri.fromFile(file), 64) })
    }
}
