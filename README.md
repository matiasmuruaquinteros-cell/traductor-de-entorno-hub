# Traductor de entorno para personas ciegas

Dispositivo que se lleva en el pecho y describe el entorno en voz alta. Tiene una cámara, un micrófono y un parlante. Un botón saca una foto y la inteligencia artificial (Google Gemini) cuenta qué hay adelante; otro botón permite hacer una pregunta con la voz, con o sin foto. También se puede llamar a los contactos y preguntar dónde se está, todo por voz.

Proyecto final de 7º año de Electrónica.

> Cada persona tiene que usar **su propia clave de Gemini**. Este repositorio no incluye ninguna clave ni contraseña: hay que completarlas antes de usarlo (ver [Seguridad](#seguridad)).

## Qué hace

- **Describe lo que ve:** toque corto en el botón de foto y suena la descripción.
- **Responde preguntas por voz,** con o sin foto, y recuerda la conversación (se puede repreguntar: "¿y de qué color es?").
- **Comandos de voz:** volumen, velocidad y tono de la voz, modo de funcionamiento, borrar la conversación.
- **Llamadas por voz:** "llamá a mamá". Busca en los contactos, **siempre pide confirmación** y llama con el altavoz. Si entra una llamada, avisa y se atiende o rechaza por voz.
- **Ubicación por voz:** "¿dónde estoy?" (calle, altura y entre qué calles), "¿dónde hay una farmacia cerca?" (lugares cercanos con distancia) y "¿cómo llego?" (camino a pie). No es un GPS que guía paso a paso: informa.
- **Modo conversación (experimental):** conversación continua con Gemini Live, con voz propia y posibilidad de interrumpir.

Estado: foto, voz, memoria, comandos y llamadas están probados en el dispositivo. El modo conversación y la ubicación por voz están en etapa de pruebas.

## Cómo funciona

```
 ESP32-S3 (pecho)  ──WiFi──►  App Android (servidor)  ──internet──►  Gemini
   cámara OV5640                · recibe foto y audio                 (describe /
   mic INMP441                  · consulta a Gemini                    responde)
   parlante MAX98357A  ◄──WAV── · convierte la respuesta a voz
                                · llamadas y ubicación del celular
```

- El **celular hace de servidor:** la app recibe la foto y el audio del ESP32, se los manda a Gemini y devuelve la respuesta ya convertida en voz, que suena en el parlante del dispositivo.
- El ESP32 se conecta al **hotspot del celular** y encuentra la app solo, sin escribir ninguna IP.
- Hay una alternativa: un servidor en Python para correr en una PC (`servidor-pc/`).

## Botones

| Botón | Acción | Qué hace |
|---|---|---|
| VOZ (GPIO 1) | mantener mientras hablás | pregunta por voz, sin foto |
| FOTO (GPIO 47) | toque corto | saca una foto y la describe |
| FOTO (GPIO 47) | mantener mientras hablás | saca una foto y responde tu pregunta sobre ella |
| FOTO (GPIO 47) | toque corto con una llamada sonando | rechaza la llamada |

Pulsadores de dos patas: una pata al GPIO y la otra a GND (sin resistencia, se usa el pull-up interno).

## Contenido del repositorio

| Carpeta | Qué es |
|---|---|
| `app-android/` | App para Android (Kotlin, una sola librería externa: OkHttp). Es el servidor: Gemini, voz, llamadas y ubicación. Se abre con Android Studio. |
| `firmware/lentes_inteligentes_v15/` | Código del ESP32-S3 (Arduino IDE). |
| `servidor-pc/` | Alternativa a la app: servidor en Python para correr en una PC. |
| `herramientas/escaner_botones/` | Sketch de diagnóstico para probar los pines de los botones. |
| `hardware/generar_placa.py` | Script que genera la placa de una cara (pertinax) para el ESP32 y el amplificador. |
| `docs/GUIA.md` | Guía paso a paso: compilar la app, configurar el hotspot, subir el firmware, usar todas las funciones y solucionar problemas. |

## Materiales

- ESP32-S3 N16R8 (con PSRAM)
- Cámara OV5640
- Micrófono I2S INMP441
- Amplificador I2S MAX98357A + parlante (4–8 Ω)
- 2 pulsadores
- Batería LiPo 3,7 V + cargador + step-up a 5 V con interruptor
- Un celular Android 8 o superior, con datos móviles (o un WiFi con internet)

## Conexiones

| Módulo | Señal | GPIO ESP32-S3 |
|---|---|---|
| OV5640 | XCLK / SIOD / SIOC | 15 / 4 / 5 |
| OV5640 | D9 D8 D7 D6 D5 D4 D3 D2 | 16 17 18 12 10 8 9 11 |
| OV5640 | VSYNC / HREF / PCLK | 6 / 7 / 13 |
| MAX98357A | BCLK / LRC / DIN | 38 / 39 / 21 |
| INMP441 | SCK / WS / SD | 42 / 41 / 45 |
| Botones | VOZ / FOTO | 1 / 47 |

El MAX98357A conviene alimentarlo con 5 V. El INMP441 va a 3,3 V con L/R a GND.

## Puesta en marcha (resumen)

La guía completa está en [`docs/GUIA.md`](docs/GUIA.md).

1. **Clave de Gemini.** Crear una gratis en [Google AI Studio](https://aistudio.google.com/apikey).
2. **App.** Abrir `app-android/` en Android Studio, usar **JDK 17 o 21** (Settings → Build Tools → Gradle → Gradle JDK; el JDK 25 no funciona con Gradle 8.14), conectar el celular con depuración USB y tocar ▶. En la app, pegar la clave y tocar **Guardar clave**.
3. **Permisos.** En la app, dar los permisos de **Llamadas** y **Ubicación** (si se quieren esas funciones).
4. **Hotspot del celular** en **2.4 GHz** (el ESP32 no ve 5 GHz).
5. **Firmware.** Abrir `firmware/lentes_inteligentes_v15/lentes_inteligentes_v15.ino` en Arduino IDE, completar `WIFI_1_SSID` y `WIFI_1_PASS` con los datos del hotspot y subirlo. Placa *ESP32S3 Dev Module*, PSRAM *OPI PSRAM*, Flash 16 MB. Para el modo conversación hace falta la librería **WebSockets** de Markus Sattler.
6. Encender el dispositivo: suena un saludo y ya se pueden usar los botones.

Si el celular y el ESP32 están en un WiFi común (casa, escuela) en vez del hotspot, poner en `SERVIDOR_IP_RESPALDO` la IP que muestra la app en la pantalla Inicio.

### Servidor en la PC (opcional)

```bash
cd servidor-pc
pip install -r requirements.txt
cp .env.ejemplo .env      # y poner la clave adentro
python servidor.py
```

Necesita además `ffmpeg` instalado. En Windows se puede usar `iniciar_servidor.bat`. En el firmware, poner la IP de la PC en `SERVIDOR_IP_RESPALDO`.

## Seguridad

- **Nunca subas tu clave de Gemini** ni la contraseña de tu WiFi. La app guarda la clave cifrada en el celular, y el servidor de PC la lee de un archivo `.env` que está excluido en `.gitignore`.
- En el firmware, `WIFI_1_SSID` y `WIFI_1_PASS` vienen con marcadores. **Antes de cada commit revisá** que sigan siendo marcadores y no tus datos reales.
- Si alguna vez se sube una clave por error, hay que **revocarla** en Google AI Studio y crear otra: borrarla del repositorio no alcanza, porque queda en el historial.

## Licencia

[MIT](LICENSE).
