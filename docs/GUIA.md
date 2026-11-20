# Guía: app del celular + ESP32

El celular reemplaza a la PC: la app es el servidor (Gemini + voz) y el ESP32 se conecta al hotspot del celular. Archivos: la carpeta `app-android` (proyecto de Android Studio) y `firmware/lentes_inteligentes_v15` (botón de voz en GPIO 1, amplificador BCLK 38 / LRC 39 / DIN 21).

## Parte 1: compilar e instalar la app (una sola vez)

1. Instalar Android Studio desde developer.android.com/studio con las opciones por defecto.
2. Copiar la carpeta `app-android` a una ruta corta, por ejemplo `C:\Proyectos\app-android`.
3. Android Studio → Open → elegir la carpeta `app-android` (la que tiene `settings.gradle.kts`). Esperar a que termine el "Gradle sync" (la primera vez baja bastante, 5 a 15 minutos).
   - Si ofrece actualizar el Android Gradle Plugin: NO aceptar.
   - Si dice que falta el SDK 35: tocar el enlace "Install missing SDK platform".
4. En el celular: Ajustes → Acerca del teléfono → Información de software → tocar 7 veces "Número de compilación". Después: Opciones de desarrollador → activar "Depuración USB".
5. Conectar el celular por USB y aceptar "Permitir depuración USB".
6. En Android Studio, arriba aparece el celular. Tocar ▶ (Run). La app se instala y se abre.

Sin cable / para otras personas: Build → Build App Bundle(s) / APK(s) → Build APK(s). El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`. Se pasa al celular y se instala (pide permitir "instalar apps desconocidas"). Cada persona tiene que cargar su propia clave de Gemini.

## Parte 2: configurar la app

1. Pegar la clave de Gemini y tocar Guardar. (Se crea gratis en https://aistudio.google.com/apikey. Es personal: no la compartas ni la subas a ningún lado.)
2. Tocar "Permitir que funcione con la pantalla apagada" → Permitir.
3. Samsung, además: Ajustes → Batería → Límites de uso en segundo plano → Aplicaciones que nunca se suspenden → agregar "Traductor de entorno". Sin esto, Samsung puede cerrar la app con la pantalla apagada.
4. Elegir Motor de voz y Voz (preferir las que dicen "sin internet") y probar con "Escuchar voz". Guardar.
5. Tocar Iniciar y aceptar las notificaciones. En la tarjeta Conexión tiene que decir "Gemini: OK".

## Parte 3: hotspot del celular

Ajustes → Conexiones → Zona Wi-Fi y Anclaje a red → Zona Wi-Fi → Configurar:

- Nombre de red: conviene uno simple, sin espacios (por ejemplo `Lentes`). Anotarlo exacto.
- Contraseña: anotarla exacta.
- Banda: **2.4 GHz**. Es obligatorio: el ESP32 no ve redes de 5 GHz.
- Seguridad: WPA2-Personal (o WPA2/WPA3). Evitar "solo WPA3".
- Si hay opción de apagar la zona Wi-Fi cuando no se usa: desactivarla.
- Datos móviles prendidos.

## Parte 4: firmware

1. Abrir `firmware/lentes_inteligentes_v15/lentes_inteligentes_v15.ino` en Arduino IDE (la carpeta y el archivo tienen que llamarse igual).
2. Placa: *ESP32S3 Dev Module*. PSRAM: *OPI PSRAM*. Flash Size: 16 MB (con un esquema de particiones de 16 MB). Si al compilar dice que el programa es muy grande, elegir *Huge APP*.
3. Instalar la biblioteca **WebSockets** de Markus Sattler (Herramientas → Administrar bibliotecas). La usa el modo conversación.
4. Reemplazar los marcadores `WIFI_1_SSID` (`NOMBRE_DE_TU_HOTSPOT`) y `WIFI_1_PASS` (`CLAVE_DE_TU_HOTSPOT`) por los datos del hotspot. `WIFI_2_*` es opcional (WiFi de casa o del colegio). **No subas esos datos reales a GitHub.**
5. `SERVIDOR_IP_RESPALDO`: dejar `""`, o poner la IP de la PC para usar `servidor.py` cuando el celular no esté.
6. `SERVIDOR_PORT` tiene que ser igual al puerto de la app (8000 por defecto).
7. Subir y abrir el Monitor Serie a 115200.

No hace falta escribir la IP del celular: el ESP32 la encuentra solo (hotspot → mDNS → respaldo).

## Parte 5: primera prueba

1. Celular: hotspot prendido, app abierta, Iniciar.
2. Encender el ESP32. En el monitor serie tiene que aparecer:
   - `[WIFI] Conectado a "Lentes" ... puerta de enlace: X.X.X.1`
   - `[SERVIDOR] X.X.X.1:8000 -> SI`
   - `[SERVIDOR] Encontrado en X.X.X.1:8000 (hotspot del celular)`
   - y suena el saludo.
3. En la app, tarjeta Conexión: "ESP32: (ip) (última petición hace N s)".
4. Toque corto en el botón de foto. La foto aparece en la app. Ajustar "Girar la foto" si queda acostada o cabeza abajo. Guardar y sacar otra.
5. Probar el botón de voz y la pulsación larga (foto + pregunta).

## Memoria de la conversación

La app guarda **toda** la conversación (preguntas, respuestas y fotos) en la memoria del celular, desde que se abre hasta que se cierra la app o se reinicia el dispositivo. Los dos modos (clásico y conversación) comparten la misma memoria.

- Cada foto queda numerada (FOTO 1, FOTO 2...) con una descripción corta. A Gemini le llega todo el texto y la lista de fotos; si una pregunta se refiere a una foto vieja ("¿qué decía el cartel de hace un rato?"), Gemini pide esa foto puntual (`ver_foto`) y la vuelve a mirar. Esa respuesta tarda un poco más.
- La última foto sigue yendo sola junto al botón de voz durante los 2 mensajes siguientes ("¿y de qué color es?").
- Para borrar: decí "olvidá todo", o tocá "Olvidar charla" en la app. El interruptor "Recordar toda la conversación" la apaga.

## Comandos por voz

Con el botón de voz (o hablando en el modo conversación) se pueden cambiar los ajustes sin tocar el celular. Gemini entiende el pedido, la app lo aplica, lo guarda y lo confirma en voz alta. La pantalla de Ajustes se actualiza sola.

| Decí algo como | Qué cambia |
|---|---|
| "subí / bajá el volumen", "volumen al 70" | volumen del parlante (50 a 95 %) |
| "hablá más rápido / más despacio", "velocidad normal" | velocidad de la voz (0,7 a 1,7) |
| "voz más aguda / más grave" | tono de la voz |
| "pasá al modo conversación", "volvé al modo clásico" | modo |
| "manos libres", "con el botón" | micrófono en el modo conversación |
| "girá la foto", "no mejores las fotos" | foto |
| "cambiá la voz a Puck" | voz del modo conversación |
| "olvidá todo" | borra la conversación |
| "¿cómo está el volumen?" | te dice los ajustes actuales |

La clave, el puerto y el modelo no se pueden cambiar por voz, a propósito. En el modo conversación, los cambios de modo, micrófono o voz se aplican después de la confirmación (1 o 2 segundos, la charla se conserva). La velocidad y el tono de la voz se notan en el modo clásico; en el modo conversación habla la voz de Gemini.

- Para actualizar la app: reemplazá los archivos del proyecto con los nuevos y tocá ▶ con el celular conectado. Conserva la clave y los ajustes.

## Llamadas por voz

**Permisos (una vez):** Ajustes → tarjeta **Llamadas** → **Permitir llamadas** y aceptá todo: contactos, hacer llamadas, estado del teléfono, atender y cortar, y registro de llamadas (sin ese último la app no sabe quién llama y solo dice "te están llamando"). Si alguno queda bloqueado, la app abre sus ajustes: activalo a mano. Si aparece en gris, tocá los **3 puntos** arriba a la derecha → **Permitir ajustes restringidos** y volvé a intentar.

**Llamar a alguien**
1. Botón de voz: "llamá a mamá".
2. La app busca en los contactos. Entiende apodos de familia (mamá, mami, abuela, nona…) y errores chicos ("Yoana" encuentra a "Johana"). Si hay varios parecidos, o el contacto tiene varios números, te pregunta cuál ("uno, celular; dos, teléfono de casa").
3. Siempre confirma: "¿Llamo a Mamá, al celular? Decime sí o no." Contestá con el botón de voz.
4. El celular llama y pone el altavoz (salvo que haya auriculares conectados).

La llamada **solo sale si contestaste que sí en un mensaje aparte**: lo controla la app, no la IA.

**Cuando te llaman**
- Modo clásico: el **celular** dice "Te llama Juan, ¿atendés o rechazás?" (suena aunque esté en silencio). Modo conversación: lo dice el **parlante del dispositivo**. El aviso se repite una vez.
- Botón de voz y decí "atendé" o "rechazá".
- Atajo: **un toque del botón de la cámara mientras suena, rechaza al instante** (sin internet).
- Durante la llamada: "cortá" o "colgá".

**Límites:** la charla telefónica va por el celular (altavoz, auricular o Bluetooth), no por el micrófono y el parlante del dispositivo: Android no deja que una app común use el audio de una llamada. Si el altavoz no se activa solo, en el Registro aparece "altavoz pedido (el celular no confirmó)": avisá. El interruptor **Llamadas por voz** apaga todo esto.

## Ubicación por voz

> Esta función está en etapa de pruebas.

**Permiso (una vez):** Ajustes → tarjeta **Ubicación** → **Permitir ubicación** → "Mientras se usa la app", con **ubicación precisa** activada (con la aproximada no se puede saber la calle). La ubicación del celular tiene que estar prendida. Iniciá el servidor con la app abierta: así el GPS sigue andando con la pantalla apagada.

| Decí algo como | Qué hace |
|---|---|
| "¿dónde estoy?", "¿en qué esquina estoy?" | calle, altura, entre qué calles (o "en la esquina con…") y barrio |
| "¿dónde hay una farmacia cerca?", "¿cuál es la escuela más cercana?" | hasta 3 lugares de Google Maps, con dirección y distancia real, del más cercano al más lejano |
| "¿cómo llego a la primera?", "¿cómo llego a Rivadavia 5000?", "¿cómo llego a la farmacia más cercana?" | distancia, minutos caminando y por qué calles ir |

- **De dónde salen los datos:** la dirección, del servicio de direcciones del celular; las esquinas, de OpenStreetMap; los lugares, de Google Maps a través de Gemini (misma clave, con cupo gratis por día); el camino a pie, del servidor de rutas de OpenStreetMap. La ubicación solo sale del celular cuando preguntás.
- **No es navegación:** dice el camino una vez. Como no sabe para dónde estás mirando, la primera indicación es por calles ("salí por Rivadavia hacia Acoyte"), no "a tu izquierda". Si el camino tiene muchos giros, dice los primeros seis y te pide que vuelvas a preguntar más adelante.
- **Precisión:** en la calle el GPS erra unos 5 a 20 m. Adentro puede ser bastante más: si el margen pasa de 40 m, lo avisa.
- Tarda unos segundos más que una pregunta normal (GPS + mapas). El interruptor **Ubicación por voz** apaga todo esto.

## Modo conversación (Gemini Live) — experimental

En vez de "pregunta → espera → respuesta", la app abre una conversación continua con Gemini Live: el audio va y vuelve en streaming, la respuesta empieza a sonar mientras se genera, Gemini habla con su propia voz, se lo puede interrumpir y recuerda toda la conversación.

**Qué hace falta**
- App actualizada (incluye la librería OkHttp: el primer sync descarga unos MB más).
- Firmware con la biblioteca **WebSockets** de Markus Sattler instalada (Arduino IDE → Herramientas → Administrar bibliotecas).
- Si al compilar dice que el programa es muy grande: Herramientas → Partition Scheme → **Huge APP** (o una de 3 MB de APP).

**Cómo se activa**
- En la app: Ajustes → **Modo conversación** → activar, elegir micrófono y voz → Guardar.
- El dispositivo lo detecta solo en unos 10 segundos (no hay que volver a subir el código). Para volver al modo clásico, se apaga el interruptor.

**Botones en modo conversación**

| Micrófono | Botón | Acción |
|---|---|---|
| Con el botón | VOZ mantener | hablás; al soltar responde. Si Gemini estaba hablando, se calla |
| Con el botón | FOTO toque | describe lo que tenés enfrente |
| Con el botón | FOTO mantener | foto + tu pregunta sobre ella |
| Abierto | (sin tocar nada) | hablás normalmente; Gemini detecta cuándo terminaste |
| Abierto | VOZ toque | callar a Gemini |
| Abierto | VOZ mantener 1 s | pausar / reanudar el micrófono (dos tonos) |
| Abierto | FOTO toque / mantener | describe la foto / manda la foto y preguntás hablando |

**Ajustes finos (en el firmware)**
- `MIC_GANANCIA_LIVE` (4): subir si Gemini no te entiende o dice que no escucha.
- `UMBRAL_VOZ` (700): solo micrófono abierto. Bajar si no detecta tu voz; subir si se activa con ruido.
- `MS_FIN_VOZ` (900): silencio que da por terminada tu frase en micrófono abierto.

**Para tener en cuenta**
- **Costo:** Live cobra por segundo de audio enviado y recibido. En micrófono abierto, el ESP32 solo manda audio cuando detecta voz, para no gastar en silencio.
- **Eco:** con micrófono abierto, el micrófono se silencia mientras suena el parlante (si no, Gemini se escucharía a sí mismo). Por eso, para interrumpir en ese modo se usa el botón VOZ.
- La conexión con Gemini se renueva sola cada ~10 minutos sin perder la conversación.

## Pitidos

- Dos tonos graves: no hay WiFi o no encuentra el servidor (la app no está iniciada).
- Un tono agudo corto: se recuperó la conexión.

## Servidor en la PC (alternativa)

Si no hay celular, se puede usar `servidor-pc/servidor.py` (Python 3, `pip install -r requirements.txt`, `ffmpeg` instalado). La clave va en un archivo `.env` (copiar `.env.ejemplo` y completarlo): **ese archivo no se sube a GitHub**. En el firmware, poner la IP de la PC en `SERVIDOR_IP_RESPALDO`. En Windows, `iniciar_servidor.bat` hace los pasos de arranque.

## Si algo falla

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| El ESP32 imprime puntitos y no conecta | Hotspot en 5 GHz, o nombre/clave distintos | Banda 2.4 GHz; copiar nombre y clave exactos (mayúsculas y espacios cuentan) |
| Conecta pero dice `No lo encontre` | La app no está iniciada, o el puerto no coincide | Iniciar en la app; mismo puerto en app y firmware |
| Anda un rato y después dos pitidos | Samsung durmió la app | Parte 2, pasos 2 y 3 |
| La app dice "Gemini: PROBLEMA" | Clave inválida o sin datos móviles | Cargar la clave de nuevo; revisar datos |
| La voz suena rara o en otro idioma | Voz que necesita internet o no es español | Elegir otra voz "sin internet" |
| Android Studio: `Unsupported class file major version` | El JDK es demasiado nuevo para Gradle | Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → elegir 17 o 21 |
| Error al compilar | Algo que no se pudo probar sin el SDK real | Copiar el error completo y abrir un issue o mandarlo |

El botón "Copiar" del registro de la app copia todo lo que pasó, para mandarlo si hay un problema.
