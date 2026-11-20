package com.lentesinteligentes.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.lentesinteligentes.app.nucleo.Ajustes
import com.lentesinteligentes.app.nucleo.MejoraFoto
import com.lentesinteligentes.app.nucleo.ProcesadorFoto
import com.lentesinteligentes.app.nucleo.Registro
import java.io.ByteArrayOutputStream

/** Rota la foto (la camara esta montada de costado) y la mejora antes de mandarla a Gemini. */
class FotoAndroid(private val registro: Registro) : ProcesadorFoto {

    override fun procesar(jpeg: ByteArray, ajustes: Ajustes): ByteArray {
        val rot = ((ajustes.rotacion % 360) + 360) % 360
        if (rot == 0 && !ajustes.mejorarFoto) return jpeg
        return try {
            val opciones = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opciones)
                ?: throw IllegalArgumentException("no es un JPEG valido")

            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                val girada = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (girada !== bmp) bmp.recycle()
                bmp = girada
            }

            if (ajustes.mejorarFoto) {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val salida = MejoraFoto.aplicar(px, w, h)
                val mejorada = Bitmap.createBitmap(salida, w, h, Bitmap.Config.ARGB_8888)
                bmp.recycle()
                bmp = mejorada
            }

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            registro.log("   [FOTO] no se pudo procesar, mando la original (${e.message})")
            jpeg
        }
    }
}
