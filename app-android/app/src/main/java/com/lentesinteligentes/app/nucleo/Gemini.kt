package com.lentesinteligentes.app.nucleo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ErrorGemini(mensaje: String, val codigo: Int = 0) : Exception(mensaje)

/**
 * Cliente REST de Gemini (lo mismo que hacia el servidor de Python, sin librerias).
 *  - reintenta ante 429 / 500 / 503 (Google saturado) y ante cortes de red
 *  - pide "pensamiento minimo" (responde mas rapido); si el modelo no lo acepta, deja de pedirlo
 */
class Gemini(
    @Volatile var clave: String,
    @Volatile var modelo: String,
    private val registro: Registro,
    private val apiBase: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    @Volatile var pensamientoOk = true
    var nivelPensamiento: String? = "minimal"
    var timeoutS = 25
    var reintentos = 2

    fun listarModelos(): List<String> {
        val datos = httpJson("$apiBase/models?pageSize=1000", null, 20, 2)
        val modelos = Json.ruta(datos, "models") as? List<*> ?: return emptyList()
        return modelos.mapNotNull { m ->
            val metodos = Json.ruta(m, "supportedGenerationMethods") as? List<*> ?: return@mapNotNull null
            if ("generateContent" !in metodos) return@mapNotNull null
            (Json.ruta(m, "name") as? String)?.removePrefix("models/")
        }
    }

    /** Si el modelo preferido no existe para esta clave, elige un flash-lite o flash. */
    fun elegirModelo(preferido: String): String {
        val disponibles = listarModelos()
        val elegido = when {
            preferido in disponibles -> preferido
            else -> disponibles.firstOrNull { "flash-lite" in it }
                ?: disponibles.firstOrNull { "flash" in it }
                ?: disponibles.firstOrNull()
                ?: throw ErrorGemini("la clave no tiene ningun modelo disponible")
        }
        modelo = elegido
        return elegido
    }

    fun generar(payload: Map<String, Any?>, timeout: Int = timeoutS): Map<String, Any?> {
        val url = "$apiBase/models/$modelo:generateContent"
        val nivel = nivelPensamiento
        if (nivel != null && pensamientoOk) {
            val gen = (payload["generationConfig"] as? Map<*, *>)?.toMutableMap() ?: mutableMapOf()
            gen["thinkingConfig"] = mapOf("thinkingLevel" to nivel)
            val con = payload.toMutableMap()
            con["generationConfig"] = gen
            try {
                return comoMapa(httpJson(url, Json.escribir(con), timeout, reintentos))
            } catch (e: ErrorGemini) {
                if (e.codigo == 400 && e.message.orEmpty().contains("thinking", ignoreCase = true)) {
                    registro.log("   [AVISO] Este modelo no acepta thinkingLevel. Sigo sin esa opcion.")
                    pensamientoOk = false
                } else {
                    throw e
                }
            }
        }
        return comoMapa(httpJson(url, Json.escribir(payload), timeout, reintentos))
    }

    @Suppress("UNCHECKED_CAST")
    private fun comoMapa(v: Any?): Map<String, Any?> =
        v as? Map<String, Any?> ?: throw ErrorGemini("respuesta de Gemini con formato inesperado")

    /** Texto de la respuesta, sin las partes de "pensamiento". */
    fun textoDe(datos: Map<String, Any?>): String {
        val texto = textoOpcional(datos)
        if (texto.isEmpty()) throw ErrorGemini("Gemini devolvio respuesta vacia")
        return texto
    }

    /** Igual que textoDe, pero vacio si el modelo solo llamo herramientas. */
    fun textoOpcional(datos: Map<String, Any?>): String =
        partesDe(datos)
            .filter { Json.ruta(it, "thought") != true }
            .mapNotNull { Json.ruta(it, "text") as? String }
            .joinToString(" ")
            .trim()

    /** Llamadas a herramientas (function calling) que pidio el modelo: (nombre, argumentos). */
    fun llamadasDe(datos: Map<String, Any?>): List<Pair<String, Map<*, *>>> =
        partesDe(datos).mapNotNull { p ->
            val fc = Json.ruta(p, "functionCall") as? Map<*, *> ?: return@mapNotNull null
            val nombre = fc["name"] as? String ?: return@mapNotNull null
            Pair(nombre, fc["args"] as? Map<*, *> ?: emptyMap<String, Any?>())
        }

    /**
     * El turno del modelo tal cual vino, para devolverlo en la vuelta siguiente.
     * Se manda sin tocar: los modelos Gemini 3 exigen recibir de vuelta su "thoughtSignature".
     */
    fun contenidoModelo(datos: Map<String, Any?>): Map<*, *> {
        val c = Json.ruta(primerCandidato(datos), "content") as? Map<*, *>
        return c ?: mapOf("role" to "model", "parts" to partesDe(datos))
    }

    private fun primerCandidato(datos: Map<String, Any?>): Any? {
        val candidatos = datos["candidates"] as? List<*>
        if (candidatos.isNullOrEmpty()) {
            val motivo = Json.ruta(datos, "promptFeedback", "blockReason") ?: "sin candidatos"
            throw ErrorGemini("Gemini no devolvio respuesta ($motivo)")
        }
        return candidatos[0]
    }

    private fun partesDe(datos: Map<String, Any?>): List<*> =
        Json.ruta(primerCandidato(datos), "content", "parts") as? List<*> ?: emptyList<Any?>()

    private fun httpJson(url: String, cuerpo: String?, timeout: Int, maxIntentos: Int): Any? {
        var intento = 0
        while (true) {
            intento++
            val con = URL(url).openConnection() as HttpURLConnection
            try {
                con.connectTimeout = 10_000
                con.readTimeout = timeout * 1000
                con.setRequestProperty("x-goog-api-key", clave)
                con.setRequestProperty("Accept", "application/json")
                if (cuerpo != null) {
                    val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                    con.requestMethod = "POST"
                    con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    con.setFixedLengthStreamingMode(bytes.size)
                    con.outputStream.use { it.write(bytes) }
                } else {
                    con.requestMethod = "GET"
                }
                val codigo = con.responseCode
                if (codigo in 200..299) {
                    val texto = con.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    return Json.leer(texto)
                }
                val detalle = (con.errorStream?.use { it.readBytes() } ?: ByteArray(0))
                    .toString(Charsets.UTF_8).take(600)
                if (codigo in listOf(429, 500, 503) && intento < maxIntentos) {
                    val espera = 1L shl (intento - 1)          // 1 s, 2 s, 4 s...
                    registro.log("   [API] Google ocupado (error $codigo). Reintento en $espera s ($intento/$maxIntentos)")
                    Thread.sleep(espera * 1000)
                    continue
                }
                throw ErrorGemini("HTTP $codigo de Gemini: $detalle", codigo)
            } catch (e: IOException) {
                if (intento < maxIntentos) {
                    registro.log("   [API] Error de red (${e.message}). Reintento en 1 s ($intento/$maxIntentos)")
                    Thread.sleep(1000)
                    continue
                }
                throw ErrorGemini("Sin conexion con Gemini: ${e.message}")
            } finally {
                con.disconnect()
            }
        }
    }
}
