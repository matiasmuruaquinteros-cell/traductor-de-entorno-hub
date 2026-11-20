package com.lentesinteligentes.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lentesinteligentes.app.nucleo.Consulta
import com.lentesinteligentes.app.nucleo.Registro
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Lo que el servicio sabe y la pantalla muestra. */
object Estado {
    private val principal = Handler(Looper.getMainLooper())
    private val lineas = ArrayDeque<String>()
    private val avisoPendiente = AtomicBoolean(false)
    val oyentes = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var activo = false
    @Volatile var puerto = Preferencias.PUERTO_POR_DEFECTO
    @Volatile var modelo = ""
    @Volatile var problemaGemini = ""
    @Volatile var problemaVoz = ""
    @Volatile var ipEsp32 = ""
    @Volatile var vistoEsp32 = 0L
    @Volatile var ultimaConsulta: Consulta? = null
    @Volatile var alOlvidar: (() -> Unit)? = null     // lo pone el servicio; la pantalla lo llama
    /** Sube cada vez que un comando de voz cambia los ajustes: la pantalla los vuelve a leer. */
    @Volatile var versionAjustes = 0
        private set

    fun ajustesCambiadosPorVoz() { versionAjustes++; avisar() }

    fun log(mensaje: String) {
        Log.i("Traductor", mensaje)
        val hora = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lineas) {
            if (mensaje.startsWith("\n")) lineas.addLast("")          // separa cada consulta
            for (l in mensaje.trimStart('\n').lines()) {
                val conHora = l.isBlank() || l.startsWith(" ") || (l.length > 3 && l[0] == '[' && l[1].isDigit())
                lineas.addLast(if (conHora) l else "[$hora] $l")
            }
            while (lineas.size > 500) lineas.removeFirst()
        }
        avisar()
    }

    fun textoRegistro(): String = synchronized(lineas) { lineas.joinToString("\n") }
    fun limpiarRegistro() { synchronized(lineas) { lineas.clear() }; avisar() }

    fun vioEsp32(ip: String) {
        val nuevo = ip != ipEsp32
        ipEsp32 = ip
        vistoEsp32 = System.currentTimeMillis()
        if (nuevo) log("[ESP32] conectado desde $ip")
        avisar()
    }

    fun nuevaConsulta(c: Consulta) { ultimaConsulta = c; avisar() }

    /** Avisa a la pantalla, agrupando avisos seguidos (como maximo uno cada 150 ms). */
    fun avisar() {
        if (avisoPendiente.compareAndSet(false, true)) {
            principal.postDelayed({
                avisoPendiente.set(false)
                for (o in oyentes) o()
            }, 150)
        }
    }

    val registro = Registro { log(it) }
}

/** Direcciones del celular y anuncio en la red (mDNS) para que el ESP32 lo encuentre. */
object Red {
    class Direccion(val interfaz: String, val ip: String) {
        val tipo: String get() = when {
            interfaz.startsWith("swlan") || interfaz.startsWith("ap") || interfaz == "wlan1" -> "hotspot"
            interfaz.startsWith("wlan") -> "WiFi"
            interfaz.startsWith("rmnet") || interfaz.startsWith("ccmni") -> "datos moviles"
            else -> interfaz
        }
    }

    fun direcciones(): List<Direccion> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { Direccion(ni.name, it.hostAddress ?: "") } }
            .filter { it.ip.isNotEmpty() && it.tipo != "datos moviles" }
    } catch (_: Exception) {
        emptyList()
    }

    class Anunciador(ctx: Context, private val puerto: Int, private val registro: Registro) {
        private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        private var oyente: NsdManager.RegistrationListener? = null

        fun anunciar() {
            val info = NsdServiceInfo().apply {
                serviceName = "lentes"
                serviceType = "_lentes._tcp"
                port = puerto
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { registro.log("[RED] anunciado en la red como '${i.serviceName}' (mDNS)") }
                override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { registro.log("[RED] no se pudo anunciar por mDNS (error $error); el ESP32 igual lo encuentra por el hotspot") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) {}
            }
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
                oyente = l
            } catch (e: Exception) {
                registro.log("[RED] mDNS no disponible: ${e.message}")
            }
        }

        fun quitar() {
            oyente?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
            oyente = null
        }
    }
}
