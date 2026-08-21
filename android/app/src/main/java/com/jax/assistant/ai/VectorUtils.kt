package com.jax.assistant.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Float-vector (de)serialization for BLOB storage + cosine similarity for semantic search.
object VectorUtils {

    fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun toFloats(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
