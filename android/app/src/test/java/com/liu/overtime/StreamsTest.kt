package com.liu.overtime

import com.liu.overtime.data.readLimited
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class StreamsTest {
    @Test fun acceptsExactlyTheLimitAndRejectsOneByteMore() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readLimited(3))
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readLimited(0))
        assertTrue(runCatching { ByteArrayInputStream(byteArrayOf(1)).readLimited(0) }.isFailure)
        assertTrue(runCatching { ByteArrayInputStream(ByteArray(8193)).readLimited(8192) }.isFailure)
    }

    @Test fun handlesAnInputProviderThatReturnsZeroWithoutSpinning() {
        val input = object : InputStream() {
            private var remaining = 3
            override fun read(b: ByteArray, off: Int, len: Int) = if (remaining > 0) 0 else -1
            override fun read() = if (remaining-- > 0) 7 else -1
        }
        assertArrayEquals(byteArrayOf(7, 7, 7), input.readLimited(3))
    }
}
