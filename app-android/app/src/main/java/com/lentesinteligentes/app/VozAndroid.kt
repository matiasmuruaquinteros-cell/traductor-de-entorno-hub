package com.lentesinteligentes.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.lentesinteligentes.app.nucleo.Ajustes
import com.lentesinteligentes.app.nucleo.Audio
import com.lentesinteligentes.app.nucleo.Registro
import com.lentesinteligentes.app.nucleo.Voz
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voz del celular (sin internet). En vez de hablar por el parlante del celular,
 * genera un WAV (synthesizeToFile) que despues se manda al ESP32.
 * IMPORTANTE: nunca llamar a sintetizar() desde el hilo principal (se traba esperando al motor).
 */
class VozAndroid(private val ctx: Context, private val registro: Registro) : Voz {

    private val candado = Any()
    private var tts: TextToSpeech? = null
    private var motorActual: String? = null
    private var configActual = ""
    private val pendientes = ConcurrentHashMap<String, CountDownLatch>()
    private val errores = ConcurrentHashMap<String, Int>()
    private val contador = AtomicInteger()

    /** Deja el motor listo para que la primera respuesta no tarde de mas. */
    fun precalentar(a: Ajustes): String = synchronized(candado) {
        val t = asegurarMotor(a.motorVoz)
        aplicarVoz(t, a)
        val v = try { t.voice } catch (_: Exception) { null }
        "motor ${t.defaultEngine.takeIf { a.motorVoz.isEmpty() } ?: a.motorVoz}, voz ${v?.name ?: "?"}"
    }

    override fun sintetizar(texto: String, ajustes: Ajustes): ByteArray = synchronized(candado) {
        try {
            sintetizarSinReintento(texto, ajustes)
        } catch (e: Exception) {
            // El sistema a veces mata el servicio de voz: se recrea una vez y se reintenta
            registro.log("   [VOZ] fallo (${e.message}); reinicio el motor y reintento")
            cerrarMotor()
            sintetizarSinReintento(texto, ajustes)
        }
    }

    private fun sintetizarSinReintento(texto: String, a: Ajustes): ByteArray {
        val t = asegurarMotor(a.motorVoz)
        aplicarVoz(t, a)
        val limite = TextToSpeech.getMaxSpeechInputLength()
        val frase = if (texto.length > limite) texto.substring(0, limite) else texto

        val id = "lentes_" + contador.incrementAndGet()
        val archivo = File(ctx.cacheDir, "$id.wav")
        val listo = CountDownLatch(1)
        pendientes[id] = listo
        try {
            val r = t.synthesizeToFile(frase, Bundle(), archivo, id)
            if (r != TextToSpeech.SUCCESS) throw IllegalStateException("el motor de voz rechazo el texto")
            if (!listo.await(15_000L + frase.length * 60L, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("el motor de voz tardo demasiado")
            }
            errores.remove(id)?.let { throw IllegalStateException("el motor de voz fallo (codigo $it)") }
            if (!archivo.exists() || archivo.length() <= 44) throw IllegalStateException("el motor de voz no genero audio")
            return Audio.prepararParaParlante(archivo.readBytes(), a.frecuenciaSalida, a.volumen)
        } finally {
            pendientes.remove(id)
            archivo.delete()
        }
    }

    fun cerrar() = synchronized(candado) { cerrarMotor() }

    private fun cerrarMotor() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        motorActual = null
        configActual = ""
    }

    private fun asegurarMotor(motor: String): TextToSpeech {
        tts?.let { if (motorActual == motor) return it }
        cerrarMotor()
        val listo = CountDownLatch(1)
        var estado = TextToSpeech.ERROR
        val nuevo = TextToSpeech(ctx.applicationContext, { s -> estado = s; listo.countDown() }, motor.ifEmpty { null })
        if (!listo.await(10, TimeUnit.SECONDS) || estado != TextToSpeech.SUCCESS) {
            try { nuevo.shutdown() } catch (_: Exception) {}
            throw IllegalStateException("el motor de voz no arranco (revisa Ajustes > Texto a voz)")
        }
        nuevo.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pendientes[it]?.countDown() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { marcarError(utteranceId, -1) }
            override fun onError(utteranceId: String?, errorCode: Int) { marcarError(utteranceId, errorCode) }
        })
        tts = nuevo
        motorActual = motor
        return nuevo
    }

    private fun marcarError(id: String?, codigo: Int) {
        if (id == null) return
        errores[id] = codigo
        pendientes[id]?.countDown()
    }

    private fun aplicarVoz(t: TextToSpeech, a: Ajustes) {
        val clave = "${a.nombreVoz}|${a.velocidadVoz}|${a.tonoVoz}"
        if (clave == configActual) return
        val voces = try { t.voices ?: emptySet() } catch (_: Exception) { emptySet<Voice>() }
        val elegida = voces.firstOrNull { it.name == a.nombreVoz } ?: vocesEnEspanol(voces).firstOrNull()
        if (elegida != null) {
            t.voice = elegida
        } else {
            t.language = Locale.forLanguageTag("es-US")
            registro.log("   [VOZ] no encontre voces en espanol instaladas; uso el idioma por defecto")
        }
        t.setSpeechRate(a.velocidadVoz)
        t.setPitch(a.tonoVoz)
        configActual = clave
    }

    companion object {
        /** Voces en espanol ya descargadas, primero las que no necesitan internet. */
        fun vocesEnEspanol(voces: Collection<Voice>): List<Voice> =
            voces.filter {
                it.locale.language == "es" &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.sortedWith(
                compareBy<Voice>({ it.isNetworkConnectionRequired }, { prioridadPais(it.locale.country) },
                    { -it.quality }, { it.name })
            )

        private fun prioridadPais(p: String) = when (p) {
            "AR" -> 0; "US" -> 1; "MX" -> 2; "419" -> 3; "ES" -> 5; else -> 4
        }

        fun describir(v: Voice): String {
            val pais = v.locale.displayCountry.ifEmpty { v.locale.country }
            val red = if (v.isNetworkConnectionRequired) "necesita internet" else "sin internet"
            return "${v.name} ($pais, $red)"
        }
    }
}
