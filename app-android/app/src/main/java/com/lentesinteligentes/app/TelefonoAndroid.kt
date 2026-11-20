package com.lentesinteligentes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.lentesinteligentes.app.nucleo.Ajustes
import com.lentesinteligentes.app.nucleo.EstadoTel
import com.lentesinteligentes.app.nucleo.GestorLlamadas
import com.lentesinteligentes.app.nucleo.NumeroContacto
import com.lentesinteligentes.app.nucleo.Registro
import com.lentesinteligentes.app.nucleo.Telefono

/**
 * El telefono real: agenda, llamar, atender, cortar y altavoz.
 * Atender y cortar usan TelecomManager.acceptRingingCall / endCall: Android las marca como
 * "viejas" desde la version 10, pero siguen funcionando para apps con el permiso ANSWER_PHONE_CALLS.
 */
class TelefonoAndroid(private val ctx: Context, private val registro: Registro) : Telefono {
    private val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var agenda: List<NumeroContacto> = emptyList()
    @Volatile private var agendaT = 0L
    @Volatile private var altavozNuestro = false

    private fun tiene(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun permisosFaltantes(): List<String> = PERMISOS.filter { !tiene(it.first) }.map { it.second }

    override fun contactos(): List<NumeroContacto> {
        if (!tiene(Manifest.permission.READ_CONTACTS)) return emptyList()
        val ahora = System.currentTimeMillis()
        if (agenda.isNotEmpty() && ahora - agendaT < VIDA_AGENDA_MS) return agenda
        val lista = ArrayList<NumeroContacto>()
        val proy = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proy, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val nombre = c.getString(0) ?: continue
                    val numero = c.getString(1) ?: continue
                    lista += NumeroContacto(nombre, numero, tipoDe(c.getInt(2), c.getString(3)))
                }
            }
        } catch (e: Exception) {
            registro.log("   [LLAMADAS] no pude leer los contactos: ${e.message}")
        }
        agenda = lista
        agendaT = ahora
        registro.log("   [LLAMADAS] agenda: ${lista.size} numeros")
        return lista
    }

    private fun tipoDe(t: Int, etiqueta: String?): String = when (t) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "celular"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "telefono de casa"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "numero del trabajo"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "numero principal"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> etiqueta?.trim()?.ifEmpty { null } ?: "otro numero"
        else -> "otro numero"
    }

    override fun nombreDe(numero: String): String? {
        if (numero.isBlank() || !tiene(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    override fun llamar(numero: String, altavoz: Boolean): String? {
        if (!tiene(Manifest.permission.CALL_PHONE)) return "falta el permiso para hacer llamadas"
        val uri = Uri.fromParts("tel", numero, null)
        val extras = Bundle()
        extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, altavoz && !hayAuriculares())
        return try {
            telecom.placeCall(uri, extras)
            registro.log("   [LLAMADAS] llamando a $numero")
            null
        } catch (e: Exception) {
            registro.log("   [LLAMADAS] placeCall fallo (${e.message}); pruebo con el marcador")
            try {
                ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e2: Exception) {
                e2.message ?: "error desconocido"
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun atender(): String? {
        if (!tiene(Manifest.permission.ANSWER_PHONE_CALLS)) return "falta el permiso para atender llamadas"
        return try {
            telecom.acceptRingingCall()
            null
        } catch (e: Exception) {
            e.message ?: "error desconocido"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun cortar(): String? {
        if (!tiene(Manifest.permission.ANSWER_PHONE_CALLS)) return "falta el permiso para cortar llamadas"
        if (Build.VERSION.SDK_INT < 28) return "este celular no deja cortar llamadas desde una app"
        return try {
            if (telecom.endCall()) null else "el celular no encontro ninguna llamada para cortar"
        } catch (e: Exception) {
            e.message ?: "error desconocido"
        }
    }

    private fun hayAuriculares(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in AURICULARES }
    } catch (_: Exception) {
        false
    }

    /** Pone la llamada en altavoz. Algunos celulares lo ignoran: queda anotado en el registro. */
    @Suppress("DEPRECATION")
    override fun activarAltavoz() {
        if (hayAuriculares()) { registro.log("   [LLAMADAS] hay auriculares conectados: no pongo el altavoz"); return }
        try {
            var ok = false
            if (Build.VERSION.SDK_INT >= 31) {
                val parlante = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (parlante != null) ok = audio.setCommunicationDevice(parlante)
            }
            if (!audio.isSpeakerphoneOn) audio.isSpeakerphoneOn = true
            ok = ok || audio.isSpeakerphoneOn
            altavozNuestro = true
            registro.log("   [LLAMADAS] altavoz ${if (ok) "activado" else "pedido (el celular no confirmo)"}")
        } catch (e: Exception) {
            registro.log("   [LLAMADAS] no pude activar el altavoz: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun soltarAltavoz() {
        if (!altavozNuestro) return
        altavozNuestro = false
        try {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            audio.isSpeakerphoneOn = false
        } catch (_: Exception) {}
    }

    /** La agenda se vuelve a leer en la proxima busqueda (por si agregaron un contacto). */
    fun olvidarAgenda() { agendaT = 0L }

    companion object {
        private const val VIDA_AGENDA_MS = 5 * 60_000L
        private const val TYPE_BLE_HEADSET = 26          // AudioDeviceInfo.TYPE_BLE_HEADSET (Android 12)
        private val AURICULARES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, TYPE_BLE_HEADSET,
        )

        /** Permiso -> como se lo nombra en voz alta y en la pantalla. */
        val PERMISOS = listOf(
            Manifest.permission.READ_CONTACTS to "contactos",
            Manifest.permission.CALL_PHONE to "hacer llamadas",
            Manifest.permission.READ_PHONE_STATE to "estado del telefono",
            Manifest.permission.ANSWER_PHONE_CALLS to "atender y cortar",
            Manifest.permission.READ_CALL_LOG to "ver quien llama",
        )
        val NOMBRES_PERMISOS: Array<String> get() = PERMISOS.map { it.first }.toTypedArray()
    }
}

/** Escucha cuando el celular suena, se atiende o se corta, y se lo pasa al gestor. */
class ReceptorLlamadas(private val gestor: GestorLlamadas) : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val estado = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> EstadoTel.SONANDO
            TelephonyManager.EXTRA_STATE_OFFHOOK -> EstadoTel.EN_CURSO
            TelephonyManager.EXTRA_STATE_IDLE -> EstadoTel.LIBRE
            else -> return
        }
        @Suppress("DEPRECATION")
        val numero = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        gestor.cambioDeEstado(estado, numero)
    }

    fun registrar(ctx: Context) {
        val filtro = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        // Es un aviso del sistema (solo Android lo puede mandar)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(this, filtro, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(this, filtro)
    }
}

/**
 * Voz por el parlante del CELULAR (no del dispositivo): avisa las llamadas entrantes en el modo
 * clasico, donde el ESP32 solo escucha a la app cuando se aprieta un boton.
 * Usa el canal de accesibilidad: suena aunque el celular este en silencio.
 */
class AvisoEnCelular(ctx: Context, motor: String, private val registro: Registro) {
    @Volatile private var listo = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext, { s ->
        listo = s == TextToSpeech.SUCCESS
        if (!listo) registro.log("[LLAMADAS] el motor de voz del celular no arranco: no puedo avisar en voz alta")
    }, motor.ifEmpty { null })

    fun decir(texto: String, a: Ajustes) {
        if (!listo) { registro.log("[LLAMADAS] (aviso no dicho: motor de voz no listo) $texto"); return }
        try {
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            val voces = try { tts.voices.orEmpty() } catch (_: Exception) { emptySet() }
            (voces.firstOrNull { it.name == a.nombreVoz } ?: VozAndroid.vocesEnEspanol(voces).firstOrNull())?.let { tts.voice = it }
            tts.setSpeechRate(a.velocidadVoz)
            tts.setPitch(a.tonoVoz)
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle(), "llamada")
            registro.log("[LLAMADAS] aviso en el celular: $texto")
        } catch (e: Exception) {
            registro.log("[LLAMADAS] no pude avisar en el celular: ${e.message}")
        }
    }

    fun cerrar() { try { tts.shutdown() } catch (_: Exception) {} }
}
