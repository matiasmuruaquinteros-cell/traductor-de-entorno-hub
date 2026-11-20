package com.lentesinteligentes.app.nucleo

import java.util.concurrent.atomic.AtomicInteger

/**
 * MODO CONVERSACION (Gemini Live): une al ESP32 con Gemini Live.
 *
 * PROTOCOLO ESP32 <-> APP  (WebSocket en ws://celular:8000/live)
 *
 *  Binario ESP32 -> app:   [0x01] + PCM 16 bit 16 kHz mono   (microfono)
 *                          [0x02] + JPEG                      (foto)
 *  Binario app -> ESP32:   [0x11] + PCM 16 bit mono a la frecuencia de "config" (parlante)
 *
 *  Texto ESP32 -> app (JSON):
 *    {"t":"hola","v":11}                      al conectarse
 *    {"t":"hablar_ini"} / {"t":"hablar_fin"}  boton apretado / soltado (modo boton)
 *    {"t":"voz_fin"}                          modo abierto: el ESP32 dejo de oir voz
 *    {"t":"foto","pregunta":true|false}       el proximo binario 0x02 es la foto
 *    {"t":"callar"}                           el usuario quiere cortar la respuesta
 *
 *  Texto app -> ESP32 (JSON):
 *    {"t":"modo","modo":"live"|"clasico"}     si es "clasico", el ESP32 vuelve a HTTP
 *    {"t":"config","mic":"boton"|"abierto","fs":24000}
 *    {"t":"estado","listo":true|false,"error":"..."}
 *    {"t":"callar"}                           vaciar el parlante ya
 *    {"t":"fin_turno"}                        Gemini termino de hablar
 */
class PuenteLive(
    crearLive: (EventosLive) -> GeminiLive,
    private val voz: Voz,
    private val procesadorFoto: ProcesadorFoto,
    private val registro: Registro,
    private val memoria: MemoriaConversacion,
    private val ajustes: () -> Ajustes,
) : EventosLive {

    val live: GeminiLive = crearLive(this)

    var alConsultar: ((Consulta) -> Unit)? = null
    var alVerEsp32: ((String) -> Unit)? = null
    /** Lo pone el servicio: guarda los ajustes que cambio un comando de voz (sin reconectar). */
    var alCambiarAjustes: ((Ajustes) -> Unit)? = null
    /** Llamadas por voz (lo pone el servicio). null = sin llamadas. */
    @Volatile var llamadas: GestorLlamadas? = null
    @Volatile private var esperandoTurnoUsuario = true   // para contar turnos del usuario (confirmar llamadas)

    private fun llamadasActivas(a: Ajustes = ajustes()): GestorLlamadas? = llamadas?.takeIf { a.llamadas }

    /** Ubicacion por voz (lo pone el servicio). null = sin ubicacion. */
    @Volatile var ubicacion: GestorUbicacion? = null
    private fun ubicacionActiva(a: Ajustes = ajustes()): GestorUbicacion? = ubicacion?.takeIf { a.ubicacion }

    // Cambios que obligan a rehacer la sesion: se aplican cuando Gemini termina de confirmar
    private val candadoReconfig = Any()
    @Volatile private var reconfigPendiente = false
    @Volatile private var olvidoPendiente = false
    @Volatile private var marcaReconfig = 0

    @Volatile private var esp: ConexionWs? = null
    private val sesionEsp = AtomicInteger()

    // Estado del turno
    @Volatile private var hablando = false          // el usuario tiene el boton apretado
    @Volatile private var descartar = false         // ignorar el audio de Gemini hasta el proximo turno
    @Volatile private var esperandoFoto: Boolean? = null   // null = no; true/false = con pregunta o no
    @Volatile private var fotoTurno: ByteArray? = null
    @Volatile private var fotoTurnoId: Int? = null
    @Volatile private var tipoTurno = "voz"
    @Volatile private var marcaPedido = 0L          // cuando termino de hablar o mando la foto
    @Volatile private var primerAudio = 0L
    private val pregunta = StringBuilder()
    private val respuesta = StringBuilder()
    @Volatile private var bytesAudioTurno = 0L

    // =====================================================================
    // CONEXION DEL ESP32 (corre en el hilo del servidor hasta que se corta)
    // =====================================================================
    fun atenderEsp32(ws: ConexionWs) {
        val a = ajustes()
        if (!a.modoLive) {
            ws.enviarTexto(Json.escribir(mapOf("t" to "modo", "modo" to "clasico")))
            registro.log("[LIVE] el ESP32 pidio modo conversacion pero esta apagado en Ajustes")
            return
        }
        esp?.cerrar()                    // un solo ESP32 a la vez: el nuevo reemplaza al viejo
        esp = ws
        val sesion = sesionEsp.incrementAndGet()
        alVerEsp32?.invoke(ws.ip)
        registro.log("\n[LIVE] ESP32 conectado desde ${ws.ip} (mic ${if (a.micAbierto) "abierto" else "con boton"})")
        enviarEsp(mapOf("t" to "modo", "modo" to "live"))
        enviarEsp(mapOf("t" to "config", "mic" to if (a.micAbierto) "abierto" else "boton", "fs" to FS_PARLANTE))
        reiniciarTurno()

        Thread({ conectarGemini(sesion, saludar = true) }, "live-conectar").start()

        try {
            while (true) {
                val m = ws.leer() ?: break
                alVerEsp32?.invoke(ws.ip)
                if (m.binario) recibirBinario(m.datos) else recibirControl(m.texto)
            }
        } catch (e: Exception) {
            registro.log("[LIVE] ESP32 desconectado: ${e.message}")
        } finally {
            if (sesionEsp.get() == sesion) {
                esp = null
                live.cerrar()            // la conversacion queda guardada (handle) para cuando vuelva
                registro.log("[LIVE] ESP32 se fue; Gemini Live en pausa")
            }
        }
    }

    /** Ajustes cambiados en la pantalla: si el ESP32 esta en Live, se reconfigura. */
    fun ajustesCambiaron() {
        val ws = esp ?: return
        val a = ajustes()
        if (!a.modoLive) {
            enviarEsp(mapOf("t" to "modo", "modo" to "clasico"))
            ws.cerrar()
            return
        }
        enviarEsp(mapOf("t" to "config", "mic" to if (a.micAbierto) "abierto" else "boton", "fs" to FS_PARLANTE))
        val sesion = sesionEsp.get()
        Thread({ conectarGemini(sesion, saludar = false) }, "live-reconfig").start()
    }

    fun olvidar() {
        live.olvidar()
        val sesion = sesionEsp.get()
        if (esp != null) Thread({ conectarGemini(sesion, saludar = false) }, "live-olvidar").start()
    }

    val esp32Conectado: Boolean get() = esp != null

    private fun conectarGemini(sesion: Int, saludar: Boolean) {
        val a = ajustes()
        var espera = 1000L
        while (sesionEsp.get() == sesion && esp != null) {
            var ok = live.conectar(config(a))
            if (!ok && a.vozLive.isNotEmpty() && sesionEsp.get() == sesion) {
                registro.log("[LIVE] fallo con la voz ${a.vozLive}: pruebo con la voz por defecto")
                ok = live.conectar(config(a, sinVoz = true))
            }
            if (ok) {
                // Lo que Gemini no sabe todavia (lo hablado en el modo clasico, o todo si la sesion es nueva)
                val previo = if (a.memoria) memoria.pendientesParaLive(todo = !live.reanudo) else ""
                if (previo.isNotEmpty()) {
                    live.enviarContexto(previo)
                    registro.log("[LIVE] le pase a Gemini lo hablado antes (${memoria.resumenContadores()})")
                }
                enviarEsp(mapOf("t" to "estado", "listo" to true))
                if (saludar) decir(SALUDO)
                return
            }
            registro.log("[LIVE] no pude conectar con Gemini: ${live.ultimoError}")
            enviarEsp(mapOf("t" to "estado", "listo" to false, "error" to live.ultimoError))
            if (saludar) { decir(errorHablado(live.ultimoError)); return }
            Thread.sleep(espera)
            espera = (espera * 2).coerceAtMost(15_000)
        }
    }

    private fun config(a: Ajustes, sinVoz: Boolean = false): ConfigLive {
        val tel = llamadasActivas(a)
        val ubic = ubicacionActiva(a)
        return ConfigLive(
            modelo = a.modeloLive.ifBlank { ConfigLive.MODELO_POR_DEFECTO },
            micAbierto = a.micAbierto,
            voz = if (sinVoz) "" else a.vozLive,
            extra = Comandos.instrucciones(a) + (if (tel != null) HerramientasLlamadas.INSTRUCCIONES else "") +
                (if (ubic != null) HerramientasUbicacion.INSTRUCCIONES else ""),
            herramientas = Comandos.herramientas(tel != null, ubic != null),
        )
    }

    // =====================================================================
    // DEL ESP32 HACIA GEMINI
    // =====================================================================
    private fun recibirControl(texto: String) {
        val m = try { Json.leer(texto) as? Map<*, *> } catch (_: Exception) { null } ?: return
        when (m["t"]) {
            "hola" -> registro.log("[LIVE] firmware v${m["v"] ?: "?"}")
            "hablar_ini" -> {
                llamadas?.nuevoTurno()
                esperandoTurnoUsuario = false
                hablando = true
                descartar = true
                enviarEsp(mapOf("t" to "callar"))          // si Gemini estaba hablando, se calla ya
                cerrarTurnoSiHayAlgo()
                if (!live.listo) { avisarSinConexion(); return }   // la reconexion sigue en otro hilo
                live.empezarHablar()
            }
            "hablar_fin" -> {
                if (!hablando) return
                hablando = false
                descartar = false
                marcaPedido = System.currentTimeMillis()
                primerAudio = 0L
                live.terminarHablar()
            }
            "voz_fin" -> live.finDelAudio()
            "foto" -> {
                esperandoFoto = m["pregunta"] == true
            }
            "callar" -> {
                descartar = true
                enviarEsp(mapOf("t" to "callar"))
                cerrarTurnoSiHayAlgo()
            }
        }
    }

    private fun recibirBinario(b: ByteArray) {
        if (b.isEmpty()) return
        val datos = b.copyOfRange(1, b.size)
        when (b[0].toInt()) {
            0x01 -> live.enviarAudio(datos)
            0x02 -> recibirFoto(datos, esperandoFoto ?: false)
        }
    }

    private fun recibirFoto(jpeg: ByteArray, conPregunta: Boolean) {
        esperandoFoto = null
        val a = ajustes()
        if (!conPregunta) {
            // Atajo: toque corto de FOTO mientras suena una llamada = rechazarla
            llamadasActivas(a)?.rechazoRapido()?.let { frase ->
                descartar = true
                enviarEsp(mapOf("t" to "callar"))
                cerrarTurnoSiHayAlgo()
                decir(frase)
                return
            }
        }
        val foto = try { procesadorFoto.procesar(jpeg, a) } catch (e: Exception) { jpeg }
        if (!conPregunta) {
            descartar = true                                // corta lo que estuviera diciendo
            enviarEsp(mapOf("t" to "callar"))
            cerrarTurnoSiHayAlgo()
        }
        if (!live.listo) { avisarSinConexion(); return }
        val guardada = if (a.memoria) memoria.nuevaFoto(foto) else null
        val etiqueta = guardada?.let { ConfigLive.etiquetaFoto(it.id, it.hora) } ?: ""
        fotoTurno = foto
        fotoTurnoId = guardada?.id
        tipoTurno = if (conPregunta) "foto+voz" else "foto"
        registro.log("[LIVE] ${guardada?.let { "FOTO ${it.id}" } ?: "foto"} enviada (${foto.size / 1024} KB)" +
                if (conPregunta) " junto con la pregunta" else "")
        if (conPregunta) {
            live.enviarFotoDeContexto(foto, etiqueta)         // la pregunta llega hablando, despues
        } else {
            descartar = false
            marcaPedido = System.currentTimeMillis()
            primerAudio = 0L
            live.enviarFotoYPedido(foto, ConfigLive.PEDIDO_DESCRIBIR, etiqueta)   // foto y pedido en el mismo turno
        }
    }

    // =====================================================================
    // DE GEMINI HACIA EL ESP32
    // =====================================================================
    override fun audio(pcm: ByteArray, frecuencia: Int) {
        if (descartar || hablando) return
        if (primerAudio == 0L) {
            primerAudio = System.currentTimeMillis()
            if (marcaPedido > 0) registro.log("[LIVE] primera voz a los %.2f s".format((primerAudio - marcaPedido) / 1000.0))
        }
        bytesAudioTurno += pcm.size
        val salida = prepararAudio(pcm, frecuencia, ajustes().volumen)
        var i = 0
        while (i < salida.size) {
            val n = minOf(TROZO_BYTES, salida.size - i)
            val marco = ByteArray(n + 1)
            marco[0] = 0x11
            System.arraycopy(salida, i, marco, 1, n)
            if (esp?.enviarBinario(marco) != true) return
            i += n
        }
    }

    override fun interrumpido() {
        enviarEsp(mapOf("t" to "callar"))
    }

    override fun transcripcion(delUsuario: Boolean, texto: String) {
        if (delUsuario && esperandoTurnoUsuario) {        // el usuario empezo a hablar despues de una respuesta
            esperandoTurnoUsuario = false
            llamadas?.nuevoTurno()
        }
        synchronized(this) { (if (delUsuario) pregunta else respuesta).append(texto) }
    }

    override fun turnoCompleto() {
        esperandoTurnoUsuario = true
        enviarEsp(mapOf("t" to "fin_turno"))
        if (!descartar) cerrarTurnoSiHayAlgo()
        descartar = false
        if (reconfigPendiente) {
            // se deja terminar de sonar la confirmacion antes de rehacer la sesion
            Thread({ Thread.sleep(2500); aplicarReconfig() }, "live-reconfig-voz").start()
        }
    }

    // =====================================================================
    // COMANDOS POR VOZ (herramientas que llama Gemini)
    // =====================================================================
    override fun herramienta(id: String, nombre: String, args: Map<*, *>) {
        when (nombre) {
            Comandos.CAMBIAR_AJUSTE -> {
                val r = Comandos.aplicar(ajustes(), args, enLive = true)
                registro.log("[LIVE] [COMANDO] cambiar_ajuste $args -> ${r.frase}")
                if (r.cambio) alCambiarAjustes?.invoke(r.ajustes)
                if (r.reconectarLive) programarReconfig()
                live.responderHerramienta(id, nombre, mapOf("resultado" to r.frase))
            }
            Comandos.OLVIDAR -> {
                memoria.olvidar("pedido por voz")
                olvidoPendiente = true
                programarReconfig()
                live.responderHerramienta(id, nombre, mapOf("resultado" to "Conversacion borrada. Confirmale que empiezan de cero."))
            }
            Comandos.VER_FOTO -> {
                val n = (args["numero"] as? Number)?.toInt() ?: args["numero"]?.toString()?.toIntOrNull() ?: 0
                val f = memoria.foto(n)?.takeIf { it.jpeg.isNotEmpty() }
                registro.log("[LIVE] Gemini pidio la FOTO $n: ${if (f != null) "se la mando" else "no esta"}")
                if (f != null) live.enviarFotoDeContexto(f.jpeg, "FOTO ${f.id} (${f.hora}), la que pediste con ver_foto:")
                live.responderHerramienta(id, nombre,
                    if (f != null) mapOf("ok" to true, "nota" to "La FOTO $n se agrego a la conversacion. Usala para responder.")
                    else mapOf("ok" to false, "error" to "La FOTO $n no esta guardada."))
            }
            in HerramientasLlamadas.NOMBRES -> {
                val tel = llamadasActivas()
                val r = tel?.ejecutar(nombre, args)
                    ?: GestorLlamadas.Resultado("Las llamadas estan desactivadas en la app.", mapOf("error" to "desactivadas"))
                registro.log("[LIVE] [LLAMADAS] $nombre -> ${r.frase}")
                live.responderHerramienta(id, nombre, r.paraIa +
                    ("instruccion" to "Decile al usuario esto, con tus palabras pero sin cambiar el sentido: ${r.frase}"))
            }
            in HerramientasUbicacion.NOMBRES -> {
                // GPS + mapas tardan unos segundos: en otro hilo, para no frenar el audio de Gemini
                val u = ubicacionActiva()
                Thread({
                    val r = u?.ejecutar(nombre, args)
                        ?: GestorUbicacion.Resultado("La ubicacion esta desactivada en la app.", mapOf("error" to "desactivada"))
                    registro.log("[LIVE] [UBICACION] $nombre -> ${r.frase}")
                    live.responderHerramienta(id, nombre, r.paraIa +
                        ("instruccion" to "Decile al usuario esto, sin cambiar nombres, calles ni distancias: ${r.frase}"))
                }, "live-ubicacion").start()
            }
            else -> live.responderHerramienta(id, nombre, mapOf("error" to "herramienta desconocida"))
        }
    }

    // =====================================================================
    // LLAMADAS: avisos que vienen del telefono
    // =====================================================================
    /** Avisa una llamada entrante por el parlante del dispositivo. false = no hay ESP32 en modo conversacion. */
    fun avisarLlamada(frase: String, contextoIa: String): Boolean {
        if (esp == null || !ajustes().modoLive) return false
        descartar = true                                   // corta lo que Gemini estuviera diciendo
        enviarEsp(mapOf("t" to "callar"))
        cerrarTurnoSiHayAlgo()
        decir(frase)
        if (live.listo) live.enviarContexto(contextoIa)
        registro.log("[LIVE] aviso de llamada por el parlante del dispositivo")
        return true
    }

    /** El estado del telefono cambio (atendida, terminada): Gemini se entera sin contestar nada. */
    fun contextoTelefono(texto: String) {
        if (esp != null && live.listo) live.enviarContexto(texto)
    }

    /** Modo, microfono, voz u olvidar: se aplica al terminar la confirmacion (o a los 10 s, por las dudas). */
    private fun programarReconfig() {
        reconfigPendiente = true
        val marca = ++marcaReconfig
        Thread({ Thread.sleep(10_000); if (marca == marcaReconfig) aplicarReconfig() }, "live-reconfig-espera").start()
    }

    private fun aplicarReconfig() = synchronized(candadoReconfig) {
        if (!reconfigPendiente) return@synchronized
        reconfigPendiente = false
        if (olvidoPendiente) { olvidoPendiente = false; live.olvidar() }
        registro.log("[LIVE] aplico el cambio pedido por voz")
        ajustesCambiaron()
    }

    override fun desconectado(motivo: String) {
        enviarEsp(mapOf("t" to "estado", "listo" to false, "error" to motivo))
        val sesion = sesionEsp.get()
        if (esp != null) Thread({ conectarGemini(sesion, saludar = false) }, "live-reconexion").start()
    }

    // =====================================================================
    // AYUDAS
    // =====================================================================
    private fun cerrarTurnoSiHayAlgo() {
        val p: String; val r: String
        synchronized(this) {
            p = Asistente.limpiarTexto(pregunta.toString())
            r = Asistente.limpiarTexto(respuesta.toString())
            pregunta.setLength(0); respuesta.setLength(0)
        }
        if (p.isNotEmpty() || r.isNotEmpty()) {
            val a = ajustes()
            if (a.memoria) {
                val preg = p.ifEmpty { fotoTurnoId?.let { "(saco la FOTO $it y pidio que la describas)" } ?: "" }
                memoria.agregarTurno(preg, r, fotoTurnoId, "live")
                memoria.marcarVistoPorLive()
            }
            val lat = if (primerAudio > 0 && marcaPedido > 0) (primerAudio - marcaPedido) / 1000.0 else 0.0
            val dur = bytesAudioTurno / 2.0 / 24000.0
            registro.log("[LIVE] PREGUNTA: ${p.ifEmpty { "(foto)" }}")
            registro.log("[LIVE] RESPUESTA: $r")
            alConsultar?.invoke(Consulta(tipoTurno, p, r, fotoTurno, lat, dur))
        }
        reiniciarTurno()
    }

    private fun reiniciarTurno() {
        fotoTurno = null
        fotoTurnoId = null
        tipoTurno = "voz"
        bytesAudioTurno = 0
        marcaPedido = 0
        primerAudio = 0
    }

    private fun enviarEsp(m: Map<String, Any?>) { esp?.enviarTexto(Json.escribir(m)) }

    private fun avisarSinConexion() {
        registro.log("[LIVE] sin conexion con Gemini: ${live.ultimoError}")
        decir(errorHablado(live.ultimoError))
    }

    /** Frase con la voz del celular (Android TTS), mandada al parlante del ESP32. */
    private fun decir(texto: String) {
        try {
            val a = ajustes()
            val wav = voz.sintetizar(texto, a.copy(frecuenciaSalida = FS_PARLANTE))
            val pcm = Audio.leerWav(wav)
            val b = ByteArray(pcm.muestras.size * 2)
            for (i in pcm.muestras.indices) {
                val v = pcm.muestras[i].toInt()
                b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
            descartar = false
            audio(b, pcm.frecuencia)
            enviarEsp(mapOf("t" to "fin_turno"))
        } catch (e: Exception) {
            registro.log("[LIVE] no pude generar la voz: ${e.message}")
        }
    }

    companion object {
        const val FS_PARLANTE = 24000
        private const val TROZO_BYTES = 4800        // 100 ms a 24 kHz
        const val SALUDO = "Modo conversacion listo."

        fun errorHablado(error: String): String = when {
            "clave" in error.lowercase() || "API key" in error || "1008" in error ->
                "No me pude conectar con Gemini. Revisa la clave en la aplicacion."
            else -> "No me pude conectar con Gemini. Revisa la conexion a internet del celular."
        }

        /**
         * Ajusta el audio de Gemini para el parlante: cambia la frecuencia si hace falta
         * y aplica el volumen (solo atenua, nunca sube: asi no satura el amplificador).
         */
        fun prepararAudio(pcm: ByteArray, frecuencia: Int, volumen: Double): ByteArray {
            val n = pcm.size / 2
            var m = ShortArray(n) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }
            if (frecuencia != FS_PARLANTE) m = Audio.remuestrear(Pcm(m, frecuencia), FS_PARLANTE).muestras
            val g = volumen.coerceIn(0.05, 1.0)
            val out = ByteArray(m.size * 2)
            for (i in m.indices) {
                val v = (m[i] * g).toInt().coerceIn(-32768, 32767)
                out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }
    }
}
