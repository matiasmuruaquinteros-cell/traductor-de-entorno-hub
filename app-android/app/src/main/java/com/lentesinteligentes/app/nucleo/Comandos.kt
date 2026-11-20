package com.lentesinteligentes.app.nucleo

import java.util.Locale

/**
 * COMANDOS POR VOZ: herramientas (function calling) que Gemini puede llamar.
 *   cambiar_ajuste        volumen, velocidad, tono, modo, microfono, foto, voz
 *   olvidar_conversacion  empezar de cero
 *   ver_foto              volver a mirar una foto vieja de la conversacion
 * (las de llamadas estan en Llamadas.kt)
 * La clave, el puerto y el modelo NO se pueden cambiar por voz, a proposito.
 */
object Comandos {
    const val CAMBIAR_AJUSTE = "cambiar_ajuste"
    const val OLVIDAR = "olvidar_conversacion"
    const val VER_FOTO = "ver_foto"

    val AJUSTES = listOf("volumen", "velocidad_voz", "tono_voz", "modo", "microfono",
                         "mejorar_foto", "rotacion_foto", "voz_conversacion")
    val VOCES_LIVE = listOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Orus", "Zephyr")

    // Rangos (los mismos que la pantalla de Ajustes)
    const val VOL_MIN = 0.50; const val VOL_MAX = 0.95; const val VOL_PASO = 0.10
    const val VEL_MIN = 0.7f; const val VEL_MAX = 1.7f; const val VEL_PASO = 0.2f
    const val TONO_MIN = 0.7f; const val TONO_MAX = 1.4f; const val TONO_PASO = 0.1f

    private fun str(desc: String, valores: List<String>? = null): Map<String, Any?> =
        if (valores == null) mapOf("type" to "STRING", "description" to desc)
        else mapOf("type" to "STRING", "description" to desc, "enum" to valores)

    /** Declaraciones para el campo "tools" (sirve igual para REST y para Live). */
    val declaraciones: List<Map<String, Any?>> = listOf(
        mapOf(
            "name" to CAMBIAR_AJUSTE,
            "description" to "Cambia un ajuste del dispositivo cuando el usuario lo pide con la voz " +
                "(por ejemplo: subi el volumen, habla mas lento, pasa a manos libres, volve al modo clasico). " +
                "Solo llamala si el usuario pide un cambio de forma clara.",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf(
                    "ajuste" to str("Que ajuste cambiar. volumen: volumen del parlante. velocidad_voz: rapidez de la voz. " +
                        "tono_voz: voz mas aguda o mas grave. modo: conversacion (Gemini Live) o clasico (pregunta y respuesta). " +
                        "microfono: boton (hablar con el boton apretado) o manos_libres (microfono abierto). " +
                        "mejorar_foto: mejorar el brillo y nitidez de las fotos. rotacion_foto: girar la foto. " +
                        "voz_conversacion: la voz de Gemini en el modo conversacion.", AJUSTES),
                    "accion" to str("subir, bajar, poner (un valor concreto), activar o desactivar.",
                        listOf("subir", "bajar", "poner", "activar", "desactivar")),
                    "valor" to str("Solo si pide un valor concreto. volumen: porcentaje de 50 a 95, o maximo/minimo. " +
                        "velocidad_voz: de 0.7 a 1.7, o normal. tono_voz: de 0.7 a 1.4, o normal. " +
                        "modo: conversacion o clasico. microfono: boton o manos_libres. rotacion_foto: 0, 90, 180 o 270. " +
                        "voz_conversacion: " + VOCES_LIVE.joinToString(", ") + " o por_defecto."),
                ),
                "required" to listOf("ajuste", "accion"),
            ),
        ),
        mapOf(
            "name" to OLVIDAR,
            "description" to "Borra toda la memoria de la conversacion y empieza de cero. " +
                "Solo si el usuario lo pide claramente (olvida todo, empecemos de nuevo, borra la conversacion).",
        ),
        mapOf(
            "name" to VER_FOTO,
            "description" to "Trae la imagen de una foto anterior de esta conversacion para volver a mirarla. " +
                "Usala cuando la pregunta se refiere a una foto vieja y la descripcion guardada no alcanza para responder.",
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to mapOf("numero" to mapOf("type" to "INTEGER", "description" to "Numero de la FOTO del indice")),
                "required" to listOf("numero"),
            ),
        ),
    )

    val herramientas: List<Map<String, Any?>> = listOf(mapOf("functionDeclarations" to declaraciones))

    /** Las herramientas de siempre y, si estan habilitadas, las de llamadas. */
    fun herramientas(conLlamadas: Boolean, conUbicacion: Boolean = false): List<Map<String, Any?>> =
        if (!conLlamadas && !conUbicacion) herramientas
        else listOf(mapOf("functionDeclarations" to declaraciones +
            (if (conLlamadas) HerramientasLlamadas.declaraciones else emptyList()) +
            (if (conUbicacion) HerramientasUbicacion.declaraciones else emptyList())))

    /** Texto para las instrucciones: como usar las herramientas y como estan los ajustes ahora. */
    fun instrucciones(a: Ajustes): String =
        " HERRAMIENTAS: si el usuario pide cambiar el volumen, la velocidad o el tono de la voz, el modo " +
        "(conversacion o clasico), el microfono (boton o manos libres), la foto o la voz, llama a $CAMBIAR_AJUSTE. " +
        "Si pide olvidar todo, llama a $OLVIDAR. Si pregunta por como estan los ajustes, contestale con estos datos. " +
        "AJUSTES ACTUALES: ${describir(a)}."

    fun describir(a: Ajustes): String =
        "volumen ${pct(a.volumen)} por ciento, velocidad de la voz ${num(a.velocidadVoz)}, tono ${num(a.tonoVoz)}, " +
        "modo ${if (a.modoLive) "conversacion" else "clasico"}, microfono ${if (a.micAbierto) "manos libres" else "con el boton"}, " +
        "mejorar foto ${if (a.mejorarFoto) "activado" else "desactivado"}, rotacion ${a.rotacion} grados, " +
        "voz de conversacion ${a.vozLive.ifEmpty { "por defecto" }}"

    class Resultado(
        val ajustes: Ajustes,
        val frase: String,              // la confirmacion para decir en voz alta
        val cambio: Boolean,
        val reconectarLive: Boolean,    // modo, microfono o voz: hay que rehacer la sesion de Gemini Live
    )

    /** Aplica un pedido de cambiar_ajuste. Nunca tira excepcion: si no entiende, lo dice. */
    fun aplicar(a: Ajustes, args: Map<*, *>?, enLive: Boolean): Resultado {
        val ajuste = (args?.get("ajuste") as? String).orEmpty().lowercase(Locale.ROOT).trim()
        val accion = (args?.get("accion") as? String).orEmpty().lowercase(Locale.ROOT).trim()
        val valor = (args?.get("valor")?.toString()).orEmpty().lowercase(Locale.ROOT).trim()
        val sube = accion == "subir"; val baja = accion == "bajar"
        val activa = accion == "activar" || (accion == "poner" && valor in listOf("si", "activado", "true", "on"))
        val desactiva = accion == "desactivar" || (accion == "poner" && valor in listOf("no", "desactivado", "false", "off"))
        fun nada(frase: String) = Resultado(a, frase, false, false)
        fun numero(): Double? = Regex("[0-9]+([.,][0-9]+)?").find(valor)?.value?.replace(',', '.')?.toDoubleOrNull()

        when (ajuste) {
            "volumen" -> {
                val nuevo = when {
                    "max" in valor -> VOL_MAX
                    "min" in valor -> VOL_MIN
                    sube -> a.volumen + VOL_PASO
                    baja -> a.volumen - VOL_PASO
                    numero() != null -> numero()!!.let { if (it > 1.5) it / 100.0 else it }
                    else -> return nada("No entendi a que volumen lo pongo.")
                }.coerceIn(VOL_MIN, VOL_MAX)
                if (Math.abs(nuevo - a.volumen) < 0.005) {
                    return nada(if (sube) "El volumen ya esta al maximo." else if (baja) "El volumen ya esta al minimo."
                                else "El volumen ya estaba en ${pct(nuevo)} por ciento.")
                }
                return Resultado(a.copy(volumen = redondear(nuevo)), "Listo, volumen en ${pct(nuevo)} por ciento.", true, false)
            }
            "velocidad_voz" -> {
                val nuevo = when {
                    "normal" in valor -> 1.0f
                    sube -> a.velocidadVoz + VEL_PASO
                    baja -> a.velocidadVoz - VEL_PASO
                    numero() != null -> numero()!!.toFloat()
                    else -> return nada("No entendi a que velocidad pongo la voz.")
                }.coerceIn(VEL_MIN, VEL_MAX)
                val nota = if (enLive) " Se nota en el modo clasico; en este modo hablo yo y voy a tratar de ir a ese ritmo." else ""
                if (Math.abs(nuevo - a.velocidadVoz) < 0.01f) return nada("La velocidad ya estaba en ${num(nuevo)}.$nota")
                return Resultado(a.copy(velocidadVoz = redondearF(nuevo)),
                    "Listo, " + (if (nuevo > a.velocidadVoz) "hablo mas rapido" else "hablo mas despacio") + ".$nota", true, false)
            }
            "tono_voz" -> {
                val nuevo = when {
                    "normal" in valor -> 1.0f
                    sube -> a.tonoVoz + TONO_PASO
                    baja -> a.tonoVoz - TONO_PASO
                    numero() != null -> numero()!!.toFloat()
                    else -> return nada("No entendi como cambio el tono.")
                }.coerceIn(TONO_MIN, TONO_MAX)
                val nota = if (enLive) " Se nota en el modo clasico." else ""
                if (Math.abs(nuevo - a.tonoVoz) < 0.01f) return nada("El tono ya estaba asi.$nota")
                return Resultado(a.copy(tonoVoz = redondearF(nuevo)),
                    "Listo, voz " + (if (nuevo > a.tonoVoz) "mas aguda" else "mas grave") + ".$nota", true, false)
            }
            "modo" -> {
                val live = when {
                    "conversa" in valor || "live" in valor || activa -> true
                    "clasic" in valor || desactiva -> false
                    else -> !a.modoLive
                }
                if (live == a.modoLive) return nada("Ya estoy en el modo ${if (live) "conversacion" else "clasico"}.")
                return Resultado(a.copy(modoLive = live),
                    if (live) "Listo, paso al modo conversacion. Dame unos segundos."
                    else "Listo, vuelvo al modo clasico. Dame unos segundos.", true, true)
            }
            "microfono" -> {
                val abierto = when {
                    "manos" in valor || "abierto" in valor || activa -> true
                    "boton" in valor || "botón" in valor || desactiva -> false
                    else -> !a.micAbierto
                }
                if (abierto == a.micAbierto) return nada(if (abierto) "Ya estoy en manos libres." else "Ya estoy en modo boton.")
                val nota = if (!a.modoLive) " Esto se usa en el modo conversacion." else ""
                return Resultado(a.copy(micAbierto = abierto),
                    (if (abierto) "Listo, manos libres: te escucho sin apretar el boton." else "Listo, ahora hablame con el boton apretado.") + nota,
                    true, a.modoLive)
            }
            "mejorar_foto" -> {
                val si = if (activa) true else if (desactiva) false else !a.mejorarFoto
                if (si == a.mejorarFoto) return nada(if (si) "Ya estoy mejorando las fotos." else "Ya estaba sin mejorar las fotos.")
                return Resultado(a.copy(mejorarFoto = si), if (si) "Listo, voy a mejorar las fotos." else "Listo, dejo las fotos como salen.", true, false)
            }
            "rotacion_foto" -> {
                val n = numero()?.toInt()
                val nuevo = when {
                    n != null && n in listOf(0, 90, 180, 270) -> n
                    sube || accion == "poner" -> (a.rotacion + 90) % 360
                    baja -> (a.rotacion + 270) % 360
                    else -> return nada("No entendi cuanto giro la foto.")
                }
                if (nuevo == a.rotacion) return nada("La foto ya estaba girada $nuevo grados.")
                return Resultado(a.copy(rotacion = nuevo), "Listo, giro la foto $nuevo grados.", true, false)
            }
            "voz_conversacion" -> {
                val nueva = if ("defecto" in valor || valor.isEmpty()) "" else VOCES_LIVE.firstOrNull { it.lowercase(Locale.ROOT) == valor } ?: ""
                if (valor.isNotEmpty() && "defecto" !in valor && nueva.isEmpty())
                    return nada("No conozco esa voz. Puedo usar " + VOCES_LIVE.joinToString(", ") + ".")
                if (nueva == a.vozLive) return nada("Ya estoy usando esa voz.")
                return Resultado(a.copy(vozLive = nueva), "Listo, cambio la voz. Dame un segundo.", true, a.modoLive)
            }
        }
        return nada("Ese ajuste no lo puedo cambiar con la voz.")
    }

    private fun pct(v: Double) = Math.round(v * 100).toInt()
    private fun num(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun redondear(v: Double) = Math.round(v * 100) / 100.0
    private fun redondearF(v: Float) = Math.round(v * 10) / 10f
}
