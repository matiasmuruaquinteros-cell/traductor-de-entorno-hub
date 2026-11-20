/* =====================================================================
   LENTES INTELIGENTES  -  ESP32-S3 N16R8 + OV5640 + MAX98357A + INMP441
   v15  (servidor en el celular + modo conversacion con Gemini Live; base: v12)

   BOTONES (pulsadores de 2 patas: una pata al GPIO, la otra a GND)
   - BOTON VOZ  (GPIO 1):  mantener apretado mientras hablas (max 10 s).
                           Al soltar se manda solo el audio. Sin foto.
   - BOTON FOTO (GPIO 47): toque corto (< 0.5 s) -> foto, la IA la describe.
                           Mantener (> 0.5 s)   -> foto + tu pregunta sobre
                           esa foto; la grabacion termina al soltar.

   Cambios v12:
   - BOTON VOZ pasa de GPIO 14 a GPIO 1.
   - Amplificador MAX98357A: BCLK en GPIO 38, LRC en GPIO 39, DIN en GPIO 21.

   Cambios v11:
   - MODO CONVERSACION (experimental): Gemini Live por streaming. La respuesta
     empieza a sonar mientras se genera, Gemini habla con su propia voz, se lo
     puede interrumpir y recuerda toda la conversacion.
   - El modo se elige en la app (Ajustes > Modo conversacion). El ESP32 le pregunta
     a la app al conectarse y cambia solo; no hace falta volver a subir el codigo.
   - Microfono en modo conversacion (tambien se elige en la app):
       * Con el boton apretado: igual que antes, mantener mientras hablas.
       * Abierto (manos libres): se habla sin tocar nada. VOZ toque = callar a Gemini,
         VOZ mantener 1 s = pausar / reanudar el microfono.
   - Requiere la libreria "WebSockets" de Markus Sattler (Administrador de librerias).

   Cambios v10:
   - Se conecta al hotspot del celular (y opcionalmente a un segundo WiFi).
   - Encuentra el servidor solo, sin escribir ninguna IP:
       1. el celular que le da el hotspot (la puerta de enlace de la red)
       2. el celular anunciado como "lentes" en la red (mDNS), si estan en el mismo WiFi
       3. SERVIDOR_IP_RESPALDO (por ejemplo la PC con servidor.py)
   - Si el servidor deja de responder, lo vuelve a buscar y reintenta una vez.
   - Avisos con pitidos: dos tonos graves = no hay conexion con el servidor.
   - Lee la frecuencia del audio de la cabecera del WAV (antes asumia 16000 Hz).

   Cambios v9:
   - Botones movidos de GPIO 1/2 a GPIO 14/47. En muchas placas ESP32-S3
     con camara, GPIO 2 tiene un LED soldado que tira el pin hacia abajo:
     el boton no responde y aparecen disparos fantasma.
   - Antirrebote real: la presion y el soltado tienen que ser estables un
     tiempo minimo, y despues de cada accion el boton se ignora un momento.
   - Si un boton queda trabado en bajo al encender, se deshabilita y se avisa
     (antes el sistema se quedaba colgado).
   - PRUEBA_BOTONES 1: modo para verificar el cableado por el monitor serie.
   - Timeout HTTP a 60 s (el error -11 era el ESP32 cansandose de esperar).
   - La foto se toma cuando la exposicion se estabilizo, no a tiempo fijo.
   - Calidad JPEG 10 (antes 12): mas detalle, no cambia la temperatura.
   ===================================================================== */

#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>
#include <WiFiMulti.h>
#include <ESPmDNS.h>
#include <HTTPClient.h>
#include <driver/i2s.h>
#include <WebSocketsClient.h>        // libreria "WebSockets" de Markus Sattler
#include "freertos/stream_buffer.h"

// =====================================================
// CONFIGURACION
// =====================================================
// Redes WiFi. Se conecta a la que tenga mejor senal.
//   Red 1: el HOTSPOT DEL CELULAR (nombre y contrasena tal cual aparecen en el celular).
//   Red 2: opcional, el WiFi de tu casa o del colegio. Para no usarla, deja WIFI_2_SSID = "".
// OJO: el ESP32 solo ve redes de 2.4 GHz. En el hotspot del Samsung hay que elegir la banda 2.4 GHz.
const char* WIFI_1_SSID = "NOMBRE_DE_TU_HOTSPOT";   // <-- cambiar (el hotspot del celular, en 2.4 GHz)
const char* WIFI_1_PASS = "CLAVE_DE_TU_HOTSPOT";    // <-- cambiar (no subir la real a GitHub)
const char* WIFI_2_SSID = "";
const char* WIFI_2_PASS = "";

// El servidor se busca solo (ver arriba). Esta IP es solo un respaldo, por ejemplo la PC
// con servidor.py. "" = no usar respaldo.
const char* SERVIDOR_IP_RESPALDO = "";
const int   SERVIDOR_PORT = 8000;      // el mismo puerto que en la app (8000 por defecto)

// Pulsadores de 2 patas: una pata al GPIO y la otra a GND (usan la resistencia interna).
#define BOTON_VOZ   1      // GPIO 1: mantener = pregunta por voz, sin foto
#define BOTON_FOTO  47     // corto = foto | mantener = foto + pregunta sobre la foto

// 1 = modo prueba: no saca fotos ni graba, solo muestra en el monitor serie
//     cada cambio de los botones. Sirve para verificar el cableado. Despues volver a 0.
#define PRUEBA_BOTONES  0

// Antirrebote
#define MS_PRESION_ESTABLE   30    // el boton tiene que estar apretado este tiempo seguido
#define MS_SOLTADO_ESTABLE   60    // y suelto este tiempo seguido para contar como soltado
#define MS_BLOQUEO_ACCION    400   // despues de cada accion, el boton se ignora este tiempo

// =====================================================
// CAMARA: IMAGEN Y TEMPERATURA  (ajustables)
// =====================================================
#define CAMARA_STANDBY       1     // 1 = dormir el sensor entre usos (menos calor). 0 = siempre encendido
#define MS_AJUSTE_MIN        300   // espera minima al despertar la camara
#define MS_AJUSTE_MAX        1500  // espera maxima si la exposicion no se estabiliza
#define CAM_ESTABLE_PCT      6     // la exposicion se considera estable cuando dos cuadros
                                   // seguidos difieren menos de este % en tamano
#define CAM_CALIDAD_JPEG     10    // 10 = mas detalle (antes 12). Menor numero = mejor calidad
#define CAM_AE_NIVEL         1     // -2 .. 2   (mas alto = foto mas clara)
#define CAM_BRILLO           0     // -2 .. 2
#define CAM_CONTRASTE        1     // -2 .. 2   (la imagen salia lavada)
#define CAM_SATURACION       1     // -2 .. 2   (colores mas vivos)
#define CAM_VOLTEAR          1     // 1 = da vuelta la imagen verticalmente
#define CAM_ESPEJO           1     // 1 = espeja horizontalmente  (VOLTEAR 1 + ESPEJO 1 = girar 180 grados)
#define CAM_GANANCIA_MAX     GAINCEILING_16X   // mas alto = mas claro con poca luz, pero mas ruido
#define CPU_MHZ              160   // 240 calienta mas; 160 alcanza

// =====================================================
// PINOUT CAMARA OV5640
// =====================================================
#define PWDN_GPIO_NUM    -1
#define RESET_GPIO_NUM   -1
#define XCLK_GPIO_NUM    15
#define SIOD_GPIO_NUM     4
#define SIOC_GPIO_NUM     5
#define Y9_GPIO_NUM      16
#define Y8_GPIO_NUM      17
#define Y7_GPIO_NUM      18
#define Y6_GPIO_NUM      12
#define Y5_GPIO_NUM      10
#define Y4_GPIO_NUM       8
#define Y3_GPIO_NUM       9
#define Y2_GPIO_NUM      11
#define VSYNC_GPIO_NUM    6
#define HREF_GPIO_NUM     7
#define PCLK_GPIO_NUM    13

// =====================================================
// PINOUT I2S SALIDA  MAX98357A
// =====================================================
#define I2S_OUT_BCLK  38    // BCLK del amplificador
#define I2S_OUT_LRC   39    // LRC del amplificador
#define I2S_OUT_DOUT  21    // DIN del amplificador

// =====================================================
// PINOUT I2S ENTRADA  INMP441
// =====================================================
#define I2S_IN_SCK    42
#define I2S_IN_WS     41
#define I2S_IN_SD     45

// =====================================================
// PARAMETROS DE AUDIO Y TIEMPOS
// =====================================================
#define SAMPLE_RATE         16000
#define BITS_POR_MUESTRA    16
#define DMA_BUF_COUNT       8
#define DMA_BUF_LEN         512
#define MS_PULSACION_LARGA  500
#define MAX_SEG_GRABACION   10
#define MAX_BYTES_AUDIO     (SAMPLE_RATE * 2 * MAX_SEG_GRABACION)
#define MIN_BYTES_VOZ       9600          // 0.3 s: menos que esto se descarta

 // =====================================================
// MODO CONVERSACION (Gemini Live)
// =====================================================
#define MIC_TRAMA_MUESTRAS   640     // 40 ms a 16 kHz por cada envio
#define MIC_TRAMA_BYTES      (MIC_TRAMA_MUESTRAS * 2)
#define MIC_COLA_TRAMAS      25      // 1 s de audio esperando para salir por WiFi
#define MIC_TRAMAS_PREVIAS   8       // modo abierto: 320 ms antes de que se detecte la voz
#define MIC_GANANCIA_LIVE    4       // el INMP441 da poco nivel; x4 (subir si Gemini no te entiende)
#define UMBRAL_VOZ           700     // modo abierto: nivel para considerar que hablas (bajar si no te detecta,
                                     // subir si se activa con ruido). Mira [LIVE] en el monitor serie.
#define MS_FIN_VOZ           900     // modo abierto: silencio para dar por terminada la frase
#define MS_ECO_COLA          400     // el microfono sigue mudo este tiempo despues de que suena el parlante
#define MS_PAUSA_MIC         1000    // modo abierto: mantener VOZ este tiempo pausa el microfono
#define MAX_SEG_HABLAR_LIVE  30
#define PCM_LIVE_FS          24000   // Gemini Live responde a 24 kHz
#define SEG_BUFFER_PARLANTE  30      // segundos de respuesta que entran en el buffer (PSRAM)

const uint32_t TIMEOUT_HTTP_MS         = 60000;  // espera de la respuesta (Gemini + voz). Error -11 = se paso
const uint32_t TIMEOUT_PRIMER_BYTE_MS  = 30000;  // espera del primer audio (Gemini + TTS)
const uint32_t TIMEOUT_STREAM_MS       = 10000;  // silencio maximo una vez que ya sono algo

// =====================================================
// ESTADO GLOBAL
// =====================================================
bool tienePSRAM   = false;
bool sistemaListo = false;

char urlImagen[80];
char urlAudio[80];
char urlContexto[80];
char urlBienvenida[80];

// Servidor encontrado (se busca solo)
WiFiMulti wifiMulti;
char servidorIp[40]       = "";
int  servidorPuerto       = SERVIDOR_PORT;
volatile bool servidorOk  = false;
bool bienvenidaPendiente  = true;      // el saludo suena la primera vez que se encuentra el servidor
bool mdnsListo            = false;
uint32_t frecuenciaSalida = SAMPLE_RATE;

// bufGrabacion = [44 bytes de encabezado WAV][PCM ...]
uint8_t* bufGrabacion  = nullptr;
size_t   bytesGrabados = 0;               // solo PCM, sin los 44 bytes

// Coordinacion con la tarea de la foto
volatile int  decisionFoto   = 0;         // 0 pendiente, 1 toque corto, 2 voz
volatile bool fotoCapturada  = false;
volatile bool fotoTerminada  = false;     // la tarea ya subio (o descarto) la foto
volatile bool contextoOk     = false;     // el servidor confirmo la foto de contexto
volatile bool tareaFotoViva  = false;
camera_fb_t* volatile fotoFb = nullptr;
char sesionId[16];

unsigned long tSoltar = 0;                // para medir latencia
bool usarStandby = CAMARA_STANDBY;        // se desactiva solo si el sensor no responde

// =====================================================
// INICIALIZACION I2S
// =====================================================
void init_i2s_salida() {
  i2s_config_t cfg = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate          = SAMPLE_RATE,
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count        = DMA_BUF_COUNT,
    .dma_buf_len          = DMA_BUF_LEN,
    .use_apll             = false,
    .tx_desc_auto_clear   = true
  };
  i2s_pin_config_t pins = {
    .mck_io_num   = I2S_PIN_NO_CHANGE,
    .bck_io_num   = I2S_OUT_BCLK,
    .ws_io_num    = I2S_OUT_LRC,
    .data_out_num = I2S_OUT_DOUT,
    .data_in_num  = I2S_PIN_NO_CHANGE
  };
  i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  i2s_set_pin(I2S_NUM_0, &pins);
  i2s_zero_dma_buffer(I2S_NUM_0);
}

void vaciarI2S_salida() {
  uint8_t silencio[512] = {0};
  size_t escritos;
  for (int i = 0; i < 6; i++) {
    i2s_write(I2S_NUM_0, silencio, sizeof(silencio), &escritos, portMAX_DELAY);
  }
  i2s_zero_dma_buffer(I2S_NUM_0);
}

// Frecuencia del parlante segun la cabecera del WAV que manda el servidor
void ajustarFrecuenciaSalida(uint32_t fs) {
  if (fs == frecuenciaSalida || fs < 8000 || fs > 48000) return;
  i2s_set_clk(I2S_NUM_0, fs, I2S_BITS_PER_SAMPLE_16BIT, I2S_CHANNEL_MONO);
  frecuenciaSalida = fs;
  Serial.printf("[AUDIO] Frecuencia de salida: %u Hz\n", fs);
}

// Pitido por el parlante (para avisar sin depender del servidor)
void pitido(int hz, int ms) {
  const int N = 256;
  int16_t buf[N];
  size_t escritos;
  uint32_t fs = frecuenciaSalida;
  uint32_t total = fs * ms / 1000;
  uint32_t rampa = fs / 200;                      // 5 ms de subida y bajada: sin chasquidos
  for (uint32_t i = 0; i < total; i += N) {
    for (int k = 0; k < N; k++) {
      uint32_t j = i + k;
      float env = 1.0f;
      if (j < rampa) env = (float)j / rampa;
      else if (j + rampa > total) env = (j < total) ? (float)(total - j) / rampa : 0.0f;
      buf[k] = (int16_t)(9000.0f * env * sinf(2.0f * PI * hz * j / fs));
    }
    i2s_write(I2S_NUM_0, buf, sizeof(buf), &escritos, portMAX_DELAY);
  }
}

// Dos tonos graves: no hay conexion con el servidor (o no hay WiFi)
void avisoSinServidor() {
  pitido(440, 120); delay(80); pitido(330, 180);
  vaciarI2S_salida();
}

// Un tono agudo corto: se recupero la conexion
void avisoConectado() {
  pitido(1200, 90);
  vaciarI2S_salida();
}

void init_i2s_entrada() {
  i2s_config_t cfg = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate          = SAMPLE_RATE,
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count        = DMA_BUF_COUNT,
    .dma_buf_len          = DMA_BUF_LEN,
    .use_apll             = false,
    .tx_desc_auto_clear   = false
  };
  i2s_pin_config_t pins = {
    .mck_io_num   = I2S_PIN_NO_CHANGE,
    .bck_io_num   = I2S_IN_SCK,
    .ws_io_num    = I2S_IN_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num  = I2S_IN_SD
  };
  i2s_driver_install(I2S_NUM_1, &cfg, 0, NULL);
  i2s_set_pin(I2S_NUM_1, &pins);
}

// =====================================================
// GRABACION DE AUDIO
// =====================================================

// Tira lo que el microfono acumulo antes de presionar el boton.
void descartarMicPrevio() {
  int32_t basura[256];
  size_t leidos = 1;
  for (int i = 0; i < 24 && leidos > 0; i++) {
    i2s_read(I2S_NUM_1, basura, sizeof(basura), &leidos, 0);
  }
}

// Una lectura corta del microfono (maximo 100 ms de espera) que se agrega al buffer.
void leerMicUnaVez() {
  int32_t buf32[256];
  size_t leidos = 0;
  i2s_read(I2S_NUM_1, buf32, sizeof(buf32), &leidos, pdMS_TO_TICKS(100));
  int muestras = leidos / sizeof(int32_t);

  uint8_t* pcm = bufGrabacion + 44;
  for (int i = 0; i < muestras; i++) {
    if (bytesGrabados + 2 > MAX_BYTES_AUDIO) break;
    int16_t muestra16 = (int16_t)(buf32[i] >> 16);
    pcm[bytesGrabados]     = (uint8_t)(muestra16 & 0xFF);
    pcm[bytesGrabados + 1] = (uint8_t)((muestra16 >> 8) & 0xFF);
    bytesGrabados += 2;
  }
}

void escribirEncabezadoWAV(uint8_t* dest, uint32_t nBytesPCM) {
  uint32_t byteRate   = SAMPLE_RATE * 1 * BITS_POR_MUESTRA / 8;
  uint16_t blockAlign = 1 * BITS_POR_MUESTRA / 8;

  dest[0]='R'; dest[1]='I'; dest[2]='F'; dest[3]='F';
  uint32_t riffSize = 36 + nBytesPCM;
  memcpy(dest + 4,  &riffSize,   4);
  dest[8]='W'; dest[9]='A'; dest[10]='V'; dest[11]='E';
  dest[12]='f'; dest[13]='m'; dest[14]='t'; dest[15]=' ';
  uint32_t fmtSize = 16;
  memcpy(dest + 16, &fmtSize,    4);
  uint16_t audioFmt = 1;
  memcpy(dest + 20, &audioFmt,   2);
  uint16_t canales = 1;
  memcpy(dest + 22, &canales,    2);
  uint32_t sr = SAMPLE_RATE;
  memcpy(dest + 24, &sr,         4);
  memcpy(dest + 28, &byteRate,   4);
  memcpy(dest + 32, &blockAlign, 2);
  uint16_t bps = BITS_POR_MUESTRA;
  memcpy(dest + 34, &bps,        2);
  dest[36]='d'; dest[37]='a'; dest[38]='t'; dest[39]='a';
  memcpy(dest + 40, &nBytesPCM, 4);
}

// =====================================================
// REPRODUCCION
// =====================================================
bool leerExacto(WiFiClient* stream, uint8_t* dest, size_t cantidad, uint32_t timeoutMs) {
  size_t leidos = 0;
  unsigned long t0 = millis();
  while (leidos < cantidad) {
    if (stream->available() > 0) {
      int n = stream->read(dest + leidos, cantidad - leidos);
      if (n > 0) { leidos += n; t0 = millis(); }
    } else {
      if (!stream->connected()) return false;
      if (millis() - t0 > timeoutMs) return false;
      delay(1);
    }
  }
  return true;
}

void reproducirWAV(HTTPClient& http) {
  WiFiClient* stream = http.getStreamPtr();
  int totalDeclarado = http.getSize();       // -1 en streaming (HTTP/1.0 sin Content-Length)

  uint8_t header[44];
  if (!leerExacto(stream, header, 44, TIMEOUT_PRIMER_BYTE_MS)) {
    Serial.println("[AUDIO] Error: encabezado incompleto.");
    vaciarI2S_salida();
    return;
  }
  uint16_t canales = header[22] | (header[23] << 8);
  uint32_t fsWav   = header[24] | (header[25] << 8) | ((uint32_t)header[26] << 16) | ((uint32_t)header[27] << 24);
  uint16_t bitsWav = header[34] | (header[35] << 8);
  if (memcmp(header, "RIFF", 4) != 0 || canales != 1 || bitsWav != 16) {
    Serial.printf("[AUDIO] Aviso: formato inesperado (%u canales, %u bits). Se espera mono 16 bits.\n", canales, bitsWav);
  }
  ajustarFrecuenciaSalida(fsWav);

  long restante = (totalDeclarado > 44) ? (totalDeclarado - 44) : -1;
  unsigned long t0       = millis();
  unsigned long ultDato  = millis();
  unsigned long totalPCM = 0;
  uint8_t buffer[1024];
  int sobrante = 0;
  size_t escritos;

  while (true) {
    if (restante == 0) break;
    size_t aPedir = sizeof(buffer) - sobrante;
    if (restante > 0 && (long)aPedir > restante) aPedir = (size_t)restante;

    int n = stream->read(buffer + sobrante, aPedir);
    if (n > 0) {
      ultDato = millis();
      if (restante > 0) restante -= n;
      int total = n + sobrante;
      int par   = total & ~1;
      if (par > 0) {
        if (totalPCM == 0 && tSoltar > 0) {
          Serial.printf("[LAT] primer audio %lu ms despues de soltar el boton\n", millis() - tSoltar);
        }
        i2s_write(I2S_NUM_0, buffer, par, &escritos, portMAX_DELAY);
        totalPCM += par;
      }
      sobrante = total - par;
      if (sobrante == 1) buffer[0] = buffer[par];
    } else {
      if (!stream->connected() && stream->available() == 0) break;
      uint32_t limite = (totalPCM == 0) ? TIMEOUT_PRIMER_BYTE_MS : TIMEOUT_STREAM_MS;
      if (millis() - ultDato > limite) {
        Serial.println("[AUDIO] Timeout esperando datos.");
        break;
      }
      delay(1);
    }
  }

  vaciarI2S_salida();
  Serial.printf("[AUDIO] Reproduccion terminada (%lu bytes en %lu ms).\n", totalPCM, millis() - t0);
}

// =====================================================
// BUSQUEDA DEL SERVIDOR
// =====================================================
void armarUrls() {
  snprintf(urlImagen,     sizeof(urlImagen),     "http://%s:%d/upload_imagen",   servidorIp, servidorPuerto);
  snprintf(urlAudio,      sizeof(urlAudio),      "http://%s:%d/upload_audio",    servidorIp, servidorPuerto);
  snprintf(urlContexto,   sizeof(urlContexto),   "http://%s:%d/upload_contexto", servidorIp, servidorPuerto);
  snprintf(urlBienvenida, sizeof(urlBienvenida), "http://%s:%d/bienvenida",      servidorIp, servidorPuerto);
}

// Pregunta /ping. Responde rapido si hay alguien y rapido si no (puerto cerrado).
bool probarServidor(const char* ip, int puerto) {
  if (!ip || !ip[0]) return false;
  char url[72];
  snprintf(url, sizeof(url), "http://%s:%d/ping", ip, puerto);
  HTTPClient http;
  http.begin(url);
  http.setConnectTimeout(1500);
  http.setTimeout(2500);
  int codigo = http.GET();
  bool ok = (codigo == 200) && http.getString().indexOf("ok") >= 0;
  http.end();
  Serial.printf("[SERVIDOR]   %s:%d -> %s\n", ip, puerto, ok ? "SI" : "no");
  return ok;
}

void usarServidor(const char* ip, int puerto, const char* como) {
  strlcpy(servidorIp, ip, sizeof(servidorIp));
  servidorPuerto = puerto;
  armarUrls();
  servidorOk = true;
  Serial.printf("[SERVIDOR] Encontrado en %s:%d (%s)\n", servidorIp, servidorPuerto, como);
}

IPAddress ipDeMdns(int i) {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  return MDNS.address(i);
#else
  return MDNS.IP(i);
#endif
}

bool buscarServidor() {
  servidorOk = false;
  if (WiFi.status() != WL_CONNECTED) return false;
  Serial.println("[SERVIDOR] Buscando el servidor...");

  // 0. El mismo de la ultima vez
  if (servidorIp[0] && probarServidor(servidorIp, servidorPuerto)) {
    usarServidor(servidorIp, servidorPuerto, "el mismo de antes");
    return true;
  }

  // 1. Hotspot: el celular es la puerta de enlace de la red
  String gw = WiFi.gatewayIP().toString();
  if (gw != "0.0.0.0" && probarServidor(gw.c_str(), SERVIDOR_PORT)) {
    usarServidor(gw.c_str(), SERVIDOR_PORT, "hotspot del celular");
    return true;
  }

  // 2. Mismo WiFi: el celular se anuncia como servicio "_lentes._tcp"
  if (!mdnsListo) mdnsListo = MDNS.begin("lentes-esp32");
  if (mdnsListo) {
    int n = MDNS.queryService("lentes", "tcp");
    for (int i = 0; i < n; i++) {
      String ip = ipDeMdns(i).toString();
      int puerto = MDNS.port(i);
      if (probarServidor(ip.c_str(), puerto)) {
        usarServidor(ip.c_str(), puerto, "anunciado en la red (mDNS)");
        return true;
      }
    }
  }

  // 3. Respaldo fijo (por ejemplo la PC)
  if (SERVIDOR_IP_RESPALDO[0] && probarServidor(SERVIDOR_IP_RESPALDO, SERVIDOR_PORT)) {
    usarServidor(SERVIDOR_IP_RESPALDO, SERVIDOR_PORT, "IP de respaldo");
    return true;
  }

  Serial.println("[SERVIDOR] No lo encontre. En el celular: abri la app y toca Iniciar.");
  return false;
}

// Antes de cada boton: si no hay WiFi o servidor, se avisa con pitidos en vez de grabar para nada.
bool asegurarServidor() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WIFI] Error: sin conexion a la red.");
    avisoSinServidor();
    return false;
  }
  if (servidorOk || buscarServidor()) return true;
  avisoSinServidor();
  return false;
}

// =====================================================
// COMUNICACION HTTP
// =====================================================
void reproducirBienvenida() {
  if (WiFi.status() != WL_CONNECTED) return;

  HTTPClient http;
  http.begin(urlBienvenida);
  http.setTimeout(30000);
  http.setConnectTimeout(5000);

  Serial.println("[INICIO] Solicitando saludo de bienvenida al servidor...");
  int codigo = http.GET();

  if (codigo == 200) {
    reproducirWAV(http);
  } else {
    Serial.printf("[INICIO] No se pudo reproducir bienvenida (codigo %d). Continuando...\n", codigo);
  }
  http.end();
}

// Si no se pudo ni conectar (servidor cerrado o cambio de red), lo busca de nuevo.
// Devuelve true si vale la pena reintentar.
bool reintentarConexion(int codigo, int intento) {
  if (codigo != HTTPC_ERROR_CONNECTION_REFUSED || intento > 0) return false;
  Serial.println("[HTTP] No conecta con el servidor. Lo busco de nuevo...");
  return buscarServidor();
}

// Toque corto: foto -> respuesta
void enviarFoto(camera_fb_t* fb) {
  for (int intento = 0; intento < 2; intento++) {
    HTTPClient http;
    http.useHTTP10(true);
    http.begin(urlImagen);
    http.setTimeout(TIMEOUT_HTTP_MS);
    http.setConnectTimeout(5000);
    http.addHeader("Content-Type", "image/jpeg");

    Serial.printf("[HTTP] Enviando foto (%u bytes) a %s...\n", fb->len, servidorIp);
    int codigo = http.POST(fb->buf, fb->len);
    if (tSoltar > 0) Serial.printf("[LAT] foto enviada, respuesta iniciada a %lu ms\n", millis() - tSoltar);
    Serial.printf("[HTTP] Respuesta foto: codigo %d\n", codigo);

    if (codigo == 200) {
      reproducirWAV(http);
    } else {
      Serial.printf("[HTTP] Error al enviar foto: %s (codigo %d)\n", http.errorToString(codigo).c_str(), codigo);
    }
    http.end();
    if (codigo == 200) return;
    if (reintentarConexion(codigo, intento)) continue;
    avisoSinServidor();                      // cualquier otra falla: el usuario escucha los pitidos
    return;
  }
}

// Pulsacion larga: la foto sube en paralelo mientras el usuario habla
bool subirContexto(camera_fb_t* fb) {
  HTTPClient http;
  http.begin(urlContexto);
  http.setTimeout(8000);
  http.setConnectTimeout(4000);
  http.addHeader("Content-Type", "image/jpeg");
  http.addHeader("X-Sesion", sesionId);

  unsigned long t0 = millis();
  int codigo = http.POST(fb->buf, fb->len);
  http.end();
  Serial.printf("[HTTP] Foto de contexto: codigo %d (%lu ms)\n", codigo, millis() - t0);
  return codigo == 200;
}

// Audio -> respuesta.  modo = "voz" (boton de voz) o "foto" (pregunta sobre la foto)
void enviarAudio(const char* modo) {
  escribirEncabezadoWAV(bufGrabacion, bytesGrabados);
  size_t wavSize = 44 + bytesGrabados;

  for (int intento = 0; intento < 2; intento++) {
    HTTPClient http;
    http.useHTTP10(true);
    http.begin(urlAudio);
    http.setTimeout(TIMEOUT_HTTP_MS);
    http.setConnectTimeout(5000);
    http.addHeader("Content-Type", "audio/wav");
    http.addHeader("X-Sesion", sesionId);
    http.addHeader("X-Foto", contextoOk ? "1" : "0");
    http.addHeader("X-Modo", modo);

    Serial.printf("[HTTP] Enviando audio (%u bytes, modo %s, foto: %s) a %s...\n",
                  wavSize, modo, contextoOk ? "si" : "no", servidorIp);
    int codigo = http.POST(bufGrabacion, wavSize);
    if (tSoltar > 0) Serial.printf("[LAT] audio enviado, respuesta iniciada a %lu ms\n", millis() - tSoltar);
    Serial.printf("[HTTP] Respuesta audio: codigo %d\n", codigo);

    if (codigo == 200) {
      reproducirWAV(http);
    } else {
      Serial.printf("[HTTP] Error al enviar audio: %s (codigo %d)\n", http.errorToString(codigo).c_str(), codigo);
    }
    http.end();
    if (codigo == 200) return;
    if (reintentarConexion(codigo, intento)) continue;
    avisoSinServidor();                      // cualquier otra falla: el usuario escucha los pitidos
    return;
  }
}

// =====================================================
// CAMARA
// =====================================================
void ajustarSensor() {
  sensor_t* s = esp_camera_sensor_get();
  if (!s) { Serial.println("[CAMARA] No pude leer el sensor para ajustarlo."); return; }
  if (s->set_exposure_ctrl) s->set_exposure_ctrl(s, 1);   // exposicion automatica
  if (s->set_aec2)          s->set_aec2(s, 1);            // exposicion automatica del DSP
  if (s->set_ae_level)      s->set_ae_level(s, CAM_AE_NIVEL);
  if (s->set_gain_ctrl)     s->set_gain_ctrl(s, 1);       // ganancia automatica
  if (s->set_gainceiling)   s->set_gainceiling(s, (gainceiling_t)CAM_GANANCIA_MAX);
  if (s->set_whitebal)      s->set_whitebal(s, 1);        // balance de blancos automatico
  if (s->set_awb_gain)      s->set_awb_gain(s, 1);
  if (s->set_brightness)    s->set_brightness(s, CAM_BRILLO);
  if (s->set_lenc)          s->set_lenc(s, 1);            // correccion de lente (bordes menos oscuros)
  if (s->set_contrast)      s->set_contrast(s, CAM_CONTRASTE);
  if (s->set_saturation)    s->set_saturation(s, CAM_SATURACION);
  if (s->set_vflip)         s->set_vflip(s, CAM_VOLTEAR);
  if (s->set_hmirror)       s->set_hmirror(s, CAM_ESPEJO);
  Serial.printf("[CAMARA] Ajustes: AE %d, brillo %d, standby %s\n",
                CAM_AE_NIVEL, CAM_BRILLO, usarStandby ? "SI" : "NO");
}

// Registro 0x3008 del OV5640: bit 6 = modo de bajo consumo (conserva la configuracion).
void camaraDormir() {
  sensor_t* s = esp_camera_sensor_get();
  if (s && s->set_reg) s->set_reg(s, 0x3008, 0x40, 0x40);
}

void camaraDespertar() {
  sensor_t* s = esp_camera_sensor_get();
  if (s && s->set_reg) s->set_reg(s, 0x3008, 0x40, 0x00);
}

camera_fb_t* tomarFoto() {
  unsigned long t0 = millis();
  uint32_t minimo = usarStandby ? MS_AJUSTE_MIN : 0;
  if (usarStandby) camaraDespertar();

  // Se descartan cuadros hasta que la exposicion se estabiliza. Cuando la camara
  // todavia esta ajustando la luz, cada cuadro sale con brillo distinto y el JPEG
  // cambia mucho de tamano; cuando se acomoda, dos cuadros seguidos pesan casi lo mismo.
  int descartados = 0;
  size_t tamAnterior = 0;
  bool estable = false;
  camera_fb_t* fb = nullptr;
  while (true) {
    fb = esp_camera_fb_get();
    if (!fb) break;
    unsigned long dt = millis() - t0;
    if (tamAnterior > 0) {
      size_t dif = (fb->len > tamAnterior) ? fb->len - tamAnterior : tamAnterior - fb->len;
      estable = dif * 100 < tamAnterior * CAM_ESTABLE_PCT;
    }
    if (descartados >= 1 && dt >= minimo && (estable || dt >= MS_AJUSTE_MAX)) break;
    tamAnterior = fb->len;
    esp_camera_fb_return(fb);
    fb = nullptr;
    descartados++;
  }

  if (!fb && usarStandby) {
    // El sensor no volvio del modo de bajo consumo: se desactiva el standby y se reintenta.
    Serial.println("[CAMARA] No responde al despertar. Desactivo el standby (pone CAMARA_STANDBY 0).");
    usarStandby = false;
    camaraDespertar();
    delay(300);
    fb = esp_camera_fb_get();
    if (fb) { esp_camera_fb_return(fb); delay(150); fb = esp_camera_fb_get(); }
  }

  if (usarStandby) camaraDormir();         // la foto ya esta en memoria

  if (!fb) {
    Serial.println("[CAMARA] Error: no se pudo capturar la imagen.");
    return nullptr;
  }
  Serial.printf("[CAMARA] Captura OK: %u x %u (%u bytes) en %lu ms, %d cuadros descartados, exposicion %s\n",
                fb->width, fb->height, fb->len, millis() - t0, descartados,
                estable ? "estable" : "SIN estabilizar (poca luz o escena cambiando)");
  return fb;
}

// Corre en el nucleo 0 mientras el nucleo 1 graba la voz.
void tareaFoto(void* arg) {
  camera_fb_t* fb = tomarFoto();
  fotoFb = fb;
  fotoCapturada = true;

  unsigned long t0 = millis();
  while (decisionFoto == 0 && millis() - t0 < 15000) {
    vTaskDelay(pdMS_TO_TICKS(5));
  }

  if (decisionFoto == 2 && fb) {
    contextoOk = subirContexto(fb);
    esp_camera_fb_return(fb);
    fotoFb = nullptr;
  }
  // Si fue toque corto, la foto la usa y libera manejarBotonFoto().

  fotoTerminada = true;
  tareaFotoViva = false;
  vTaskDelete(NULL);
}

bool lanzarTareaFoto() {
  tareaFotoViva = true;
  BaseType_t ok = xTaskCreatePinnedToCore(tareaFoto, "tareaFoto", 12288, NULL, 1, NULL, 0);
  if (ok != pdPASS) {
    tareaFotoViva = false;
    return false;
  }
  return true;
}

void esperarTareaAnterior() {
  unsigned long t0 = millis();
  while (tareaFotoViva && millis() - t0 < 15000) delay(10);
  if (!tareaFotoViva && fotoFb) {            // red de seguridad: nunca dejar la camara tomada
    esp_camera_fb_return(fotoFb);
    fotoFb = nullptr;
  }
}

bool esperarFlag(volatile bool& flag, uint32_t ms) {
  unsigned long t0 = millis();
  while (!flag && millis() - t0 < ms) delay(5);
  return flag;
}

// =====================================================
// CONTROL DE LOS BOTONES  (con antirrebote)
// =====================================================
bool vozHabilitado  = true;    // se deshabilita si el pin queda trabado en bajo al encender
bool fotoHabilitado = true;
unsigned long tUltimaAccion = 0;

// La presion cuenta solo si el pin se mantiene en bajo MS_PRESION_ESTABLE seguidos.
bool presionEstable(int pin) {
  unsigned long t0 = millis();
  while (millis() - t0 < MS_PRESION_ESTABLE) {
    if (digitalRead(pin) != LOW) return false;   // fue un rebote o ruido
    delay(2);
  }
  return true;
}

// Detecta el soltado sin bloquear: se llama en cada vuelta del bucle de grabacion.
// Cuenta como soltado solo si el pin estuvo en alto MS_SOLTADO_ESTABLE seguidos;
// un rebote (un instante en alto) no corta la grabacion.
struct DetectorSoltado {
  unsigned long tAlto = 0;     // momento en que se vio alto por primera vez (0 = sigue apretado)
  bool soltado(int pin) {
    if (digitalRead(pin) == LOW) { tAlto = 0; return false; }
    if (tAlto == 0) tAlto = millis();
    return millis() - tAlto >= MS_SOLTADO_ESTABLE;
  }
  bool sigueApretado() { return tAlto == 0; }
};

void esperarQueSuelte(int pin) {
  DetectorSoltado d;
  unsigned long t0 = millis();
  while (!d.soltado(pin) && millis() - t0 < 5000) delay(5);
  tUltimaAccion = millis();                      // arranca el bloqueo post-accion
}

// ---------- BOTON VOZ: pregunta por voz, sin foto ----------
void manejarBotonVoz(unsigned long tPresion) {
  esperarTareaAnterior();
  WiFi.setSleep(false);
  if (!asegurarServidor()) {                 // sin servidor: pitidos y no se graba
    esperarQueSuelte(BOTON_VOZ);
    WiFi.setSleep(true);
    return;
  }

  snprintf(sesionId, sizeof(sesionId), "%lu", tPresion);
  contextoOk = false;                        // este boton nunca manda foto
  tSoltar    = 0;
  Serial.printf("\n[BOTON VOZ] (GPIO %d) Grabando... solta el boton para enviar. Sesion %s\n",
                BOTON_VOZ, sesionId);

  descartarMicPrevio();
  bytesGrabados = 0;
  DetectorSoltado det;
  while (!det.soltado(BOTON_VOZ)) {
    leerMicUnaVez();
    if (bytesGrabados >= MAX_BYTES_AUDIO) {
      Serial.println("[MIC] Limite maximo alcanzado (10 s).");
      break;
    }
  }
  tSoltar = det.tAlto ? det.tAlto : millis();

  float seg = (float)bytesGrabados / (SAMPLE_RATE * 2);
  Serial.printf("[MIC] Fin grabacion: %u bytes (%.1f s)\n", bytesGrabados, seg);

  if (bytesGrabados < MIN_BYTES_VOZ) {
    Serial.println("[MIC] Grabacion demasiado corta, descartada. Mantene el boton mientras hablas.");
  } else if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WIFI] Error: sin conexion a la red.");
  } else {
    enviarAudio("voz");
  }

  esperarQueSuelte(BOTON_VOZ);
  tSoltar = 0;
  WiFi.setSleep(true);
  Serial.println("[ESTADO] Listo. Esperando un boton.");
}

// ---------- BOTON FOTO: corto = foto | largo = foto + pregunta ----------
void manejarBotonFoto(unsigned long tPresion) {
  esperarTareaAnterior();
  WiFi.setSleep(false);                      // WiFi a pleno durante la interaccion
  if (!asegurarServidor()) {                 // sin servidor: pitidos y no se saca la foto
    esperarQueSuelte(BOTON_FOTO);
    WiFi.setSleep(true);
    return;
  }

  snprintf(sesionId, sizeof(sesionId), "%lu", tPresion);
  decisionFoto  = 0;
  fotoCapturada = false;
  fotoTerminada = false;
  contextoOk    = false;
  fotoFb        = nullptr;
  tSoltar       = 0;
  Serial.printf("\n[BOTON FOTO] (GPIO %d) Presionado. Sesion %s\n", BOTON_FOTO, sesionId);

  // La foto empieza a tomarse YA (nucleo 0). Si no se puede crear la tarea, se toma aca.
  bool tareaOk = lanzarTareaFoto();
  if (!tareaOk) {
    Serial.println("[AVISO] No se pudo crear la tarea de foto. Modo secuencial.");
    fotoFb = tomarFoto();
    fotoCapturada = true;
    fotoTerminada = true;
  }

  // El microfono graba desde ya; si resulta toque corto, se descarta.
  descartarMicPrevio();
  bytesGrabados = 0;
  bool largo = false;
  DetectorSoltado det;

  while (!det.soltado(BOTON_FOTO)) {
    leerMicUnaVez();

    // Solo cuenta como largo si el boton SIGUE apretado al cumplirse el tiempo
    // (un rebote de soltado justo antes de los 500 ms no lo convierte en largo).
    if (!largo && det.sigueApretado() && millis() - tPresion >= MS_PULSACION_LARGA) {
      largo = true;
      decisionFoto = 2;
      Serial.println("[ACCION] Pulsacion larga -> foto + pregunta (solta el boton para enviar)");
    }
    if (bytesGrabados >= MAX_BYTES_AUDIO) {
      Serial.println("[MIC] Limite maximo alcanzado (10 s).");
      break;
    }
  }
  tSoltar = det.tAlto ? det.tAlto : millis();
  if (!largo) decisionFoto = 1;

  if (!largo) {
    // ---------- TOQUE CORTO: solo foto ----------
    Serial.printf("[ACCION] Toque corto (%lu ms) -> foto\n", tSoltar - tPresion);
    esperarFlag(fotoCapturada, 4000);
    camera_fb_t* fb = fotoFb;
    if (fb) {
      if (WiFi.status() == WL_CONNECTED) {
        enviarFoto(fb);
      } else {
        Serial.println("[WIFI] Error: sin conexion a la red.");
      }
      esp_camera_fb_return(fb);
      fotoFb = nullptr;
    } else {
      Serial.println("[CAMARA] No hay foto para enviar.");
    }
  } else {
    // ---------- PULSACION LARGA: foto + pregunta ----------
    float seg = (float)bytesGrabados / (SAMPLE_RATE * 2);
    Serial.printf("[MIC] Fin grabacion: %u bytes (%.1f s)\n", bytesGrabados, seg);

    esperarFlag(fotoTerminada, 6000);        // normalmente ya termino: subio mientras hablabas

    if (!tareaOk && fotoFb) {                // modo secuencial de emergencia
      if (WiFi.status() == WL_CONNECTED) contextoOk = subirContexto(fotoFb);
      esp_camera_fb_return(fotoFb);
      fotoFb = nullptr;
    }

    if (bytesGrabados < MIN_BYTES_VOZ) {
      Serial.println("[MIC] Grabacion demasiado corta, descartada.");
    } else if (WiFi.status() != WL_CONNECTED) {
      Serial.println("[WIFI] Error: sin conexion a la red.");
    } else {
      enviarAudio("foto");
    }
  }

  esperarQueSuelte(BOTON_FOTO);
  tSoltar = 0;
  WiFi.setSleep(true);                       // vuelve al ahorro de energia
  Serial.println("[ESTADO] Listo. Esperando un boton.");
}

// =====================================================
// MODO CONVERSACION (GEMINI LIVE)  -  nuevo en v11
// =====================================================
// Todo pasa por un WebSocket permanente con la app: ws://celular:8000/live
//   ESP32 -> app:  [0x01]+PCM 16 kHz (mic), [0x02]+JPEG (foto), textos JSON de control
//   app -> ESP32:  [0x11]+PCM a la frecuencia de "config" (parlante), textos JSON
// El parlante tiene su propia tarea con un buffer grande en PSRAM: el audio de Gemini
// llega mas rapido que lo que se reproduce y se va acumulando ahi.

struct TramaMic { uint16_t n; uint8_t d[1 + MIC_TRAMA_BYTES]; };

WebSocketsClient wsLive;
volatile bool modoLive        = false;   // true = conversacion; false = clasico (HTTP)
volatile bool wsConectado     = false;
volatile bool micAbiertoLive  = false;   // lo decide la app ("config")
volatile bool micActivo       = false;   // boton apretado (modo boton)
volatile bool micPausado      = false;   // modo abierto: pausado con VOZ mantenido
volatile bool vaciarParlante  = false;
volatile bool vozFinPendiente = false;   // modo abierto: dejo de oirse voz
volatile uint32_t ultimoSonidoMs = 0;
volatile bool geminiListo     = false;
unsigned long tDesconexionWs  = 0;
int fallosWs                  = 0;

StreamBufferHandle_t bufParlante = nullptr;
StaticStreamBuffer_t bufParlanteEstructura;
QueueHandle_t colaMic = nullptr;

bool parlanteSonando() {
  if (!bufParlante) return false;
  return xStreamBufferBytesAvailable(bufParlante) > 0 || (millis() - ultimoSonidoMs) < MS_ECO_COLA;
}

// Mete audio en el buffer del parlante (lo reproduce tareaParlante)
void encolarParlante(const uint8_t* datos, size_t n) {
  if (!bufParlante || n < 2) return;
  n &= ~((size_t)1);
  size_t enviados = xStreamBufferSend(bufParlante, datos, n, pdMS_TO_TICKS(200));
  if (enviados < n) Serial.println("[LIVE] Buffer del parlante lleno: se perdio audio.");
}

// Pitido por el buffer del parlante (en modo conversacion no se escribe directo al I2S)
void pitidoLive(int hz, int ms) {
  const int N = 256;
  int16_t buf[N];
  uint32_t fs = frecuenciaSalida;
  uint32_t total = fs * ms / 1000;
  uint32_t rampa = fs / 200;
  for (uint32_t i = 0; i < total; i += N) {
    int k = 0;
    for (; k < N && i + k < total; k++) {
      uint32_t j = i + k;
      float env = 1.0f;
      if (j < rampa) env = (float)j / rampa;
      else if (j + rampa > total) env = (float)(total - j) / rampa;
      buf[k] = (int16_t)(9000.0f * env * sinf(2.0f * PI * hz * j / fs));
    }
    encolarParlante((uint8_t*)buf, k * 2);
  }
}

void avisoSinServidorLive() { pitidoLive(440, 120); pitidoLive(1, 80); pitidoLive(330, 180); }
void avisoMicLive(bool encendido) { if (encendido) { pitidoLive(900, 70); pitidoLive(1300, 70); } else { pitidoLive(1300, 70); pitidoLive(900, 70); } }

// ---------- Tarea del parlante (nucleo 0) ----------
void tareaParlante(void* arg) {
  uint8_t buf[1024];
  size_t escritos;
  for (;;) {
    if (!modoLive || !bufParlante) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }
    if (vaciarParlante) {
      while (xStreamBufferReceive(bufParlante, buf, sizeof(buf), 0) > 0) {}
      i2s_zero_dma_buffer(I2S_NUM_0);
      vaciarParlante = false;
      continue;
    }
    size_t n = xStreamBufferReceive(bufParlante, buf, sizeof(buf), pdMS_TO_TICKS(20));
    if (n >= 2) {
      i2s_write(I2S_NUM_0, buf, n & ~((size_t)1), &escritos, portMAX_DELAY);
      ultimoSonidoMs = millis();
    }
  }
}

// ---------- Tarea del microfono (nucleo 0) ----------
// Modo boton: manda todo mientras micActivo.
// Modo abierto: compuerta de voz local (solo manda cuando hay voz, con 320 ms de audio
// previo para no cortar el principio) y se silencia mientras suena el parlante (eco).
void tareaMic(void* arg) {
  static int32_t crudo[MIC_TRAMA_MUESTRAS];
  static TramaMic previas[MIC_TRAMAS_PREVIAS];
  int idxPrevia = 0, cantPrevias = 0;
  bool compuerta = false;
  unsigned long tUltVoz = 0;
  TramaMic t;

  for (;;) {
    bool abierto = micAbiertoLive && !micPausado && wsConectado;
    if (!modoLive || (!micActivo && !abierto)) {
      if (compuerta) { compuerta = false; vozFinPendiente = true; }
      cantPrevias = 0;
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    size_t leidos = 0;
    i2s_read(I2S_NUM_1, crudo, sizeof(crudo), &leidos, pdMS_TO_TICKS(100));
    int muestras = leidos / sizeof(int32_t);
    if (muestras < MIC_TRAMA_MUESTRAS) continue;

    t.n = 1 + MIC_TRAMA_BYTES;
    t.d[0] = 0x01;
    int16_t* pcm = (int16_t*)(t.d + 1);   // el byte 0 es el tipo; el ESP32-S3 tolera acceso desalineado
    double suma = 0;
    for (int i = 0; i < MIC_TRAMA_MUESTRAS; i++) {
      int32_t v = (crudo[i] >> 16) * MIC_GANANCIA_LIVE;
      if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
      int16_t s = (int16_t)v;
      memcpy((uint8_t*)pcm + 2 * i, &s, 2);
      suma += (double)s * s;
    }

    if (micActivo) {                         // modo boton: todo
      xQueueSend(colaMic, &t, 0);
      continue;
    }

    // ----- modo abierto -----
    float rms = sqrtf(suma / MIC_TRAMA_MUESTRAS);
    bool eco = parlanteSonando();
    bool hayVoz = !eco && rms > UMBRAL_VOZ;

    if (eco && compuerta) {                  // empezo a hablar Gemini: cortar lo nuestro
      compuerta = false;
      vozFinPendiente = true;
      cantPrevias = 0;
      continue;
    }
    if (hayVoz) {
      if (!compuerta) {
        compuerta = true;
        // primero el audio previo, en orden
        int inicio = (idxPrevia - cantPrevias + MIC_TRAMAS_PREVIAS) % MIC_TRAMAS_PREVIAS;
        for (int k = 0; k < cantPrevias; k++) xQueueSend(colaMic, &previas[(inicio + k) % MIC_TRAMAS_PREVIAS], 0);
        cantPrevias = 0;
      }
      tUltVoz = millis();
      xQueueSend(colaMic, &t, 0);
    } else if (compuerta) {
      xQueueSend(colaMic, &t, 0);            // cola de silencio despues de hablar
      if (millis() - tUltVoz > MS_FIN_VOZ) { compuerta = false; vozFinPendiente = true; }
    } else if (!eco) {
      previas[idxPrevia] = t;
      idxPrevia = (idxPrevia + 1) % MIC_TRAMAS_PREVIAS;
      if (cantPrevias < MIC_TRAMAS_PREVIAS) cantPrevias++;
    }
  }
}

// Manda por el WebSocket lo que junto el microfono (se llama desde el loop)
void enviarColaMic() {
  TramaMic t;
  while (colaMic && xQueueReceive(colaMic, &t, 0) == pdTRUE) {
    if (wsConectado) wsLive.sendBIN(t.d, t.n);
  }
  if (vozFinPendiente) {
    vozFinPendiente = false;
    if (wsConectado) wsLive.sendTXT("{\"t\":\"voz_fin\"}");
  }
}

void enviarTextoLive(const char* s) { if (wsConectado) wsLive.sendTXT(s); }

bool enviarFotoLive(camera_fb_t* fb, bool conPregunta) {
  if (!fb || !wsConectado) return false;
  uint8_t* marco = tienePSRAM ? (uint8_t*)ps_malloc(fb->len + 1) : (uint8_t*)malloc(fb->len + 1);
  if (!marco) { Serial.println("[LIVE] Sin memoria para la foto."); return false; }
  marco[0] = 0x02;
  memcpy(marco + 1, fb->buf, fb->len);
  enviarTextoLive(conPregunta ? "{\"t\":\"foto\",\"pregunta\":true}" : "{\"t\":\"foto\",\"pregunta\":false}");
  bool ok = wsLive.sendBIN(marco, fb->len + 1);
  free(marco);
  Serial.printf("[LIVE] Foto enviada (%u bytes)%s\n", fb->len, conPregunta ? " con pregunta" : "");
  return ok;
}

// ---------- Mensajes de la app ----------
int numeroJson(const char* s, const char* clave) {
  const char* p = strstr(s, clave);
  return p ? atoi(p + strlen(clave)) : 0;
}

void detenerLive();

void alEventoWs(WStype_t tipo, uint8_t* datos, size_t largo) {
  switch (tipo) {
    case WStype_CONNECTED:
      wsConectado = true;
      fallosWs = 0;
      Serial.printf("[LIVE] Conectado al celular (ws://%s:%d/live)\n", servidorIp, servidorPuerto);
      wsLive.sendTXT("{\"t\":\"hola\",\"v\":11}");
      break;

    case WStype_DISCONNECTED:
      if (wsConectado) {
        Serial.println("[LIVE] Se corto la conexion con el celular.");
        tDesconexionWs = millis();
      } else {
        fallosWs++;
      }
      wsConectado = false;
      geminiListo = false;
      micActivo = false;
      break;

    case WStype_TEXT: {
      char txt[192];
      size_t n = largo < sizeof(txt) - 1 ? largo : sizeof(txt) - 1;
      memcpy(txt, datos, n); txt[n] = 0;
      if (strstr(txt, "\"t\":\"callar\"")) {
        vaciarParlante = true;
      } else if (strstr(txt, "\"t\":\"config\"")) {
        micAbiertoLive = strstr(txt, "\"mic\":\"abierto\"") != nullptr;
        int fs = numeroJson(txt, "\"fs\":");
        vaciarParlante = true;
        delay(30);
        if (fs > 0) ajustarFrecuenciaSalida(fs);
        Serial.printf("[LIVE] Microfono: %s. Parlante a %d Hz\n",
                      micAbiertoLive ? "ABIERTO (manos libres)" : "con el boton apretado", fs);
      } else if (strstr(txt, "\"t\":\"estado\"")) {
        geminiListo = strstr(txt, "\"listo\":true") != nullptr;
        Serial.printf("[LIVE] Gemini: %s\n", geminiListo ? "listo" : txt);
      } else if (strstr(txt, "\"t\":\"modo\"") && strstr(txt, "\"clasico\"")) {
        Serial.println("[LIVE] La app pide el modo clasico.");
        detenerLive();
      } else if (strstr(txt, "\"t\":\"fin_turno\"")) {
        // Gemini termino de hablar; lo que queda en el buffer se sigue reproduciendo
      }
      break;
    }

    case WStype_BIN:
      if (largo > 1 && datos[0] == 0x11) encolarParlante(datos + 1, largo - 1);
      break;

    default:
      break;
  }
}

void iniciarLive() {
  if (modoLive) return;
  vaciarI2S_salida();
  modoLive = true;
  wsConectado = false;
  fallosWs = 0;
  tDesconexionWs = millis();
  WiFi.setSleep(false);                      // el streaming necesita el WiFi a pleno
  wsLive.onEvent(alEventoWs);
  wsLive.begin(servidorIp, servidorPuerto, "/live");
  wsLive.setReconnectInterval(2000);
  wsLive.enableHeartbeat(15000, 3000, 2);
  Serial.println("[MODO] CONVERSACION (Gemini Live)");
}

void detenerLive() {
  if (!modoLive) return;
  micActivo = false;
  modoLive = false;                          // las tareas de mic y parlante se quedan quietas
  wsLive.disconnect();
  wsConectado = false;
  delay(60);
  if (bufParlante) xStreamBufferReset(bufParlante);
  vaciarI2S_salida();
  Serial.println("[MODO] CLASICO (pregunta y respuesta por HTTP)");
}

// Le pregunta a la app en que modo trabajar: "live" o "clasico"
bool preguntarModoLive() {
  if (!servidorOk) return false;
  char url[72];
  snprintf(url, sizeof(url), "http://%s:%d/modo", servidorIp, servidorPuerto);
  HTTPClient http;
  http.begin(url);
  http.setConnectTimeout(1500);
  http.setTimeout(2500);
  int codigo = http.GET();
  bool live = (codigo == 200) && http.getString().indexOf("live") >= 0;
  http.end();
  return live;                               // 404 = app vieja: modo clasico
}

// ---------- Botones en modo conversacion ----------
void sinConexionLive(int pin) {
  Serial.println("[LIVE] Sin conexion con el celular o con Gemini.");
  avisoSinServidorLive();
  esperarQueSuelte(pin);
}

// Mientras el boton esta apretado: el mic va mandando y el WebSocket sigue atendiendo
bool streamingMientrasApretado(int pin, unsigned long tPresion, camera_fb_t* volatile* foto, bool* fotoMandada) {
  DetectorSoltado det;
  while (!det.soltado(pin)) {
    wsLive.loop();
    enviarColaMic();
    if (foto && fotoMandada && !*fotoMandada && fotoCapturada) {
      *fotoMandada = true;
      camera_fb_t* fb = *foto;
      if (fb) { enviarFotoLive(fb, true); esp_camera_fb_return(fb); *foto = nullptr; }
    }
    if (millis() - tPresion > MAX_SEG_HABLAR_LIVE * 1000UL) {
      Serial.println("[LIVE] Limite de tiempo hablando alcanzado.");
      break;
    }
    delay(2);
  }
  return true;
}

void hablarLive(int pin, unsigned long tPresion, bool conFoto) {
  vaciarParlante = true;                      // si Gemini estaba hablando, se calla
  enviarTextoLive("{\"t\":\"hablar_ini\"}");
  micActivo = true;
  Serial.printf("[LIVE] Escuchando... solta el boton cuando termines%s\n", conFoto ? " (con foto)" : "");

  bool fotoMandada = !conFoto;
  streamingMientrasApretado(pin, tPresion, conFoto ? &fotoFb : nullptr, &fotoMandada);

  micActivo = false;
  delay(60);                                  // la ultima trama del microfono
  enviarColaMic();
  if (conFoto && !fotoMandada) {              // la foto termino despues de soltar
    esperarFlag(fotoCapturada, 3000);
    if (fotoFb) { enviarFotoLive(fotoFb, true); esp_camera_fb_return(fotoFb); fotoFb = nullptr; }
  }
  enviarTextoLive("{\"t\":\"hablar_fin\"}");
  Serial.printf("[LIVE] Enviado (%lu ms hablando). Esperando la respuesta...\n", millis() - tPresion);
  tUltimaAccion = millis();
}

void tareaFotoLive(void* arg) {
  fotoFb = tomarFoto();
  fotoCapturada = true;
  tareaFotoViva = false;
  vTaskDelete(NULL);
}

void manejarVozLive(unsigned long tPresion) {
  if (!wsConectado || !geminiListo) { sinConexionLive(BOTON_VOZ); return; }

  if (!micAbiertoLive) {                      // modo boton: mantener y hablar
    Serial.printf("\n[BOTON VOZ] (GPIO %d) modo conversacion\n", BOTON_VOZ);
    hablarLive(BOTON_VOZ, tPresion, false);
    esperarQueSuelte(BOTON_VOZ);
    return;
  }

  // Modo abierto: toque = callar a Gemini. Mantener 1 s = pausar / reanudar el microfono.
  DetectorSoltado det;
  while (!det.soltado(BOTON_VOZ) && millis() - tPresion < MS_PAUSA_MIC) { wsLive.loop(); delay(5); }
  if (det.sigueApretado()) {
    micPausado = !micPausado;
    Serial.printf("[LIVE] Microfono %s\n", micPausado ? "EN PAUSA" : "ACTIVO");
    avisoMicLive(!micPausado);
  } else {
    Serial.println("[LIVE] Callar");
    vaciarParlante = true;
    enviarTextoLive("{\"t\":\"callar\"}");
  }
  esperarQueSuelte(BOTON_VOZ);
}

void manejarFotoLive(unsigned long tPresion) {
  if (!wsConectado || !geminiListo) { sinConexionLive(BOTON_FOTO); return; }
  esperarTareaAnterior();
  Serial.printf("\n[BOTON FOTO] (GPIO %d) modo conversacion\n", BOTON_FOTO);

  fotoCapturada = false;
  fotoFb = nullptr;
  tareaFotoViva = true;
  if (xTaskCreatePinnedToCore(tareaFotoLive, "fotoLive", 12288, NULL, 1, NULL, 0) != pdPASS) {
    tareaFotoViva = false;
    fotoFb = tomarFoto();
    fotoCapturada = true;
  }

  // Esperar a ver si es toque corto o mantenido
  DetectorSoltado det;
  bool largo = false;
  while (!det.soltado(BOTON_FOTO)) {
    wsLive.loop();
    if (det.sigueApretado() && millis() - tPresion >= MS_PULSACION_LARGA) { largo = true; break; }
    delay(2);
  }

  if (largo) {
    if (micAbiertoLive) {
      // manos libres: la foto se manda y la pregunta se hace hablando normalmente
      esperarFlag(fotoCapturada, 4000);
      if (fotoFb) { enviarFotoLive(fotoFb, true); esp_camera_fb_return(fotoFb); fotoFb = nullptr; }
      pitidoLive(1000, 60);
    } else {
      hablarLive(BOTON_FOTO, tPresion, true);
    }
  } else {
    Serial.println("[ACCION] Toque corto -> foto");
    esperarFlag(fotoCapturada, 4000);
    if (fotoFb) { enviarFotoLive(fotoFb, false); esp_camera_fb_return(fotoFb); fotoFb = nullptr; }
    else Serial.println("[CAMARA] No hay foto para enviar.");
  }
  esperarQueSuelte(BOTON_FOTO);
}

// Bucle del modo conversacion
void loopLive() {
  wsLive.loop();
  enviarColaMic();

  // Si el celular no aparece en 20 s, se vuelve a buscar el servidor (y su modo)
  if (!wsConectado && millis() - tDesconexionWs > 20000) {
    Serial.println("[LIVE] No encuentro el celular. Vuelvo a buscar el servidor.");
    detenerLive();
    servidorOk = false;
    avisoSinServidor();
    return;
  }
  // App vieja sin modo conversacion (rechaza /live): volver al clasico
  if (!wsConectado && fallosWs >= 3) {
    Serial.println("[LIVE] La app no acepta el modo conversacion. Sigo en modo clasico.");
    detenerLive();
    return;
  }

  if (millis() - tUltimaAccion < MS_BLOQUEO_ACCION) return;
  if (vozHabilitado && digitalRead(BOTON_VOZ) == LOW) {
    unsigned long tPresion = millis();
    if (presionEstable(BOTON_VOZ)) manejarVozLive(tPresion);
  } else if (fotoHabilitado && digitalRead(BOTON_FOTO) == LOW) {
    unsigned long tPresion = millis();
    if (presionEstable(BOTON_FOTO)) manejarFotoLive(tPresion);
  }
}

// Modo prueba: muestra cada cambio de los botones, con rebotes incluidos.
void probarBotones() {
  static int antV = -1, antF = -1;
  static unsigned long tV = 0, tF = 0;
  int v = digitalRead(BOTON_VOZ), f = digitalRead(BOTON_FOTO);
  if (v != antV) {
    Serial.printf("[PRUEBA] BOTON VOZ  (GPIO %d): %-9s  (+%lu ms)\n", BOTON_VOZ,
                  v == LOW ? "APRETADO" : "suelto", antV < 0 ? 0 : millis() - tV);
    antV = v; tV = millis();
  }
  if (f != antF) {
    Serial.printf("[PRUEBA] BOTON FOTO (GPIO %d): %-9s  (+%lu ms)\n", BOTON_FOTO,
                  f == LOW ? "APRETADO" : "suelto", antF < 0 ? 0 : millis() - tF);
    antF = f; tF = millis();
  }
}

// =====================================================
// CONEXION
// =====================================================
// Se llama cada vez que el WiFi se conecta (al encender o al volver a conectarse).
void alConectarWifi() {
  Serial.printf("\n[WIFI] Conectado a \"%s\". IP de la placa: %s, puerta de enlace: %s\n",
                WiFi.SSID().c_str(), WiFi.localIP().toString().c_str(), WiFi.gatewayIP().toString().c_str());
  if (mdnsListo) { MDNS.end(); mdnsListo = false; }       // red nueva: reiniciar mDNS
  if (buscarServidor()) {
    if (preguntarModoLive()) {
      iniciarLive();                         // el saludo lo manda la app por el WebSocket
      bienvenidaPendiente = false;
      return;
    }
    if (bienvenidaPendiente) {
      reproducirBienvenida();
      bienvenidaPendiente = false;
    } else {
      avisoConectado();
    }
  } else {
    avisoSinServidor();
  }
  WiFi.setSleep(true);                       // ahorro de energia mientras espera un boton
}

// =====================================================
// SETUP
// =====================================================
void setup() {
  setCpuFrequencyMhz(CPU_MHZ);
  delay(1500);
  Serial.begin(115200);
  Serial.println("\n======================================");
  Serial.println("INICIANDO LENTES INTELIGENTES v12");
  Serial.println("======================================");


  init_i2s_salida();
  init_i2s_entrada();

  camera_config_t config = {};
  config.ledc_channel  = LEDC_CHANNEL_0;
  config.ledc_timer    = LEDC_TIMER_0;
  config.pin_d0        = Y2_GPIO_NUM;
  config.pin_d1        = Y3_GPIO_NUM;
  config.pin_d2        = Y4_GPIO_NUM;
  config.pin_d3        = Y5_GPIO_NUM;
  config.pin_d4        = Y6_GPIO_NUM;
  config.pin_d5        = Y7_GPIO_NUM;
  config.pin_d6        = Y8_GPIO_NUM;
  config.pin_d7        = Y9_GPIO_NUM;
  config.pin_xclk      = XCLK_GPIO_NUM;
  config.pin_pclk      = PCLK_GPIO_NUM;
  config.pin_vsync     = VSYNC_GPIO_NUM;
  config.pin_href      = HREF_GPIO_NUM;
  config.pin_sccb_sda  = SIOD_GPIO_NUM;
  config.pin_sccb_scl  = SIOC_GPIO_NUM;
  config.pin_pwdn      = PWDN_GPIO_NUM;
  config.pin_reset     = RESET_GPIO_NUM;
  config.xclk_freq_hz  = 10000000;
  config.pixel_format  = PIXFORMAT_JPEG;
  config.grab_mode     = CAMERA_GRAB_WHEN_EMPTY;
  config.fb_count      = 1;

  tienePSRAM = psramFound();
  if (tienePSRAM) {
    config.frame_size   = FRAMESIZE_XGA;
    config.jpeg_quality = CAM_CALIDAD_JPEG;
    config.fb_location  = CAMERA_FB_IN_PSRAM;
    bufGrabacion = (uint8_t*)ps_malloc(MAX_BYTES_AUDIO + 44);
  } else {
    config.frame_size   = FRAMESIZE_SVGA;
    config.jpeg_quality = 12;
    config.fb_location  = CAMERA_FB_IN_DRAM;
    bufGrabacion = (uint8_t*)malloc(MAX_BYTES_AUDIO + 44);
  }

  if (!bufGrabacion) {
    Serial.println("[MEMORIA] Error al reservar buffer de audio. Reiniciando...");
    delay(2000);
    ESP.restart();
  }

  // Modo conversacion: buffer del parlante (PSRAM), cola del microfono y sus tareas
  {
    size_t tam = (size_t)PCM_LIVE_FS * 2 * SEG_BUFFER_PARLANTE;
    if (!tienePSRAM) tam = 48000;            // sin PSRAM: 1 s (puede cortarse)
    uint8_t* mem = tienePSRAM ? (uint8_t*)ps_malloc(tam + 1) : (uint8_t*)malloc(tam + 1);
    if (mem) bufParlante = xStreamBufferCreateStatic(tam, 1, mem, &bufParlanteEstructura);
    colaMic = xQueueCreate(MIC_COLA_TRAMAS, sizeof(TramaMic));
    if (!bufParlante || !colaMic) Serial.println("[LIVE] Sin memoria: el modo conversacion no va a andar.");
    xTaskCreatePinnedToCore(tareaParlante, "parlante", 4096, NULL, 3, NULL, 0);
    xTaskCreatePinnedToCore(tareaMic, "mic", 6144, NULL, 2, NULL, 0);
  }

  if (esp_camera_init(&config) != ESP_OK) {
    Serial.println("[CAMARA] Error de inicializacion.");
    return;
  }
  Serial.println("[CAMARA] Lista.");
  ajustarSensor();
  if (usarStandby) camaraDormir();

  pinMode(BOTON_VOZ,  INPUT_PULLUP);
  pinMode(BOTON_FOTO, INPUT_PULLUP);
  delay(100);

#if PRUEBA_BOTONES
  Serial.println("======================================");
  Serial.println("MODO PRUEBA DE BOTONES (PRUEBA_BOTONES 1)");
  Serial.printf ("Apreta cada boton. Tiene que aparecer GPIO %d para VOZ y GPIO %d para FOTO.\n",
                 BOTON_VOZ, BOTON_FOTO);
  Serial.println("Si al soltar aparecen varios cambios seguidos de pocos ms, son rebotes.");
  Serial.println("======================================");
  sistemaListo = true;
  return;                                    // en modo prueba no se conecta ni saluda
#endif

  // Si al encender un boton lee "apretado" mas de 3 s, esta mal cableado o el pin
  // tiene algo conectado (por ejemplo un LED de la placa). Se deshabilita y se avisa.
  unsigned long tEspera = millis();
  while ((digitalRead(BOTON_VOZ) == LOW || digitalRead(BOTON_FOTO) == LOW) && millis() - tEspera < 3000) {
    delay(50);
  }
  if (digitalRead(BOTON_VOZ) == LOW) {
    vozHabilitado = false;
    Serial.printf("[BOTON] ATENCION: GPIO %d (VOZ) lee apretado todo el tiempo. Lo deshabilito. Revisar cableado.\n", BOTON_VOZ);
  }
  if (digitalRead(BOTON_FOTO) == LOW) {
    fotoHabilitado = false;
    Serial.printf("[BOTON] ATENCION: GPIO %d (FOTO) lee apretado todo el tiempo. Lo deshabilito. Revisar cableado.\n", BOTON_FOTO);
  }

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  wifiMulti.addAP(WIFI_1_SSID, WIFI_1_PASS);
  if (WIFI_2_SSID[0]) wifiMulti.addAP(WIFI_2_SSID, WIFI_2_PASS);
  Serial.printf("[WIFI] Buscando la red \"%s\"%s%s%s\n", WIFI_1_SSID,
                WIFI_2_SSID[0] ? " o \"" : "", WIFI_2_SSID, WIFI_2_SSID[0] ? "\"" : "");

  unsigned long t0 = millis();
  while (wifiMulti.run(5000) != WL_CONNECTED && millis() - t0 < 20000) {
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    alConectarWifi();
  } else {
    Serial.println("\n[WIFI] Sin conexion todavia. Sigo intentando en segundo plano.");
    Serial.println("[WIFI] Revisa que el hotspot este prendido, en 2.4 GHz, y el nombre/clave.");
    avisoSinServidor();
  }

  sistemaListo = true;
  Serial.println("======================================");
  Serial.println("SISTEMA LISTO:");
  Serial.printf ("  BOTON VOZ  (GPIO %d): mantener y hablar  -> pregunta sin foto\n", BOTON_VOZ);
  Serial.printf ("  BOTON FOTO (GPIO %d): toque corto        -> describe la foto\n", BOTON_FOTO);
  Serial.println("                       mantener y hablar  -> pregunta sobre la foto");
  Serial.println("  Modo (clasico o conversacion): se elige en la app, en Ajustes.");
  Serial.println("======================================");
}

// =====================================================
// BUCLE PRINCIPAL
// =====================================================
void loop() {
#if PRUEBA_BOTONES
  probarBotones();
  delay(1);
  return;
#endif

  if (!sistemaListo) {
    delay(500);
    return;
  }

  // WiFi: reconectar y, al volver, buscar el servidor de nuevo
  static bool estabaConectado = (WiFi.status() == WL_CONNECTED);
  static unsigned long ultReintentoWifi = 0;
  bool conectado = (WiFi.status() == WL_CONNECTED);
  if (!conectado && millis() - ultReintentoWifi > 10000) {
    ultReintentoWifi = millis();
    Serial.println("[WIFI] Desconectado. Reintentando...");
    WiFi.setSleep(false);
    conectado = (wifiMulti.run(5000) == WL_CONNECTED);
  }
  if (conectado && !estabaConectado) alConectarWifi();
  if (!conectado && estabaConectado) servidorOk = false;
  estabaConectado = conectado;

  if (!conectado && modoLive) detenerLive();

  // Modo conversacion: tiene su propio bucle
  if (modoLive) {
    loopLive();
    delay(1);
    return;
  }

  // Servidor: si no esta, buscarlo cada 15 s (por ejemplo, si la app se abrio despues)
  static unsigned long ultBusqueda = 0;
  if (conectado && !servidorOk && millis() - ultBusqueda > 15000) {
    ultBusqueda = millis();
    WiFi.setSleep(false);
    if (buscarServidor()) {
      if (preguntarModoLive()) { iniciarLive(); bienvenidaPendiente = false; return; }
      if (bienvenidaPendiente) { reproducirBienvenida(); bienvenidaPendiente = false; }
      else avisoConectado();
    }
    WiFi.setSleep(true);
  }

  // Modo clasico: cada 10 s pregunta si en la app se activo el modo conversacion
  static unsigned long ultModo = 0;
  if (conectado && servidorOk && millis() - ultModo > 10000 && millis() - tUltimaAccion > 3000) {
    ultModo = millis();
    if (preguntarModoLive()) { iniciarLive(); return; }
  }

  if (millis() - tUltimaAccion < MS_BLOQUEO_ACCION) {     // bloqueo post-accion (antirrebote)
    delay(5);
    return;
  }

  if (vozHabilitado && digitalRead(BOTON_VOZ) == LOW) {
    unsigned long tPresion = millis();
    if (presionEstable(BOTON_VOZ)) manejarBotonVoz(tPresion);
  } else if (fotoHabilitado && digitalRead(BOTON_FOTO) == LOW) {
    unsigned long tPresion = millis();
    if (presionEstable(BOTON_FOTO)) manejarBotonFoto(tPresion);
  }
  delay(5);
}
