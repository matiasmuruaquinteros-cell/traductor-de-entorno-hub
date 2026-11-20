package com.lentesinteligentes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.lentesinteligentes.app.nucleo.Asistente
import com.lentesinteligentes.app.nucleo.Gemini
import com.lentesinteligentes.app.nucleo.GeminiLive
import com.lentesinteligentes.app.nucleo.Ajustes
import com.lentesinteligentes.app.nucleo.GestorLlamadas
import com.lentesinteligentes.app.nucleo.GestorUbicacion
import com.lentesinteligentes.app.nucleo.MemoriaConversacion
import com.lentesinteligentes.app.nucleo.PuenteLive
import com.lentesinteligentes.app.nucleo.Respuesta
import com.lentesinteligentes.app.nucleo.MiniServidorHttp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servicio en primer plano: el servidor sigue andando con la pantalla apagada.
 * Muestra una notificacion fija ("Traductor de entorno activo") mientras funciona.
 */
class ServicioLentes : Service() {

    private var servidor: MiniServidorHttp? = null
    private var gemini: Gemini? = null
    private var voz: VozAndroid? = null
    private var puente: PuenteLive? = null
    private var anunciador: Red.Anunciador? = null
    private var receptorLlamadas: ReceptorLlamadas? = null
    private var avisoCelular: AvisoEnCelular? = null
    private var bloqueoCpu: PowerManager.WakeLock? = null
    private var bloqueoWifi: WifiManager.WifiLock? = null
    private val tareas: ExecutorService = Executors.newSingleThreadExecutor()
    private val registro = Estado.registro

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> { stopSelf(); return START_NOT_STICKY }
            ACCION_RECARGAR -> { if (servidor != null) recargar(); return START_STICKY }
        }
        if (servidor == null) arrancar()
        return START_STICKY
    }

    private fun arrancar() {
        crearCanal()
        val notif = notificacion("Iniciando...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_NOTIF, notif, tiposServicio())
            } else {
                startForeground(ID_NOTIF, notif)
            }
        } catch (e: Exception) {
            // Pasa si Android reinicia el servicio en segundo plano y no lo deja quedar activo
            registro.log("[ERROR] Android no dejo iniciar el servicio (${e.message}). Abri la app y toca Iniciar.")
            stopSelf()
            return
        }

        // Que el celular no duerma la CPU ni el WiFi mientras los lentes estan activos
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bloqueoCpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lentes:servidor").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val modoWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        bloqueoWifi = wm.createWifiLock(modoWifi, "lentes:wifi").apply { setReferenceCounted(false); acquire() }

        val ajustes = Preferencias.leer(this)
        val g = Gemini(ClaveSegura.leer(this), ajustes.modelo, registro)
        val v = VozAndroid(this, registro)
        // Una sola memoria para toda la conversacion, compartida por los dos modos (vive mientras ande el servidor)
        val memoria = MemoriaConversacion(registro)
        val asistente = Asistente(g, v, FotoAndroid(registro), registro, memoria) { Preferencias.leer(this) }
        // Ajustes cambiados por un comando de voz: se guardan y la pantalla se actualiza sola
        val guardarPorVoz: (Ajustes) -> Unit = { nuevos ->
            Preferencias.guardar(this, nuevos)
            Estado.ajustesCambiadosPorVoz()
        }
        asistente.alCambiarAjustes = guardarPorVoz
        val verEsp32: (String) -> Unit = { ip ->
            val cambio = ip != Estado.ipEsp32
            Estado.vioEsp32(ip)
            if (cambio) actualizarNotificacion()
        }
        asistente.alConsultar = { Estado.nuevaConsulta(it) }
        asistente.alVerEsp32 = verEsp32

        // Modo conversacion (Gemini Live): el puente se queda con el WebSocket del ESP32 v11
        val clave = ClaveSegura.leer(this)
        val pl = PuenteLive(
            { eventos -> GeminiLive(clave, ConectorOkHttp(), registro, eventos) },
            v, FotoAndroid(registro), registro, memoria,
        ) { Preferencias.leer(this) }
        pl.alCambiarAjustes = guardarPorVoz
        pl.alConsultar = { Estado.nuevaConsulta(it) }
        pl.alVerEsp32 = verEsp32
        Estado.alOlvidar = { asistente.olvidar(); pl.olvidar() }

        // Llamadas por voz: el mismo gestor para los dos modos
        val telefono = TelefonoAndroid(this, registro)
        val gestor = GestorLlamadas(telefono, registro)
        val aviso = AvisoEnCelular(this, ajustes.motorVoz, registro)
        gestor.activo = { Preferencias.leer(this).llamadas }
        gestor.anunciar = { frase, ayuda, contexto ->
            Thread({
                // Modo conversacion con el dispositivo conectado: por su parlante. Si no, por el del celular.
                if (!pl.avisarLlamada(frase, contexto)) aviso.decir(frase + ayuda, Preferencias.leer(this))
            }, "aviso-llamada").start()
        }
        gestor.alCambiar = { texto -> pl.contextoTelefono(texto) }
        asistente.llamadas = gestor
        pl.llamadas = gestor
        val receptor = ReceptorLlamadas(gestor)
        try {
            receptor.registrar(this)
            receptorLlamadas = receptor
        } catch (e: Exception) {
            registro.log("[LLAMADAS] no pude escuchar las llamadas entrantes: ${e.message}")
        }
        avisoCelular = aviso

        // Ubicacion por voz: donde estoy, lugares cercanos (Google Maps via Gemini) y como llegar
        val ubic = GestorUbicacion(LocalizadorAndroid(this, registro), g, registro)
        ubic.activo = { Preferencias.leer(this).ubicacion }
        asistente.ubicacion = ubic
        pl.ubicacion = ubic
        val faltan = telefono.permisosFaltantes()
        if (faltan.isNotEmpty() && ajustes.llamadas) {
            registro.log("[LLAMADAS] faltan permisos: ${faltan.joinToString()}. Ajustes > Llamadas > Permitir llamadas.")
        }

        val puerto = Preferencias.puerto(this)
        val srv = MiniServidorHttp(puerto, registro) { p ->
            if (p.ruta == "/modo") {          // el firmware v11 pregunta en que modo trabajar
                verEsp32(p.ipCliente)
                Respuesta.texto(if (Preferencias.leer(this).modoLive) "live" else "clasico")
            } else asistente.manejar(p)
        }
        srv.alWebSocket = { p, ws ->
            if (p.ruta == "/live") pl.atenderEsp32(ws) else ws.cerrar()
        }
        try {
            srv.iniciar()
        } catch (e: Exception) {
            registro.log("[ERROR] No pude abrir el puerto $puerto: ${e.message}. Proba con otro puerto.")
            Estado.problemaGemini = "No se pudo abrir el puerto $puerto"
            stopSelf()
            return
        }
        servidor = srv
        puente = pl
        gemini = g
        voz = v
        Estado.activo = true
        Estado.puerto = puerto
        Estado.ipEsp32 = ""
        Estado.vistoEsp32 = 0L
        registro.log("==================================================")
        registro.log("TRADUCTOR DE ENTORNO PARA CIEGOS - servidor en el celular")
        registro.log("Escuchando en el puerto $puerto")
        for (d in Red.direcciones()) registro.log("  ${d.tipo}: http://${d.ip}:$puerto")
        anunciador = Red.Anunciador(this, puerto, registro).also { it.anunciar() }
        actualizarNotificacion()
        Estado.avisar()

        tareas.execute { comprobarGemini(); comprobarVoz() }
    }

    /** La pantalla guardo ajustes nuevos (clave, modelo, voz). */
    private fun recargar() {
        val g = gemini ?: return
        actualizarTipos()
        g.clave = ClaveSegura.leer(this)
        g.pensamientoOk = true
        puente?.let { it.live.clave = g.clave; it.ajustesCambiaron() }
        tareas.execute { comprobarGemini(); comprobarVoz() }
    }

    /**
     * connectedDevice siempre; location solo si ya hay permiso de ubicacion (Android 14 no deja
     * declararlo sin permiso). Con location, el GPS sigue andando con la pantalla apagada.
     */
    private fun tiposServicio(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return t
    }

    /** Si el permiso de ubicacion se dio con el servicio ya andando, se le suma el tipo location. */
    private fun actualizarTipos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            startForeground(ID_NOTIF, notificacion("Funcionando"), tiposServicio())
            actualizarNotificacion()
        } catch (e: Exception) {
            registro.log("[UBICACION] no pude activar la ubicacion en segundo plano: ${e.message}")
        }
    }

    private fun comprobarGemini() {
        val g = gemini ?: return
        if (g.clave.isEmpty()) {
            Estado.problemaGemini = "Falta la clave de Gemini"
            registro.log("[GEMINI] Falta la clave. Cargala en Ajustes y toca Guardar.")
            Estado.avisar()
            return
        }
        try {
            val m = g.elegirModelo(Preferencias.leer(this).modelo)
            Estado.modelo = m
            Estado.problemaGemini = ""
            registro.log("[GEMINI] OK, usando $m")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            Estado.problemaGemini = when {
                "API key" in msg || "API_KEY" in msg -> "La clave de Gemini no es valida"
                "Sin conexion" in msg -> "Sin internet (revisa los datos moviles)"
                else -> msg.take(120)
            }
            registro.log("[GEMINI] ERROR: $msg")
        }
        Estado.avisar()
    }

    private fun comprobarVoz() {
        val v = voz ?: return
        try {
            v.cerrar()          // por si cambio el motor o la voz
            val info = v.precalentar(Preferencias.leer(this))
            Estado.problemaVoz = ""
            registro.log("[VOZ] lista: $info")
        } catch (e: Exception) {
            Estado.problemaVoz = e.message.orEmpty()
            registro.log("[VOZ] ERROR: ${e.message}")
        }
        Estado.avisar()
    }

    /**
     * El usuario cerro la app deslizandola en "recientes": se apaga el servidor.
     * (Con el boton Inicio o la pantalla apagada la app NO se cierra y el servidor sigue.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        registro.log("[FIN] La app se cerro desde recientes")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Estado.alOlvidar = null
        receptorLlamadas?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receptorLlamadas = null
        avisoCelular?.cerrar()
        avisoCelular = null
        Thread { puente?.live?.cerrar() }.start()
        servidor?.detener()
        anunciador?.quitar()
        tareas.shutdownNow()
        Thread { voz?.cerrar() }.start()
        try { bloqueoCpu?.release() } catch (_: Exception) {}
        try { bloqueoWifi?.release() } catch (_: Exception) {}
        servidor = null
        Estado.activo = false
        registro.log("[FIN] Servidor detenido")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // NOTIFICACION
    // ------------------------------------------------------------------
    private fun crearCanal() {
        val canal = NotificationChannel(CANAL, "Traductor activo", NotificationManager.IMPORTANCE_LOW)
        canal.description = "Aviso fijo mientras el celular atiende al dispositivo"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(canal)
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val detener = PendingIntent.getService(
            this, 1, Intent(this, ServicioLentes::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Traductor de entorno activo")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", detener).build())
            .build()
    }

    private fun actualizarNotificacion() {
        val ip = Red.direcciones().firstOrNull()?.ip ?: "sin red"
        val esp = if (Estado.ipEsp32.isNotEmpty()) "dispositivo conectado" else "esperando al dispositivo"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ID_NOTIF, notificacion("$ip:${Estado.puerto} - $esp"))
    }

    companion object {
        const val ACCION_DETENER = "com.lentesinteligentes.app.DETENER"
        const val ACCION_RECARGAR = "com.lentesinteligentes.app.RECARGAR"
        private const val CANAL = "lentes"
        private const val ID_NOTIF = 1
    }
}
