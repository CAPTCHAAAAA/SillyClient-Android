package com.sillyclient.render

internal object ChameleonPixelSampler {
    fun representativeColor(pixels: IntArray): Int? {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        var count = 0

        for (pixel in pixels) {
            if ((pixel ushr 24 and 0xFF) <= 200) continue
            red[pixel ushr 16 and 0xFF]++
            green[pixel ushr 8 and 0xFF]++
            blue[pixel and 0xFF]++
            count++
        }
        if (count == 0) return null

        val medianRank = (count - 1) / 2
        val medianRed = median(red, medianRank)
        val medianGreen = median(green, medianRank)
        val medianBlue = median(blue, medianRank)

        var bestPixel = 0
        var bestDistance = Long.MAX_VALUE
        for (pixel in pixels) {
            if ((pixel ushr 24 and 0xFF) <= 200) continue
            val deltaRed = (pixel ushr 16 and 0xFF) - medianRed
            val deltaGreen = (pixel ushr 8 and 0xFF) - medianGreen
            val deltaBlue = (pixel and 0xFF) - medianBlue
            val distance = deltaRed.toLong() * deltaRed +
                deltaGreen.toLong() * deltaGreen +
                deltaBlue.toLong() * deltaBlue
            if (distance < bestDistance) {
                bestDistance = distance
                bestPixel = pixel
            }
        }
        return (bestPixel and 0x00FFFFFF) or (0xFF shl 24)
    }

    private fun median(histogram: IntArray, rank: Int): Int {
        var remaining = rank
        for (value in histogram.indices) {
            remaining -= histogram[value]
            if (remaining < 0) return value
        }
        return histogram.lastIndex
    }
}
