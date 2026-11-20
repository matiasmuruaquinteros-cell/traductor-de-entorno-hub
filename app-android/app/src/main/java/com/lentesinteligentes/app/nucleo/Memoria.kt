package com.lentesinteligentes.app.nucleo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MEMORIA DE LA CONVERSACION COMPLETA (en la RAM del celular).
 *  - Guarda todos los turnos y todas las fotos desde que arranca la app
 *    (o desde que se reinicia el dispositivo) hasta que se apaga.
 *  - La comparten el modo clasico y el modo conversacion: lo que se hablo en uno
 *    lo sabe el otro.
 *  - A Gemini se le manda TODO el texto (barato) y un indice de fotos con una
 *    descripcion corta. La imagen de una foto vieja solo viaja si Gemini la pide
 *    con la herramienta ver_foto: asi no se confunde ni se vuelve lento.
 */
class FotoGuardada(val id: Int, val hora: String, val jpeg: ByteArray) {
    @Volatile var descripcion: String = ""
}

class TurnoMemoria(
    val pregunta: String,
    val respuesta: String,
    val fotoId: Int?,
    val origen: String,        // "clasico" o "live"
    val hora: String,
)

class MemoriaConversacion(private val registro: Registro) {
    private val candado = Any()
    private val turnos = ArrayList<TurnoMemoria>()
    private val fotos = ArrayList<FotoGuardada>()
    private var proximaFoto = 1
    private var enviadosALive = 0          // turnos del modo clasico que Gemini Live ya conoce

    /** Limites de seguridad para que la RAM no crezca sin fin (son muy altos para una charla real). */
    var maxTurnos = 400
    var maxFotosConImagen = 150            // las mas viejas conservan solo la descripcion

    fun hayAlgo(): Boolean = synchronized(candado) { turnos.isNotEmpty() || fotos.isNotEmpty() }

    fun olvidar(motivo: String = "") = synchronized(candado) {
        turnos.clear(); fotos.clear(); proximaFoto = 1; enviadosALive = 0
        registro.log("[MEMORIA] conversacion olvidada" + if (motivo.isNotEmpty()) " ($motivo)" else "")
    }

    fun nuevaFoto(jpeg: ByteArray): FotoGuardada = synchronized(candado) {
        val f = FotoGuardada(proximaFoto++, hora(), jpeg)
        fotos.add(f)
        // las fotos muy viejas sueltan la imagen y quedan con su descripcion
        val conImagen = fotos.filter { it.jpeg.isNotEmpty() }
        if (conImagen.size > maxFotosConImagen) {
            val vieja = conImagen.first()
            fotos[fotos.indexOf(vieja)] = FotoGuardada(vieja.id, vieja.hora, ByteArray(0)).also { it.descripcion = vieja.descripcion }
        }
        f
    }

    fun foto(id: Int): FotoGuardada? = synchronized(candado) { fotos.firstOrNull { it.id == id } }

    fun describirFoto(id: Int, texto: String) = synchronized(candado) {
        val f = fotos.firstOrNull { it.id == id } ?: return@synchronized
        if (f.descripcion.isEmpty() && texto.isNotBlank()) f.descripcion = recortar(texto, 220)
    }

    fun agregarTurno(pregunta: String, respuesta: String, fotoId: Int?, origen: String) = synchronized(candado) {
        turnos.add(TurnoMemoria(pregunta.ifBlank { "(sin pregunta hablada)" }, respuesta, fotoId, origen, hora()))
        while (turnos.size > maxTurnos) { turnos.removeAt(0); if (enviadosALive > 0) enviadosALive-- }
        if (fotoId != null) describirFoto(fotoId, respuesta)
    }

    fun turnos(): List<TurnoMemoria> = synchronized(candado) { turnos.toList() }

    /** La foto mas nueva si pertenece a uno de los ultimos `ultimos` turnos (para "y de que color es?"). */
    fun fotoReciente(ultimos: Int): FotoGuardada? = synchronized(candado) {
        val f = fotos.lastOrNull() ?: return null
        if (f.jpeg.isEmpty()) return null
        val recientes = turnos.takeLast(ultimos)
        if (recientes.any { it.fotoId == f.id } || turnos.none { it.fotoId == f.id }) f else null
    }

    /** "FOTO 3 (14:32): una taza roja..." una por linea. */
    fun indiceFotos(): String = synchronized(candado) {
        fotos.joinToString("\n") { f ->
            "FOTO ${f.id} (${f.hora}): ${f.descripcion.ifEmpty { "sin descripcion todavia" }}" +
                if (f.jpeg.isEmpty()) " [la imagen ya no esta guardada]" else ""
        }
    }

    /**
     * Lo que Gemini Live todavia no sabe, en texto, y lo marca como enviado.
     * todo = true: sesion nueva de Gemini (no reanudada): va toda la conversacion.
     * todo = false: sesion reanudada: solo lo que se hablo en el modo clasico mientras tanto.
     */
    fun pendientesParaLive(todo: Boolean): String = synchronized(candado) {
        val nuevos = if (todo) turnos.toList() else turnos.drop(enviadosALive).filter { it.origen == "clasico" }
        enviadosALive = turnos.size
        if (nuevos.isEmpty() && !(todo && fotos.isNotEmpty())) return ""
        val sb = StringBuilder("Contexto: esto ya lo hablamos antes en esta misma sesion (no lo repitas, solo tenelo en cuenta):\n")
        for (t in nuevos) {
            sb.append("[${t.hora}] Yo: ${t.pregunta}")
            if (t.fotoId != null) sb.append(" (con la FOTO ${t.fotoId})")
            sb.append("\nVos: ${t.respuesta}\n")
        }
        val indice = indiceFotos()
        if (indice.isNotEmpty()) sb.append("Fotos guardadas:\n").append(indice)
        sb.toString()
    }

    /** Lo que ya escucho Gemini Live por su cuenta tambien cuenta como enviado. */
    fun marcarVistoPorLive() = synchronized(candado) { enviadosALive = turnos.size }

    fun resumenContadores(): String = synchronized(candado) { "${turnos.size} turnos, ${fotos.size} fotos" }

    private fun hora() = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    companion object {
        fun recortar(t: String, max: Int): String = if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "..."
    }
}
