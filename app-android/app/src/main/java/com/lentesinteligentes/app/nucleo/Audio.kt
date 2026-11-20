package com.lentesinteligentes.app.nucleo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Audio PCM mono de 16 bits. */
class Pcm(val muestras: ShortArray, val frecuencia: Int) {
    val segundos: Double get() = muestras.size.toDouble() / frecuencia
}

/**
 * Todo el manejo de audio, sin ffmpeg:
 *  - leer y armar archivos WAV
 *  - cambiar la frecuencia (la voz del celular sale a 22050 o 24000 Hz, el ESP32 usa 16000)
 *  - recortar silencios, normalizar el volumen
 *  - limpiar el audio del microfono antes de mandarlo a Gemini
 */
object Audio {

    // ------------------------------------------------------------------
    // WAV
    // ------------------------------------------------------------------
    fun encabezadoWav(nBytesPcm: Int, frecuencia: Int, canales: Int = 1, bits: Int = 16): ByteArray {
        val h = ByteArray(44)
        fun txt(pos: Int, s: String) { for (k in s.indices) h[pos + k] = s[k].code.toByte() }
        fun u32(pos: Int, v: Int) { for (k in 0 until 4) h[pos + k] = (v ushr (8 * k)).toByte() }
        fun u16(pos: Int, v: Int) { h[pos] = v.toByte(); h[pos + 1] = (v ushr 8).toByte() }
        txt(0, "RIFF"); u32(4, 36 + nBytesPcm); txt(8, "WAVE")
        txt(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, canales)
        u32(24, frecuencia); u32(28, frecuencia * canales * bits / 8)
        u16(32, canales * bits / 8); u16(34, bits)
        txt(36, "data"); u32(40, nBytesPcm)
        return h
    }

    /** WAV completo (encabezado de 44 bytes + PCM little endian). */
    fun aWav(p: Pcm): ByteArray {
        val n = p.muestras.size * 2
        val out = ByteArray(44 + n)
        encabezadoWav(n, p.frecuencia).copyInto(out)
        var j = 44
        for (s in p.muestras) {
            out[j] = s.toByte()
            out[j + 1] = (s.toInt() shr 8).toByte()
            j += 2
        }
        return out
    }

    /**
     * Lee un WAV recorriendo sus bloques (no asume 44 bytes: los motores de voz a veces
     * agregan bloques extra). Acepta 8/16/24/32 bits enteros o 32 bits flotante, y mezcla
     * a mono si viene en estereo.
     */
    fun leerWav(b: ByteArray): Pcm {
        fun u16(p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)
        fun tag(p: Int) = String(CharArray(4) { (b[p + it].toInt() and 0xFF).toChar() })

        if (b.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") throw IllegalArgumentException("no es un WAV")
        var formato = 1; var canales = 1; var frecuencia = 16000; var bits = 16
        var datosIni = -1; var datosLen = 0
        var p = 12
        while (p + 8 <= b.size) {
            val id = tag(p)
            var len = u32(p + 4)
            val cuerpo = p + 8
            if (id == "fmt ") {
                formato = u16(cuerpo); canales = u16(cuerpo + 2)
                frecuencia = u32(cuerpo + 4); bits = u16(cuerpo + 14)
                if (formato == 0xFFFE && len >= 26) formato = u16(cuerpo + 24) // WAVE_FORMAT_EXTENSIBLE
            } else if (id == "data") {
                // Algunos motores escriben el tamano en 0 o 0xFFFFFFFF mientras graban
                if (len <= 0 || cuerpo.toLong() + len > b.size) len = b.size - cuerpo
                datosIni = cuerpo; datosLen = len
                break
            }
            if (len < 0) break
            p = cuerpo + len + (len and 1)
        }
        if (datosIni < 0) throw IllegalArgumentException("WAV sin datos")
        if (canales < 1) canales = 1

        val bytesMuestra = bits / 8
        val porCuadro = bytesMuestra * canales
        if (porCuadro <= 0) throw IllegalArgumentException("WAV con formato raro ($bits bits)")
        val n = datosLen / porCuadro
        val out = ShortArray(n)
        for (i in 0 until n) {
            var suma = 0.0
            for (c in 0 until canales) {
                val q = datosIni + i * porCuadro + c * bytesMuestra
                val v: Double = when {
                    formato == 3 && bits == 32 -> java.lang.Float.intBitsToFloat(u32(q)).toDouble()
                    bits == 8 -> ((b[q].toInt() and 0xFF) - 128) / 128.0
                    bits == 16 -> u16(q).toShort() / 32768.0
                    bits == 24 -> (((b[q].toInt() and 0xFF) or ((b[q + 1].toInt() and 0xFF) shl 8) or
                            (b[q + 2].toInt() shl 16))) / 8388608.0
                    bits == 32 -> u32(q) / 2147483648.0
                    else -> throw IllegalArgumentException("WAV de $bits bits no soportado")
                }
                suma += v
            }
            out[i] = aShort(suma / canales)
        }
        return Pcm(out, frecuencia)
    }

    private fun aShort(x: Double): Short {
        val v = (x * 32767.0).roundToInt()
        return v.coerceIn(-32768, 32767).toShort()
    }

    // ------------------------------------------------------------------
    // CAMBIO DE FRECUENCIA (sinc con ventana: sin el sonido metalico de la interpolacion lineal)
    // ------------------------------------------------------------------
    fun remuestrear(p: Pcm, destino: Int): Pcm {
        if (p.frecuencia == destino || p.muestras.isEmpty()) return p
        val x = p.muestras
        val razon = destino.toDouble() / p.frecuencia
        val corte = min(1.0, razon) * 0.92          // filtro antialias un poco por debajo de Nyquist
        val ceros = 10
        val mitad = ceros / corte                    // mitad del filtro, en muestras de entrada
        val nOut = floor(x.size * razon).toInt()
        val out = ShortArray(nOut)
        for (n in 0 until nOut) {
            val t = n / razon                        // posicion en la entrada
            val k0 = max(0, ceil(t - mitad).toInt())
            val k1 = min(x.size - 1, floor(t + mitad).toInt())
            var acc = 0.0
            var pesos = 0.0
            for (k in k0..k1) {
                val d = t - k
                val w = 0.5 + 0.5 * cos(PI * d / mitad)          // ventana de Hann
                val arg = PI * corte * d
                val s = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
                val h = corte * s * w
                acc += x[k] * h
                pesos += h
            }
            // Dividir por la suma de pesos deja la ganancia en 1, incluso en los bordes del archivo
            val v = if (pesos > 1e-6) acc / pesos else 0.0
            out[n] = v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Pcm(out, destino)
    }

    // ------------------------------------------------------------------
    // NIVELES
    // ------------------------------------------------------------------
    fun pico(m: ShortArray): Double {
        var p = 0
        for (s in m) { val a = abs(s.toInt()); if (a > p) p = a }
        return p / 32768.0
    }

    fun rms(m: ShortArray): Double {
        if (m.isEmpty()) return 0.0
        var acc = 0.0
        for (s in m) acc += s.toDouble() * s
        return sqrt(acc / m.size) / 32768.0
    }

    /** Saca el silencio del principio y del final (los motores de voz agregan 100-300 ms). */
    fun recortarSilencio(p: Pcm, umbral: Double = 0.01, margenMs: Int = 40): Pcm {
        val m = p.muestras
        val lim = (umbral * 32768).toInt()
        var a = 0
        while (a < m.size && abs(m[a].toInt()) < lim) a++
        if (a >= m.size) return p                     // todo silencio: no se toca
        var b = m.size - 1
        while (b > a && abs(m[b].toInt()) < lim) b--
        val margen = p.frecuencia * margenMs / 1000
        val ini = max(0, a - margen)
        val fin = min(m.size, b + 1 + margen)
        return Pcm(m.copyOfRange(ini, fin), p.frecuencia)
    }

    /** Lleva el pico al nivel pedido (0..1) y suaviza 5 ms en cada punta para evitar chasquidos. */
    fun normalizar(p: Pcm, picoObjetivo: Double): Pcm {
        val m = p.muestras
        val pk = pico(m)
        val factor = if (pk > 0.001) picoObjetivo / pk else 1.0
        val out = ShortArray(m.size)
        val rampa = min(m.size / 2, p.frecuencia * 5 / 1000)
        for (i in m.indices) {
            var g = factor
            if (i < rampa) g *= i.toDouble() / rampa
            else if (i >= m.size - rampa) g *= (m.size - 1 - i).toDouble() / rampa
            out[i] = (m[i] * g).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return Pcm(out, p.frecuencia)
    }

    /** Cadena completa para la voz que va al parlante del ESP32. */
    fun prepararParaParlante(wavDelMotor: ByteArray, frecuenciaSalida: Int, volumen: Double): ByteArray {
        var p = leerWav(wavDelMotor)
        p = recortarSilencio(p)
        p = remuestrear(p, frecuenciaSalida)
        p = normalizar(p, volumen)
        return aWav(p)
    }

    // ------------------------------------------------------------------
    // MICROFONO
    // ------------------------------------------------------------------
    /** Pico y RMS en % de la escala completa. */
    fun nivelWav(wav: ByteArray): Pair<Double, Double> {
        val p = leerWav(wav)
        return Pair(pico(p.muestras) * 100, rms(p.muestras) * 100)
    }

    /**
     * Reemplaza al "highpass + loudnorm" de ffmpeg:
     *  1. filtro pasa altos de 80 Hz (saca el zumbido grave y la continua del INMP441)
     *  2. sube el volumen hasta un nivel comodo (unos -18 dB) sin pasarse de -1 dB de pico
     */
    fun limpiarMicrofono(wav: ByteArray): ByteArray {
        val p = leerWav(wav)
        val fs = p.frecuencia.toDouble()
        val x = p.muestras

        // Biquad pasa altos (Robert Bristow-Johnson), Q = 0.707
        val w0 = 2 * PI * 80.0 / fs
        val alpha = sin(w0) / (2 * 0.7071)
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 + c) / 2 / a0; val b1 = -(1 + c) / a0; val b2 = (1 + c) / 2 / a0
        val a1 = -2 * c / a0; val a2 = (1 - alpha) / a0
        val y = DoubleArray(x.size)
        // El filtro arranca "acomodado" a la continua del microfono: sin golpe al principio
        val x0 = if (x.isNotEmpty()) x[0] / 32768.0 else 0.0
        var x1 = x0; var x2 = x0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i] / 32768.0
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            y[i] = yi
        }

        // Nivel de la voz: RMS de los bloques de 20 ms que no son silencio
        val bloque = max(1, (fs * 0.02).toInt())
        var acc = 0.0; var nBloques = 0
        var pk = 0.0
        var i = 0
        while (i < y.size) {
            val fin = min(y.size, i + bloque)
            var e = 0.0
            for (k in i until fin) { e += y[k] * y[k]; val a = abs(y[k]); if (a > pk) pk = a }
            e /= (fin - i)
            if (sqrt(e) > 0.003) { acc += e; nBloques++ }   // -50 dB: debajo de eso es silencio
            i = fin
        }
        val objetivoRms = 0.126   // -18 dB
        val techoPico = 0.89      // -1 dB
        val factor = when {
            pk < 0.0003 -> 1.0                                         // no hay senal
            nBloques == 0 -> min(techoPico / pk, 40.0)
            else -> min(min(objetivoRms / sqrt(acc / nBloques), techoPico / pk), 40.0)
        }
        val out = ShortArray(y.size) { k -> aShort(y[k] * factor) }
        return aWav(Pcm(out, p.frecuencia))
    }
}
