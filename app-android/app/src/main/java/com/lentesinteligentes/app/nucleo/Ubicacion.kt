package com.lentesinteligentes.app.nucleo

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UBICACION POR VOZ
 *
 *  donde_estoy     GPS -> direccion (Geocoder del celular) + entre que calles (OpenStreetMap / Overpass)
 *  buscar_lugares  Gemini con Google Maps ("escuela", "farmacia"...) -> nombre, direccion y distancia
 *  como_llegar     ruta a pie (servidor de rutas de OpenStreetMap) -> resumen hablado por calles
 *
 * No guia paso a paso: dice la distancia, el tiempo y por que calles ir. Las indicaciones salen
 * de un calculo de ruta real, no de lo que imagina la IA.
 */

class Posicion(val lat: Double, val lon: Double, val precision: Float = 0f)

class Direccion(val calle: String, val altura: String, val barrio: String, val ciudad: String)

/** Lo que la app necesita del celular. En el celular lo implementa LocalizadorAndroid. */
interface Localizador {
    fun permisosFaltantes(): List<String>
    /** null si se puede usar; si no, que pasa (por ejemplo, la ubicacion del celular esta apagada). */
    fun problema(): String?
    /** Posicion actual (puede tardar unos segundos). null si no hay senal. */
    fun posicion(): Posicion?
    fun direccion(p: Posicion): Direccion?
    /** Busca una direccion o un nombre cerca de [cerca]. */
    fun buscarDireccion(texto: String, cerca: Posicion): Posicion?
}

/** GET HTTP (Overpass y rutas de OpenStreetMap). */
fun interface Web {
    fun get(url: String, timeoutMs: Int): String
}

object WebSimple : Web {
    override fun get(url: String, timeoutMs: Int): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = timeoutMs
            con.readTimeout = timeoutMs
            // Los servidores de OpenStreetMap piden identificarse
            con.setRequestProperty("User-Agent", "TraductorDeEntorno/1.0 (proyecto escolar, ayuda para personas ciegas)")
            con.setRequestProperty("Accept", "application/json")
            val codigo = con.responseCode
            if (codigo !in 200..299) throw java.io.IOException("HTTP $codigo")
            return con.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            con.disconnect()
        }
    }
}

object HerramientasUbicacion {
    const val DONDE_ESTOY = "donde_estoy"
    const val BUSCAR = "buscar_lugares"
    const val COMO_LLEGAR = "como_llegar"
    val NOMBRES = setOf(DONDE_ESTOY, BUSCAR, COMO_LLEGAR)

    val declaraciones: List<Map<String, Any?>> = listOf(
        mapOf(
            "name" to DONDE_ESTOY,
            "description" to "Dice en que calle y altura esta el usuario, entre que calles y en que barrio. " +
                "Usala cuando pregunta donde estoy, en que calle estoy, en que esquina estoy.",
        ),
        mapOf(
            "name" to BUSCAR,
            "description" to "Busca lugares cercanos al usuario con Google Maps y dice nombre, direccion y distancia. " +
                "Usala cuando pregunta donde hay, donde queda o cual es el mas cercano (escuela, farmacia, " +
                "parada de colectivo, hospital, cajero, supermercado, un negocio por su nombre...).",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("que" to mapOf("type" to "STRING", "description" to
                    "Que buscar, en pocas palabras: escuela, farmacia, parada del colectivo 60, Hospital Durand...")),
                "required" to listOf("que"),
            ),
        ),
        mapOf(
            "name" to COMO_LLEGAR,
            "description" to "Dice como llegar caminando: distancia, tiempo y por que calles ir. destino puede ser el " +
                "numero de un lugar que ya le nombraste (1, 2, 3), su nombre, una direccion o algo para buscar " +
                "(la farmacia mas cercana).",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("destino" to mapOf("type" to "STRING", "description" to
                    "Numero del lugar ya nombrado, nombre del lugar, direccion, o que buscar.")),
                "required" to listOf("destino"),
            ),
        ),
    )

    const val INSTRUCCIONES =
        " UBICACION: si pregunta donde esta o en que calle, llama a $DONDE_ESTOY. Si busca un lugar cercano, llama a $BUSCAR. " +
        "Si pregunta como llegar, llama a $COMO_LLEGAR (con el numero del lugar si ya se lo nombraste). " +
        "Nunca inventes direcciones, distancias ni caminos: usa siempre estas herramientas y repeti lo que devuelven."
}

/** Cerebro de la ubicacion. No depende de Android: el GPS entra por [Localizador]. */
class GestorUbicacion(
    private val loc: Localizador,
    private val gemini: Gemini?,
    private val registro: Registro,
    private val web: Web = WebSimple,
    private val overpass: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ),
    private val servidorRutas: String = "https://routing.openstreetmap.de/routed-foot",
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    var activo: () -> Boolean = { true }

    class Resultado(val frase: String, val paraIa: Map<String, Any?>)
    class Lugar(val nombre: String, val direccion: String, val pos: Posicion?, val distancia: Double?)

    @Volatile private var ultimos: List<Lugar> = emptyList()
    @Volatile private var ultimosT = 0L
    private val hilos = Executors.newCachedThreadPool { r -> Thread(r, "ubicacion").apply { isDaemon = true } }

    fun ejecutar(nombre: String, args: Map<*, *>): Resultado {
        val t0 = System.nanoTime()
        val r = try {
            when (nombre) {
                HerramientasUbicacion.DONDE_ESTOY -> dondeEstoy()
                HerramientasUbicacion.BUSCAR -> buscarLugares((args["que"] as? String).orEmpty().trim())
                HerramientasUbicacion.COMO_LLEGAR -> comoLlegar(args["destino"]?.toString().orEmpty().trim())
                else -> frase("No se hacer eso.")
            }
        } catch (e: Exception) {
            registro.log("   [UBICACION] error: ${e.message}")
            frase("Tuve un problema con la ubicacion. Proba de nuevo en un momento.")
        }
        registro.log("   [UBICACION] $nombre $args (${"%.1f".format((System.nanoTime() - t0) / 1e9)} s) -> ${r.frase}")
        return r
    }

    // =====================================================================
    // DONDE ESTOY
    // =====================================================================
    fun dondeEstoy(): Resultado {
        val (p, error) = posicionOError()
        if (p == null) return error!!
        // La direccion (Geocoder) y las calles de alrededor (OpenStreetMap) se piden a la vez
        val futuroCalles = hilos.submit(Callable { callesCercanas(p) })
        val dir = try { loc.direccion(p) } catch (e: Exception) { null }
        val crudo = try { futuroCalles.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
        val calles = crudo?.let { analizarCalles(it, p, dir?.calle) }

        val partes = StringBuilder()
        val calle = dir?.calle?.takeIf { it.isNotBlank() } ?: calles?.calle
        if (calle == null) {
            partes.append("No pude saber en que calle estas")
        } else {
            partes.append("Estas en ").append(calle)
            alturaHablada(dir?.altura)?.let { partes.append(" al ").append(it) }
        }
        when {
            calles?.esquina != null -> partes.append(", en la esquina con ").append(calles.esquina)
            calles?.entre != null -> partes.append(", entre ").append(calles.entre.first).append(" y ").append(calles.entre.second)
            calles?.cerca != null -> partes.append(", cerca de la esquina con ").append(calles.cerca)
        }
        val zona = dir?.barrio?.takeIf { it.isNotBlank() } ?: dir?.ciudad?.takeIf { it.isNotBlank() }
        if (zona != null && calle != null) partes.append(", en ").append(zona)
        partes.append(".")
        if (p.precision > PRECISION_DUDOSA) {
            partes.append(" Es aproximado: el GPS tiene un margen de unos ${redondear10(p.precision.toDouble())} metros.")
        }
        val f = hablable(partes.toString())
        return Resultado(f, mapOf("resultado" to f, "decile" to f,
            "lat" to redondear5(p.lat), "lon" to redondear5(p.lon)))
    }

    class InfoCalles(val calle: String?, val esquina: String?, val entre: Pair<String, String>?, val cerca: String?)

    /** JSON de Overpass con las calles con nombre a menos de 150 m. null si no responde. */
    private fun callesCercanas(p: Posicion): String? {
        val q = "[out:json][timeout:8];way[highway][name](around:$RADIO_CALLES,${p.lat},${p.lon});out geom;"
        for (base in overpass) {
            try {
                return web.get("$base?data=" + URLEncoder.encode(q, "UTF-8"), 9000)
            } catch (e: Exception) {
                registro.log("   [UBICACION] OpenStreetMap ($base) no respondio: ${e.message}")
            }
        }
        return null
    }

    private class Via(val nombre: String, val nodos: List<Long>, val pts: List<DoubleArray>)

    /**
     * De las calles cercanas: sobre cual esta y entre que esquinas.
     * Se trabaja en metros, con el usuario en (0, 0).
     */
    fun analizarCalles(json: String, p: Posicion, calleGeocoder: String?): InfoCalles? {
        val datos = try { Json.leer(json) } catch (e: Exception) { return null }
        val cosLat = Math.cos(Math.toRadians(p.lat))
        fun xy(lat: Double, lon: Double) = doubleArrayOf((lon - p.lon) * cosLat * 111_320.0, (lat - p.lat) * 110_540.0)
        val vias = (Json.ruta(datos, "elements") as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            if (m["type"] != "way") return@mapNotNull null
            val nombre = (Json.ruta(m, "tags", "name") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val geo = (m["geometry"] as? List<*>).orEmpty().mapNotNull { g ->
                val lat = (Json.ruta(g, "lat") as? Number)?.toDouble() ?: return@mapNotNull null
                val lon = (Json.ruta(g, "lon") as? Number)?.toDouble() ?: return@mapNotNull null
                xy(lat, lon)
            }
            val nodos = (m["nodes"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }
            if (geo.size < 2 || nodos.size != geo.size) return@mapNotNull null
            Via(nombre, nodos, geo)
        }
        if (vias.isEmpty()) return null

        val cercana = vias.minByOrNull { distanciaVia(it).first } ?: return null
        val deGeocoder = calleGeocoder?.let { g ->
            vias.filter { mismaCalle(it.nombre, g) }.minByOrNull { distanciaVia(it).first }
                ?.takeIf { distanciaVia(it).first < MAX_DIST_CALLE_GEOCODER }
        }
        val actual = deGeocoder ?: cercana.takeIf { distanciaVia(it).first < MAX_DIST_CALLE } ?: return InfoCalles(null, null, null, null)
        val tramos = vias.filter { mismaCalle(it.nombre, actual.nombre) }
        val nodosCalle = HashMap<Long, DoubleArray>()
        for (v in tramos) v.nodos.forEachIndexed { i, n -> nodosCalle[n] = v.pts[i] }

        // Esquinas: otras calles que comparten un punto con esta
        val cruces = HashMap<String, Pair<String, DoubleArray>>()   // nombre normalizado -> (nombre, punto mas cercano)
        for (v in vias) {
            if (mismaCalle(v.nombre, actual.nombre)) continue
            for (n in v.nodos) {
                val pt = nodosCalle[n] ?: continue
                val clave = normalizarCalle(v.nombre)
                val previo = cruces[clave]
                if (previo == null || largo(pt) < largo(previo.second)) cruces[clave] = Pair(v.nombre, pt)
            }
        }
        val dir = tramos.map { distanciaVia(it) }.minByOrNull { it.first }!!.second
        val esquina = cruces.values.filter { largo(it.second) < RADIO_ESQUINA }.minByOrNull { largo(it.second) }?.first
        val adelante = cruces.values.filter { punto(it.second, dir) > 0 }.minByOrNull { punto(it.second, dir) }
        val atras = cruces.values.filter { punto(it.second, dir) < 0 }.maxByOrNull { punto(it.second, dir) }
        val nombreCalle = calleGeocoder?.takeIf { deGeocoder != null } ?: actual.nombre
        return when {
            esquina != null -> InfoCalles(nombreCalle, esquina, null, null)
            adelante != null && atras != null -> InfoCalles(nombreCalle, null, Pair(atras.first, adelante.first), null)
            else -> InfoCalles(nombreCalle, null, null, (adelante ?: atras)?.first)
        }
    }

    /** Distancia del usuario (0,0) a la via, y la direccion del tramo mas cercano. */
    private fun distanciaVia(v: Via): Pair<Double, DoubleArray> {
        var mejor = Double.MAX_VALUE
        var dir = doubleArrayOf(1.0, 0.0)
        for (i in 0 until v.pts.size - 1) {
            val a = v.pts[i]; val b = v.pts[i + 1]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-a[0]) * dx + (-a[1]) * dy) / l2
            val tc = t.coerceIn(0.0, 1.0)
            val px = a[0] + tc * dx; val py = a[1] + tc * dy
            val d = Math.sqrt(px * px + py * py)
            if (d < mejor) { mejor = d; dir = doubleArrayOf(dx, dy) }
        }
        return Pair(mejor, dir)
    }

    private fun largo(v: DoubleArray) = Math.sqrt(v[0] * v[0] + v[1] * v[1])
    private fun punto(v: DoubleArray, d: DoubleArray) = (v[0] * d[0] + v[1] * d[1]) / maxOf(1e-9, largo(d))

    // =====================================================================
    // BUSCAR LUGARES (Gemini + Google Maps)
    // =====================================================================
    fun buscarLugares(que: String): Resultado {
        if (que.isEmpty()) return frase("Que lugar queres que busque?")
        val (p, error) = posicionOError()
        if (p == null) return error!!
        val lugares = try { lugaresCerca(que, p) } catch (e: ErrorGemini) {
            registro.log("   [UBICACION] Google Maps (Gemini) fallo: ${e.message}")
            return frase(if (e.codigo == 400) "No pude buscar en Google Maps con este modelo de Gemini. Revisa el modelo en Ajustes."
                         else "No pude buscar lugares: ${if (e.message.orEmpty().startsWith("Sin conexion")) "el celular no tiene internet" else "Google no respondio"}.")
        } ?: return frase("Para buscar lugares necesito la clave de Gemini.")
        if (lugares.isEmpty()) return frase("No encontre $que cerca.", "encontrados" to 0)
        ultimos = lugares
        ultimosT = ahora()

        val f = StringBuilder()
        val a = lugares[0]
        f.append(if (lugares.size == 1) "Encontre " else "Lo mas cerca es ").append(a.nombre)
        dondeQueda(a)?.let { f.append(", en ").append(it) }
        a.distancia?.let { f.append(", a ").append(distanciaHablada(it)) }
        f.append(".")
        if (lugares.size > 1) {
            f.append(" Tambien hay ")
            f.append(lugares.drop(1).joinToString(" y ") { l ->
                l.nombre + (l.distancia?.let { ", a " + distanciaHablada(it) } ?: "")
            })
            f.append(".")
        }
        f.append(" Queres que te diga como llegar?")
        val texto = hablable(f.toString())
        return Resultado(texto, mapOf("resultado" to texto, "decile" to texto,
            "lugares" to lugares.mapIndexed { i, l -> "${i + 1}: ${l.nombre}, ${l.direccion}" +
                (l.distancia?.let { ", a ${it.toInt()} m" } ?: "") }))
    }

    /** null si no hay Gemini. Lista vacia si no encontro nada. */
    private fun lugaresCerca(que: String, p: Posicion): List<Lugar>? {
        val g = gemini ?: return null
        val pedido = "Soy una persona ciega y estoy en latitud ${p.lat}, longitud ${p.lon}. Busca en Google Maps los lugares " +
            "mas cercanos a esa posicion que sean: $que. Elegi como maximo $MAX_LUGARES, los mas cercanos caminando. " +
            "Respondeme SOLO con una linea por lugar, con este formato exacto y sin nada mas:\n" +
            "LUGAR: nombre | DIRECCION: calle y altura, barrio, ciudad\n" +
            "Si no hay ninguno cerca, responde solo: NADA"
        val payload = mapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to pedido)))),
            "tools" to listOf(mapOf("googleMaps" to emptyMap<String, Any>())),
            "toolConfig" to mapOf("retrievalConfig" to mapOf("latLng" to mapOf("latitude" to p.lat, "longitude" to p.lon))),
            "generationConfig" to mapOf("temperature" to 0.2, "maxOutputTokens" to 500),
        )
        val datos = g.generar(payload, 30)
        val texto = g.textoOpcional(datos)
        registro.log("   [UBICACION] Google Maps: ${texto.replace('\n', ' ').take(300)}")
        var crudos = leerLugares(texto)
        if (crudos.isEmpty() && !texto.contains("NADA")) {
            // Si no respeto el formato, se usan los lugares que cito de Maps
            crudos = titulosDeMaps(datos).map { Pair(it, "") }
        }
        // Coordenadas de cada lugar (a la vez) para medir la distancia real desde el usuario
        val futuros = crudos.take(MAX_LUGARES).map { (nombre, dir) ->
            hilos.submit(Callable {
                val pos = try { loc.buscarDireccion(dir.ifEmpty { nombre }, p) ?: if (dir.isNotEmpty()) loc.buscarDireccion(nombre, p) else null }
                          catch (e: Exception) { null }
                Lugar(nombre, dir, pos, pos?.let { metros(p, it) })
            })
        }
        return futuros.mapNotNull { try { it.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null } }
            .filter { it.distancia == null || it.distancia < MAX_DISTANCIA_LUGAR }
            .sortedBy { it.distancia ?: Double.MAX_VALUE }
    }

    fun leerLugares(texto: String): List<Pair<String, String>> =
        texto.lines().mapNotNull { l ->
            val m = Regex("LUGAR:\\s*(.+?)\\s*\\|\\s*DIRECCI[OÓ]N:\\s*(.+)", RegexOption.IGNORE_CASE).find(l.trim()) ?: return@mapNotNull null
            val nombre = m.groupValues[1].trim().trim('*', ' ')
            val dir = m.groupValues[2].trim().trim('*', ' ')
            if (nombre.isEmpty()) null else Pair(nombre, dir)
        }.distinctBy { it.first.lowercase(Locale.ROOT) }

    private fun titulosDeMaps(datos: Map<String, Any?>): List<String> {
        val cand = (datos["candidates"] as? List<*>)?.firstOrNull()
        return (Json.ruta(cand, "groundingMetadata", "groundingChunks") as? List<*>).orEmpty()
            .mapNotNull { Json.ruta(it, "maps", "title") as? String }.distinct()
    }

    private fun dondeQueda(l: Lugar): String? = l.direccion.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    // =====================================================================
    // COMO LLEGAR (ruta a pie de OpenStreetMap)
    // =====================================================================
    fun comoLlegar(destino: String): Resultado {
        if (destino.isEmpty()) return frase("A donde queres ir?")
        val (p, error) = posicionOError()
        if (p == null) return error!!
        val lugar = elegirDestino(destino, p)
            ?: return frase("No encontre donde queda $destino.")
        val d = lugar.pos ?: return frase("No pude ubicar ${lugar.nombre} en el mapa.")
        val ruta = try { pedirRuta(p, d) } catch (e: Exception) {
            registro.log("   [UBICACION] el servidor de rutas no respondio: ${e.message}")
            null
        }
        val texto = if (ruta == null) {
            "${lugar.nombre} queda a ${distanciaHablada(metros(p, d))} en linea recta, hacia el ${rumbo(p, d)}. " +
            "No pude calcular el camino por las calles."
        } else {
            "Para llegar a ${lugar.nombre}: son ${distanciaHablada(ruta.metros)}, unos ${minutos(ruta.metros)} caminando. " +
            ruta.pasos + " Tene cuidado al cruzar."
        }.let { hablable(it) }
        return Resultado(texto, mapOf("resultado" to texto, "decile" to texto))
    }

    private fun elegirDestino(destino: String, p: Posicion): Lugar? {
        val lista = if (ahora() - ultimosT < VIDA_LUGARES_MS) ultimos else emptyList()
        numeroDe(destino)?.let { n -> lista.getOrNull(n - 1)?.let { return it } }
        val dn = normalizarCalle(destino)
        lista.firstOrNull { val ln = normalizarCalle(it.nombre); dn.length >= 3 && (ln.contains(dn) || dn.contains(ln)) }?.let { return it }
        // Una direccion ("Rivadavia 5000"): directo al mapa
        if (destino.any { it.isDigit() } && numeroDe(destino) == null) {
            val pos = try { loc.buscarDireccion(destino, p) } catch (e: Exception) { null }
            if (pos != null) return Lugar(destino, destino, pos, metros(p, pos))
        }
        // Algo para buscar ("la farmacia mas cercana", "el Hospital Durand")
        val buscado = try { lugaresCerca(destino, p) } catch (e: Exception) { null }
        if (buscado != null) buscado.firstOrNull { it.pos != null }?.let { ultimos = buscado; ultimosT = ahora(); return it }
        val pos = try { loc.buscarDireccion(destino, p) } catch (e: Exception) { null }
        return pos?.let { Lugar(destino, destino, it, metros(p, it)) }
    }

    class Ruta(val metros: Double, val pasos: String)

    private fun pedirRuta(a: Posicion, b: Posicion): Ruta? {
        val url = "$servidorRutas/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?steps=true&overview=false&alternatives=false"
        val datos = Json.leer(web.get(url, 10_000))
        if (Json.ruta(datos, "code") != "Ok") return null
        val ruta = (Json.ruta(datos, "routes") as? List<*>)?.firstOrNull() ?: return null
        val total = (Json.ruta(ruta, "distance") as? Number)?.toDouble() ?: return null
        val pasos = ((Json.ruta(ruta, "legs") as? List<*>)?.firstOrNull()?.let { Json.ruta(it, "steps") } as? List<*>).orEmpty()
            .mapNotNull { s ->
                Paso(
                    (Json.ruta(s, "maneuver", "type") as? String).orEmpty(),
                    (Json.ruta(s, "maneuver", "modifier") as? String).orEmpty(),
                    (Json.ruta(s, "name") as? String).orEmpty().trim(),
                    (Json.ruta(s, "distance") as? Number)?.toDouble() ?: 0.0,
                    (Json.ruta(s, "maneuver", "exit") as? Number)?.toInt(),
                )
            }
        return Ruta(total, describirRuta(pasos))
    }

    class Paso(val tipo: String, val giro: String, val calle: String, var metros: Double, val salida: Int?)

    /** Pasos de OSRM -> indicaciones habladas, juntando los tramos que siguen por la misma calle. */
    fun describirRuta(pasosCrudos: List<Paso>): String {
        val pasos = ArrayList<Paso>()
        for (s in pasosCrudos) {
            val ultimo = pasos.lastOrNull()
            val sigueIgual = s.tipo in setOf("new name", "continue", "notification") &&
                (s.giro.isEmpty() || s.giro == "straight") && ultimo != null && ultimo.tipo != "arrive"
            val chiquito = s.metros < 8 && s.tipo in setOf("new name", "continue", "notification")
            if (ultimo != null && ((sigueIgual && (s.calle == ultimo.calle || s.calle.isEmpty())) || chiquito)) {
                ultimo.metros += s.metros
                continue
            }
            pasos += Paso(s.tipo, s.giro, s.calle, s.metros, s.salida)
        }
        val frases = ArrayList<String>()
        for ((i, s) in pasos.withIndex()) {
            val siguiente = pasos.drop(i + 1).firstOrNull { it.calle.isNotEmpty() && it.calle != s.calle }?.calle
            val tramo = tramoHablado(s.metros)
            val enCalle = if (s.calle.isNotEmpty()) " en ${s.calle}" else ""
            frases += when (s.tipo) {
                "depart" -> (if (s.calle.isNotEmpty()) "Sali por ${s.calle}" else "Empeza a caminar") +
                    (if (siguiente != null) " hacia $siguiente" else "") + " y camina $tramo."
                "arrive" -> "Llegas: queda " + when {
                    "left" in s.giro -> "a tu izquierda."
                    "right" in s.giro -> "a tu derecha."
                    else -> "adelante tuyo."
                }
                "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                    "En la rotonda toma la " + ordinal(s.salida ?: 1) + " salida" + enCalle + " y camina $tramo."
                "new name", "continue" ->
                    if (s.giro.isEmpty() || s.giro == "straight") "Segui derecho por ${s.calle.ifEmpty { "la misma calle" }}, $tramo."
                    else "${giroHablado(s.giro)}$enCalle y camina $tramo."
                else -> "${giroHablado(s.giro)}$enCalle y camina $tramo."
            }
        }
        if (frases.size <= MAX_INDICACIONES + 1) return frases.joinToString(" ")
        val corte = pasos[MAX_INDICACIONES - 1].calle
        return frases.take(MAX_INDICACIONES).joinToString(" ") +
            " Despues hay ${frases.size - MAX_INDICACIONES} indicaciones mas" +
            (if (corte.isNotEmpty()) ": cuando estes en $corte, preguntame de nuevo." else "; cuando avances, preguntame de nuevo.")
    }

    private fun giroHablado(g: String) = when (g) {
        "uturn" -> "Pega la vuelta"
        "sharp right" -> "Dobla bien a la derecha"
        "right" -> "Dobla a la derecha"
        "slight right" -> "Dobla apenas a la derecha"
        "sharp left" -> "Dobla bien a la izquierda"
        "left" -> "Dobla a la izquierda"
        "slight left" -> "Dobla apenas a la izquierda"
        else -> "Segui derecho"
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "primera"; 2 -> "segunda"; 3 -> "tercera"; 4 -> "cuarta"; 5 -> "quinta"; else -> "$n." }

    // =====================================================================
    // AYUDAS
    // =====================================================================
    private fun posicionOError(): Pair<Posicion?, Resultado?> {
        val faltan = loc.permisosFaltantes()
        if (faltan.isNotEmpty()) return Pair(null, frase("Me falta el permiso de ubicacion. En la app del celular anda a Ajustes y toca Permitir ubicacion.",
            "error" to "falta permiso de ubicacion"))
        loc.problema()?.let { return Pair(null, frase(it, "error" to it)) }
        val p = try { loc.posicion() } catch (e: Exception) { null }
            ?: return Pair(null, frase("No pude saber donde estas: el GPS no tiene senal. Si estas adentro, proba cerca de una ventana o afuera.",
                "error" to "sin posicion"))
        registro.log("   [UBICACION] posicion ${redondear5(p.lat)}, ${redondear5(p.lon)} (margen ${p.precision.toInt()} m)")
        return Pair(p, null)
    }

    private fun frase(f: String, vararg extra: Pair<String, Any?>) = Resultado(f, mapOf("resultado" to f, "decile" to f) + extra)

    companion object {
        const val RADIO_CALLES = 150
        const val RADIO_ESQUINA = 20.0
        const val MAX_DIST_CALLE = 45.0
        const val MAX_DIST_CALLE_GEOCODER = 70.0
        const val PRECISION_DUDOSA = 40f
        const val MAX_LUGARES = 3
        const val MAX_DISTANCIA_LUGAR = 15_000.0
        const val MAX_INDICACIONES = 6
        const val VIDA_LUGARES_MS = 15 * 60_000L
        const val METROS_POR_CUADRA = 100.0
        const val METROS_POR_MINUTO = 75.0       // 4,5 km/h, paso tranquilo

        private val PALABRAS_CALLE = setOf("avenida", "av", "avda", "calle", "pasaje", "pje", "boulevard", "bulevar", "bv", "diagonal", "diag")

        /** Abreviaturas de calles y nombres a palabras, para que la voz no lea "a ve" o "ene grado". */
        fun hablable(s: String): String = s
            .replace(Regex("\\bAv(da)?\\.?\\s+"), "Avenida ")
            .replace(Regex("\\bN\\.?\\s*[°º]\\s*"), "Numero ")
            .replace(Regex("\\bGral\\.\\s*"), "General ")
            .replace(Regex("\\bPte\\.\\s*"), "Presidente ")
            .replace(Regex("\\bDr\\.\\s*"), "Doctor ")
            .replace(Regex("\\bSta\\.\\s*"), "Santa ")
            .replace(Regex("\\bBv\\.?\\s+"), "Bulevar ")
            .replace(Regex("\\bEsc\\.\\s*"), "Escuela ")
            .replace(Regex("\\bSto\\.\\s*"), "Santo ")

        fun normalizarCalle(s: String): String =
            BuscadorContactos.normalizar(s).split(" ").filter { it.isNotEmpty() && it !in PALABRAS_CALLE }.joinToString(" ")

        fun mismaCalle(a: String, b: String): Boolean {
            val x = normalizarCalle(a); val y = normalizarCalle(b)
            return x.isNotEmpty() && (x == y || (x.length >= 4 && y.length >= 4 && (x.endsWith(y) || y.endsWith(x))))
        }

        /** Distancia en metros (Haversine). */
        fun metros(a: Posicion, b: Posicion): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(h))
        }

        fun rumbo(a: Posicion, b: Posicion): String {
            val y = Math.sin(Math.toRadians(b.lon - a.lon)) * Math.cos(Math.toRadians(b.lat))
            val x = Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat)) -
                Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.cos(Math.toRadians(b.lon - a.lon))
            val grados = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
            return listOf("norte", "noreste", "este", "sudeste", "sur", "sudoeste", "oeste", "noroeste")[((grados + 22.5) / 45).toInt() % 8]
        }

        fun distanciaHablada(m: Double): String = when {
            m < 50 -> "menos de cincuenta metros"
            m < 1000 -> "${redondear10(m)} metros"
            else -> {
                val km = (m / 100).toInt() / 10.0
                if (km == Math.floor(km)) "${km.toInt()} ${if (km.toInt() == 1) "kilometro" else "kilometros"}"
                else "${km.toInt()} coma ${((km * 10).toInt() % 10)} kilometros"
            }
        }

        fun tramoHablado(m: Double): String {
            if (m < 15) return "unos pocos pasos"
            if (m < 80) return "unos ${redondear10(m)} metros"
            val cuadras = Math.round(m / METROS_POR_CUADRA).toInt()
            if (m > 1500) return "${distanciaHablada(m)}, unas $cuadras cuadras"
            return if (cuadras == 1) "una cuadra" else "$cuadras cuadras"
        }

        fun minutos(m: Double): String {
            val n = maxOf(1, Math.round(m / METROS_POR_MINUTO).toInt())
            return if (n == 1) "un minuto" else "$n minutos"
        }

        /** "4523" -> "4500" (asi se dice la altura); null si no hay numero. */
        fun alturaHablada(altura: String?): String? {
            val n = Regex("\\d+").find(altura.orEmpty())?.value?.toIntOrNull() ?: return null
            return if (n < 100) n.toString() else (n / 100 * 100).toString()
        }

        fun numeroDe(s: String): Int? {
            val t = BuscadorContactos.normalizar(s)
            Regex("^(el |la |lugar |opcion |numero )*(\\d)$").find(t)?.let { return it.groupValues[2].toInt() }
            val palabras = mapOf("uno" to 1, "una" to 1, "primero" to 1, "primera" to 1, "dos" to 2, "segundo" to 2, "segunda" to 2,
                "tres" to 3, "tercero" to 3, "tercera" to 3)
            val w = t.split(" ").filter { it !in setOf("el", "la", "lugar", "opcion", "numero") }
            return if (w.size == 1) palabras[w[0]] else null
        }

        private fun redondear10(m: Double) = (Math.round(m / 10) * 10).toInt()
        private fun redondear5(v: Double) = Math.round(v * 100_000) / 100_000.0
    }
}
