package com.pixel.gallery.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class MediaFileFingerprint(val bytes: Long, val modifiedMillis: Long, val sha256: String)

fun mediaFileSha256(file: File): String {
    return FileInputStream(file).use(::mediaStreamSha256)
}

fun mediaStreamSha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun fingerprintMediaFile(file: File): MediaFileFingerprint {
    if (!file.isFile) throw IOException("Source file is unavailable")
    val size = file.length()
    val modified = file.lastModified()
    val hash = mediaFileSha256(file)
    if (file.length() != size || file.lastModified() != modified) throw IOException("Source changed during verification; source was kept")
    return MediaFileFingerprint(size, modified, hash)
}

fun verifyMediaFile(file: File, fingerprint: MediaFileFingerprint, verifyTime: Boolean = false) {
    if (!file.isFile || file.length() != fingerprint.bytes || mediaFileSha256(file) != fingerprint.sha256) {
        throw IOException("File content verification failed; no source deletion was authorized")
    }
    if (verifyTime && file.lastModified() != fingerprint.modifiedMillis) throw IOException("Modification time could not be preserved")
}

fun restoreMediaModifiedTime(file: File, modifiedMillis: Long) {
    if (modifiedMillis <= 0L) throw IOException("Source modification time is unavailable")
    if (file.lastModified() != modifiedMillis && !file.setLastModified(modifiedMillis)) {
        throw IOException("Destination cannot preserve the original modification time")
    }
    if (file.lastModified() != modifiedMillis) throw IOException("Destination modification time verification failed")
}

/** The caller owns an exclusively created target; this never decides an overwrite policy. */
fun copyVerifiedMediaBytes(source: File, ownedTarget: File, fingerprint: MediaFileFingerprint) {
    FileInputStream(source).use { input ->
        FileOutputStream(ownedTarget, false).use { output ->
            input.copyTo(output, 128 * 1024)
            output.flush()
            output.fd.sync()
        }
    }
    verifyMediaFile(source, fingerprint, verifyTime = true)
    verifyMediaFile(ownedTarget, fingerprint)
    restoreMediaModifiedTime(ownedTarget, fingerprint.modifiedMillis)
}
