package com.liu.overtime.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Unlike InputStream.readNBytes this works on Android 8–12 without desugaring. */
fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "文件过大，最大允许 ${limit / 1024} KB" }
    }
    return output.toByteArray()
}
