package com.nowly.app.updates

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The only network access in the app: reading the release manifest and fetching the release APK. */
interface UpdateTransport {
    /** Reads a small UTF-8 document, failing when it is larger than [limit] bytes. */
    fun text(url: String, limit: Int): String

    /** Streams the body into [target], failing when it is larger than [limit] bytes. */
    fun download(url: String, target: File, limit: Long, onBytes: (Long) -> Unit)
}

class HttpUpdateTransport : UpdateTransport {
    override fun text(url: String, limit: Int): String = open(url) { input ->
        val bytes = input.readNBytesCompat(limit + 1)
        if (bytes.size > limit) throw IOException("Response is too large")
        bytes.toString(Charsets.UTF_8)
    }

    override fun download(url: String, target: File, limit: Long, onBytes: (Long) -> Unit) = open(url) { input ->
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                if (received > limit) throw IOException("Download is too large")
                output.write(buffer, 0, count)
                onBytes(received)
            }
        }
    }

    private fun <T> open(url: String, read: (InputStream) -> T): T {
        // HTTPS only; the platform does not follow a redirect that leaves HTTPS.
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use(read)
        } finally { connection.disconnect() }
    }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }
}
