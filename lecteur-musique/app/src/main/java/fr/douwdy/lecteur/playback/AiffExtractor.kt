package fr.douwdy.lecteur.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.max
import kotlin.math.min

/** Ajoute la prise en charge des fichiers AIFF aux extracteurs d'ExoPlayer. */
@OptIn(UnstableApi::class)
class WithAiffExtractorsFactory(private val base: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = base.createExtractors() + AiffExtractor()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        base.createExtractors(uri, responseHeaders) + AiffExtractor()
}

/**
 * Lecture des fichiers AIFF / AIFF-C non compressés (PCM 16, 24 ou 32 bits), qu'ExoPlayer ne gère pas.
 *
 * Structure : un en-tête « FORM » puis des blocs, dont COMM (format) et SSND (échantillons).
 * Les échantillons sont envoyés tels quels au lecteur, par paquets d'environ 100 ms.
 */
@OptIn(UnstableApi::class)
class AiffExtractor : Extractor {

    private lateinit var extractorOutput: ExtractorOutput
    private lateinit var trackOutput: TrackOutput

    private var format: PcmFormat? = null
    private var dataStart = C.INDEX_UNSET.toLong()
    private var dataEnd = C.INDEX_UNSET.toLong()
    private var headerRead = false

    // Position de lecture, réinitialisée à chaque saut.
    private var startTimeUs = 0L
    private var framesWritten = 0L
    private var pendingBytes = 0

    private class PcmFormat(
        val channels: Int,
        val sampleRate: Int,
        val frames: Long,
        val bytesPerFrame: Int,
        val encoding: Int,
    )

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ParsableByteArray(12)
        if (!input.peekFully(header.data, 0, 12, true)) return false
        if (header.readInt() != FORM) return false
        header.skipBytes(4)
        val type = header.readInt()
        return type == AIFF || type == AIFC
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerRead) {
            readHeader(input)
            headerRead = true
            // On se place au début des échantillons (le saut en arrière peut être nécessaire
            // si le bloc SSND précède le bloc COMM).
            if (input.position != dataStart) {
                seekPosition.position = dataStart
                return Extractor.RESULT_SEEK
            }
        }
        return readSamples(input)
    }

    private fun readHeader(input: ExtractorInput) {
        val header = ParsableByteArray(12)
        input.readFully(header.data, 0, 12)
        header.skipBytes(8)
        val isAifc = header.readInt() == AIFC

        var comm: PcmFormat? = null
        var ssndStart = C.INDEX_UNSET.toLong()
        var ssndEnd = C.INDEX_UNSET.toLong()
        val chunkHeader = ParsableByteArray(8)

        while (comm == null || ssndStart == C.INDEX_UNSET.toLong()) {
            if (!input.readFully(chunkHeader.data, 0, 8, true)) break
            chunkHeader.position = 0
            val id = chunkHeader.readInt()
            val size = chunkHeader.readUnsignedInt()
            val padded = size + (size and 1)
            when (id) {
                COMM -> {
                    val body = ParsableByteArray(size.toInt())
                    input.readFully(body.data, 0, size.toInt())
                    if (padded > size) input.skipFully(1)
                    comm = parseComm(body, isAifc)
                }
                SSND -> {
                    val ssnd = ParsableByteArray(8)
                    input.readFully(ssnd.data, 0, 8)
                    val offset = ssnd.readUnsignedInt()
                    ssndStart = input.position + offset
                    ssndEnd = input.position - 8 + size
                    if (input.length != C.LENGTH_UNSET.toLong()) ssndEnd = min(ssndEnd, input.length)
                    // Sauter les échantillons pour trouver COMM s'il est placé après.
                    if (comm == null) input.skipFully((padded - 8).toInt())
                }
                else -> input.skipFully(padded.toInt())
            }
        }

        val pcm = comm ?: throw ParserException.createForMalformedContainer("AIFF sans bloc COMM", null)
        if (ssndStart == C.INDEX_UNSET.toLong()) {
            throw ParserException.createForMalformedContainer("AIFF sans bloc SSND", null)
        }
        format = pcm
        dataStart = ssndStart
        dataEnd = min(ssndEnd, ssndStart + pcm.frames * pcm.bytesPerFrame)

        val bytesPerSecond = pcm.sampleRate * pcm.bytesPerFrame
        trackOutput.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(pcm.channels)
                .setSampleRate(pcm.sampleRate)
                .setPcmEncoding(pcm.encoding)
                .setAverageBitrate(bytesPerSecond * 8)
                .setPeakBitrate(bytesPerSecond * 8)
                .setMaxInputSize(targetSampleBytes(pcm))
                .build(),
        )
        extractorOutput.seekMap(AiffSeekMap(pcm, dataStart))
    }

    private fun parseComm(body: ParsableByteArray, isAifc: Boolean): PcmFormat {
        val channels = body.readUnsignedShort()
        val frames = body.readUnsignedInt()
        val bitsPerSample = body.readUnsignedShort()
        val sampleRate = readExtended(body).toInt()
        val compression = if (isAifc && body.bytesLeft() >= 4) body.readInt() else NONE

        // Les tailles non multiples de 8 (12, 20 bits…) sont stockées dans l'octet supérieur.
        val bytesPerSample = (bitsPerSample + 7) / 8
        val encoding = when (compression) {
            NONE, TWOS -> when (bytesPerSample) {
                2 -> C.ENCODING_PCM_16BIT_BIG_ENDIAN
                3 -> C.ENCODING_PCM_24BIT_BIG_ENDIAN
                4 -> C.ENCODING_PCM_32BIT_BIG_ENDIAN
                else -> C.ENCODING_INVALID
            }
            SOWT -> when (bytesPerSample) {
                2 -> C.ENCODING_PCM_16BIT
                3 -> C.ENCODING_PCM_24BIT
                4 -> C.ENCODING_PCM_32BIT
                else -> C.ENCODING_INVALID
            }
            else -> C.ENCODING_INVALID
        }
        if (encoding == C.ENCODING_INVALID || channels == 0 || sampleRate <= 0) {
            throw ParserException.createForUnsupportedContainerFeature(
                "AIFF non pris en charge : $bitsPerSample bits, compression ${fourCc(compression)}",
            )
        }
        return PcmFormat(channels, sampleRate, frames, channels * bytesPerSample, encoding)
    }

    private fun readSamples(input: ExtractorInput): Int {
        val pcm = format ?: return Extractor.RESULT_END_OF_INPUT
        val target = targetSampleBytes(pcm)
        val bytesLeft = dataEnd - input.position
        if (bytesLeft <= 0) {
            commit(pcm, force = true)
            return Extractor.RESULT_END_OF_INPUT
        }
        val toRead = min((target - pendingBytes).toLong(), bytesLeft).toInt()
        val read = trackOutput.sampleData(input, toRead, true)
        if (read == C.RESULT_END_OF_INPUT) {
            commit(pcm, force = true)
            return Extractor.RESULT_END_OF_INPUT
        }
        pendingBytes += read
        commit(pcm, force = false)
        return Extractor.RESULT_CONTINUE
    }

    /** Publie les trames complètes accumulées, dès qu'il y en a assez (ou à la fin du fichier). */
    private fun commit(pcm: PcmFormat, force: Boolean) {
        if (!force && pendingBytes < targetSampleBytes(pcm)) return
        val frames = pendingBytes / pcm.bytesPerFrame
        if (frames == 0) return
        val size = frames * pcm.bytesPerFrame
        val timeUs = startTimeUs + framesWritten * C.MICROS_PER_SECOND / pcm.sampleRate
        trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, pendingBytes - size, null)
        framesWritten += frames
        pendingBytes -= size
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            headerRead = false
            format = null
        }
        startTimeUs = timeUs
        framesWritten = 0
        pendingBytes = 0
    }

    override fun release() = Unit

    private class AiffSeekMap(private val pcm: PcmFormat, private val dataStart: Long) : SeekMap {
        override fun isSeekable() = true

        override fun getDurationUs(): Long = pcm.frames * C.MICROS_PER_SECOND / pcm.sampleRate

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val frame = (timeUs * pcm.sampleRate / C.MICROS_PER_SECOND)
                .coerceIn(0, max(0, pcm.frames - 1))
            return SeekMap.SeekPoints(
                SeekPoint(frame * C.MICROS_PER_SECOND / pcm.sampleRate, dataStart + frame * pcm.bytesPerFrame),
            )
        }
    }

    private companion object {
        val FORM = fourCc("FORM")
        val AIFF = fourCc("AIFF")
        val AIFC = fourCc("AIFC")
        val COMM = fourCc("COMM")
        val SSND = fourCc("SSND")
        val NONE = fourCc("NONE")
        val TWOS = fourCc("twos")
        val SOWT = fourCc("sowt")

        fun fourCc(s: String): Int = s.fold(0) { acc, c -> (acc shl 8) or c.code }

        fun fourCc(value: Int): String =
            String(CharArray(4) { ((value shr (24 - 8 * it)) and 0xFF).toChar() })

        /** Environ 100 ms d'audio par paquet. */
        fun targetSampleBytes(pcm: PcmFormat): Int =
            max(1, pcm.sampleRate / 10) * pcm.bytesPerFrame

        /** Nombre à virgule flottante IEEE 754 étendu sur 80 bits (fréquence d'échantillonnage). */
        fun readExtended(data: ParsableByteArray): Double {
            val exponent = data.readUnsignedShort() and 0x7FFF
            val mantissa = data.readLong()
            if (exponent == 0 && mantissa == 0L) return 0.0
            return Math.scalb((mantissa ushr 11).toDouble(), exponent - 16383 - 52)
        }
    }
}
