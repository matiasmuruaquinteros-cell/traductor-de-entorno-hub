package com.lentesinteligentes.app.nucleo

import java.text.Normalizer
import java.util.Locale

/**
 * LLAMADAS POR VOZ
 *
 *  Llamar:   "llama a mama" -> preparar_llamada -> la app busca en los contactos y pregunta
 *            "Llamo a Mama, al celular?" -> el usuario dice "si" EN OTRO TURNO -> confirmar_llamada -> llama.
 *            La confirmacion la controla la app: confirmar_llamada en el mismo turno que
 *            preparar_llamada se rechaza, asi la IA nunca puede llamar sin que el usuario diga que si.
 *  Entrante: el celular suena -> la app avisa quien llama -> "atende" / "rechaza" -> responder_llamada.
 *            Atajo: un toque del boton FOTO mientras suena rechaza al instante (sin IA ni internet).
 *  Audio:    la charla telefonica va por el celular (altavoz automatico si no hay auriculares).
 *            Android no deja que una app comun use el audio de una llamada telefonica.
 */

/** Un numero de la agenda. */
class NumeroContacto(val nombre: String, val numero: String, val tipo: String)

enum class EstadoTel { LIBRE, SONANDO, EN_CURSO }

/** Lo que la app necesita del telefono. En el celular lo implementa TelefonoAndroid. */
interface Telefono {
    /** Permisos que faltan, en palabras ("contactos", "hacer llamadas"...). Vacio = todo bien. */
    fun permisosFaltantes(): List<String>
    fun contactos(): List<NumeroContacto>
    fun nombreDe(numero: String): String?
    /** Cada accion devuelve null si salio bien, o el motivo del error. */
    fun llamar(numero: String, altavoz: Boolean): String?
    fun atender(): String?
    fun cortar(): String?
    fun activarAltavoz()
    fun soltarAltavoz()
}

/** Herramientas (function calling) de llamadas, para Gemini REST y Live. */
object HerramientasLlamadas {
    const val PREPARAR = "preparar_llamada"
    const val CONFIRMAR = "confirmar_llamada"
    const val RESPONDER = "responder_llamada"
    val NOMBRES = setOf(PREPARAR, CONFIRMAR, RESPONDER)

    val declaraciones: List<Map<String, Any?>> = listOf(
        mapOf(
            "name" to PREPARAR,
            "description" to "Busca un contacto en la agenda del celular para llamarlo. Usala cuando el usuario pide " +
                "llamar a alguien (llama a mama, llamalo a Juan Perez). NO hace la llamada: despues hay que preguntarle " +
                "al usuario si confirma. Si antes le ofreciste varias opciones numeradas y elige una, pasa su numero en opcion.",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "nombre" to mapOf("type" to "STRING", "description" to
                        "El nombre tal como lo dijo el usuario, sin 'a' ni 'mi' (por ejemplo: mama, Juan Perez, la abuela)."),
                    "opcion" to mapOf("type" to "INTEGER", "description" to
                        "Solo si eligio una de las opciones numeradas que le ofreciste."),
                ),
            ),
        ),
        mapOf(
            "name" to CONFIRMAR,
            "description" to "Hace la llamada que preparaste, o la cancela. Usala SOLO despues de que le preguntaste " +
                "'llamo a tal persona?' y el usuario respondio en un mensaje nuevo. si=true si dijo que si, " +
                "si=false si dijo que no o cambio de idea. Nunca la uses en el mismo turno que preparar_llamada.",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("si" to mapOf("type" to "BOOLEAN", "description" to "true = llamar, false = cancelar")),
                "required" to listOf("si"),
            ),
        ),
        mapOf(
            "name" to RESPONDER,
            "description" to "Atiende o rechaza la llamada que esta sonando, o corta la llamada en curso. " +
                "Usala cuando el usuario dice atender, si, contesta, rechazar, no, cortar o colgar.",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("accion" to mapOf("type" to "STRING",
                    "description" to "atender, rechazar (la que suena) o cortar (la que esta en curso)",
                    "enum" to listOf("atender", "rechazar", "cortar"))),
                "required" to listOf("accion"),
            ),
        ),
    )

    const val INSTRUCCIONES =
        " LLAMADAS: si el usuario pide llamar a alguien, llama a $PREPARAR con el nombre y despues dale la pregunta " +
        "que te devuelve. Solo cuando el usuario conteste esa pregunta, llama a $CONFIRMAR (si=true o si=false). " +
        "Nunca hagas una llamada sin que el usuario haya dicho que si. Si pide atender, rechazar, cortar o colgar " +
        "una llamada, usa $RESPONDER."
}

/**
 * Cerebro de las llamadas. No depende de Android: el telefono real entra por [Telefono].
 * Thread-safe: lo llaman el servidor (consultas del ESP32), Gemini Live y el receptor de llamadas.
 */
class GestorLlamadas(
    private val tel: Telefono,
    private val registro: Registro,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    /** Si las llamadas estan habilitadas en Ajustes. */
    var activo: () -> Boolean = { true }
    /** Avisar una llamada entrante. ayuda = como responder con los botones (para el aviso en el celular). */
    var anunciar: ((frase: String, ayuda: String, contextoIa: String) -> Unit)? = null
    /** Cambio de estado que Gemini Live tiene que saber (atendida, terminada). */
    var alCambiar: ((contextoIa: String) -> Unit)? = null
    /** Ejecutar algo mas tarde (en las pruebas se reemplaza). */
    var programar: (Long, () -> Unit) -> Unit = { ms, f -> Thread({ Thread.sleep(ms); f() }, "llamadas").start() }

    class Opcion(val nombre: String, val numero: String, val tipo: String)
    class Resultado(val frase: String, val paraIa: Map<String, Any?>)

    private val candado = Any()
    @Volatile var estado = EstadoTel.LIBRE
        private set
    @Volatile var quien = ""             // quien llama, o con quien se habla
        private set
    private var turno = 0
    private var opciones: List<Opcion> = emptyList()
    private var opcionesT = 0L
    private var pendiente: Opcion? = null
    private var pendienteTurno = -1
    private var pendienteT = 0L
    private var altavozPedido = false
    private var rechazadaPorNosotros = false
    private var marcaSonando = 0
    private var numeroEntrante = ""

    /** Cada pedido nuevo del usuario (boton de voz, o lo que dijo en el modo conversacion). */
    fun nuevoTurno() { synchronized(candado) { turno++ } }

    fun sonando(): Boolean = estado == EstadoTel.SONANDO

    // =====================================================================
    // HERRAMIENTAS
    // =====================================================================
    fun ejecutar(nombre: String, args: Map<*, *>): Resultado {
        val r = when (nombre) {
            HerramientasLlamadas.PREPARAR -> preparar(
                (args["nombre"] as? String).orEmpty(),
                (args["opcion"] as? Number)?.toInt() ?: args["opcion"]?.toString()?.toIntOrNull())
            HerramientasLlamadas.CONFIRMAR -> confirmar(siDe(args["si"]))
            HerramientasLlamadas.RESPONDER -> responder((args["accion"] as? String).orEmpty().lowercase(Locale.ROOT).trim())
            else -> Resultado("No se hacer eso.", mapOf("error" to "herramienta desconocida"))
        }
        registro.log("   [LLAMADAS] $nombre $args -> ${r.frase}")
        return r
    }

    private fun siDe(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.lowercase(Locale.ROOT) in setOf("true", "si", "sí", "1")
        is Number -> v.toInt() != 0
        else -> null
    }

    fun preparar(nombre: String, opcion: Int?): Resultado = synchronized(candado) {
        faltan(listOf("contactos", "hacer llamadas"))?.let { return it }
        if (estado == EstadoTel.SONANDO) return frase("Ahora te esta llamando ${quien.ifEmpty { "alguien" }}. Primero decime si atiendo o rechazo.")
        if (estado == EstadoTel.EN_CURSO) return frase("Ya hay una llamada en curso. Si queres, primero decime que la corte.")

        // Eligio una de las opciones que le ofreci
        if (opcion != null && opciones.isNotEmpty() && ahora() - opcionesT < VIDA_OPCIONES_MS) {
            val o = opciones.getOrNull(opcion - 1)
                ?: return frase("Esa opcion no existe. ${listar(opciones)} A cual llamo?", "opciones" to textoOpciones())
            return dejarPendiente(o)
        }

        val q = nombre.trim()
        if (q.isEmpty()) return frase("A quien queres que llame?")
        val encontrados = BuscadorContactos.buscar(tel.contactos(), q)
        if (encontrados.isEmpty()) {
            return frase("No encontre a $q en tus contactos.", "encontrados" to 0)
        }
        val ops = encontrados.take(MAX_OPCIONES).map { Opcion(it.nombre, it.numero, it.tipo) }
        if (ops.size == 1) return dejarPendiente(ops[0])

        opciones = ops
        opcionesT = ahora()
        pendiente = null
        val mismaPersona = ops.all { it.nombre == ops[0].nombre }
        val f = if (mismaPersona) "${ops[0].nombre} tiene ${numeroEnPalabras(ops.size)} numeros: ${listarTipos(ops)} A cual llamo?"
                else "Encontre ${numeroEnPalabras(ops.size)}: ${listar(ops)} A cual llamo?"
        return Resultado(f, mapOf("resultado" to "hay varias opciones; preguntale cual y despues llama a preparar_llamada con opcion",
            "opciones" to textoOpciones(), "decile" to f))
    }

    private fun dejarPendiente(o: Opcion): Resultado {
        pendiente = o
        pendienteTurno = turno
        pendienteT = ahora()
        opciones = emptyList()
        val f = "Llamo a ${o.nombre}, al ${o.tipo}? Decime si o no."
        return Resultado(f, mapOf("resultado" to "contacto encontrado, falta que el usuario confirme",
            "contacto" to o.nombre, "tipo" to o.tipo, "decile" to f))
    }

    fun confirmar(si: Boolean?): Resultado = synchronized(candado) {
        val p = pendiente ?: return frase("No tengo ninguna llamada para confirmar. Decime a quien queres llamar.")
        if (ahora() - pendienteT > VIDA_PENDIENTE_MS) {
            pendiente = null
            return frase("Paso mucho tiempo. Decime de nuevo a quien llamo.")
        }
        if (pendienteTurno >= turno) {
            // la IA quiso confirmar sin que el usuario contestara: se vuelve a preguntar
            val f = "Llamo a ${p.nombre}, al ${p.tipo}? Decime si o no."
            return Resultado(f, mapOf("error" to "el usuario todavia no confirmo; hacele la pregunta y espera su respuesta",
                "decile" to f))
        }
        if (si == null) return frase("No entendi. Llamo a ${p.nombre}? Decime si o no.")
        pendiente = null
        if (!si) return frase("Listo, no llamo.", "cancelada" to true)
        if (estado != EstadoTel.LIBRE) return frase("No puedo llamar ahora porque hay otra llamada.")
        faltan(listOf("hacer llamadas"))?.let { return it }
        val error = tel.llamar(p.numero, altavoz = true)
        if (error != null) {
            registro.log("   [LLAMADAS] no pude llamar: $error")
            return frase("No pude hacer la llamada: $error.", "error" to error)
        }
        altavozPedido = true
        quien = p.nombre
        rechazadaPorNosotros = false
        return frase("Llamando a ${p.nombre}.", "llamando" to true)
    }

    fun responder(accion: String): Resultado = synchronized(candado) {
        when {
            accion == "atender" -> {
                if (estado == EstadoTel.EN_CURSO) return frase("La llamada ya esta en curso.")
                if (estado != EstadoTel.SONANDO) return frase("No hay ninguna llamada sonando.")
                faltan(listOf("atender y cortar"))?.let { return it }
                val error = tel.atender()
                if (error != null) return frase("No pude atender: $error.", "error" to error)
                altavozPedido = true
                return frase("Atendi la llamada${if (quien.isNotEmpty()) " de $quien" else ""}. Habla cerca del celular.",
                    "atendida" to true)
            }
            accion == "rechazar" && estado == EstadoTel.SONANDO -> {
                faltan(listOf("atender y cortar"))?.let { return it }
                val error = tel.cortar()
                if (error != null) return frase("No pude rechazar la llamada: $error.", "error" to error)
                rechazadaPorNosotros = true
                return frase("Listo, rechace la llamada${if (quien.isNotEmpty()) " de $quien" else ""}.", "rechazada" to true)
            }
            accion == "cortar" || accion == "rechazar" -> {
                if (estado == EstadoTel.SONANDO) return responderSinCandado("rechazar")
                if (estado != EstadoTel.EN_CURSO) return frase("No hay ninguna llamada en curso.")
                faltan(listOf("atender y cortar"))?.let { return it }
                val error = tel.cortar()
                if (error != null) return frase("No pude cortar: $error.", "error" to error)
                rechazadaPorNosotros = true
                return frase("Llamada terminada.", "terminada" to true)
            }
            else -> return frase("No entendi si atiendo, rechazo o corto.")
        }
    }

    private fun responderSinCandado(accion: String) = responder(accion)   // el candado es reentrante

    /** Toque corto del boton FOTO: si esta sonando, rechaza al instante. Si no, null (foto normal). */
    fun rechazoRapido(): String? {
        if (!activo() || estado != EstadoTel.SONANDO) return null
        val r = responder("rechazar")
        registro.log("   [LLAMADAS] boton FOTO mientras sonaba -> ${r.frase}")
        return r.frase
    }

    // =====================================================================
    // ESTADO DEL TELEFONO (lo avisa el receptor de Android)
    // =====================================================================
    fun cambioDeEstado(nuevo: EstadoTel, numero: String) {
        var aviso: (() -> Unit)? = null
        synchronized(candado) {
            when (nuevo) {
                EstadoTel.SONANDO -> {
                    if (numero.isNotBlank()) numeroEntrante = numero
                    if (estado == EstadoTel.SONANDO) {
                        if (numero.isNotBlank() && quien.isEmpty()) quien = describir(numero)
                        return
                    }
                    estado = EstadoTel.SONANDO
                    rechazadaPorNosotros = false
                    altavozPedido = false
                    pendiente = null
                    quien = if (numero.isNotBlank()) describir(numero) else ""
                    registro.log("\n[LLAMADAS] Esta sonando una llamada${if (quien.isNotEmpty()) " de $quien" else ""}")
                    val marca = ++marcaSonando
                    // Android a veces avisa dos veces (con y sin numero): se espera un momento para tener el nombre
                    if (activo()) programar(ESPERA_NOMBRE_MS) { anunciarSonando(marca, 1) }
                }
                EstadoTel.EN_CURSO -> {
                    if (estado == EstadoTel.EN_CURSO) return
                    val antes = estado
                    estado = EstadoTel.EN_CURSO
                    registro.log("[LLAMADAS] Llamada en curso${if (quien.isNotEmpty()) " con $quien" else ""}")
                    if (altavozPedido) {
                        programar(600) { if (estado == EstadoTel.EN_CURSO) tel.activarAltavoz() }
                        programar(2000) { if (estado == EstadoTel.EN_CURSO) tel.activarAltavoz() }
                    }
                    val q = quien
                    if (activo()) aviso = {
                        alCambiar?.invoke("[AVISO DEL SISTEMA] " + (if (antes == EstadoTel.SONANDO)
                            "Se atendio la llamada${if (q.isNotEmpty()) " de $q" else ""}; esta en curso por el celular."
                            else "Hay una llamada en curso${if (q.isNotEmpty()) " con $q" else ""}.") +
                            " Si el usuario pide cortar o colgar, usa responder_llamada con cortar.")
                    }
                }
                EstadoTel.LIBRE -> {
                    if (estado == EstadoTel.LIBRE) return
                    val antes = estado
                    val q = quien
                    estado = EstadoTel.LIBRE
                    marcaSonando++
                    if (altavozPedido) tel.soltarAltavoz()
                    altavozPedido = false
                    numeroEntrante = ""
                    val texto = when {
                        antes == EstadoTel.SONANDO && rechazadaPorNosotros -> "La llamada fue rechazada."
                        antes == EstadoTel.SONANDO -> "La llamada${if (q.isNotEmpty()) " de $q" else ""} dejo de sonar sin que se atienda."
                        else -> "La llamada termino."
                    }
                    registro.log("[LLAMADAS] $texto")
                    quien = ""
                    if (activo()) aviso = { alCambiar?.invoke("[AVISO DEL SISTEMA] $texto Ya no hay ninguna llamada.") }
                }
            }
        }
        aviso?.invoke()
    }

    private fun anunciarSonando(marca: Int, vez: Int) {
        val frase: String; val ayuda: String; val contexto: String
        synchronized(candado) {
            if (marca != marcaSonando || estado != EstadoTel.SONANDO) return
            if (quien.isEmpty() && numeroEntrante.isNotBlank()) quien = describir(numeroEntrante)
            frase = if (quien.isEmpty()) "Te estan llamando. Atendes o rechazas?" else "Te llama $quien. Atendes o rechazas?"
            ayuda = if (vez == 1) " Apreta el boton de voz y deci atender o rechazar, o toca el boton de la camara para rechazar." else ""
            contexto = "[AVISO DEL SISTEMA, no lo dijo el usuario] Esta sonando una llamada" +
                (if (quien.isNotEmpty()) " de $quien" else "") + ". Ya le avise al usuario. Si dice que atiende o que si, " +
                "usa responder_llamada con atender; si dice que no o que rechaza, con rechazar."
        }
        anunciar?.invoke(frase, ayuda, contexto)
        if (vez < VECES_AVISO) programar(REPETIR_AVISO_MS) { anunciarSonando(marca, vez + 1) }
    }

    /** Lo que Gemini tiene que saber del telefono en esta consulta (modo clasico). */
    fun estadoParaIa(): String = synchronized(candado) {
        val partes = ArrayList<String>()
        when (estado) {
            EstadoTel.SONANDO -> partes += "AHORA ESTA SONANDO una llamada${if (quien.isNotEmpty()) " de $quien" else ""}: " +
                "si el usuario dice atender o si, llama a responder_llamada con atender; si dice rechazar o no, con rechazar."
            EstadoTel.EN_CURSO -> partes += "Hay una llamada en curso${if (quien.isNotEmpty()) " con $quien" else ""}: " +
                "si pide cortar o colgar, llama a responder_llamada con cortar."
            EstadoTel.LIBRE -> {}
        }
        val p = pendiente
        if (p != null && ahora() - pendienteT <= VIDA_PENDIENTE_MS) {
            partes += "Le preguntaste si llamas a ${p.nombre} (${p.tipo}). Si en este audio dice que si, llama a confirmar_llamada " +
                "con si=true; si dice que no, con si=false."
        } else if (opciones.isNotEmpty() && ahora() - opcionesT < VIDA_OPCIONES_MS) {
            partes += "Le ofreciste estas opciones para llamar: ${textoOpciones()}. Si elige una, llama a preparar_llamada con su opcion."
        }
        return if (partes.isEmpty()) "" else " ESTADO DEL TELEFONO: " + partes.joinToString(" ")
    }

    // =====================================================================
    // AYUDAS
    // =====================================================================
    private fun faltan(necesarios: List<String>): Resultado? {
        val f = tel.permisosFaltantes().filter { it in necesarios }
        if (f.isEmpty()) return null
        val txt = "Me falta el permiso de ${f.joinToString(" y ")}. En la app del celular anda a Ajustes y toca Permitir llamadas."
        return Resultado(txt, mapOf("error" to "faltan permisos: ${f.joinToString()}", "decile" to txt))
    }

    private fun frase(f: String, vararg extra: Pair<String, Any?>): Resultado =
        Resultado(f, mapOf("resultado" to f, "decile" to f) + extra)

    private fun describir(numero: String): String {
        tel.nombreDe(numero)?.takeIf { it.isNotBlank() }?.let { return it }
        val digitos = numero.filter { it.isDigit() }
        if (digitos.isEmpty()) return "un numero privado"
        return "un numero que no esta en tus contactos, terminado en ${digitos.takeLast(4).toList().joinToString(" ")}"
    }

    private fun listar(ops: List<Opcion>): String =
        ops.mapIndexed { i, o -> "${numeroEnPalabras(i + 1)}, ${o.nombre}, ${o.tipo}." }.joinToString(" ")

    private fun listarTipos(ops: List<Opcion>): String =
        ops.mapIndexed { i, o -> "${numeroEnPalabras(i + 1)}, ${o.tipo}." }.joinToString(" ")

    private fun textoOpciones(): String =
        opciones.mapIndexed { i, o -> "${i + 1}: ${o.nombre} (${o.tipo})" }.joinToString("; ")

    companion object {
        const val MAX_OPCIONES = 5
        const val VIDA_OPCIONES_MS = 120_000L
        const val VIDA_PENDIENTE_MS = 90_000L
        const val ESPERA_NOMBRE_MS = 500L
        const val REPETIR_AVISO_MS = 12_000L
        const val VECES_AVISO = 2

        fun numeroEnPalabras(n: Int) = when (n) {
            1 -> "uno"; 2 -> "dos"; 3 -> "tres"; 4 -> "cuatro"; 5 -> "cinco"; 6 -> "seis"; else -> n.toString()
        }
    }
}

/**
 * Busca un nombre dicho en voz alta en la agenda. Tolera tildes, mayusculas, apodos de familia
 * (mama/mami/ma, papa/papi...) y errores chicos de transcripcion (Yoana / Johana).
 */
object BuscadorContactos {
    private val FAMILIA = listOf(
        setOf("mama", "mami", "ma", "madre", "mamita", "mamu", "vieja"),
        setOf("papa", "papi", "pa", "padre", "papito", "viejo"),
        setOf("abuela", "abu", "nona", "abuelita", "yaya", "lela"),
        setOf("abuelo", "abu", "nono", "abuelito", "lelo"),
        setOf("hermano", "hermanito", "bro"),
        setOf("hermana", "hermanita", "sis"),
    )
    private val RELLENO = setOf("a", "al", "mi", "la", "el", "le", "los", "las", "con", "llama", "llamar", "llamale", "llamalo", "llamala")

    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

    /** Numeros de los contactos que coinciden, del mejor al peor (celular primero), sin repetidos. */
    fun buscar(agenda: List<NumeroContacto>, consulta: String): List<NumeroContacto> {
        val palabras = normalizar(consulta).split(" ").filter { it.isNotEmpty() }
        val q = palabras.dropWhile { it in RELLENO }.ifEmpty { palabras }
        if (q.isEmpty()) return emptyList()

        // Puntaje por nombre (un mismo nombre puede tener varios numeros)
        val porNombre = LinkedHashMap<String, MutableList<NumeroContacto>>()
        for (c in agenda) if (c.nombre.isNotBlank() && c.numero.isNotBlank()) porNombre.getOrPut(c.nombre.trim()) { ArrayList() } += c
        val puntajes = porNombre.keys.associateWith { puntaje(normalizar(it), q) }.filterValues { it >= MINIMO }
        if (puntajes.isEmpty()) return emptyList()
        val mejor = puntajes.values.max()
        val elegidos = puntajes.filterValues { it >= mejor - MARGEN }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })

        val salida = ArrayList<NumeroContacto>()
        for ((nombre, _) in elegidos) {
            val vistos = HashSet<String>()
            porNombre[nombre].orEmpty()
                .sortedBy { ordenTipo(it.tipo) }
                .forEach { if (vistos.add(clave(it.numero))) salida += it }
        }
        return salida
    }

    private const val MINIMO = 60.0
    private const val MARGEN = 8.0

    private fun ordenTipo(t: String) = when {
        "celular" in t -> 0; "principal" in t -> 1; "casa" in t -> 2; "trabajo" in t -> 3; else -> 4
    }

    /** Ultimos 8 digitos: +54 9 11 1234-5678 y 011 15 1234-5678 son el mismo numero. */
    private fun clave(numero: String) = numero.filter { it.isDigit() }.takeLast(8)

    fun puntaje(nombre: String, q: List<String>): Double {
        val n = nombre.split(" ").filter { it.isNotEmpty() }
        if (n.isEmpty()) return 0.0
        val consulta = q.joinToString(" ")
        if (nombre == consulta) return 100.0
        var total = 0.0
        for (w in q) {
            val variantes = FAMILIA.filter { w in it }.flatten().toSet().ifEmpty { setOf(w) }
            var mejor = 0.0
            for (v in variantes) for (p in n) mejor = maxOf(mejor, parecido(v, p))
            if (mejor == 0.0) {
                return if (nombre.contains(consulta) && consulta.length >= 3) 70.0 else 0.0
            }
            total += mejor
        }
        var p = 95.0 * total / q.size
        if (n.size == q.size) p += 5.0          // "Mama" le gana a "Mama de Juan"
        return p.coerceAtMost(100.0)
    }

    private fun parecido(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length >= 3 && b.startsWith(a)) return 0.9
        if (b.length >= 3 && a.startsWith(b)) return 0.85
        if (a.length < 4 || b.length < 4) return 0.0
        // Se compara como suena: el reconocimiento de voz no sabe si es Johana o Yoana, Valeria o Baleria
        val fa = fonetica(a); val fb = fonetica(b)
        if (fa == fb) return 0.95
        val s = 1.0 - distancia(fa, fb).toDouble() / maxOf(fa.length, fb.length)
        return if (s >= 0.7) s * 0.9 else 0.0
    }

    /** Version "como suena" en castellano rioplatense. */
    fun fonetica(p: String): String = p
        .replace("ch", "\u0001").replace("sh", "\u0001")     // ch/sh se conservan como un sonido
        .replace("ll", "y").replace("h", "").replace("\u0001", "ch")
        .replace("qu", "k").replace("c(?=[ei])".toRegex(), "s").replace("c", "k")
        .replace("z", "s").replace("v", "b").replace("w", "u")
        .replace("y", "i").replace("j", "i").replace("ge", "ie").replace("gi", "ii")   // Yoana = Johana
        .replace("(.)\\1+".toRegex(), "$1")

    private fun distancia(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previo = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, previo + if (a[i - 1] == b[j - 1]) 0 else 1)
                previo = t
            }
        }
        return d[b.length]
    }
}
