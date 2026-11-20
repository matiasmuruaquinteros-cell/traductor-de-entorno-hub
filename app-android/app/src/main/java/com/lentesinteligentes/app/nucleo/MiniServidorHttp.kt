package com.lentesinteligentes.app.nucleo

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Peticion(
    val metodo: String,
    val ruta: String,
    val encabezados: Map<String, String>,   // claves en minuscula
    val cuerpo: ByteArray,
    val ipCliente: String,
) {
    fun encabezado(nombre: String): String = encabezados[nombre.lowercase()] ?: ""
}

class Respuesta(val codigo: Int, val tipo: String, val cuerpo: ByteArray) {
    companion object {
        fun texto(s: String, codigo: Int = 200) = Respuesta(codigo, "text/plain; charset=utf-8", s.toByteArray())
        fun html(s: String) = Respuesta(200, "text/html; charset=utf-8", s.toByteArray())
        fun wav(b: ByteArray) = Respuesta(200, "audio/wav", b)
        fun jpeg(b: ByteArray) = Respuesta(200, "image/jpeg", b)
        fun vacia(codigo: Int) = Respuesta(codigo, "text/plain", ByteArray(0))
    }
}

/**
 * Servidor HTTP chiquito: lo justo para el ESP32 (GET y POST con Content-Length,
 * una peticion por conexion). Cada conexion se atiende en su propio hilo, porque
 * la foto de contexto y el audio llegan al mismo tiempo.
 */
class MiniServidorHttp(
    private val puerto: Int,
    private val registro: Registro,
    private val manejador: (Peticion) -> Respuesta,
) {
    /**
     * Si no es null, las conexiones que piden "Upgrade: websocket" se pasan aca.
     * La funcion se queda con la conexion hasta que termina (corre en su propio hilo).
     */
    @Volatile var alWebSocket: ((Peticion, ConexionWs) -> Unit)? = null

    private var servidor: ServerSocket? = null
    private var hilos: ExecutorService? = null
    @Volatile var activo = false
        private set

    fun iniciar() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(puerto))           // todas las interfaces: WiFi y hotspot
        servidor = s
        val pool = Executors.newFixedThreadPool(12)
        hilos = pool
        activo = true
        Thread({
            while (activo) {
                try {
                    val c = s.accept()
                    pool.execute { atender(c) }
                } catch (e: SocketException) {
                    if (activo) registro.log("[SERVIDOR] error aceptando conexion: ${e.message}")
                } catch (e: Exception) {
                    registro.log("[SERVIDOR] error: ${e.message}")
                }
            }
        }, "servidor-http").apply { isDaemon = true }.start()
    }

    fun detener() {
        activo = false
        try { servidor?.close() } catch (_: IOException) {}
        hilos?.shutdownNow()
        hilos?.awaitTermination(2, TimeUnit.SECONDS)
        servidor = null
        hilos = null
    }

    private fun atender(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = 30_000
                val entrada = BufferedInputStream(s.getInputStream())
                val ip = s.inetAddress?.hostAddress ?: "?"

                val cabecera = leerCabecera(entrada) ?: return
                val lineas = cabecera.split("\r\n")
                val partes = lineas[0].split(" ")
                if (partes.size < 2) { responder(s, Respuesta.vacia(400)); return }
                val metodo = partes[0].uppercase()
                val ruta = partes[1].substringBefore('?')
                val enc = HashMap<String, String>()
                for (l in lineas.drop(1)) {
                    val k = l.indexOf(':')
                    if (k > 0) enc[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim()
                }

                if (enc["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                    val f = alWebSocket
                    val clave = enc["sec-websocket-key"]
                    if (f == null || clave == null) { responder(s, Respuesta.vacia(404)); return }
                    s.soTimeout = 45_000           // el ESP32 manda un ping cada 15 s
                    val ws = ConexionWs(s, entrada)
                    ws.aceptar(clave)
                    try {
                        f(Peticion(metodo, ruta, enc, ByteArray(0), ip), ws)
                    } finally {
                        ws.cerrar()
                    }
                    return
                }

                if (enc["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    s.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }

                val cuerpo = when {
                    enc["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> leerChunked(entrada)
                    enc["content-length"] != null -> {
                        val n = enc["content-length"]!!.toIntOrNull() ?: -1
                        if (n < 0 || n > MAX_CUERPO) { responder(s, Respuesta.vacia(413)); return }
                        leerExacto(entrada, n)
                    }
                    else -> ByteArray(0)
                }

                val resp = try {
                    manejador(Peticion(metodo, ruta, enc, cuerpo, ip))
                } catch (e: Exception) {
                    registro.log("[SERVIDOR] error procesando $ruta: ${e.message}")
                    Respuesta.vacia(500)
                }
                responder(s, resp)
            } catch (e: IOException) {
                registro.log("[SERVIDOR] conexion cortada: ${e.message}")
            }
        }
    }

    private fun responder(s: Socket, r: Respuesta) {
        val cab = "HTTP/1.1 ${r.codigo} ${motivo(r.codigo)}\r\n" +
                "Content-Type: ${r.tipo}\r\n" +
                "Content-Length: ${r.cuerpo.size}\r\n" +
                "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(cab.toByteArray(Charsets.US_ASCII))
        out.write(r.cuerpo)
        out.flush()
        try { s.shutdownOutput() } catch (_: IOException) {}
    }

    private fun motivo(c: Int) = when (c) {
        200 -> "OK"; 101 -> "Switching Protocols"; 400 -> "Bad Request"; 404 -> "Not Found"
        413 -> "Payload Too Large"; 500 -> "Internal Server Error"; else -> "Status"
    }

    /** Lee hasta la linea en blanco que separa la cabecera del cuerpo. */
    private fun leerCabecera(e: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var a = 0; var b = 0; var c = 0
        while (true) {
            val d = e.read()
            if (d < 0) return if (buf.size() == 0) null else throw IOException("cabecera incompleta")
            buf.write(d)
            if (a == '\r'.code && b == '\n'.code && c == '\r'.code && d == '\n'.code) break
            a = b; b = c; c = d
            if (buf.size() > 16 * 1024) throw IOException("cabecera demasiado larga")
        }
        val t = buf.toByteArray()
        return String(t, 0, t.size - 4, Charsets.ISO_8859_1)
    }

    private fun leerExacto(e: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var leidos = 0
        while (leidos < n) {
            val k = e.read(out, leidos, n - leidos)
            if (k < 0) throw IOException("el cuerpo llego incompleto ($leidos de $n bytes)")
            leidos += k
        }
        return out
    }

    private fun leerLinea(e: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val d = e.read()
            if (d < 0) throw IOException("fin inesperado")
            if (d == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(d.toChar())
        }
    }

    private fun leerChunked(e: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val tam = leerLinea(e).substringBefore(';').trim().toInt(16)
            if (tam == 0) {
                while (leerLinea(e).isNotEmpty()) { /* trailers */ }
                return out.toByteArray()
            }
            if (out.size() + tam > MAX_CUERPO) throw IOException("cuerpo demasiado grande")
            out.write(leerExacto(e, tam))
            leerLinea(e)
        }
    }

    companion object {
        const val MAX_CUERPO = 5 * 1024 * 1024
    }
}
