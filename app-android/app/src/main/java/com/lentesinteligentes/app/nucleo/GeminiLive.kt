package com.lentesinteligentes.app.nucleo

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Transporte WebSocket. En el celular lo implementa OkHttp (ConectorOkHttp);
// en las pruebas, el WebSocket que trae Java. Asi esta clase no depende de nada.
// ---------------------------------------------------------------------------
interface CanalWs {
    fun enviar(texto: String): Boolean
    fun cerrar()
}

interface OyenteWs {
    fun alAbrir(canal: CanalWs)        // puede llegar antes de que abrir() devuelva
    fun alMensaje(texto: String)
    fun alCerrar(motivo: String)
}

fun interface ConectorWs {
    fun abrir(url: String, oyente: OyenteWs): CanalWs
}

/** Lo que Gemini Live le avisa al puente. Se llama desde el hilo del WebSocket. */
interface EventosLive {
    fun audio(pcm: ByteArray, frecuencia: Int)     // PCM 16 bit mono little-endian
    fun interrumpido()
    fun turnoCompleto()
    fun transcripcion(delUsuario: Boolean, texto: String)
    fun desconectado(motivo: String)
    /** Gemini pidio usar una herramienta (comandos por voz, ver una foto vieja). Hay que contestar con responderHerramienta. */
    fun herramienta(id: String, nombre: String, args: Map<*, *>)
}

class ConfigLive(
    val modelo: String = MODELO_POR_DEFECTO,
    val micAbierto: Boolean = false,            // false = se habla con el boton apretado
    val instrucciones: String = INSTRUCCIONES,
    val voz: String = "",                       // "" = la voz por defecto del modelo
    val extra: String = "",                     // se suma a las instrucciones (ajustes actuales)
    val herramientas: List<Map<String, Any?>> = Comandos.herramientas,
) {
    companion object {
        const val MODELO_POR_DEFECTO = "gemini-3.8-live"

        val INSTRUCCIONES =
            "Sos el asistente de voz de un traductor de entorno para una persona ciega: un dispositivo con una " +
            "camara colgada en el pecho. Hablas siempre en espanol rioplatense, con voz calida y natural. " +
            "Respuestas cortas: una a tres oraciones, salvo que te pidan mas detalle. " +
            "Cuando recibas una imagen, es lo que el usuario tiene enfrente en este momento, a la altura del pecho; " +
            "ubica las cosas respecto de el (adelante tuyo, a tu izquierda, cerca, lejos). " +
            "Cada foto llega con un numero (FOTO 1, FOTO 2...). " +
            "Si el usuario pide que la describas, describi lo importante para orientarse y para " +
            "manipular el objeto: que es, donde esta, colores, texto visible y riesgos. " +
            "Si te hace una pregunta sobre la imagen, responde solo eso. " +
            "Si la imagen esta oscura o borrosa, decilo y sugeri acercar, alejar o buscar mas luz. " +
            "Las fotos anteriores siguen valiendo para preguntas que se refieran a ellas: busca en la conversacion " +
            "a que foto o tema concreto se refiere y responde sobre eso, sin mezclar temas viejos si no viene al caso. " +
            "Si necesitas volver a mirar una foto vieja, llama a ver_foto con su numero. " +
            "Si te pregunta por algo que tiene enfrente ahora y la ultima foto es vieja, " +
            "pedile que saque otra foto con el boton de la camara. " +
            "Despues de usar una herramienta, confirma en pocas palabras lo que hiciste. " +
            "Nunca inventes lo que no se ve. Si no entendiste el audio, pedi que repita."

        const val PEDIDO_DESCRIBIR =
            "Esta foto es de lo que tengo enfrente AHORA, ignora las fotos anteriores. Describime brevemente lo que ves."

        fun etiquetaFoto(id: Int, hora: String) = "FOTO $id ($hora)."
    }
}

/**
 * Cliente de la Live API de Gemini (WebSocket bidireccional).
 *  - El audio del usuario entra en streaming (PCM 16 kHz).
 *  - Gemini contesta con audio en streaming (PCM 24 kHz) y con la transcripcion.
 *  - Modo boton: actividad manual (activityStart / activityEnd).
 *    Modo abierto: Gemini detecta solo cuando empieza y termina de hablar.
 *  - Reanudacion de sesion: si se corta (o Gemini avisa con goAway), se reconecta
 *    con el ultimo "handle" y la conversacion sigue donde estaba.
 */
class GeminiLive(
    @Volatile var clave: String,
    private val conector: ConectorWs,
    private val registro: Registro,
    private val eventos: EventosLive,
    private val base: String = "wss://generativelanguage.googleapis.com",
) {
    @Volatile private var canal: CanalWs? = null
    @Volatile var listo = false
        private set
    @Volatile var ultimoError = ""
        private set
    @Volatile private var handle = ""
    @Volatile private var config = ConfigLive()
    @Volatile private var generacion = 0             // descarta avisos de conexiones viejas
    /** true si la ultima conexion retomo una conversacion vieja (Gemini ya tiene el contexto). */
    @Volatile var reanudo = false
        private set
    private val candado = Any()

    /** Conecta y espera la confirmacion del setup. Devuelve true si quedo listo. */
    fun conectar(cfg: ConfigLive, esperaMs: Long = 10_000): Boolean {
        config = cfg
        if (clave.isEmpty()) { ultimoError = "Falta la clave de Gemini"; return false }
        val listoLatch = CountDownLatch(1)
        val gen: Int
        synchronized(candado) {
            cerrarCanal()
            generacion++
            gen = generacion
            listo = false
        }
        reanudo = handle.isNotEmpty()
        val url = "$base/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$clave"
        val oyente = object : OyenteWs {
            override fun alAbrir(canal: CanalWs) {
                if (gen != generacion) return
                this@GeminiLive.canal = canal
                canal.enviar(Json.escribir(setup(cfg)))
            }
            override fun alMensaje(texto: String) {
                if (gen != generacion) return
                procesar(texto, listoLatch)
            }
            override fun alCerrar(motivo: String) {
                if (gen != generacion) return
                val estabaListo = listo
                listo = false
                canal = null
                ultimoError = motivo
                listoLatch.countDown()
                registro.log("[LIVE] conexion cerrada: $motivo")
                if (estabaListo) eventos.desconectado(motivo)
            }
        }
        try {
            val c = conector.abrir(url, oyente)
            if (gen == generacion && canal == null) canal = c
        } catch (e: Exception) {
            ultimoError = "No se pudo abrir la conexion: ${e.message}"
            return false
        }
        if (!listoLatch.await(esperaMs, TimeUnit.MILLISECONDS)) {
            ultimoError = "Gemini Live no respondio en ${esperaMs / 1000} s"
            cerrar()
            return false
        }
        if (listo) {
            ultimoError = ""
            registro.log("[LIVE] listo (${cfg.modelo}, mic ${if (cfg.micAbierto) "abierto" else "con boton"}" +
                    (if (handle.isNotEmpty()) ", conversacion reanudada" else "") + ")")
        }
        return listo
    }

    /** Si se cayo, reconecta conservando la conversacion. */
    fun asegurar(): Boolean = listo || conectar(config)

    fun olvidar() { handle = "" }

    fun cerrar() = synchronized(candado) {
        generacion++
        listo = false
        cerrarCanal()
    }

    private fun cerrarCanal() {
        try { canal?.cerrar() } catch (_: Exception) {}
        canal = null
    }

    // ------------------------------------------------------------------
    // ENVIOS
    // ------------------------------------------------------------------
    fun enviarAudio(pcm16k: ByteArray) =
        enviarEntrada(mapOf("audio" to mapOf("data" to b64(pcm16k), "mimeType" to "audio/pcm;rate=16000")))

    /**
     * Foto + pedido en UN solo turno (clientContent). Por realtimeInput la API no garantiza
     * el orden entre la imagen y el texto, y Gemini podia contestar con la foto anterior.
     */
    fun enviarFotoYPedido(jpeg: ByteArray, texto: String, etiqueta: String = ""): Boolean =
        enviarContenido(conEtiqueta(etiqueta) + listOf(parteFoto(jpeg), mapOf("text" to texto)), turnoCompleto = true)

    /** Foto que se suma a la conversacion sin pedir respuesta (la pregunta llega hablando, o la pidio ver_foto). */
    fun enviarFotoDeContexto(jpeg: ByteArray, etiqueta: String = ""): Boolean =
        enviarContenido(conEtiqueta(etiqueta) + listOf(parteFoto(jpeg)), turnoCompleto = false)

    /** Texto que se agrega al contexto sin pedir respuesta (lo hablado antes en el modo clasico). */
    fun enviarContexto(texto: String): Boolean =
        enviarContenido(listOf(mapOf("text" to texto)), turnoCompleto = false)

    /** Respuesta a una llamada de herramienta (toolCall). */
    fun responderHerramienta(id: String, nombre: String, respuesta: Map<String, Any?>): Boolean {
        val c = canal
        if (!listo || c == null) return false
        val r = LinkedHashMap<String, Any?>()
        if (id.isNotEmpty()) r["id"] = id
        r["name"] = nombre
        r["response"] = respuesta
        return c.enviar(Json.escribir(mapOf("toolResponse" to mapOf("functionResponses" to listOf(r)))))
    }

    private fun conEtiqueta(e: String): List<Map<String, Any?>> = if (e.isEmpty()) emptyList() else listOf(mapOf("text" to e))

    private fun parteFoto(jpeg: ByteArray) =
        mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(jpeg)))

    private fun enviarContenido(partes: List<Map<String, Any?>>, turnoCompleto: Boolean): Boolean {
        val c = canal
        if (!listo || c == null) return false
        return c.enviar(Json.escribir(mapOf("clientContent" to mapOf(
            "turns" to listOf(mapOf("role" to "user", "parts" to partes)),
            "turnComplete" to turnoCompleto,
        ))))
    }

    fun enviarTexto(texto: String) = enviarEntrada(mapOf("text" to texto))

    fun empezarHablar() = enviarEntrada(mapOf("activityStart" to emptyMap<String, Any>()))
    fun terminarHablar() = enviarEntrada(mapOf("activityEnd" to emptyMap<String, Any>()))
    fun finDelAudio() = enviarEntrada(mapOf("audioStreamEnd" to true))

    private fun enviarEntrada(m: Map<String, Any?>): Boolean {
        val c = canal
        if (!listo || c == null) return false
        return c.enviar(Json.escribir(mapOf("realtimeInput" to m)))
    }

    // ------------------------------------------------------------------
    // MENSAJES DE GEMINI
    // ------------------------------------------------------------------
    private fun procesar(texto: String, listoLatch: CountDownLatch) {
        val m = try { Json.leer(texto) as? Map<*, *> } catch (e: Exception) { null } ?: return

        if (m.containsKey("setupComplete")) {
            listo = true
            listoLatch.countDown()
        }

        (m["serverContent"] as? Map<*, *>)?.let { sc ->
            (Json.ruta(sc, "inputTranscription", "text") as? String)?.let { if (it.isNotBlank()) eventos.transcripcion(true, it) }
            (Json.ruta(sc, "outputTranscription", "text") as? String)?.let { if (it.isNotBlank()) eventos.transcripcion(false, it) }
            if (sc["interrupted"] == true) eventos.interrumpido()
            (Json.ruta(sc, "modelTurn", "parts") as? List<*>)?.forEach { p ->
                val datos = Json.ruta(p, "inlineData", "data") as? String ?: return@forEach
                val mime = Json.ruta(p, "inlineData", "mimeType") as? String ?: ""
                if (mime.isEmpty() || mime.startsWith("audio/pcm")) {
                    eventos.audio(Base64.getDecoder().decode(datos), frecuenciaDe(mime))
                }
            }
            if (sc["turnComplete"] == true) eventos.turnoCompleto()
        }

        (Json.ruta(m, "toolCall", "functionCalls") as? List<*>)?.forEach { fc ->
            val f = fc as? Map<*, *> ?: return@forEach
            val nombre = f["name"] as? String ?: return@forEach
            eventos.herramienta(f["id"]?.toString().orEmpty(), nombre, f["args"] as? Map<*, *> ?: emptyMap<String, Any?>())
        }

        (m["sessionResumptionUpdate"] as? Map<*, *>)?.let { u ->
            val nuevo = u["newHandle"] as? String
            if (u["resumable"] == true && !nuevo.isNullOrEmpty()) handle = nuevo
        }

        (m["goAway"] as? Map<*, *>)?.let {
            registro.log("[LIVE] Gemini va a cerrar la conexion (${it["timeLeft"] ?: "?"}): reconecto")
            Thread({ conectar(config) }, "live-reconexion").start()
        }
    }

    private fun setup(cfg: ConfigLive): Map<String, Any?> {
        val gen = LinkedHashMap<String, Any?>()
        gen["responseModalities"] = listOf("AUDIO")
        if (cfg.voz.isNotEmpty()) {
            gen["speechConfig"] = mapOf("voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to cfg.voz)))
        }
        val vad: Map<String, Any?> = if (cfg.micAbierto) mapOf(
            "disabled" to false,
            "startOfSpeechSensitivity" to "START_SENSITIVITY_LOW",   // menos disparos por ruido
            "endOfSpeechSensitivity" to "END_SENSITIVITY_LOW",
            "prefixPaddingMs" to 200,
            "silenceDurationMs" to 700,
        ) else mapOf("disabled" to true)

        val s = LinkedHashMap<String, Any?>()
        s["model"] = "models/${cfg.modelo}"
        s["generationConfig"] = gen
        s["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to cfg.instrucciones + cfg.extra)))
        s["tools"] = cfg.herramientas
        s["realtimeInputConfig"] = mapOf("automaticActivityDetection" to vad)
        s["inputAudioTranscription"] = emptyMap<String, Any>()
        s["outputAudioTranscription"] = emptyMap<String, Any>()
        s["contextWindowCompression"] = mapOf("slidingWindow" to emptyMap<String, Any>())
        s["sessionResumption"] = if (handle.isNotEmpty()) mapOf("handle" to handle) else emptyMap<String, Any>()
        return mapOf("setup" to s)
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    companion object {
        /** "audio/pcm;rate=24000" -> 24000 (Gemini Live siempre devuelve 24 kHz). */
        fun frecuenciaDe(mime: String): Int =
            Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
    }
}
