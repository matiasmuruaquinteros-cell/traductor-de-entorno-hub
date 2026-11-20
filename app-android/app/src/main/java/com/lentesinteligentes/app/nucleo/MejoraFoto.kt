package com.lentesinteligentes.app.nucleo

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mejora de la foto, igual que hacia el servidor de Python con Pillow:
 *  1. autocontraste por canal (estira el rango; deja quemar un % de luces)
 *  2. gamma (< 1 aclara las sombras: caras a contraluz)
 *  3. saturacion del color
 *  4. nitidez
 * Trabaja sobre pixeles ARGB (como los da Android), asi se puede probar fuera del celular.
 */
object MejoraFoto {

    class Parametros(
        val corteBajo: Double = 0.5,   // % de pixeles oscuros que se descartan al estirar
        val corteAlto: Double = 3.0,   // % de luces que se dejan quemar
        val gamma: Double = 0.75,
        val color: Double = 1.25,
        val nitidez: Double = 1.5,
    )

    fun aplicar(px: IntArray, ancho: Int, alto: Int, p: Parametros = Parametros()): IntArray {
        val n = ancho * alto
        require(px.size >= n) { "faltan pixeles" }

        // ---- 1 y 2: una tabla por canal (autocontraste + gamma) ----
        val hist = Array(3) { IntArray(256) }
        for (i in 0 until n) {
            val c = px[i]
            hist[0][(c shr 16) and 0xFF]++
            hist[1][(c shr 8) and 0xFF]++
            hist[2][c and 0xFF]++
        }
        val tablaGamma = IntArray(256) { (255 * (it / 255.0).pow(p.gamma)).roundToInt().coerceIn(0, 255) }
        val tablas = Array(3) { canal ->
            val ac = tablaAutocontraste(hist[canal], n, p.corteBajo, p.corteAlto)
            IntArray(256) { tablaGamma[ac[it]] }
        }

        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        val satur = p.color
        for (i in 0 until n) {
            val c = px[i]
            var rr = tablas[0][(c shr 16) and 0xFF]
            var gg = tablas[1][(c shr 8) and 0xFF]
            var bb = tablas[2][c and 0xFF]
            // ---- 3: color (mezcla con el gris, como ImageEnhance.Color) ----
            if (satur != 1.0) {
                val gris = (rr * 299 + gg * 587 + bb * 114) / 1000.0
                rr = (gris + satur * (rr - gris)).roundToInt().coerceIn(0, 255)
                gg = (gris + satur * (gg - gris)).roundToInt().coerceIn(0, 255)
                bb = (gris + satur * (bb - gris)).roundToInt().coerceIn(0, 255)
            }
            r[i] = rr; g[i] = gg; b[i] = bb
        }

        // ---- 4: nitidez (como ImageEnhance.Sharpness: mezcla con la version suavizada) ----
        val out = IntArray(n)
        val f = p.nitidez
        for (y in 0 until alto) {
            for (x in 0 until ancho) {
                val i = y * ancho + x
                if (f == 1.0 || x == 0 || y == 0 || x == ancho - 1 || y == alto - 1) {
                    out[i] = (0xFF shl 24) or (r[i] shl 16) or (g[i] shl 8) or b[i]
                    continue
                }
                out[i] = (0xFF shl 24) or
                        (nitido(r, i, ancho, f) shl 16) or
                        (nitido(g, i, ancho, f) shl 8) or
                        nitido(b, i, ancho, f)
            }
        }
        return out
    }

    // Filtro SMOOTH de Pillow: [[1,1,1],[1,5,1],[1,1,1]] / 13
    private fun nitido(c: IntArray, i: Int, w: Int, f: Double): Int {
        val suma = c[i - w - 1] + c[i - w] + c[i - w + 1] +
                c[i - 1] + 5 * c[i] + c[i + 1] +
                c[i + w - 1] + c[i + w] + c[i + w + 1]
        val suave = (suma / 13.0).roundToInt()
        return (suave + f * (c[i] - suave)).roundToInt().coerceIn(0, 255)
    }

    /** Igual que ImageOps.autocontrast(cutoff=(bajo, alto)) para un canal. */
    private fun tablaAutocontraste(h0: IntArray, total: Int, bajo: Double, alto: Double): IntArray {
        val h = h0.copyOf()
        var corte = (total * bajo / 100).toInt()
        for (k in 0 until 256) {
            if (corte <= 0) break
            if (corte > h[k]) { corte -= h[k]; h[k] = 0 } else { h[k] -= corte; corte = 0 }
        }
        corte = (total * alto / 100).toInt()
        for (k in 255 downTo 0) {
            if (corte <= 0) break
            if (corte > h[k]) { corte -= h[k]; h[k] = 0 } else { h[k] -= corte; corte = 0 }
        }
        var lo = 0
        while (lo < 256 && h[lo] == 0) lo++
        var hi = 255
        while (hi >= 0 && h[hi] == 0) hi--
        if (hi <= lo) return IntArray(256) { it }
        val escala = 255.0 / (hi - lo)
        val offset = -lo * escala
        return IntArray(256) { (it * escala + offset).toInt().coerceIn(0, 255) }
    }
}
