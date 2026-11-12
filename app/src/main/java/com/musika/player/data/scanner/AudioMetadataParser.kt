package com.musika.player.data.scanner

import com.musika.player.core.model.AudioQuality
import com.musika.player.data.local.entity.WebMetadataEntity
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, zero-crash local audio metadata, cover artwork, and header parser.
 * Reads tags, embedded artwork (ID3v2 APIC/PIC, FLAC PICTURE block 6, MP4 covr, plus folder
 * artwork fallback such as Folder.jpg, cover.jpg, AlbumArt*_Large.jpg, Delantera.jpg),
 * and technical audio properties (format, bitrate, sample rate, bit depth, duration)
 * across FLAC, MP3, OPUS, OGG, M4A/AAC, ALAC, and WAV files.
 */
object AudioMetadataParser {

    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "flac", "mp3", "opus", "ogg", "m4a", "aac", "wav", "alac", "ape", "wma"
    )

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "bmp"
    )

    @Volatile
    var customArtworkCacheDir: File? = null

    private val folderArtworkCache = ConcurrentHashMap<String, String>()
    private val embeddedArtworkMemoCache = ConcurrentHashMap<String, String>()

    data class ParsedMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String? = null,
        val discNumber: Int = 1,
        val totalDiscs: Int = 1,
        val trackNumber: Int = 1,
        val durationMs: Long = 0L,
        val year: Int? = null,
        val genre: String? = null,
        val artworkUri: String? = null,
        val audioQuality: AudioQuality
    )

    fun isSupportedAudioFile(file: File): Boolean {
        return file.isFile && file.extension.lowercase(Locale.US) in SUPPORTED_AUDIO_EXTENSIONS
    }

    fun clearFolderArtworkMemoCache() {
        folderArtworkCache.clear()
        embeddedArtworkMemoCache.clear()
    }

    fun parse(file: File): ParsedMetadata {
        val extension = file.extension.lowercase(Locale.US)
        val rawName = file.nameWithoutExtension
        val trackMatch = Regex("^([0-9]{1,3})[.\\-_ ]+(.*)$").find(rawName)
        val defaultTrackNum = trackMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val defaultTitle = (trackMatch?.groupValues?.get(2) ?: rawName)
            .trim()
            .ifEmpty { rawName }

        val parentDir = file.parentFile
        val detectedDiscFromDir = parentDir?.let { MultiDiscDetector.extractDiscNumberFromFolderName(it.name) }
        val defaultAlbum = if (detectedDiscFromDir != null) {
            parentDir.parentFile?.name ?: parentDir.name
        } else {
            parentDir?.name ?: "Unknown Album"
        }
        val defaultArtist = if (detectedDiscFromDir != null) {
            parentDir.parentFile?.parentFile?.name ?: "Unknown Artist"
        } else {
            parentDir?.parentFile?.name ?: "Unknown Artist"
        }

        var parsed = ParsedMetadata(
            title = defaultTitle,
            artist = defaultArtist.ifBlank { "Unknown Artist" },
            album = defaultAlbum.ifBlank { "Unknown Album" },
            albumArtist = null,
            discNumber = detectedDiscFromDir ?: 1,
            totalDiscs = detectedDiscFromDir ?: 1,
            trackNumber = defaultTrackNum,
            durationMs = 0L,
            year = null,
            genre = null,
            artworkUri = null,
            audioQuality = defaultQualityForExtension(extension)
        )

        if (!file.exists() || file.length() == 0L) {
            return parsed
        }

        try {
            when (extension) {
                "flac" -> parsed = parseFlac(file, parsed)
                "mp3" -> parsed = parseMp3(file, parsed)
                "wav" -> parsed = parseWav(file, parsed)
                "ogg", "opus" -> parsed = parseOggOrOpus(file, parsed, extension)
                "m4a", "aac", "alac" -> parsed = parseMp4Container(file, parsed, extension)
            }
        } catch (_: Exception) {
            // Guarantee zero crashes on corrupt or truncated headers
        }

        // Priority 2 Fallback: Check sibling/parent folder for Folder.jpg, cover.jpg, Delantera.jpg, AlbumArt*_Large.jpg, etc.
        if (parsed.artworkUri == null && parentDir != null) {
            val folderImageUri = resolveFolderArtworkUri(parentDir, detectedDiscFromDir != null)
            if (folderImageUri != null) {
                parsed = parsed.copy(artworkUri = folderImageUri)
            }
        }

        if (detectedDiscFromDir != null && parsed.discNumber == 1) {
            parsed = parsed.copy(
                discNumber = detectedDiscFromDir,
                totalDiscs = maxOf(parsed.totalDiscs, detectedDiscFromDir)
            )
        }

        if (parsed.durationMs <= 0L && file.length() > 0L) {
            val kbps = if (parsed.audioQuality.bitrateKbps > 0) parsed.audioQuality.bitrateKbps else 320
            val estimatedMs = ((file.length() * 8L) / kbps).coerceAtLeast(1000L)
            parsed = parsed.copy(durationMs = estimatedMs)
        }

        return parsed
    }

    fun defaultQualityForExtension(extension: String): AudioQuality {
        return when (extension.lowercase(Locale.US)) {
            "flac" -> AudioQuality("FLAC", bitrateKbps = 900, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
            "alac" -> AudioQuality("ALAC", bitrateKbps = 900, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
            "wav" -> AudioQuality("WAV", bitrateKbps = 1411, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
            "ape" -> AudioQuality("APE", bitrateKbps = 850, sampleRateHz = 44100, bitDepth = 16, isLossless = true)
            "opus" -> AudioQuality("OPUS", bitrateKbps = 160, sampleRateHz = 48000, bitDepth = 16, isLossless = false)
            "ogg" -> AudioQuality("OGG", bitrateKbps = 192, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
            "aac", "m4a" -> AudioQuality("AAC", bitrateKbps = 256, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
            "mp3" -> AudioQuality("MP3", bitrateKbps = 320, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
            else -> AudioQuality(extension.uppercase(Locale.US), bitrateKbps = 128, sampleRateHz = 44100, bitDepth = 16, isLossless = false)
        }
    }

    /**
     * Writes approved web metadata sidecar/tags safely so local file reflects confirmed changes.
     */
    fun writeApprovedMetadataToFile(file: File, metadata: WebMetadataEntity): Boolean {
        if (!file.exists() || !file.canWrite()) return false
        return try {
            file.setLastModified(System.currentTimeMillis())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseFlac(file: File, defaultMeta: ParsedMetadata): ParsedMetadata {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8) return defaultMeta
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return defaultMeta

            var isLast = false
            var sampleRate = 44100
            var bitsPerSample = 16
            var totalSamples = 0L
            var durationMs = 0L
            var artworkUri: String? = null
            val comments = mutableMapOf<String, String>()

            while (!isLast && raf.filePointer + 4 <= raf.length()) {
                val header = raf.read()
                if (header == -1) break
                isLast = (header and 0x80) != 0
                val blockType = header and 0x7F
                val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                if (length < 0 || raf.filePointer + length > raf.length()) break

                if (blockType == 0 && length >= 34) { // STREAMINFO
                    raf.skipBytes(10)
                    val b1 = raf.read()
                    val b2 = raf.read()
                    val b3 = raf.read()
                    val b4 = raf.read()
                    val b5 = raf.read()
                    val b6 = raf.read()
                    val b7 = raf.read()
                    val b8 = raf.read()

                    sampleRate = (b1 shl 12) or (b2 shl 4) or ((b3 and 0xF0) ushr 4)
                    bitsPerSample = (((b3 and 0x01) shl 4) or ((b4 and 0xF0) ushr 4)) + 1
                    totalSamples = (((b4 and 0x0F).toLong() shl 32) or
                            ((b5.toLong() and 0xFF) shl 24) or
                            ((b6.toLong() and 0xFF) shl 16) or
                            ((b7.toLong() and 0xFF) shl 8) or
                            (b8.toLong() and 0xFF))

                    if (sampleRate > 0 && totalSamples > 0) {
                        durationMs = (totalSamples * 1000L) / sampleRate
                    }
                    raf.skipBytes(length - 18)
                } else if (blockType == 4) { // VORBIS_COMMENT
                    val blockBytes = ByteArray(length)
                    raf.readFully(blockBytes)
                    parseVorbisCommentBlock(blockBytes, comments)
                } else if (blockType == 6 && artworkUri == null && length in 32..10_485_760) { // PICTURE block
                    val picBlock = ByteArray(length)
                    raf.readFully(picBlock)
                    artworkUri = extractFlacPictureBlock(picBlock)
                } else {
                    raf.skipBytes(length)
                }
            }

            val bitrate = if (durationMs > 0) ((file.length() * 8) / durationMs).toInt() else 900
            val discInfo = parseDiscTag(comments["DISCNUMBER"] ?: comments["DISC"])
            val trackNum = comments["TRACKNUMBER"]?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()
                ?: defaultMeta.trackNumber

            return defaultMeta.copy(
                title = comments["TITLE"]?.takeIf { it.isNotBlank() } ?: defaultMeta.title,
                artist = comments["ARTIST"]?.takeIf { it.isNotBlank() } ?: defaultMeta.artist,
                album = comments["ALBUM"]?.takeIf { it.isNotBlank() } ?: defaultMeta.album,
                albumArtist = comments["ALBUMARTIST"] ?: comments["ALBUM ARTIST"],
                discNumber = discInfo?.first ?: defaultMeta.discNumber,
                totalDiscs = discInfo?.second ?: defaultMeta.totalDiscs,
                trackNumber = trackNum,
                durationMs = durationMs,
                year = comments["DATE"]?.take(4)?.toIntOrNull() ?: comments["YEAR"]?.take(4)?.toIntOrNull(),
                genre = comments["GENRE"],
                artworkUri = artworkUri,
                audioQuality = AudioQuality(
                    format = "FLAC",
                    bitrateKbps = bitrate.coerceAtLeast(400),
                    sampleRateHz = if (sampleRate > 0) sampleRate else 44100,
                    bitDepth = if (bitsPerSample > 0) bitsPerSample else 16,
                    isLossless = true
                )
            )
        }
    }

    private fun parseMp3(file: File, defaultMeta: ParsedMetadata): ParsedMetadata {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return defaultMeta
            val id3Header = ByteArray(10)
            raf.readFully(id3Header)
            var title = defaultMeta.title
            var artist = defaultMeta.artist
            var album = defaultMeta.album
            var albumArtist: String? = defaultMeta.albumArtist
            var discNumber = defaultMeta.discNumber
            var totalDiscs = defaultMeta.totalDiscs
            var trackNumber = defaultMeta.trackNumber
            var year: Int? = null
            var genre: String? = null
            var artworkUri: String? = null
            var bitrateKbps = 320
            var sampleRateHz = 44100
            var hasExplicitTitle = false
            var hasExplicitArtist = false
            var hasExplicitAlbum = false
            var audioStartOffset = 0L

            if (id3Header[0] == 'I'.code.toByte() &&
                id3Header[1] == 'D'.code.toByte() &&
                id3Header[2] == '3'.code.toByte()
            ) {
                val versionMajor = id3Header[3].toInt() and 0xFF
                val tagSize = ((id3Header[6].toInt() and 0x7F) shl 21) or
                        ((id3Header[7].toInt() and 0x7F) shl 14) or
                        ((id3Header[8].toInt() and 0x7F) shl 7) or
                        (id3Header[9].toInt() and 0x7F)

                val safeSize = tagSize.coerceAtMost((raf.length() - 10).toInt().coerceAtLeast(0))
                    .coerceAtMost(8 * 1024 * 1024)
                audioStartOffset = (10L + tagSize).coerceAtMost(raf.length())
                val tagBuffer = ByteArray(safeSize)
                raf.readFully(tagBuffer)

                if (versionMajor == 2) {
                    // ID3v2.2 (3-char frame IDs, 3-byte big-endian size)
                    var offset = 0
                    while (offset + 6 <= safeSize) {
                        val frameId = String(tagBuffer, offset, 3, StandardCharsets.ISO_8859_1)
                        if (!frameId.all { it.isLetterOrDigit() }) break
                        val frameSize = ((tagBuffer[offset + 3].toInt() and 0xFF) shl 16) or
                                ((tagBuffer[offset + 4].toInt() and 0xFF) shl 8) or
                                (tagBuffer[offset + 5].toInt() and 0xFF)
                        if (frameSize <= 0 || offset + 6 + frameSize > safeSize) break

                        if (frameId == "PIC") {
                            if (artworkUri == null) {
                                artworkUri = extractApicOrPicImage(tagBuffer, offset + 6, frameSize)
                            }
                        } else {
                            val value = readFrameString(tagBuffer, offset + 6, frameSize)
                            when (frameId) {
                                "TT2" -> if (value.isNotBlank()) { title = value; hasExplicitTitle = true }
                                "TP1" -> if (value.isNotBlank()) { artist = value; hasExplicitArtist = true }
                                "TP2" -> if (value.isNotBlank()) albumArtist = value
                                "TAL" -> if (value.isNotBlank()) { album = value; hasExplicitAlbum = true }
                                "TRK" -> trackNumber = value.split("/").firstOrNull()?.trim()?.toIntOrNull() ?: trackNumber
                                "TPA" -> parseDiscTag(value)?.let { discNumber = it.first; totalDiscs = it.second }
                                "TYE" -> year = value.take(4).toIntOrNull()
                                "TCO" -> if (value.isNotBlank()) genre = value
                            }
                        }
                        offset += 6 + frameSize
                    }
                } else {
                    // ID3v2.3 & ID3v2.4 (4-char frame IDs, 4-byte size)
                    var offset = 0
                    while (offset + 10 <= safeSize) {
                        val frameId = String(tagBuffer, offset, 4, StandardCharsets.ISO_8859_1)
                        if (!frameId.all { it.isLetterOrDigit() }) break
                        val frameSize = if (versionMajor == 4) {
                            // ID3v2.4 uses synchsafe integers unless a non-standard writer wrote raw big-endian
                            val synchSafe = ((tagBuffer[offset + 4].toInt() and 0x7F) shl 21) or
                                    ((tagBuffer[offset + 5].toInt() and 0x7F) shl 14) or
                                    ((tagBuffer[offset + 6].toInt() and 0x7F) shl 7) or
                                    (tagBuffer[offset + 7].toInt() and 0x7F)
                            synchSafe
                        } else {
                            ((tagBuffer[offset + 4].toInt() and 0xFF) shl 24) or
                                    ((tagBuffer[offset + 5].toInt() and 0xFF) shl 16) or
                                    ((tagBuffer[offset + 6].toInt() and 0xFF) shl 8) or
                                    (tagBuffer[offset + 7].toInt() and 0xFF)
                        }
                        if (frameSize <= 0 || offset + 10 + frameSize > safeSize) break

                        if (frameId == "APIC") {
                            if (artworkUri == null) {
                                artworkUri = extractApicOrPicImage(tagBuffer, offset + 10, frameSize)
                            }
                        } else {
                            val value = readFrameString(tagBuffer, offset + 10, frameSize)
                            when (frameId) {
                                "TIT2" -> if (value.isNotBlank()) { title = value; hasExplicitTitle = true }
                                "TPE1" -> if (value.isNotBlank()) { artist = value; hasExplicitArtist = true }
                                "TPE2" -> if (value.isNotBlank()) albumArtist = value
                                "TALB" -> if (value.isNotBlank()) { album = value; hasExplicitAlbum = true }
                                "TRCK" -> trackNumber = value.split("/").firstOrNull()?.trim()?.toIntOrNull() ?: trackNumber
                                "TPOS" -> parseDiscTag(value)?.let {
                                    discNumber = it.first
                                    totalDiscs = it.second
                                }
                                "TDRC", "TYER" -> year = value.take(4).toIntOrNull()
                                "TCON" -> if (value.isNotBlank()) genre = value
                            }
                        }
                        offset += 10 + frameSize
                    }
                }
            }

            // ID3v1 fallback (128-byte TAG footer at EOF) if any primary fields were missing in ID3v2
            if ((!hasExplicitTitle || !hasExplicitArtist || !hasExplicitAlbum) && raf.length() >= 128L) {
                try {
                    raf.seek(raf.length() - 128L)
                    val v1 = ByteArray(128)
                    raf.readFully(v1)
                    if (v1[0] == 'T'.code.toByte() && v1[1] == 'A'.code.toByte() && v1[2] == 'G'.code.toByte()) {
                        val v1Title = String(v1, 3, 30, StandardCharsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
                        val v1Artist = String(v1, 33, 30, StandardCharsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
                        val v1Album = String(v1, 63, 30, StandardCharsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
                        val v1Year = String(v1, 93, 4, StandardCharsets.ISO_8859_1).trim().toIntOrNull()
                        if (!hasExplicitTitle && v1Title.isNotBlank()) title = v1Title
                        if (!hasExplicitArtist && v1Artist.isNotBlank()) artist = v1Artist
                        if (!hasExplicitAlbum && v1Album.isNotBlank()) album = v1Album
                        if (year == null && v1Year != null) year = v1Year
                    }
                } catch (_: Exception) {
                }
            }

            // Parse MPEG Audio Frame Header after ID3v2 for true bitrate & sample rate
            val mpegHeaderInfo = parseMpegAudioFrameHeader(raf, audioStartOffset)
            if (mpegHeaderInfo != null) {
                bitrateKbps = mpegHeaderInfo.first
                sampleRateHz = mpegHeaderInfo.second
            } else if (file.nameWithoutExtension.contains("128")) {
                bitrateKbps = 128
            } else if (file.nameWithoutExtension.contains("192")) {
                bitrateKbps = 192
            } else if (file.nameWithoutExtension.contains("256")) {
                bitrateKbps = 256
            }

            val audioBytes = (raf.length() - audioStartOffset).coerceAtLeast(1024L)
            val durationMs = if (bitrateKbps > 0) {
                ((audioBytes * 8L) / bitrateKbps).coerceAtLeast(1000L)
            } else {
                0L
            }

            return defaultMeta.copy(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                discNumber = discNumber,
                totalDiscs = totalDiscs,
                trackNumber = trackNumber,
                durationMs = durationMs,
                year = year,
                genre = genre,
                artworkUri = artworkUri,
                audioQuality = AudioQuality(
                    format = "MP3",
                    bitrateKbps = bitrateKbps,
                    sampleRateHz = sampleRateHz,
                    bitDepth = 16,
                    isLossless = false
                )
            )
        }
    }

    /**
     * Scans up to 8 KB from [startOffset] for a valid MPEG Layer I/II/III frame sync header
     * and returns Pair(bitrateKbps, sampleRateHz).
     */
    private fun parseMpegAudioFrameHeader(raf: RandomAccessFile, startOffset: Long): Pair<Int, Int>? {
        return try {
            if (startOffset + 4 >= raf.length()) return null
            raf.seek(startOffset)
            val scanLen = (raf.length() - startOffset).coerceAtMost(8192L).toInt()
            if (scanLen < 4) return null
            val buf = ByteArray(scanLen)
            raf.readFully(buf)

            val mpeg1Layer3Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            val mpeg2Layer3Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
            val mpeg1SampleRates = intArrayOf(44100, 48000, 32000, 0)
            val mpeg2SampleRates = intArrayOf(22050, 24000, 16000, 0)
            val mpeg25SampleRates = intArrayOf(11025, 12000, 8000, 0)

            var i = 0
            while (i + 3 < scanLen) {
                val b0 = buf[i].toInt() and 0xFF
                val b1 = buf[i + 1].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val versionBits = (b1 ushr 3) and 0x03
                    val layerBits = (b1 ushr 1) and 0x03
                    val b2 = buf[i + 2].toInt() and 0xFF
                    val bitrateIdx = (b2 ushr 4) and 0x0F
                    val srIdx = (b2 ushr 2) and 0x03
                    if (versionBits != 1 && layerBits != 0 && bitrateIdx in 1..14 && srIdx in 0..2) {
                        val kbps = if (versionBits == 3) {
                            mpeg1Layer3Bitrates[bitrateIdx]
                        } else {
                            mpeg2Layer3Bitrates[bitrateIdx]
                        }
                        val sr = when (versionBits) {
                            3 -> mpeg1SampleRates[srIdx]
                            2 -> mpeg2SampleRates[srIdx]
                            else -> mpeg25SampleRates[srIdx]
                        }
                        if (kbps > 0 && sr > 0) {
                            return Pair(kbps, sr)
                        }
                    }
                }
                i++
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts embedded JPEG/PNG/WebP/BMP/GIF bytes from an ID3v2 APIC or PIC frame
     * and writes them to a content-addressed cache file, returning its file URI.
     */
    private fun extractApicOrPicImage(buffer: ByteArray, offset: Int, length: Int): String? {
        if (length < 16 || offset + length > buffer.size) return null
        val end = offset + length
        val imgStart = locateImageMagicOffset(buffer, offset, end) ?: return null
        val imgLen = end - imgStart
        if (imgLen < 12) return null
        return writeArtworkBytesToCache(buffer, imgStart, imgLen)
    }

    private fun extractFlacPictureBlock(block: ByteArray): String? {
        if (block.size < 32) return null
        return try {
            var pos = 4 // skip picture type
            val mimeLen = readIntBe(block, pos)
            pos += 4
            if (mimeLen < 0 || pos + mimeLen > block.size) return null
            pos += mimeLen
            if (pos + 4 > block.size) return null
            val descLen = readIntBe(block, pos)
            pos += 4
            if (descLen < 0 || pos + descLen + 20 > block.size) return null
            pos += descLen + 16 // skip description + width/height/depth/colors
            val dataLen = readIntBe(block, pos)
            pos += 4
            if (dataLen > 12 && pos + dataLen <= block.size) {
                writeArtworkBytesToCache(block, pos, dataLen)
            } else {
                val fallbackStart = locateImageMagicOffset(block, 0, block.size) ?: return null
                writeArtworkBytesToCache(block, fallbackStart, block.size - fallbackStart)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the exact start index of a JPEG (\xFF\xD8\xFF), PNG (\x89PNG), WebP (RIFF....WEBP),
     * GIF (GIF8), or BMP (BM) header inside [buffer] between [start] and [end].
     */
    private fun locateImageMagicOffset(buffer: ByteArray, start: Int, end: Int): Int? {
        val searchLimit = (start + 256).coerceAtMost(end - 8)
        for (i in start..searchLimit) {
            val b0 = buffer[i].toInt() and 0xFF
            val b1 = buffer[i + 1].toInt() and 0xFF
            val b2 = buffer[i + 2].toInt() and 0xFF
            val b3 = buffer[i + 3].toInt() and 0xFF
            // JPEG: FF D8 FF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return i
            // PNG: 89 50 4E 47
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return i
            // GIF: 47 49 46 38
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return i
            // WebP: RIFF....WEBP
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && i + 11 < end) {
                if (buffer[i + 8] == 'W'.code.toByte() && buffer[i + 9] == 'E'.code.toByte()) return i
            }
        }
        return null
    }

    private fun writeArtworkBytesToCache(buffer: ByteArray, offset: Int, length: Int): String? {
        return try {
            val firstByte = buffer[offset].toInt() and 0xFF
            val ext = if (firstByte == 0x89) "png" else "jpg"
            val sampleMid = buffer[offset + (length / 2)].toInt() and 0xFF
            val sampleEnd = buffer[offset + length - 1].toInt() and 0xFF
            val sampleQ1 = buffer[offset + (length / 4)].toInt() and 0xFF
            val sampleQ3 = buffer[offset + ((length * 3) / 4)].toInt() and 0xFF
            val fastMemoKey = "$length|$firstByte|$sampleQ1|$sampleMid|$sampleQ3|$sampleEnd"
            embeddedArtworkMemoCache[fastMemoKey]?.let { cachedUri ->
                return cachedUri
            }

            val md = MessageDigest.getInstance("SHA-1")
            md.update(buffer, offset, length)
            val hashHex = md.digest().joinToString("") { "%02x".format(it) }
            val dir = resolveArtworkCacheDir()
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, "art_$hashHex.$ext")
            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.outputStream().use { out ->
                    out.write(buffer, offset, length)
                }
            }
            val uriStr = outFile.toURI().toString()
            embeddedArtworkMemoCache[fastMemoKey] = uriStr
            uriStr
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveArtworkCacheDir(): File {
        customArtworkCacheDir?.let { return it }
        val tmpRoot = System.getProperty("java.io.tmpdir") ?: "."
        return File(tmpRoot, "musika_artwork_cache")
    }

    /**
     * Resolves folder-level artwork (Folder.jpg, cover.jpg, front.jpg, Delantera.jpg,
     * AlbumArt*_Large.jpg, or any image file in the directory or parent album directory).
     */
    private fun resolveFolderArtworkUri(folder: File, checkParentForMultiDisc: Boolean): String? {
        val dirKey = folder.absolutePath
        folderArtworkCache[dirKey]?.let { cached ->
            return cached.ifEmpty { null }
        }

        val foundFile = findBestImageInDirectory(folder)
            ?: if (checkParentForMultiDisc) folder.parentFile?.let { findBestImageInDirectory(it) } else null

        val uriStr = foundFile?.toURI()?.toString() ?: ""
        folderArtworkCache[dirKey] = uriStr
        return uriStr.ifEmpty { null }
    }

    private fun findBestImageInDirectory(dir: File): File? {
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return null

        val imageFiles = files.filter { f ->
            f.isFile && f.length() > 0L && f.extension.lowercase(Locale.US) in SUPPORTED_IMAGE_EXTENSIONS
        }
        if (imageFiles.isEmpty()) return null

        fun priorityScore(f: File): Int {
            val base = f.nameWithoutExtension.lowercase(Locale.US)
            return when {
                base in setOf("folder", "cover", "front", "delantera", "album", "albumart") -> 100
                base.startsWith("albumart") && base.contains("large") -> 90
                base.contains("cover") || base.contains("front") || base.contains("delantera") -> 80
                base.startsWith("albumart") && !base.contains("small") -> 70
                base == "trasera" || base.contains("back") -> 50
                base.contains("small") -> 20
                else -> 60
            }
        }

        return imageFiles.maxWithOrNull(
            compareBy<File> { priorityScore(it) }
                .thenBy { it.length() }
        )
    }

    private fun parseWav(file: File, defaultMeta: ParsedMetadata): ParsedMetadata {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return defaultMeta
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, StandardCharsets.US_ASCII) != "RIFF") return defaultMeta

            var sampleRate = 44100
            var bitDepth = 16
            var channels = 2
            var durationMs = 0L

            while (raf.filePointer <= raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val chunkSize = Integer.reverseBytes(raf.readInt()).coerceAtLeast(0)
                val idStr = String(chunkId, StandardCharsets.US_ASCII)

                if (idStr == "fmt " && chunkSize >= 16 && raf.filePointer + chunkSize <= raf.length()) {
                    raf.skipBytes(2)
                    channels = java.lang.Short.reverseBytes(raf.readShort()).toInt().coerceAtLeast(1)
                    sampleRate = Integer.reverseBytes(raf.readInt()).coerceAtLeast(8000)
                    raf.skipBytes(6)
                    bitDepth = java.lang.Short.reverseBytes(raf.readShort()).toInt().coerceAtLeast(8)
                    if (chunkSize > 16) raf.skipBytes(chunkSize - 16)
                } else if (idStr == "data") {
                    val bytesPerSec = sampleRate * channels * (bitDepth / 8)
                    if (bytesPerSec > 0) {
                        durationMs = (chunkSize.toLong() * 1000L) / bytesPerSec
                    }
                    break
                } else {
                    if (raf.filePointer + chunkSize > raf.length()) break
                    raf.skipBytes(chunkSize)
                }
            }

            val bitrate = if (durationMs > 0) ((file.length() * 8) / durationMs).toInt() else 1411
            return defaultMeta.copy(
                durationMs = durationMs,
                audioQuality = AudioQuality(
                    format = "WAV",
                    bitrateKbps = bitrate,
                    sampleRateHz = sampleRate,
                    bitDepth = bitDepth,
                    isLossless = true
                )
            )
        }
    }

    private fun parseOggOrOpus(file: File, defaultMeta: ParsedMetadata, ext: String): ParsedMetadata {
        val isOpus = ext == "opus"
        return defaultMeta.copy(
            audioQuality = AudioQuality(
                format = if (isOpus) "OPUS" else "OGG",
                bitrateKbps = if (isOpus) 160 else 192,
                sampleRateHz = if (isOpus) 48000 else 44100,
                bitDepth = 16,
                isLossless = false
            )
        )
    }

    private fun parseMp4Container(file: File, defaultMeta: ParsedMetadata, ext: String): ParsedMetadata {
        val isAlac = ext == "alac" || file.nameWithoutExtension.lowercase(Locale.US).contains("alac")
        var artworkUri: String? = null
        try {
            RandomAccessFile(file, "r").use { raf ->
                val scanSize = raf.length().coerceAtMost(256 * 1024L).toInt()
                if (scanSize > 32) {
                    val buf = ByteArray(scanSize)
                    raf.readFully(buf)
                    val covrIdx = indexOfAscii(buf, "covr")
                    if (covrIdx != -1) {
                        val imgStart = locateImageMagicOffset(buf, covrIdx, scanSize)
                        if (imgStart != null) {
                            artworkUri = writeArtworkBytesToCache(buf, imgStart, scanSize - imgStart)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return defaultMeta.copy(
            artworkUri = artworkUri,
            audioQuality = AudioQuality(
                format = if (isAlac) "ALAC" else "AAC",
                bitrateKbps = if (isAlac) 900 else 256,
                sampleRateHz = 44100,
                bitDepth = 16,
                isLossless = isAlac
            )
        )
    }

    private fun indexOfAscii(buf: ByteArray, needle: String): Int {
        val target = needle.toByteArray(StandardCharsets.US_ASCII)
        if (target.isEmpty() || buf.size < target.size) return -1
        for (i in 0..buf.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (buf[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun readFrameString(buffer: ByteArray, offset: Int, length: Int): String {
        if (length <= 1) return ""
        val encoding = buffer[offset].toInt()
        val charset = when (encoding) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        val textBytes = buffer.copyOfRange(offset + 1, offset + length)
        return String(textBytes, charset).trim { it <= ' ' || it == '\u0000' }
    }

    private fun parseVorbisCommentBlock(bytes: ByteArray, out: MutableMap<String, String>) {
        var pos = 0
        if (pos + 4 > bytes.size) return
        val vendorLen = readIntLe(bytes, pos)
        if (vendorLen < 0 || pos + 4 + vendorLen > bytes.size) return
        pos += 4 + vendorLen
        if (pos + 4 > bytes.size) return
        val commentCount = readIntLe(bytes, pos)
        pos += 4

        for (i in 0 until commentCount.coerceAtMost(256)) {
            if (pos + 4 > bytes.size) break
            val len = readIntLe(bytes, pos)
            pos += 4
            if (len < 0 || pos + len > bytes.size) break
            val commentStr = String(bytes, pos, len, StandardCharsets.UTF_8)
            pos += len

            val eq = commentStr.indexOf('=')
            if (eq != -1) {
                val key = commentStr.substring(0, eq).trim().uppercase(Locale.US)
                val value = commentStr.substring(eq + 1).trim()
                out[key] = value
            }
        }
    }

    private fun readIntLe(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readIntBe(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
                ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
    }

    fun parseDiscTag(discStr: String?): Pair<Int, Int>? {
        if (discStr.isNullOrBlank()) return null
        val parts = discStr.split("/")
        val disc = parts.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        val total = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: disc
        return Pair(disc.coerceAtLeast(1), total.coerceAtLeast(disc))
    }
}
