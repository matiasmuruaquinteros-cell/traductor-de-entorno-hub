package com.lentesinteligentes.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lentesinteligentes.app.nucleo.Ajustes

class MainActivity : Activity() {

    private lateinit var txtEstado: TextView
    private lateinit var txtChip: TextView
    private lateinit var tarjetaPrincipal: View
    private lateinit var btnIniciar: Button
    private lateinit var txtIp: TextView
    private lateinit var txtEsp: TextView
    private lateinit var txtGemini: TextView
    private lateinit var avisoBateria: View
    private lateinit var imgFoto: ImageView
    private lateinit var txtTipoConsulta: TextView
    private lateinit var txtPregunta: TextView
    private lateinit var txtConsulta: TextView
    private lateinit var txtTiempos: TextView
    private lateinit var edtClave: EditText
    private lateinit var txtClaveEstado: TextView
    private lateinit var btnVerClave: Button
    private lateinit var edtModelo: EditText
    private lateinit var edtPuerto: EditText
    private lateinit var spnRotacion: Spinner
    private lateinit var swMejorar: Switch
    private lateinit var lblVolumen: TextView
    private lateinit var skVolumen: SeekBar
    private lateinit var spnMotor: Spinner
    private lateinit var spnVoz: Spinner
    private lateinit var lblVelocidad: TextView
    private lateinit var skVelocidad: SeekBar
    private lateinit var swSaludoIa: Switch
    private lateinit var swMemoria: Switch
    private lateinit var swLive: Switch
    private lateinit var spnMicLive: Spinner
    private lateinit var spnVozLive: Spinner
    private lateinit var edtModeloLive: EditText
    private lateinit var txtRegistro: TextView
    private lateinit var swLlamadas: Switch
    private lateinit var txtPermisosLlamadas: TextView
    private lateinit var swUbicacion: Switch
    private lateinit var txtPermisosUbicacion: TextView

    private val reloj = Handler(Looper.getMainLooper())
    private var versionAjustesVista = Estado.versionAjustes
    private val alCambiarEstado: () -> Unit = {
        if (Estado.versionAjustes != versionAjustesVista) {
            // un comando de voz cambio algo: la pantalla muestra los valores nuevos
            versionAjustesVista = Estado.versionAjustes
            cargarAjustesEnPantalla()
            Toast.makeText(this, "Ajustes cambiados por voz", Toast.LENGTH_SHORT).show()
        }
        refrescar()
    }
    private var fotoMostrada: ByteArray? = null

    // Motor de voz propio de la pantalla: solo para listar voces y para "Escuchar voz"
    private var ttsPantalla: TextToSpeech? = null
    private var motores: List<TextToSpeech.EngineInfo> = emptyList()
    private var voces: List<Voice> = emptyList()
    private var cargandoSpinners = false

    private val rotaciones = listOf(0, 90, 180, 270)

    private lateinit var paginas: List<View>
    private lateinit var botonesNav: List<TextView>
    private var paginaActual = INICIO
    private var claveVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtEstado = findViewById(R.id.txtEstado)
        txtChip = findViewById(R.id.txtChip)
        tarjetaPrincipal = findViewById(R.id.tarjetaPrincipal)
        btnIniciar = findViewById(R.id.btnIniciar)
        txtIp = findViewById(R.id.txtIp)
        txtEsp = findViewById(R.id.txtEsp)
        txtGemini = findViewById(R.id.txtGemini)
        avisoBateria = findViewById(R.id.avisoBateria)
        imgFoto = findViewById(R.id.imgFoto)
        imgFoto.clipToOutline = true
        txtTipoConsulta = findViewById(R.id.txtTipoConsulta)
        txtPregunta = findViewById(R.id.txtPregunta)
        txtConsulta = findViewById(R.id.txtConsulta)
        txtTiempos = findViewById(R.id.txtTiempos)
        edtClave = findViewById(R.id.edtClave)
        txtClaveEstado = findViewById(R.id.txtClaveEstado)
        btnVerClave = findViewById(R.id.btnVerClave)
        edtModelo = findViewById(R.id.edtModelo)
        edtPuerto = findViewById(R.id.edtPuerto)
        spnRotacion = findViewById(R.id.spnRotacion)
        swMejorar = findViewById(R.id.swMejorar)
        lblVolumen = findViewById(R.id.lblVolumen)
        skVolumen = findViewById(R.id.skVolumen)
        spnMotor = findViewById(R.id.spnMotor)
        spnVoz = findViewById(R.id.spnVoz)
        lblVelocidad = findViewById(R.id.lblVelocidad)
        skVelocidad = findViewById(R.id.skVelocidad)
        swSaludoIa = findViewById(R.id.swSaludoIa)
        swMemoria = findViewById(R.id.swMemoria)
        swLive = findViewById(R.id.swLive)
        spnMicLive = findViewById(R.id.spnMicLive)
        spnVozLive = findViewById(R.id.spnVozLive)
        edtModeloLive = findViewById(R.id.edtModeloLive)
        txtRegistro = findViewById(R.id.txtRegistro)
        swLlamadas = findViewById(R.id.swLlamadas)
        txtPermisosLlamadas = findViewById(R.id.txtPermisosLlamadas)
        swUbicacion = findViewById(R.id.swUbicacion)
        txtPermisosUbicacion = findViewById(R.id.txtPermisosUbicacion)

        paginas = listOf(findViewById(R.id.pagInicio), findViewById(R.id.pagAjustes), findViewById(R.id.pagRegistro))
        botonesNav = listOf(findViewById(R.id.navInicio), findViewById(R.id.navAjustes), findViewById(R.id.navRegistro))
        botonesNav.forEachIndexed { i, b -> b.setOnClickListener { mostrarPagina(i) } }

        ajustarBordes()
        cargarAjustesEnPantalla()

        btnIniciar.setOnClickListener { if (Estado.activo) detener() else iniciar(guardarAntes = true) }
        findViewById<Button>(R.id.btnBateria).setOnClickListener { pedirBateria() }
        findViewById<Button>(R.id.btnSalir).setOnClickListener { apagarYSalir() }
        findViewById<Button>(R.id.btnCopiarIp).setOnClickListener {
            val ip = Red.direcciones().firstOrNull()?.ip
            if (ip == null) aviso("El celular no esta conectado a ninguna red")
            else copiar("ip celular", ip, "IP copiada: $ip")
        }
        findViewById<Button>(R.id.btnGuardar).setOnClickListener { guardar() }
        findViewById<Button>(R.id.btnGuardarClave).setOnClickListener { guardarClave() }
        btnVerClave.setOnClickListener { verClave(!claveVisible) }
        findViewById<Button>(R.id.btnOlvidar).setOnClickListener {
            val f = Estado.alOlvidar
            if (f == null) aviso("El servidor esta detenido")
            else { Thread { f() }.start(); aviso("Conversacion olvidada") }
        }
        findViewById<Button>(R.id.btnProbarVoz).setOnClickListener { probarVoz() }
        findViewById<Button>(R.id.btnPermisosLlamadas).setOnClickListener { pedirPermisosLlamadas() }
        findViewById<Button>(R.id.btnPermisosUbicacion).setOnClickListener { pedirPermisoUbicacion() }
        findViewById<Button>(R.id.btnCopiar).setOnClickListener {
            copiar("registro traductor", Estado.textoRegistro(), "Registro copiado")
        }
        findViewById<Button>(R.id.btnLimpiar).setOnClickListener { Estado.limpiarRegistro() }

        mostrarPagina(savedInstanceState?.getInt("pagina", INICIO) ?: INICIO)

        // Al abrir la app el servidor arranca solo (no al girar la pantalla, que tambien llama a onCreate)
        if (savedInstanceState == null && !Estado.activo) {
            if (ClaveSegura.hayClave(this)) {
                iniciar(guardarAntes = false)
            } else {
                mostrarPagina(AJUSTES)
                aviso("Pega tu clave de Gemini y toca Guardar clave: el servidor arranca solo.")
            }
        }

        crearTtsPantalla(Preferencias.leer(this).motorVoz)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pagina", paginaActual)
    }

    override fun onStart() {
        super.onStart()
        Estado.oyentes.add(alCambiarEstado)
        if (Estado.versionAjustes != versionAjustesVista) {      // cambiaron por voz con la app en segundo plano
            versionAjustesVista = Estado.versionAjustes
            cargarAjustesEnPantalla()
        }
        refrescar()
        mostrarPermisosLlamadas()
        mostrarPermisoUbicacion()
        reloj.post(tic)
    }

    override fun onStop() {
        Estado.oyentes.remove(alCambiarEstado)
        reloj.removeCallbacks(tic)
        super.onStop()
    }

    override fun onDestroy() {
        ttsPantalla?.shutdown()
        super.onDestroy()
    }

    /** Cada segundo: actualiza el "hace N s" del ESP32. */
    private val tic = object : Runnable {
        override fun run() {
            refrescarConexion()
            reloj.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------
    // INICIAR / DETENER
    // ------------------------------------------------------------------
    private fun iniciar(guardarAntes: Boolean) {
        if (!ClaveSegura.hayClave(this) && edtClave.text.isBlank()) {
            aviso("Primero carga la clave de Gemini")
            mostrarPagina(AJUSTES)
            edtClave.requestFocus()
            return
        }
        if (guardarAntes) guardar(silencioso = true)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, ServicioLentes::class.java))
        txtEstado.text = "Iniciando..."
    }

    private fun detener() {
        startService(Intent(this, ServicioLentes::class.java).setAction(ServicioLentes.ACCION_DETENER))
    }

    /** Boton "Apagar y salir": detiene el servidor y cierra la app (tambien la saca de recientes). */
    private fun apagarYSalir() {
        if (Estado.activo) detener()
        finishAndRemoveTask()
    }

    private fun pedirBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------
    // PANTALLA
    // ------------------------------------------------------------------
    private fun refrescar() {
        val activo = Estado.activo
        val live = Preferencias.leer(this).modoLive
        txtEstado.text = if (!activo) "Detenido" else if (live) "Funcionando · Conversacion" else "Funcionando"
        tarjetaPrincipal.setBackgroundResource(if (activo) R.drawable.fondo_hero else R.drawable.fondo_hero_apagado)
        btnIniciar.text = if (activo) "Detener servidor" else "Iniciar servidor"
        txtChip.text = if (activo) "● Activo" else "● Apagado"
        txtChip.setTextColor(color(if (activo) R.color.verde else R.color.rojo))
        refrescarConexion()

        val c = Estado.ultimaConsulta
        if (c != null) {
            txtTipoConsulta.visibility = View.VISIBLE
            txtTipoConsulta.text = when (c.tipo) {
                "foto" -> "📷 Foto"
                "foto+voz" -> "📷 Foto + pregunta"
                "voz" -> "🎤 Voz"
                else -> "👋 Saludo"
            }
            txtPregunta.visibility = if (c.pregunta.isNotEmpty()) View.VISIBLE else View.GONE
            txtPregunta.text = "“${c.pregunta}”"
            txtConsulta.text = c.respuesta
            txtTiempos.visibility = if (c.segTotal > 0) View.VISIBLE else View.GONE
            txtTiempos.text = "Gemini %.1f s  ·  total %.1f s".format(c.segGemini, c.segTotal)
            val f = c.foto
            if (f != null && f !== fotoMostrada) {
                val op = BitmapFactory.Options().apply { inSampleSize = 2 }
                imgFoto.setImageBitmap(BitmapFactory.decodeByteArray(f, 0, f.size, op))
                imgFoto.visibility = View.VISIBLE
                fotoMostrada = f
            }
        }
        if (paginaActual == REGISTRO) txtRegistro.text = Estado.textoRegistro().ifEmpty { "(vacio)" }
    }

    private fun refrescarConexion() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        avisoBateria.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        val dirs = Red.direcciones()
        val puerto = if (Estado.activo) Estado.puerto else Preferencias.puerto(this)
        txtIp.text = if (dirs.isEmpty()) "Sin red WiFi" else dirs.joinToString("\n") { "${it.ip}:$puerto" }

        txtEsp.text = when {
            !Estado.activo -> "—"
            Estado.vistoEsp32 == 0L -> "Esperando..."
            else -> {
                val seg = (System.currentTimeMillis() - Estado.vistoEsp32) / 1000
                val hace = if (seg < 60) "$seg s" else "${seg / 60} min"
                "${Estado.ipEsp32}\nhace $hace"
            }
        }
        txtGemini.text = when {
            !Estado.activo -> "—"
            Estado.problemaGemini.isNotEmpty() -> "⚠ ${Estado.problemaGemini}"
            Estado.problemaVoz.isNotEmpty() -> "⚠ Voz: ${Estado.problemaVoz}"
            Estado.modelo.isNotEmpty() -> "✓ OK"
            else -> "Verificando..."
        }
    }

    // ------------------------------------------------------------------
    // NAVEGACION Y UTILIDADES
    // ------------------------------------------------------------------
    private fun mostrarPagina(i: Int) {
        paginaActual = i.coerceIn(0, 2)
        paginas.forEachIndexed { n, p -> p.visibility = if (n == paginaActual) View.VISIBLE else View.GONE }
        botonesNav.forEachIndexed { n, b -> b.alpha = if (n == paginaActual) 1f else 0.4f }
        if (paginaActual == REGISTRO) txtRegistro.text = Estado.textoRegistro().ifEmpty { "(vacio)" }
    }

    private fun color(id: Int) = getColor(id)

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    private fun copiar(etiqueta: String, texto: String, mensaje: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(etiqueta, texto))
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // AJUSTES
    // ------------------------------------------------------------------
    private fun cargarAjustesEnPantalla() {
        val a = Preferencias.leer(this)
        edtClave.setText(ClaveSegura.leer(this))      // la clave guardada queda a la vista (oculta con puntos)
        verClave(false)
        mostrarEstadoClave()
        edtModelo.setText(a.modelo)
        edtPuerto.setText(Preferencias.puerto(this).toString())

        spnRotacion.adapter = adaptador(rotaciones.map { if (it == 0) "Sin girar" else "$it grados" })
        spnRotacion.setSelection(rotaciones.indexOf(a.rotacion).coerceAtLeast(0))
        swMejorar.isChecked = a.mejorarFoto
        swSaludoIa.isChecked = a.saludoIa
        swMemoria.isChecked = a.memoria
        swLive.isChecked = a.modoLive
        swLlamadas.isChecked = a.llamadas
        mostrarPermisosLlamadas()
        swUbicacion.isChecked = a.ubicacion
        mostrarPermisoUbicacion()
        spnMicLive.adapter = adaptador(listOf("Hablar con el boton apretado", "Microfono abierto (manos libres)"))
        spnMicLive.setSelection(if (a.micAbierto) 1 else 0)
        spnVozLive.adapter = adaptador(VOCES_LIVE.map { it.ifEmpty { "Por defecto" } })
        spnVozLive.setSelection(VOCES_LIVE.indexOf(a.vozLive).coerceAtLeast(0))
        edtModeloLive.setText(a.modeloLive)

        // Volumen: 50% a 95% del maximo
        skVolumen.progress = ((a.volumen * 100).toInt() - 50).coerceIn(0, 45)
        mostrarVolumen()
        skVolumen.setOnSeekBarChangeListener(alMover { mostrarVolumen() })

        // Velocidad: 0.7x a 1.7x
        skVelocidad.progress = ((a.velocidadVoz - 0.7f) * 10).toInt().coerceIn(0, 10)
        mostrarVelocidad()
        skVelocidad.setOnSeekBarChangeListener(alMover { mostrarVelocidad() })
    }

    private fun mostrarVolumen() { lblVolumen.text = "Volumen del parlante: ${skVolumen.progress + 50}%" }
    private fun mostrarVelocidad() { lblVelocidad.text = "Velocidad de la voz: %.1fx".format(velocidad()) }
    private fun velocidad() = 0.7f + skVelocidad.progress / 10f

    private fun alMover(f: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) = f()
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun adaptador(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun ajustesDePantalla(): Ajustes {
        val previo = Preferencias.leer(this)
        return previo.copy(
            modelo = edtModelo.text.toString().trim().ifEmpty { Ajustes().modelo },
            rotacion = rotaciones[spnRotacion.selectedItemPosition.coerceIn(0, 3)],
            mejorarFoto = swMejorar.isChecked,
            volumen = (skVolumen.progress + 50) / 100.0,
            saludoIa = swSaludoIa.isChecked,
            memoria = swMemoria.isChecked,
            modoLive = swLive.isChecked,
            llamadas = swLlamadas.isChecked,
            ubicacion = swUbicacion.isChecked,
            micAbierto = spnMicLive.selectedItemPosition == 1,
            vozLive = VOCES_LIVE.getOrElse(spnVozLive.selectedItemPosition) { "" },
            modeloLive = edtModeloLive.text.toString().trim().ifEmpty { Ajustes().modeloLive },
            velocidadVoz = velocidad(),
            motorVoz = motores.getOrNull(spnMotor.selectedItemPosition)?.name ?: previo.motorVoz,
            nombreVoz = voces.getOrNull(spnVoz.selectedItemPosition)?.name ?: previo.nombreVoz,
        )
    }

    /** Muestra u oculta la clave escrita (sin borrarla). */
    private fun verClave(visible: Boolean) {
        claveVisible = visible
        val pos = edtClave.selectionEnd
        edtClave.inputType = InputType.TYPE_CLASS_TEXT or
            (if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        edtClave.typeface = Typeface.MONOSPACE
        edtClave.setSelection(pos.coerceIn(0, edtClave.text.length))
        btnVerClave.text = if (visible) "Ocultar" else "Mostrar"
    }

    private fun mostrarEstadoClave() {
        val hay = ClaveSegura.hayClave(this)
        txtClaveEstado.text = if (hay) "✓ Guardada" else "Sin guardar"
        txtClaveEstado.setTextColor(color(if (hay) R.color.verde else R.color.amarillo))
    }

    /** Guarda la clave si cambio. Devuelve true si se guardo una nueva. */
    private fun guardarClaveSiCambio(): Boolean {
        val clave = edtClave.text.toString().trim()
        if (clave.isEmpty() || clave == ClaveSegura.leer(this)) return false
        ClaveSegura.guardar(this, clave)
        mostrarEstadoClave()
        return true
    }

    private fun guardarClave() {
        val clave = edtClave.text.toString().trim()
        if (clave.isEmpty()) { aviso("Pega la clave primero"); return }
        val nueva = guardarClaveSiCambio()
        verClave(false)
        when {
            !Estado.activo -> { aviso("Clave guardada. Iniciando el servidor..."); iniciar(guardarAntes = false); mostrarPagina(INICIO) }
            nueva -> {
                startService(Intent(this, ServicioLentes::class.java).setAction(ServicioLentes.ACCION_RECARGAR))
                aviso("Clave guardada. Probando con Gemini...")
            }
            else -> aviso("Esa clave ya estaba guardada")
        }
    }

    private fun guardar(silencioso: Boolean = false) {
        guardarClaveSiCambio()
        val puerto = edtPuerto.text.toString().toIntOrNull()
        val puertoCambio = puerto != null && puerto in 1024..65535 && puerto != Preferencias.puerto(this)
        if (puerto != null && puerto in 1024..65535) Preferencias.guardarPuerto(this, puerto)
        Preferencias.guardar(this, ajustesDePantalla())

        if (Estado.activo) {
            startService(Intent(this, ServicioLentes::class.java).setAction(ServicioLentes.ACCION_RECARGAR))
        }
        if (!silencioso) {
            val extra = if (puertoCambio && Estado.activo) " El puerto nuevo se usa al Detener e Iniciar." else ""
            Toast.makeText(this, "Ajustes guardados.$extra", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // LLAMADAS: permisos
    // ------------------------------------------------------------------
    private fun permisosLlamadasFaltantes(): List<Pair<String, String>> =
        TelefonoAndroid.PERMISOS.filter { checkSelfPermission(it.first) != PackageManager.PERMISSION_GRANTED }

    private fun mostrarPermisosLlamadas() {
        val faltan = permisosLlamadasFaltantes()
        if (faltan.isEmpty()) {
            txtPermisosLlamadas.text = "✓ Permisos completos"
            txtPermisosLlamadas.setTextColor(color(R.color.verde))
        } else {
            txtPermisosLlamadas.text = "Faltan permisos: " + faltan.joinToString(", ") { it.second }
            txtPermisosLlamadas.setTextColor(color(R.color.amarillo))
        }
    }

    private fun pedirPermisosLlamadas() {
        val faltan = permisosLlamadasFaltantes()
        if (faltan.isEmpty()) { aviso("Ya estan todos los permisos de llamadas"); return }
        requestPermissions(faltan.map { it.first }.toTypedArray(), PEDIDO_LLAMADAS)
    }

    // ------------------------------------------------------------------
    // UBICACION: permiso
    // ------------------------------------------------------------------
    private fun tieneUbicacionPrecisa() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun mostrarPermisoUbicacion() {
        val aproximada = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        when {
            tieneUbicacionPrecisa() -> {
                txtPermisosUbicacion.text = "✓ Ubicacion precisa permitida"
                txtPermisosUbicacion.setTextColor(color(R.color.verde))
            }
            aproximada -> {
                txtPermisosUbicacion.text = "Solo ubicacion aproximada: hace falta la precisa para saber la calle"
                txtPermisosUbicacion.setTextColor(color(R.color.amarillo))
            }
            else -> {
                txtPermisosUbicacion.text = "Falta el permiso de ubicacion"
                txtPermisosUbicacion.setTextColor(color(R.color.amarillo))
            }
        }
    }

    private fun pedirPermisoUbicacion() {
        if (tieneUbicacionPrecisa()) { aviso("La ubicacion ya esta permitida"); return }
        requestPermissions(LocalizadorAndroid.PERMISOS, PEDIDO_UBICACION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PEDIDO_UBICACION) {
            mostrarPermisoUbicacion()
            if (tieneUbicacionPrecisa()) {
                aviso("Listo: preguntale donde estas")
                // el servicio se suma el tipo "ubicacion" para usar el GPS con la pantalla apagada
                if (Estado.activo) startService(Intent(this, ServicioLentes::class.java).setAction(ServicioLentes.ACCION_RECARGAR))
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                aviso("Activa a mano: Permisos > Ubicacion > Permitir solo mientras se usa la app, con Usar ubicacion precisa.")
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            } else {
                aviso("Hace falta la ubicacion precisa para saber en que calle estas")
            }
            return
        }
        if (requestCode != PEDIDO_LLAMADAS) return
        mostrarPermisosLlamadas()
        val faltan = permisosLlamadasFaltantes()
        if (faltan.isEmpty()) { aviso("Listo: ya podes llamar y atender por voz"); return }
        // Si Android ya no muestra el cartel (rechazado dos veces o bloqueado), se abren los ajustes de la app
        if (faltan.none { shouldShowRequestPermissionRationale(it.first) }) {
            aviso("Activa a mano: " + faltan.joinToString(", ") { it.second } +
                ". Si aparece gris, toca los 3 puntos > Permitir ajustes restringidos.")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // VOCES (motor propio de la pantalla)
    // ------------------------------------------------------------------
    private fun crearTtsPantalla(motor: String) {
        ttsPantalla?.shutdown()
        var t: TextToSpeech? = null
        t = TextToSpeech(this, { estado ->
            if (estado == TextToSpeech.SUCCESS && t != null) llenarSpinnersDeVoz(t!!)
            else Toast.makeText(this, "No arranco el motor de voz", Toast.LENGTH_LONG).show()
        }, motor.ifEmpty { null })
        ttsPantalla = t
    }

    private fun llenarSpinnersDeVoz(t: TextToSpeech) {
        val a = Preferencias.leer(this)
        cargandoSpinners = true
        motores = t.engines.orEmpty()
        spnMotor.adapter = adaptador(motores.map { it.label })
        val motorElegido = a.motorVoz.ifEmpty { t.defaultEngine }
        spnMotor.setSelection(motores.indexOfFirst { it.name == motorElegido }.coerceAtLeast(0))

        voces = VozAndroid.vocesEnEspanol(t.voices.orEmpty())
        if (voces.isEmpty()) {
            spnVoz.adapter = adaptador(listOf("No hay voces en espanol: instalalas en Ajustes > Texto a voz"))
        } else {
            spnVoz.adapter = adaptador(voces.map { VozAndroid.describir(it) })
            spnVoz.setSelection(voces.indexOfFirst { it.name == a.nombreVoz }.coerceAtLeast(0))
        }

        spnMotor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (cargandoSpinners) return
                val nuevo = motores.getOrNull(pos)?.name ?: return
                if (nuevo == a.motorVoz.ifEmpty { t.defaultEngine }) return   // es el mismo que ya esta
                // Otro motor: se guardan sus voces y se vuelve a cargar la lista
                Preferencias.guardar(this@MainActivity, ajustesDePantalla().copy(motorVoz = nuevo, nombreVoz = ""))
                crearTtsPantalla(nuevo)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spnMotor.post { cargandoSpinners = false }
    }

    private fun probarVoz() {
        val t = ttsPantalla ?: return
        voces.getOrNull(spnVoz.selectedItemPosition)?.let { t.voice = it }
        t.setSpeechRate(velocidad())
        t.speak("Hola, asi suena la voz del traductor de entorno. Tenes adelante una taza roja sobre la mesa.",
            TextToSpeech.QUEUE_FLUSH, null, "prueba")
    }

    // ------------------------------------------------------------------
    // Android 15 dibuja la app debajo de las barras del sistema: se agrega margen
    // arriba (barra de estado) y abajo (barra de navegacion o teclado).
    // ------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun ajustarBordes() {
        val raiz = findViewById<View>(R.id.raiz)
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            val arriba: Int
            val abajo: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                arriba = b.top; abajo = b.bottom
            } else {
                arriba = insets.systemWindowInsetTop
                abajo = insets.systemWindowInsetBottom
            }
            v.setPadding(0, arriba, 0, abajo)
            insets
        }
    }

    companion object {
        private const val INICIO = 0
        private const val AJUSTES = 1
        private const val REGISTRO = 2
        private const val PEDIDO_LLAMADAS = 2
        private const val PEDIDO_UBICACION = 3
        /** Voces prediseñadas de Gemini Live ("" = la del modelo). */
        private val VOCES_LIVE = listOf("", "Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Orus", "Zephyr")
    }
}
