package com.lentesinteligentes.app.nucleo

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * La logica del servidor de Python, puerto por puerto:
 *   GET  /bienvenida       saludo al encender el ESP32
 *   POST /upload_imagen    toque corto del boton FOTO: describe la foto
 *   POST /upload_contexto  pulsacion larga del boton FOTO: guarda la foto de la sesion
 *   POST /upload_audio     X-Modo voz (boton VOZ) o foto (pregunta sobre la foto)
 *   GET  /ping             el ESP32 lo usa para encontrar el celular
 *   GET  /probar_audio     frase de prueba
 *   GET  /, /ultima_foto, /ultima_foto_original   diagnostico desde un navegador
 */
class Asistente(
    val gemini: Gemini,
    private val voz: Voz,
    private val procesadorFoto: ProcesadorFoto,
    private val registro: Registro,
    private val memoria: MemoriaConversacion,
    private val ajustes: () -> Ajustes,
) {
    /** Avisos para la pantalla. */
    var alConsultar: ((Consulta) -> Unit)? = null
    var alVerEsp32: ((String) -> Unit)? = null

    @Volatile var ultimaFoto: ByteArray? = null            // la que ve Gemini (mejorada)
        private set
    @Volatile var ultimaFotoOriginal: ByteArray? = null    // la que mando la camara
        private set
    @Volatile private var ultimaFotoT = 0L
    @Volatile var ultimoTexto = ""
        private set
    @Volatile var ultimaPregunta = ""
        private set
    val contadorFotos = AtomicInteger()
    val contadorAudios = AtomicInteger()

    @Volatile private var bienvenidaCache: ByteArray? = null
    @Volatile private var bienvenidaClave = ""

    private class Sesion {
        @Volatile var foto: ByteArray? = null
        val llego = CountDownLatch(1)
        val creada = System.currentTimeMillis()
    }
    private val sesiones = ConcurrentHashMap<String, Sesion>()

    // =====================================================================
    // MEMORIA DE LA CONVERSACION
    //  Toda la charla (texto + fotos) vive en `memoria`, compartida con el modo
    //  conversacion. A Gemini va todo el texto y el indice de fotos; la imagen de una
    //  foto vieja solo si la pide con ver_foto.
    // =====================================================================
    /** Lo pone el servicio: guarda los ajustes que cambio un comando de voz. */
    var alCambiarAjustes: ((Ajustes) -> Unit)? = null
    @Volatile var maxTurnosEnviados = 200

    /** Llamadas por voz (lo pone el servicio). null = sin llamadas. */
    @Volatile var llamadas: GestorLlamadas? = null

    private fun llamadasActivas(a: Ajustes): GestorLlamadas? = llamadas?.takeIf { a.llamadas }

    /** Ubicacion por voz (lo pone el servicio). null = sin ubicacion. */
    @Volatile var ubicacion: GestorUbicacion? = null

    private fun ubicacionActiva(a: Ajustes): GestorUbicacion? = ubicacion?.takeIf { a.ubicacion }

    fun olvidar() = memoria.olvidar("boton de la app")

    // =====================================================================
    // RUTAS
    // =====================================================================
    fun manejar(p: Peticion): Respuesta {
        if (p.ruta != "/" && !p.ruta.startsWith("/ultima_foto")) alVerEsp32?.invoke(p.ipCliente)
        return when ("${p.metodo} ${p.ruta}") {
            "GET /ping" -> Respuesta.texto("ok lentes")
            "GET /bienvenida" -> bienvenida()
            "POST /upload_imagen" -> uploadImagen(p)
            "POST /upload_contexto" -> uploadContexto(p)
            "POST /upload_audio" -> uploadAudio(p)
            "GET /probar_audio" -> probarAudio()
            "GET /ultima_foto" -> ultimaFoto?.let { Respuesta.jpeg(it) } ?: Respuesta.vacia(404)
            "GET /ultima_foto_original" -> ultimaFotoOriginal?.let { Respuesta.jpeg(it) } ?: Respuesta.vacia(404)
            "GET /" -> Respuesta.html(paginaInicio())
            else -> Respuesta.vacia(404)
        }
    }

    // =====================================================================
    // BIENVENIDA
    // =====================================================================
    private fun bienvenida(): Respuesta {
        val a = ajustes()
        registro.log("\n[${hora()}] Bienvenida pedida (el ESP32 se encendio o se reinicio)")
        if (memoria.hayAlgo()) memoria.olvidar("el dispositivo se reinicio")
        val clave = "${a.motorVoz}|${a.nombreVoz}|${a.velocidadVoz}|${a.tonoVoz}|${a.volumen}|${a.saludoIa}"
        bienvenidaCache?.let { if (clave == bienvenidaClave) { registro.log("   [BIENVENIDA] desde la memoria"); return Respuesta.wav(it) } }

        val texto = if (a.saludoIa) {
            try {
                limpiarTexto(gemini.textoDe(gemini.generar(payloadSaludo(), 15)))
            } catch (e: Exception) {
                registro.log("   [AVISO] Gemini no respondio el saludo (${e.message}); uso el fijo")
                SALUDO_FIJO
            }
        } else SALUDO_FIJO
        ultimoTexto = texto
        registro.log("   [BIENVENIDA] $texto")
        return try {
            val wav = sintetizar(texto, a)
            bienvenidaCache = wav
            bienvenidaClave = clave
            alConsultar?.invoke(Consulta("bienvenida", "", texto, null, 0.0, 0.0))
            Respuesta.wav(wav)
        } catch (e: Exception) {
            registro.log("   [ERROR bienvenida] ${e.message}")
            Respuesta.vacia(500)
        }
    }

    // =====================================================================
    // TOQUE CORTO: FOTO
    // =====================================================================
    private fun uploadImagen(p: Peticion): Respuesta {
        val t0 = System.nanoTime()
        if (p.cuerpo.isEmpty()) { registro.log("[ERROR] /upload_imagen vacio"); return Respuesta.vacia(400) }
        val a = ajustes()
        // Atajo: toque corto de FOTO mientras suena una llamada = rechazarla (sin IA ni internet)
        llamadasActivas(a)?.rechazoRapido()?.let { frase ->
            return try { Respuesta.wav(sintetizar(frase, a)) } catch (e: Exception) { Respuesta.vacia(500) }
        }
        val n = contadorFotos.incrementAndGet()
        registro.log("\n[${hora()}] FOTO #$n (toque corto): ${p.cuerpo.size} bytes desde ${p.ipCliente}")
        val foto = prepararFoto(p.cuerpo, a)
        return try {
            val tg = System.nanoTime()
            val texto = limpiarTexto(gemini.textoDe(gemini.generar(payloadImagen(foto))))
            val segG = seg(tg)
            ultimoTexto = texto
            registro.log("   [GEMINI foto] (${"%.1f".format(segG)} s) $texto")
            val wav = sintetizar(texto, a)
            if (a.memoria) {
                val f = memoria.nuevaFoto(foto)
                memoria.agregarTurno("(saco la FOTO ${f.id} y pidio que la describas)", texto, f.id, "clasico")
            }
            avisarTiempo(wav.size, seg(t0))
            alConsultar?.invoke(Consulta("foto", "", texto, foto, segG, seg(t0)))
            Respuesta.wav(wav)
        } catch (e: Exception) {
            registro.log("   [ERROR foto] ${e.message}")
            wavDeError(e, a)
        }
    }

    // =====================================================================
    // PULSACION LARGA: FOTO DE CONTEXTO
    // =====================================================================
    private fun uploadContexto(p: Peticion): Respuesta {
        if (p.cuerpo.isEmpty()) return Respuesta.vacia(400)
        contadorFotos.incrementAndGet()
        val foto = prepararFoto(p.cuerpo, ajustes())
        val sid = p.encabezado("x-sesion")
        if (sid.isNotEmpty()) {
            val s = obtenerSesion(sid)
            s.foto = foto
            s.llego.countDown()
        }
        registro.log("\n[${hora()}] FOTO DE CONTEXTO guardada (${p.cuerpo.size} bytes) sesion=${sid.ifEmpty { "-" }}")
        return Respuesta.texto("ok")
    }

    // =====================================================================
    // AUDIO: BOTON VOZ o BOTON FOTO (pulsacion larga)
    // =====================================================================
    private fun uploadAudio(p: Peticion): Respuesta {
        val t0 = System.nanoTime()
        val wav = p.cuerpo
        if (wav.isEmpty()) { registro.log("[ERROR] /upload_audio vacio"); return Respuesta.vacia(400) }
        val n = contadorAudios.incrementAndGet()
        val a = ajustes()
        val tel = llamadasActivas(a)
        tel?.nuevoTurno()                                    // para la confirmacion de llamadas

        val sid = p.encabezado("x-sesion")
        val hayFoto = p.encabezados["x-foto"]               // "1", "0" o null
        var modo = p.encabezado("x-modo").lowercase()
        val fotoReciente = ultimaFoto != null && System.currentTimeMillis() - ultimaFotoT < FOTO_VIEJA_MS
        if (modo != "voz" && modo != "foto") {               // firmware viejo, sin X-Modo
            modo = if (sid.isNotEmpty()) (if (hayFoto == "1") "foto" else "voz")
                   else (if (fotoReciente) "foto" else "voz")
        }

        // Foto de ESTA pulsacion (nunca una vieja), solo en modo foto
        var foto: ByteArray? = null
        if (modo == "foto") {
            if (sid.isNotEmpty()) {
                if (hayFoto != "0") {
                    val s = obtenerSesion(sid)
                    if (s.foto == null && !s.llego.await(ESPERA_FOTO_MS, TimeUnit.MILLISECONDS)) {
                        registro.log("   [AVISO] la foto de esta sesion no llego a tiempo")
                    }
                    foto = s.foto
                }
            } else if (fotoReciente) {
                foto = ultimaFoto
            }
        }

        val segAudio = try { Audio.leerWav(wav).segundos } catch (_: Exception) { 0.0 }
        val nombre = if (modo == "foto") "BOTON FOTO (pregunta sobre la foto)" else "BOTON VOZ"
        registro.log("\n[${hora()}] AUDIO #$n | $nombre | ${"%.1f".format(segAudio)} s | " +
                "sesion=${sid.ifEmpty { "-" }} | foto: ${if (foto != null) "SI" else "NO"}")

        try {
            val (pico, rms) = Audio.nivelWav(wav)
            val aviso = if (pico < 3) "  <- MUY BAJO, revisar microfono" else ""
            registro.log("   [MIC] pico ${"%.0f".format(pico)}% | rms ${"%.1f".format(rms)}%$aviso")
        } catch (e: Exception) {
            registro.log("   [MIC] no pude medir el nivel: ${e.message}")
        }

        var audioGemini = wav
        if (a.normalizarMic) {
            try { audioGemini = Audio.limpiarMicrofono(wav) }
            catch (e: Exception) { registro.log("   [MIC] no se pudo limpiar, mando el original (${e.message})") }
        }

        // Memoria: toda la conversacion en texto + la foto de esta consulta (o la recien sacada)
        val turnosPrevios = if (a.memoria) memoria.turnos().takeLast(maxTurnosEnviados) else emptyList()
        val fotoNueva = if (a.memoria && modo == "foto" && foto != null) memoria.nuevaFoto(foto) else null
        val previa = if (a.memoria && modo == "voz") memoria.fotoReciente(2) else null
        val indice = if (a.memoria) memoria.indiceFotos() else ""
        if (turnosPrevios.isNotEmpty() || previa != null) {
            registro.log("   [MEMORIA] ${turnosPrevios.size} turno(s) previos | ${memoria.resumenContadores()} | " +
                    "foto reciente: ${previa?.let { "FOTO ${it.id}" } ?: "NO"}")
        }

        return try {
            val tg = System.nanoTime()
            val pedido = payloadAudio(audioGemini, foto, fotoNueva?.id, modo, turnosPrevios, previa, indice, a)
            val datos = gemini.generar(pedido)
            var (preg, resp) = separarPregunta(gemini.textoOpcional(datos))
            var aVoz = a
            var olvido = false
            val llamadas = gemini.llamadasDe(datos)
            if (llamadas.isNotEmpty()) {
                val frases = ArrayList<String>()
                val respuestas = ArrayList<Pair<String, Map<String, Any?>>>()
                var pedida: FotoGuardada? = null
                for ((nombre, args) in llamadas) {
                    when (nombre) {
                        Comandos.CAMBIAR_AJUSTE -> {
                            val r = Comandos.aplicar(aVoz, args, enLive = false)
                            registro.log("   [COMANDO] cambiar_ajuste $args -> ${r.frase}")
                            if (r.cambio) aVoz = r.ajustes
                            frases += r.frase
                            respuestas += Pair(nombre, mapOf("resultado" to r.frase))
                        }
                        Comandos.OLVIDAR -> {
                            memoria.olvidar("pedido por voz")
                            olvido = true
                            frases += "Listo, borre la conversacion y empezamos de cero."
                            respuestas += Pair(nombre, mapOf("resultado" to "conversacion borrada"))
                        }
                        Comandos.VER_FOTO -> {
                            val id = (args["numero"] as? Number)?.toInt() ?: args["numero"]?.toString()?.toIntOrNull() ?: 0
                            pedida = memoria.foto(id)?.takeIf { it.jpeg.isNotEmpty() }
                            registro.log("   [MEMORIA] Gemini pidio la FOTO $id: ${if (pedida != null) "se la mando" else "no esta"}")
                            respuestas += Pair(nombre, if (pedida != null) mapOf("ok" to true, "foto" to id,
                                "nota" to "La imagen de la FOTO $id viene a continuacion.")
                                else mapOf("ok" to false, "error" to "La FOTO $id no esta guardada."))
                        }
                        in HerramientasLlamadas.NOMBRES -> {
                            val r = tel?.ejecutar(nombre, args)
                                ?: GestorLlamadas.Resultado("Las llamadas estan desactivadas en la app.", mapOf("error" to "desactivadas"))
                            frases += r.frase
                            respuestas += Pair(nombre, r.paraIa)
                        }
                        in HerramientasUbicacion.NOMBRES -> {
                            val r = ubicacionActiva(a)?.ejecutar(nombre, args)
                                ?: GestorUbicacion.Resultado("La ubicacion esta desactivada en la app.", mapOf("error" to "desactivada"))
                            frases += r.frase
                            respuestas += Pair(nombre, r.paraIa)
                        }
                        else -> respuestas += Pair(nombre, mapOf("error" to "herramienta desconocida"))
                    }
                }
                if (aVoz != a) alCambiarAjustes?.invoke(aVoz)
                if (llamadas.any { it.first == Comandos.VER_FOTO }) {
                    // Segunda vuelta: Gemini mira la foto que pidio y contesta
                    val datos2 = segundaVuelta(pedido, datos, respuestas, pedida)
                    val (p2, r2) = separarPregunta(gemini.textoDe(datos2))
                    if (p2.isNotEmpty()) preg = p2
                    resp = r2
                } else {
                    resp = frases.joinToString(" ")      // confirmacion local: sin esperar otra respuesta
                    if (preg.isEmpty()) preg = "(pedido)"
                }
            }
            val pregunta = limpiarTexto(preg)
            val texto = limpiarTexto(resp)
            if (texto.isEmpty()) throw ErrorGemini("respuesta vacia")
            val segG = seg(tg)
            ultimaPregunta = pregunta
            ultimoTexto = texto
            registro.log("   [PREGUNTA] ${pregunta.ifEmpty { "(el modelo no la transcribio)" }}")
            registro.log("   [GEMINI audio] (${"%.1f".format(segG)} s) $texto")
            val salida = sintetizar(texto, aVoz)              // con el volumen/velocidad nuevos si cambiaron
            if (aVoz.memoria && !olvido) memoria.agregarTurno(pregunta, texto, fotoNueva?.id, "clasico")
            avisarTiempo(salida.size, seg(t0))
            alConsultar?.invoke(Consulta(if (modo == "foto") "foto+voz" else "voz", pregunta, texto, foto, segG, seg(t0)))
            Respuesta.wav(salida)
        } catch (e: Exception) {
            registro.log("   [ERROR audio] ${e.message}")
            wavDeError(e, a)
        }
    }

    /** Devuelve a Gemini el resultado de las herramientas y, si pidio una foto, la imagen. */
    private fun segundaVuelta(
        pedido: Map<String, Any?>, datos: Map<String, Any?>,
        respuestas: List<Pair<String, Map<String, Any?>>>, foto: FotoGuardada?,
    ): Map<String, Any?> {
        val previos = (pedido["contents"] as? List<*>).orEmpty()
        val modelo = gemini.contenidoModelo(datos)
        val partesResp = respuestas.map { (n, r) -> mapOf("functionResponse" to mapOf("name" to n, "response" to r)) }
        val partesFoto = if (foto == null) emptyList() else listOf(
            mapOf("text" to "FOTO ${foto.id} (${foto.hora}):"),
            mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(foto.jpeg))),
        )
        fun con(contenidos: List<Any?>) = pedido.toMutableMap().also { it["contents"] = contenidos }
        return try {
            gemini.generar(con(previos + listOf(modelo, mapOf("role" to "user", "parts" to partesResp + partesFoto))))
        } catch (e: ErrorGemini) {
            if (e.codigo != 400 || foto == null) throw e
            // Por si la API no acepta la imagen junto a la respuesta de la herramienta: va en un mensaje aparte
            registro.log("   [AVISO] la foto junto a la herramienta dio error 400; la mando aparte")
            gemini.generar(con(previos + listOf(modelo, mapOf("role" to "user", "parts" to partesResp),
                mapOf("role" to "user", "parts" to partesFoto))))
        }
    }

    private fun probarAudio(): Respuesta = try {
        val wav = sintetizar("Prueba de sonido. Si escuchas esta frase, el parlante funciona correctamente.", ajustes())
        registro.log("   [PRUEBA] audio de prueba enviado")
        Respuesta.wav(wav)
    } catch (e: Exception) {
        registro.log("   [ERROR] prueba de audio: ${e.message}")
        Respuesta.vacia(500)
    }

    // =====================================================================
    // AUXILIARES
    // =====================================================================
    private fun obtenerSesion(sid: String): Sesion {
        val ahora = System.currentTimeMillis()
        sesiones.entries.removeIf { ahora - it.value.creada > VIDA_SESION_MS }
        return sesiones.computeIfAbsent(sid) { Sesion() }
    }

    private fun prepararFoto(original: ByteArray, a: Ajustes): ByteArray {
        val t0 = System.nanoTime()
        val foto = procesadorFoto.procesar(original, a)
        ultimaFotoOriginal = original
        ultimaFoto = foto
        ultimaFotoT = System.currentTimeMillis()
        if (foto !== original) registro.log("   [FOTO] procesada en ${"%.0f".format(seg(t0) * 1000)} ms")
        return foto
    }

    private fun sintetizar(texto: String, a: Ajustes): ByteArray {
        val t0 = System.nanoTime()
        val wav = voz.sintetizar(texto, a)
        val dur = (wav.size - 44) / (a.frecuenciaSalida * 2.0)
        registro.log("   [VOZ] ${wav.size} bytes | ${"%.1f".format(dur)} s de audio | generada en ${"%.2f".format(seg(t0))} s")
        return wav
    }

    /** Si algo falla, el usuario escucha que paso en vez de silencio. */
    private fun wavDeError(e: Exception, a: Ajustes): Respuesta {
        val msg = e.message.orEmpty()
        val frase = when {
            e is ErrorGemini && (e.codigo == 400 || e.codigo == 403) && msg.contains("API key", ignoreCase = true) ->
                "La clave de Gemini no es valida. Revisala en la aplicacion del celular."
            e is ErrorGemini && e.codigo == 429 -> "Google esta saturado. Proba de nuevo en un momento."
            msg.startsWith("Sin conexion") -> "El celular no tiene conexion a internet. Revisa los datos moviles."
            else -> FRASE_ERROR
        }
        return try { Respuesta.wav(sintetizar(frase, a)) } catch (e2: Exception) {
            registro.log("   [ERROR] tampoco se pudo generar el aviso de error: ${e2.message}")
            Respuesta.vacia(500)
        }
    }

    private fun avisarTiempo(bytes: Int, total: Double) {
        registro.log("   [ENVIO] WAV $bytes bytes. Total ${"%.1f".format(total)} s")
        if (total > LIMITE_ESP32_S * 0.8) {
            registro.log("   [AVISO] Tardo ${"%.0f".format(total)} s; el ESP32 corta a los $LIMITE_ESP32_S s (error -11).")
        }
    }

    private fun seg(t0: Long) = (System.nanoTime() - t0) / 1e9
    private fun hora() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    // =====================================================================
    // PEDIDOS A GEMINI
    // =====================================================================
    private fun payloadSaludo(): Map<String, Any?> = mapOf(
        "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to
            "Eres el asistente de voz de un traductor de entorno para personas ciegas: un dispositivo " +
            "con una camara en el pecho. Acabas de encenderte. Presentate brevemente en dos oraciones calidas y naturales. " +
            "Explica que con un toque corto del boton de la camara describes lo que hay enfrente, " +
            "que manteniendolo apretado te pueden preguntar sobre eso, y que con el otro boton " +
            "te pueden hacer cualquier pregunta. Sin asteriscos ni simbolos.")))),
        "generationConfig" to mapOf("temperature" to 0.5, "maxOutputTokens" to 120),
    )

    private fun payloadImagen(jpeg: ByteArray): Map<String, Any?> = mapOf(
        "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to INSTRUCCIONES_IMAGEN))),
        "contents" to listOf(mapOf("role" to "user", "parts" to listOf(
            mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(jpeg))),
            mapOf("text" to "Que tengo frente a mi?"),
        ))),
        "generationConfig" to mapOf("temperature" to 0.4, "maxOutputTokens" to 200),
    )

    private fun payloadAudio(
        wav: ByteArray, foto: ByteArray?, fotoId: Int?, modo: String,
        turnosPrevios: List<TurnoMemoria>, previa: FotoGuardada?, indiceFotos: String, a: Ajustes,
    ): Map<String, Any?> {
        val contenidos = ArrayList<Map<String, Any?>>()
        // Toda la conversacion anterior, en texto. La respuesta del modelo conserva el formato
        // PREGUNTA/RESPUESTA para que siga respetandolo.
        for (t in turnosPrevios) {
            val conFoto = if (t.fotoId != null) " (con la FOTO ${t.fotoId})" else ""
            contenidos += mapOf("role" to "user", "parts" to listOf(mapOf("text" to "[${t.hora}] Pregunta del usuario$conFoto: ${t.pregunta}")))
            contenidos += mapOf("role" to "model", "parts" to listOf(mapOf("text" to "PREGUNTA: ${t.pregunta} RESPUESTA: ${t.respuesta}")))
        }

        val partes = ArrayList<Map<String, Any?>>()
        var instrucciones: String
        if (modo == "foto") {
            instrucciones = INSTRUCCIONES_FOTO_VOZ
            partes += mapOf("text" to "Pregunta del usuario sobre la foto:")
            partes += mapOf("inlineData" to mapOf("mimeType" to "audio/wav", "data" to b64(wav)))
            if (foto != null) {
                partes += mapOf("text" to "Foto que acaba de sacar" + (if (fotoId != null) " (FOTO $fotoId):" else ":"))
                partes += mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(foto)))
            } else {
                partes += mapOf("text" to "La foto no llego en esta consulta.")
            }
        } else {
            instrucciones = INSTRUCCIONES_VOZ
            partes += mapOf("text" to "Audio con la pregunta del usuario:")
            partes += mapOf("inlineData" to mapOf("mimeType" to "audio/wav", "data" to b64(wav)))
            if (previa != null) {
                partes += mapOf("text" to "FOTO ${previa.id}, la ultima que saco (usala solo si la pregunta se refiere a ella):")
                partes += mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(previa.jpeg)))
                instrucciones += NOTA_FOTO_ANTERIOR
            } else {
                instrucciones += NOTA_SIN_IMAGEN
            }
        }
        if (turnosPrevios.isNotEmpty()) instrucciones += NOTA_HISTORIAL
        if (indiceFotos.isNotEmpty()) instrucciones += NOTA_FOTOS + "\n" + indiceFotos
        instrucciones += Comandos.instrucciones(a)
        val tel = llamadasActivas(a)
        if (tel != null) instrucciones += HerramientasLlamadas.INSTRUCCIONES + tel.estadoParaIa()
        val ubic = ubicacionActiva(a)
        if (ubic != null) instrucciones += HerramientasUbicacion.INSTRUCCIONES
        contenidos += mapOf("role" to "user", "parts" to partes)

        return mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to instrucciones))),
            "contents" to contenidos,
            "tools" to Comandos.herramientas(tel != null, ubic != null),
            "generationConfig" to mapOf("temperature" to 0.3, "maxOutputTokens" to 300),
        )
    }

    private fun paginaInicio(): String {
        val img = if (ultimaFoto != null) "<img src=\"/ultima_foto?t=${contadorFotos.get()}\">"
                  else "<p>Todavia no llego ninguna foto.</p>"
        return """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="5"><meta name="viewport" content="width=device-width">
<title>Traductor de entorno</title><style>
body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:20px;max-width:760px}
img{max-width:100%;border-radius:8px}.c{background:#1c1c1c;border-radius:8px;padding:12px;margin:12px 0}
.d{color:#888;font-size:13px}a{color:#8ab4f8}</style></head><body>
<h2>Traductor de entorno para ciegos (celular)</h2>
<div class="d">Modelo: ${gemini.modelo} | Fotos: ${contadorFotos.get()} | Audios: ${contadorAudios.get()}</div>
$img<div class="d">Foto mejorada (lo que ve Gemini). <a href="/ultima_foto_original">Ver la original</a></div>
<div class="c"><b>Ultima pregunta:</b><br>${html(ultimaPregunta.ifEmpty { "-" })}</div>
<div class="c"><b>Ultima respuesta:</b><br>${html(ultimoTexto.ifEmpty { "-" })}</div>
</body></html>"""
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val ESPERA_FOTO_MS = 3000L
        const val VIDA_SESION_MS = 60_000L
        const val FOTO_VIEJA_MS = 30_000L
        const val LIMITE_ESP32_S = 60

        private const val REGLAS_HABLA =
            "Tu respuesta sera leida en voz alta por un parlante. " +
            "Responde de forma breve, concisa y natural, en un solo parrafo. " +
            "PROHIBIDO usar asteriscos, guiones, listas, comillas o cualquier simbolo de formato. " +
            "Los numeros escribilos completos en palabras. " +
            "Maximo tres oraciones. No saludes ni te presentes."

        /** Donde esta la camara: cambia como orienta las descripciones. */
        private const val CAMARA =
            "La camara esta colgada en el pecho del usuario, mirando hacia adelante: la foto muestra lo que " +
            "tiene enfrente a la altura del pecho. Ubica las cosas respecto de el (adelante tuyo, a tu izquierda, " +
            "a la altura de la cintura, cerca o lejos). "

        val INSTRUCCIONES_IMAGEN =
            "Eres el asistente de un traductor de entorno para una persona ciega. " + CAMARA +
            "Describe lo que ves de manera que sirva de guia para saber que objeto tiene delante " +
            "y como manipularlo. Si la imagen esta muy oscura, borrosa o no se distingue bien, " +
            "dilo en una oracion y sugiere acercar o alejar el objeto o buscar mas luz. " + REGLAS_HABLA

        val INSTRUCCIONES_VOZ =
            "Eres el asistente de voz de un traductor de entorno para una persona ciega. " +
            "Recibes un audio con una pregunta o pedido del usuario. " +
            "El usuario uso el boton de voz. " +
            "Paso uno: escucha el audio y transcribe la pregunta tal cual. " +
            "Paso dos: responde con tu conocimiento general. " +
            "Si el audio no se entiende, pide amablemente que repita la pregunta. " +
            "Formato de salida OBLIGATORIO, en una sola linea: " +
            "PREGUNTA: la transcripcion RESPUESTA: tu respuesta. " + REGLAS_HABLA

        val INSTRUCCIONES_FOTO_VOZ =
            "Eres el asistente de un traductor de entorno para una persona ciega. " + CAMARA +
            "El usuario acaba de sacar una foto de lo que tiene enfrente y te hace una pregunta " +
            "por voz SOBRE ESA FOTO. " +
            "Paso uno: escucha el audio y transcribe la pregunta tal cual. " +
            "Paso dos: responde exactamente lo que pregunta mirando la foto. " +
            "No describas toda la imagen salvo que te lo pida. " +
            "Si la foto esta oscura, borrosa o no se ve lo que pregunta, dilo en una oracion y sugiere " +
            "como mejorarla: mas luz, acercar o alejar el objeto. " +
            "Si no recibiste ninguna foto, dile que la foto no llego y que lo intente de nuevo. " +
            "Si el audio no se entiende, pide amablemente que repita la pregunta. " +
            "Formato de salida OBLIGATORIO, en una sola linea: " +
            "PREGUNTA: la transcripcion RESPUESTA: tu respuesta. " + REGLAS_HABLA

        // Se agregan a las instrucciones segun el caso
        const val NOTA_SIN_IMAGEN =
            " En esta consulta NO hay imagen. Si te pregunta por algo que tiene enfrente ahora, " +
            "explicale que para eso mantenga apretado el boton de la foto mientras hace la pregunta."

        const val NOTA_FOTO_ANTERIOR =
            " Junto al audio viene la ultima foto que el usuario saco hace un momento. " +
            "Usala SOLO si la pregunta se refiere a ella o a lo que se veia (por ejemplo: de que color es, " +
            "que dice, el de la izquierda). Si la pregunta es general o no tiene relacion con la foto, " +
            "ignorala por completo y no la menciones. Si pregunta por algo que tiene enfrente ahora " +
            "y no por esa foto, explicale que mantenga apretado el boton de la foto mientras pregunta."

        const val NOTA_HISTORIAL =
            " Antes del ultimo audio esta TODA la conversacion desde que se encendio el dispositivo, solo como contexto. " +
            "Responde unicamente al ultimo audio. Si la pregunta se refiere a algo anterior (por ejemplo: y eso, " +
            "y el otro, repetilo, lo de hace un rato, la foto del cartel), busca en la conversacion a que parte " +
            "concreta se refiere y responde sobre eso. Si no se refiere a nada anterior, no mezcles temas viejos. " +
            "Si no encontras a que se refiere, preguntale en una oracion."

        const val NOTA_FOTOS =
            " FOTOS GUARDADAS de esta conversacion, con lo que se describio de cada una. Si la pregunta se refiere " +
            "a una foto anterior y con la descripcion no alcanza para responder bien, llama a ver_foto con su numero. " +
            "No pidas la foto que ya viene adjunta en este mensaje."

        const val FRASE_ERROR = "No pude procesar eso. Intenta de nuevo."
        const val SALUDO_FIJO =
            "Hola, soy tu traductor de entorno y ya estoy listo. " +
            "Toca el boton de la camara para que te describa lo que tenes enfrente, o mantenelo apretado " +
            "para preguntarme sobre eso. Con el otro boton podes hacerme cualquier pregunta o pedirme que cambie " +
            "el volumen o la velocidad de la voz."

        /** "PREGUNTA: ... RESPUESTA: ..." -> (pregunta, respuesta). Sin formato, se lee todo. */
        fun separarPregunta(texto: String): Pair<String, String> {
            val t = texto.trim()
            val iR = t.indexOf("RESPUESTA:", ignoreCase = true)
            if (iR < 0) return Pair("", t)
            val iP = t.indexOf("PREGUNTA:", ignoreCase = true)
            val pregunta = if (iP in 0 until iR) t.substring(iP + "PREGUNTA:".length, iR).trim() else ""
            return Pair(pregunta, t.substring(iR + "RESPUESTA:".length).trim())
        }

        fun limpiarTexto(texto: String): String {
            var t = texto
            for (c in listOf("*", "#", "_", "`", "•", "—", "–")) t = t.replace(c, " ")
            t = t.replace("\n", " ").replace("\r", " ")
            while ("  " in t) t = t.replace("  ", " ")
            return t.trim()
        }
    }
}
