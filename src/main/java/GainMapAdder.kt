package com.rdelbene.nivelhdr

import kotlin.math.roundToLong

/*
 * Agrega Ultra HDR a una foto que no lo tiene (las del x3, x0.5 y la frontal de la stock), sin recomprimir
 * la imagen: se le suma un gain map al archivo, con el mismo formato que usa Motorola en la x1.
 *
 * El gain map de la stock resultó ser casi una curva fija del brillo de cada zona (correlación 0.98 en las
 * fotos medidas), así que se calcula con esa misma curva a 1/4 de resolución.
 *
 * Estructura del archivo (copiada de las fotos x1 de la stock / libultrahdr):
 *  imagen principal: SOI, APPn originales, APP1 XMP (hdrgm:Version + Container:Directory),
 *                    APP2 ISO 21496-1 (solo versión), APP2 MPF (índice de las 2 imágenes), resto de la JPEG
 *  gain map:         SOI, APP1 XMP (hdrgm: máximo, gamma...), APP2 ISO 21496-1 (metadatos), JPEG chica en grises
 */
object GainMapAdder {

    private val STOCK_GAIN_X = floatArrayOf(0f, 24f, 40f, 56f, 72f, 88f, 104f, 120f, 136f, 152f, 168f, 184f, 200f, 216f, 232f, 255f)
    private val STOCK_GAIN_Y = floatArrayOf(0f, 0f, 3f, 56f, 82f, 96f, 105f, 111f, 121f, 138f, 155f, 185f, 210f, 241f, 255f, 255f)

    fun stockGainCurve(luma: Float): Float {
        if (luma <= STOCK_GAIN_X[0]) return STOCK_GAIN_Y[0]
        for (k in 1 until STOCK_GAIN_X.size) {
            if (luma <= STOCK_GAIN_X[k]) {
                val t = (luma - STOCK_GAIN_X[k - 1]) / (STOCK_GAIN_X[k] - STOCK_GAIN_X[k - 1])
                return STOCK_GAIN_Y[k - 1] + t * (STOCK_GAIN_Y[k] - STOCK_GAIN_Y[k - 1])
            }
        }
        return 255f
    }

    /** Gain map (0..255) desde la imagen ya reducida a 1/4 (ARGB), suavizado 3x3 como en la stock. */
    fun gainMapFromSmall(px: IntArray, w: Int, h: Int): ByteArray {
        val lut = FloatArray(256) { stockGainCurve(it.toFloat()) }
        val g = FloatArray(w * h) { i ->
            val p = px[i]
            lut[((77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8).coerceIn(0, 255)]
        }
        fun at(x: Int, y: Int) = g[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        return ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            var s = 4f * at(x, y)
            s += 2f * (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1))
            s += at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)
            (s / 16f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }

    private val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ISO_TAG = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.ISO_8859_1)
    private val MPF_TAG = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    /** Bloque ISO 21496-1 completo de las fotos de la stock (un canal); se cambian headroom y máximo. */
    private val ISO_TEMPLATE = hex(
        "00000000400000000010000000000024cf000010000000000010000000000024cf00001000100000001000000000000000100000000000000010000000"
    )

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        require(len <= 0xffff)
        return byteArrayOf(0xff.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun startsWith(b: ByteArray, at: Int, p: ByteArray): Boolean {
        if (at + p.size > b.size) return false
        for (i in p.indices) if (b[at + i] != p[i]) return false
        return true
    }

    private fun contains(b: ByteArray, from: Int, to: Int, p: ByteArray): Boolean {
        var i = from
        while (i + p.size <= to) { if (startsWith(b, i, p)) return true; i++ }
        return false
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.7f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun gainXmp(maxStops: Double): ByteArray {
        val m = fmt(maxStops)
        val cap = fmt(maxStops.coerceAtLeast(0.01))
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 5.5.0">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        hdrgm:Version="1.0"
        hdrgm:GainMapMin="0"
        hdrgm:GainMapMax="$m"
        hdrgm:Gamma="1"
        hdrgm:OffsetSDR="0"
        hdrgm:OffsetHDR="0"
        hdrgm:HDRCapacityMin="0"
        hdrgm:HDRCapacityMax="$cap"
        hdrgm:BaseRenditionIsHDR="False">
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
        return XMP_NS + x.toByteArray(Charsets.UTF_8)
    }

    private fun primaryXmp(gainLen: Int): ByteArray {
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        hdrgm:Version="1.0">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
             Item:Semantic="Primary"
             Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
             Item:Semantic="GainMap"
             Item:Mime="image/jpeg"
             Item:Length="$gainLen"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
        return XMP_NS + x.toByteArray(Charsets.UTF_8)
    }

    private fun isoFull(maxStops: Double): ByteArray {
        val b = ISO_TEMPLATE.copyOf()
        fun put(p: Int, v: Long) { b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte() }
        put(13, (maxStops.coerceAtLeast(0.01) * 4096).roundToLong()) // headroom alternativo (den 4096 en d+17)
        put(29, (maxStops * 4096).roundToLong())                   // gain_map_max (den 4096 en d+33)
        return ISO_TAG + b
    }

    /** MPF con 2 imágenes, igual al de la stock; tamaños y posición del gain map se completan después. */
    private fun mpf(primaryLen: Int, gainLen: Int, gainOffset: Int): ByteArray {
        val b = hex(
            "4d4d002a000000080003b00000070000000430313030b00100040000000100000002b00200070000002000000032000000000003000000" +
                "000000000000000000000000000000000000000000000000000000"
        )
        fun put(p: Int, v: Long) { b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte() }
        // entrada 1 (principal) en 50: atributo 0x00030000, tamaño, offset 0; entrada 2 en 66: atributo 0, tamaño, offset
        put(54, primaryLen.toLong()); put(58, 0)
        put(66, 0); put(70, gainLen.toLong()); put(74, gainOffset.toLong())
        return MPF_TAG + b
    }

    /**
     * Devuelve el archivo con Ultra HDR, o null si no conviene tocarlo (ya tiene gain map, es foto en movimiento,
     * o ya tiene varias imágenes adentro). [gainJpeg] es la JPEG chica del gain map (grises).
     */
    fun build(primary: ByteArray, gainJpeg: ByteArray, maxStops: Double): ByteArray? {
        if (primary.size < 4 || (primary[0].toInt() and 255) != 0xff || (primary[1].toInt() and 255) != 0xd8) return null
        // segmentos APPn del principio
        var i = 2
        var dropFrom = -1; var dropTo = -1
        while (i + 4 <= primary.size && (primary[i].toInt() and 255) == 0xff) {
            val m = primary[i + 1].toInt() and 255
            if (m !in 0xe0..0xef) break
            val len = ((primary[i + 2].toInt() and 255) shl 8) or (primary[i + 3].toInt() and 255)
            val end = i + 2 + len
            if (end > primary.size) return null
            if (m == 0xe1 && startsWith(primary, i + 4, XMP_NS)) {
                // XMP propio: si ya es Ultra HDR o foto en movimiento, no se toca; si no, se reemplaza por el nuestro
                if (contains(primary, i, end, "hdrgm".toByteArray()) || contains(primary, i, end, "Container:Directory".toByteArray()) ||
                    contains(primary, i, end, "MotionPhoto".toByteArray())) return null
                dropFrom = i; dropTo = end
            }
            if (m == 0xe2 && (startsWith(primary, i + 4, MPF_TAG) || startsWith(primary, i + 4, ISO_TAG))) return null
            i = end
        }
        val insertAt = i
        val maxS = maxStops.coerceAtLeast(0.0)

        // gain map: SOI + XMP + ISO + resto de la JPEG chica
        if ((gainJpeg[0].toInt() and 255) != 0xff || (gainJpeg[1].toInt() and 255) != 0xd8) return null
        val gain = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + segment(0xe1, gainXmp(maxS)) + segment(0xe2, isoFull(maxS)) +
            gainJpeg.copyOfRange(2, gainJpeg.size)

        val head = if (dropFrom >= 0) primary.copyOfRange(0, dropFrom) + primary.copyOfRange(dropTo, insertAt) else primary.copyOfRange(0, insertAt)
        val tail = primary.copyOfRange(insertAt, primary.size)
        val xmpSeg = segment(0xe1, primaryXmp(gain.size))
        val isoSeg = segment(0xe2, ISO_TAG + byteArrayOf(0, 0, 0, 0))
        val mpfLen = segment(0xe2, mpf(0, 0, 0)).size
        val primaryLen = head.size + xmpSeg.size + isoSeg.size + mpfLen + tail.size
        val tiffStart = head.size + xmpSeg.size + isoSeg.size + 8 // FF E2 LL LL "MPF\0"
        val mpfSeg = segment(0xe2, mpf(primaryLen, gain.size, primaryLen - tiffStart))
        return head + xmpSeg + isoSeg + mpfSeg + tail + gain
    }
}
