package com.lentesinteligentes.app.nucleo

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * WebSocket del lado del servidor (RFC 6455), lo justo para hablar con el ESP32
 * por la misma red local. Sin TLS: el ESP32 y el celular estan en la misma red.
 *
 *  - leer() devuelve el proximo mensaje de texto o binario (responde solo los ping).
 *  - enviarTexto / enviarBinario se pueden llamar desde cualquier hilo.
 */
class ConexionWs(private val socket: Socket, private val entrada: InputStream) {
    private val salida: OutputStream = socket.getOutputStream()
    private val candadoSalida = Any()
    @Volatile var abierta = true
        private set
    val ip: String = socket.inetAddress?.hostAddress ?: "?"

    class Mensaje(val binario: Boolean, val datos: ByteArray) {
        val texto: String get() = String(datos, Charsets.UTF_8)
    }

    /** Responde el pedido de "upgrade" del cliente. */
    fun aceptar(clave: String) {
        val acepto = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((clave.trim() + GUID).toByteArray(Charsets.US_ASCII))
        )
        val r = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acepto\r\n\r\n"
        synchronized(candadoSalida) {
            salida.write(r.toByteArray(Charsets.US_ASCII))
            salida.flush()
        }
    }

    /** Proximo mensaje completo, o null si el cliente cerro. */
    fun leer(): Mensaje? {
        var acumulado: java.io.ByteArrayOutputStream? = null
        var tipoFragmentado = false
        while (abierta) {
            val b0 = entrada.read()
            if (b0 < 0) { abierta = false; return null }
            val b1 = leerByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val conMascara = (b1 and 0x80) != 0
            var largo = (b1 and 0x7F).toLong()
            if (largo == 126L) largo = ((leerByte() shl 8) or leerByte()).toLong()
            else if (largo == 127L) { largo = 0; repeat(8) { largo = (largo shl 8) or leerByte().toLong() } }
            if (largo > MAX_MENSAJE) throw IOException("mensaje WebSocket demasiado grande ($largo bytes)")
            val mascara = if (conMascara) ByteArray(4) { leerByte().toByte() } else null
            val datos = leerExacto(largo.toInt())
            if (mascara != null) for (i in datos.indices) datos[i] = (datos[i].toInt() xor mascara[i and 3].toInt()).toByte()

            when (opcode) {
                0x8 -> { cerrar(); return null }                       // close
                0x9 -> { enviarMarco(0xA, datos); continue }           // ping -> pong
                0xA -> continue                                        // pong
                0x0 -> {                                               // continuacion
                    val a = acumulado ?: continue
                    a.write(datos)
                    if (a.size() > MAX_MENSAJE) throw IOException("mensaje fragmentado demasiado grande")
                    if (fin) return Mensaje(tipoFragmentado, a.toByteArray())
                }
                0x1, 0x2 -> {
                    if (fin) return Mensaje(opcode == 0x2, datos)
                    acumulado = java.io.ByteArrayOutputStream().apply { write(datos) }
                    tipoFragmentado = opcode == 0x2
                }
                else -> continue
            }
        }
        return null
    }

    fun enviarTexto(s: String): Boolean = enviarMarco(0x1, s.toByteArray(Charsets.UTF_8))
    fun enviarBinario(b: ByteArray): Boolean = enviarMarco(0x2, b)
    fun enviarPing(): Boolean = enviarMarco(0x9, ByteArray(0))

    private fun enviarMarco(opcode: Int, datos: ByteArray): Boolean {
        if (!abierta) return false
        val n = datos.size
        val cab = when {
            n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (n ushr 8).toByte(), n.toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte(); it[1] = 127
                for (k in 0 until 8) it[9 - k] = (n.toLong() ushr (8 * k)).toByte()
            }
        }
        return try {
            synchronized(candadoSalida) {
                salida.write(cab)
                salida.write(datos)
                salida.flush()
            }
            true
        } catch (e: IOException) {
            abierta = false
            false
        }
    }

    fun cerrar() {
        if (!abierta) return
        try { enviarMarco(0x8, byteArrayOf(0x03, 0xE8.toByte())) } catch (_: Exception) {}
        abierta = false
        try { socket.close() } catch (_: IOException) {}
    }

    private fun leerByte(): Int {
        val b = entrada.read()
        if (b < 0) throw EOFException("el cliente corto la conexion")
        return b
    }

    private fun leerExacto(n: Int): ByteArray {
        val out = ByteArray(n)
        var k = 0
        while (k < n) {
            val r = entrada.read(out, k, n - k)
            if (r < 0) throw EOFException("el cliente corto la conexion")
            k += r
        }
        return out
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_MENSAJE = 2L * 1024 * 1024
    }
}
