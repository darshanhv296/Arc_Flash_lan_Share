package com.example.filesharing

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

class ProgressRequestBody(
    private val inputStream: InputStream,
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val checkPause: () -> Boolean,
    private val checkCancelled: () -> Boolean,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {

    override fun contentType() = contentType
    override fun contentLength() = contentLength

    override fun writeTo(sink: BufferedSink) {
        val bufferSize = 256 * 1024 // 256KB buffer as requested
        val buffer = ByteArray(bufferSize)
        var totalBytesRead: Long = 0

        inputStream.use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (checkCancelled()) break
                
                while (checkPause() && !checkCancelled()) {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                
                if (checkCancelled()) break

                sink.write(buffer, 0, read)
                totalBytesRead += read
                onProgress(totalBytesRead, contentLength)
            }
        }
    }
}
