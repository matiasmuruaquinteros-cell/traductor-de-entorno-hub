package com.lentesinteligentes.app.nucleo

/** Donde se escriben los mensajes (en el celular: la pantalla de la app). */
fun interface Registro {
    fun log(mensaje: String)
}

/** Texto -> WAV listo para el parlante del ESP32 (16 bits mono). */
interface Voz {
    fun sintetizar(texto: String, ajustes: Ajustes): ByteArray
}

/** Rota y mejora la foto. Devuelve la original si algo falla. */
interface ProcesadorFoto {
    fun procesar(jpeg: ByteArray, ajustes: Ajustes): ByteArray
}

/** Todo lo que se puede cambiar desde la pantalla de la app. */
data class Ajustes(
    val modelo: String = "gemini-3.1-flash-lite",
    val rotacion: Int = 0,                 // 0, 90, 180 o 270
    val mejorarFoto: Boolean = true,
    val volumen: Double = 0.90,            // pico del audio: 0.5 a 0.95
    val normalizarMic: Boolean = true,
    val saludoIa: Boolean = false,         // false = saludo fijo (instantaneo)
    val memoria: Boolean = true,           // recordar los ultimos mensajes y la ultima foto
    val velocidadVoz: Float = 1.0f,
    val tonoVoz: Float = 1.0f,
    val motorVoz: String = "",             // "" = el del sistema
    val nombreVoz: String = "",            // "" = la primera voz en espanol sin internet
    val frecuenciaSalida: Int = 16000,     // la que espera el ESP32
    // --- Modo conversacion (Gemini Live, experimental) ---
    val modoLive: Boolean = false,         // false = modo clasico (pregunta -> respuesta)
    val micAbierto: Boolean = false,       // false = hablar con el boton apretado
    val modeloLive: String = "gemini-3.8-live",
    val vozLive: String = "",              // "" = la voz por defecto de Gemini
    // --- Llamadas por voz ---
    val llamadas: Boolean = true,          // llamar a contactos y atender/rechazar por voz
    val ubicacion: Boolean = true,         // donde estoy, lugares cercanos, como llegar
)

/** Lo que la app muestra de cada consulta. */
class Consulta(
    val tipo: String,                      // "foto", "voz", "foto+voz", "bienvenida"
    val pregunta: String,
    val respuesta: String,
    val foto: ByteArray?,
    val segGemini: Double,
    val segTotal: Double,
)
