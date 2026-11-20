package com.lentesinteligentes.app

import com.lentesinteligentes.app.nucleo.CanalWs
import com.lentesinteligentes.app.nucleo.ConectorWs
import com.lentesinteligentes.app.nucleo.OyenteWs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** WebSocket seguro (wss) hacia Gemini Live, con OkHttp. */
class ConectorOkHttp : ConectorWs {
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // la conexion queda abierta todo el tiempo
        .pingInterval(20, TimeUnit.SECONDS)         // detecta si se corto internet
        .build()

    override fun abrir(url: String, oyente: OyenteWs): CanalWs {
        lateinit var canal: CanalWs
        val ws = cliente.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = oyente.alAbrir(envolver(webSocket))
            override fun onMessage(webSocket: WebSocket, text: String) = oyente.alMensaje(text)
            // Gemini manda el JSON en marcos binarios
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = oyente.alMensaje(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                oyente.alCerrar("Gemini cerro la conexion ($code): $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val extra = response?.let { " (HTTP ${it.code})" } ?: ""
                oyente.alCerrar("Fallo la conexion$extra: ${t.message}")
            }
        })
        canal = envolver(ws)
        return canal
    }

    private fun envolver(ws: WebSocket) = object : CanalWs {
        override fun enviar(texto: String) = ws.send(texto)
        override fun cerrar() { ws.close(1000, "fin") }
    }
}
