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

    // estilo "solo las luces": la foto queda como una normal y brillan cielo, lámparas y reflejos
    // versión 2: arranca en luma 110 y llega casi al máximo en 200 (la v1 empezaba en 150 y apenas se notaba)
    private val LIGHTS_X = floatArrayOf(0f, 110f, 140f, 170f, 200f, 225f, 255f)
    private val LIGHTS_Y = floatArrayOf(0f, 0f, 45f, 130f, 215f, 250f, 255f)
    const val CURVE_VERSION = 2

    private fun curve(xs: FloatArray, ys: FloatArray, luma: Float): Float {
        if (luma <= xs[0]) return ys[0]
        for (k in 1 until xs.size) {
            if (luma <= xs[k]) {
                val t = (luma - xs[k - 1]) / (xs[k] - xs[k - 1])
                return ys[k - 1] + t * (ys[k] - ys[k - 1])
            }
        }
        return 255f
    }

    fun stockGainCurve(luma: Float): Float = curve(STOCK_GAIN_X, STOCK_GAIN_Y, luma)
    fun lightsGainCurve(luma: Float): Float = curve(LIGHTS_X, LIGHTS_Y, luma)

    /** Gain map (0..255) desde la imagen ya reducida a 1/4 (ARGB), suavizado 3x3 como en la stock. */
    fun gainMapFromSmall(px: IntArray, w: Int, h: Int, lightsOnly: Boolean = false): ByteArray {
        val lut = FloatArray(256) { if (lightsOnly) lightsGainCurve(it.toFloat()) else stockGainCurve(it.toFloat()) }
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

    /** Marca propia en el XMP principal: si el Ultra HDR lo agregó esta app y con qué estilo. */
    const val NS = "http://rdelbene.com/nivelhdr/1.0/"

    private fun primaryXmp(gainLen: Int, added: Boolean, style: String): ByteArray {
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        xmlns:nivelhdr="$NS"
        hdrgm:Version="1.0"
        nivelhdr:Added="${if (added) "True" else "False"}"
        nivelhdr:Style="$style"
        nivelhdr:Curve="$CURVE_VERSION">
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

    class Marks(val added: Boolean, val style: String, val curve: Int)

    /**
     * Recorre los segmentos del encabezado (hasta SOS) leyendo solo lo necesario: [read] devuelve los bytes
     * pedidos (posición, largo). Sirve para el archivo entero en memoria y para leer del disco por partes:
     * las fotos de la cámara traen bloques grandes del chip (cientos de KB) antes del XMP y del MPF.
     */
    fun walk(size: Long, read: (Long, Int) -> ByteArray, visit: (marker: Int, pos: Long, len: Int) -> Boolean) {
        var pos = 2L
        while (pos + 4 <= size) {
            val h = read(pos, 4)
            if (h.size < 4 || (h[0].toInt() and 255) != 0xff) return
            val m = h[1].toInt() and 255
            if (m == 0xda || m == 0xd9) return
            val len = ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
            if (!visit(m, pos, len)) return
            pos += 2 + len
        }
    }

    private fun arrReader(b: ByteArray): (Long, Int) -> ByteArray = { p, n ->
        val a = p.toInt().coerceIn(0, b.size); b.copyOfRange(a, minOf(b.size, a + n))
    }

    /** Lee la marca propia (null si el Ultra HDR es el original de la cámara o si no tiene). */
    fun marks(b: ByteArray): Marks? {
        var res: Marks? = null
        walk(b.size.toLong(), arrReader(b)) { m, pos, len ->
            val p = pos.toInt()
            if (m == 0xe1 && startsWith(b, p + 4, XMP_NS) && contains(b, p, p + 2 + len, NS.toByteArray())) {
                val s = String(b, p, minOf(len + 2, b.size - p), Charsets.ISO_8859_1)
                val added = Regex("nivelhdr:Added=\"(\\w+)\"").find(s)?.groupValues?.get(1) == "True"
                val style = Regex("nivelhdr:Style=\"(\\w+)\"").find(s)?.groupValues?.get(1) ?: "stock"
                val curve = Regex("nivelhdr:Curve=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                res = Marks(added, style, curve); false
            } else true
        }
        return res
    }

    /** Largo de la imagen principal leyendo del disco por partes (para la grilla). */
    fun primaryLength(size: Long, read: (Long, Int) -> ByteArray): Int? {
        var res: Int? = null
        walk(size, read) { m, pos, len ->
            if (m == 0xe2 && len in 12..4096) {
                val seg = read(pos, len + 2)
                if (startsWith(seg, 4, MPF_TAG)) { res = primaryLength(seg, 0); false } else true
            } else true
        }
        return res
    }

    /** Largo de la imagen principal según el MPF (o null si no hay). */
    fun primaryLength(b: ByteArray): Int? {
        var res: Int? = null
        walk(b.size.toLong(), arrReader(b)) { m, pos, len ->
            if (m == 0xe2 && startsWith(b, pos.toInt() + 4, MPF_TAG)) { res = primaryLength(b, pos.toInt()); false } else true
        }
        return res?.takeIf { it in 4..b.size }
    }

    /** Lee el tamaño de la primera imagen del segmento MPF que empieza en [segPos] (FF E2 LL LL "MPF\0" TIFF...). */
    private fun primaryLength(b: ByteArray, segPos: Int): Int? {
        val t = segPos + 8
        if (t + 8 > b.size) return null
        val big = b[t] == 'M'.code.toByte()
        fun u32(p: Int): Long = if (big)
            ((b[p].toLong() and 255) shl 24) or ((b[p + 1].toLong() and 255) shl 16) or ((b[p + 2].toLong() and 255) shl 8) or (b[p + 3].toLong() and 255)
        else ((b[p + 3].toLong() and 255) shl 24) or ((b[p + 2].toLong() and 255) shl 16) or ((b[p + 1].toLong() and 255) shl 8) or (b[p].toLong() and 255)
        fun u16(p: Int): Int = if (big) ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255) else ((b[p + 1].toInt() and 255) shl 8) or (b[p].toInt() and 255)
        return try {
            val ifd = t + u32(t + 4).toInt()
            val n = u16(ifd)
            for (k in 0 until n) {
                val e = ifd + 2 + k * 12
                if (u16(e) == 0xb002) {
                    val entries = t + u32(e + 8).toInt()
                    return u32(entries + 4).toInt().takeIf { it > 4 }
                }
            }
            null
        } catch (_: IndexOutOfBoundsException) { null }
    }

    /**
     * Saca el Ultra HDR: devuelve solo la imagen principal, sin el XMP del gain map, sin los bloques ISO y sin
     * MPF (queda una JPEG común, idéntica a la foto que se ve en una pantalla sin HDR). Null si no se pudo.
     */
    fun strip(b: ByteArray): ByteArray? {
        val plen = primaryLength(b) ?: return null
        val p = b.copyOfRange(0, plen)
        val out = java.io.ByteArrayOutputStream(plen)
        out.write(p, 0, 2)
        var i = 2
        while (i + 4 <= p.size && (p[i].toInt() and 255) == 0xff) {
            val m = p[i + 1].toInt() and 255
            if (m == 0xda) break
            val len = ((p[i + 2].toInt() and 255) shl 8) or (p[i + 3].toInt() and 255)
            val end = i + 2 + len
            if (end > p.size) return null
            val drop = (m == 0xe1 && startsWith(p, i + 4, XMP_NS) && contains(p, i, end, "hdrgm".toByteArray())) ||
                (m == 0xe2 && (startsWith(p, i + 4, ISO_TAG) || startsWith(p, i + 4, MPF_TAG)))
            if (!drop) out.write(p, i, end - i)
            i = end
        }
        out.write(p, i, p.size - i)
        return out.toByteArray()
    }

    /**
     * Devuelve el archivo con Ultra HDR, o null si no conviene tocarlo (ya tiene gain map, es foto en movimiento,
     * o ya tiene varias imágenes adentro). [gainJpeg] es la JPEG chica del gain map (grises).
     */
    fun build(primary: ByteArray, gainJpeg: ByteArray, maxStops: Double, added: Boolean = true, style: String = "stock"): ByteArray? {
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
        val xmpSeg = segment(0xe1, primaryXmp(gain.size, added, style))
        val isoSeg = segment(0xe2, ISO_TAG + byteArrayOf(0, 0, 0, 0))
        val mpfLen = segment(0xe2, mpf(0, 0, 0)).size
        val primaryLen = head.size + xmpSeg.size + isoSeg.size + mpfLen + tail.size
        val tiffStart = head.size + xmpSeg.size + isoSeg.size + 8 // FF E2 LL LL "MPF\0"
        val mpfSeg = segment(0xe2, mpf(primaryLen, gain.size, primaryLen - tiffStart))
        return head + xmpSeg + isoSeg + mpfSeg + tail + gain
    }
}
