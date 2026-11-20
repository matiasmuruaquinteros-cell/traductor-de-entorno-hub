"""
=====================================================================
SERVIDOR DE LENTES INTELIGENTES  -  v9  (dos botones)

  BOTON VOZ   -> /upload_audio con X-Modo: voz   (pregunta sin foto)
  BOTON FOTO  -> corto: /upload_imagen          (describe la foto)
                 largo: /upload_contexto + /upload_audio con X-Modo: foto
                        (pregunta sobre ESA foto)

  Novedades v9:
  - Tiempos de Gemini acotados (25 s por intento, 2 intentos) para responder
    siempre antes de que el ESP32 corte a los 60 s (error -11).
  - Pensamiento minimo de Gemini (mas rapido); si el modelo no lo acepta,
    se desactiva solo.
  - Aviso en consola cuando una respuesta tarda demasiado.

  Novedades v8:
  - Cada boton tiene su propio prompt: ya no hay que adivinar si la
    pregunta es sobre la foto o no; lo dice el boton que se uso.
  - Mejora de la foto antes de mandarla a Gemini (contraste, sombras,
    color y nitidez). Requiere Pillow; si no esta, se manda tal cual.
  - /ultima_foto muestra la foto mejorada (lo que ve Gemini) y
    /ultima_foto_original la que mando la camara.

  Sigue de v7: emparejado por sesion, PREGUNTA/RESPUESTA, normalizacion
  del microfono, reintentos ante 429/503, audio sin cortes ni saturacion.
=====================================================================
"""

import asyncio
import base64
import io
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from array import array
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, Request, Response
import uvicorn
from gtts import gTTS

try:
    from PIL import Image, ImageEnhance, ImageOps
    HAY_PILLOW = True
except ImportError:
    HAY_PILLOW = False

# =====================================================
# CONFIGURACION
# =====================================================
# Pega aca tu clave NUEVA, o mejor: crea un archivo .env junto a este script
# con la linea  GEMINI_API_KEY=tu_clave  (asi la clave no queda en el codigo).
GENAI_API_KEY_FIJA = ""

MODELO_PREFERIDO = "gemini-3.1-flash-lite"

# Tiempos de Gemini. El ESP32 espera como maximo 60 s; con 25 s x 2 intentos
# mas la voz, el servidor siempre contesta antes (aunque sea con el aviso de error).
TIMEOUT_GEMINI_S  = 25
REINTENTOS_GEMINI = 2
LIMITE_ESP32_S    = 60
# Pensamiento del modelo: "minimal" responde mas rapido. None = no enviar.
THINKING_LEVEL    = "minimal"

SAMPLE_RATE  = 16000
CANALES      = 1
BITS         = 16

# Volumen del parlante.
# Cada motor de voz entrega el audio a un nivel distinto: edge_tts venia suave y
# gTTS viene fuerte. Por eso NO se usa un multiplicador fijo (con gTTS, ganancia 4
# recortaba el 70% de las muestras y se escuchaba cortado). En su lugar se mide el
# pico real y se lleva siempre al mismo nivel: suena parejo con cualquier motor.
NORMALIZAR_SALIDA = True
PICO_OBJETIVO     = 0.90       # 0.0 a 1.0. Mas alto = mas fuerte. Arriba de 0.95 puede saturar.
GANANCIA          = 4.0        # solo se usa si NORMALIZAR_SALIDA = False
LIMITADOR         = True       # red de seguridad contra picos
TLD_TTS           = "com.mx"   # acento de gTTS para espanol

# Mejora de la foto antes de mandarla a Gemini (necesita: pip install pillow)
MEJORAR_FOTO    = True
ROTAR_FOTO      = 0            # 0 o 180. La camara ya gira la imagen (CAM_VOLTEAR/CAM_ESPEJO);
                               # usar 180 aca solo si sigue llegando al reves.
FOTO_GAMMA      = 0.75         # < 1 aclara las sombras (caras a contraluz). 1.0 = sin cambio
FOTO_CORTE_ALTO = 3            # % de luces que se dejan quemar para estirar el resto
FOTO_COLOR      = 1.25         # 1.0 = sin cambio
FOTO_NITIDEZ    = 1.5          # 1.0 = sin cambio

NORMALIZAR_MIC = True          # normaliza el audio del microfono antes de mandarlo
ESPERA_FOTO_S  = 3.0           # cuanto espera el audio a que llegue la foto de su sesion
VIDA_SESION_S  = 60
FOTO_VIEJA_S   = 30            # solo para firmware sin X-Sesion

FFMPEG_PATH = None
PUERTO = 8000
API_BASE = "https://generativelanguage.googleapis.com/v1beta"

REGLAS_HABLA = (
    "Tu respuesta sera leida en voz alta por un parlante. "
    "Responde de forma breve, concisa y natural, en un solo parrafo. "
    "PROHIBIDO usar asteriscos, guiones, listas, comillas o cualquier simbolo de formato. "
    "Los numeros escribilos completos en palabras. "
    "Maximo tres oraciones. No saludes ni te presentes."
)

INSTRUCCIONES_IMAGEN = (
    "Eres el asistente de unos lentes inteligentes para una persona ciega. "
    "Describe lo que ves de manera que sirva de guia para saber que objeto tiene delante "
    "y como manipularlo. Si la imagen esta muy oscura, borrosa o no se distingue bien, "
    "dilo en una oracion y sugiere acercar o alejar el objeto o buscar mas luz. "
    + REGLAS_HABLA
)

INSTRUCCIONES_VOZ = (
    "Eres el asistente de voz de unos lentes inteligentes para una persona ciega. "
    "Recibes un audio con una pregunta o pedido del usuario. "
    "En esta consulta NO hay imagen: el usuario uso el boton de voz. "
    "Paso uno: escucha el audio y transcribe la pregunta tal cual. "
    "Paso dos: responde con tu conocimiento general. "
    "Si te pregunta por algo que tiene enfrente, explicale que para eso mantenga apretado "
    "el boton de la foto mientras hace la pregunta. "
    "Si el audio no se entiende, pide amablemente que repita la pregunta. "
    "Formato de salida OBLIGATORIO, en una sola linea: "
    "PREGUNTA: la transcripcion RESPUESTA: tu respuesta. "
    + REGLAS_HABLA
)

INSTRUCCIONES_FOTO_VOZ = (
    "Eres el asistente de unos lentes inteligentes para una persona ciega. "
    "El usuario acaba de sacar una foto de lo que tiene enfrente y te hace una pregunta "
    "por voz SOBRE ESA FOTO. "
    "Paso uno: escucha el audio y transcribe la pregunta tal cual. "
    "Paso dos: responde exactamente lo que pregunta mirando la foto. "
    "No describas toda la imagen salvo que te lo pida. "
    "Si la foto esta oscura, borrosa o no se ve lo que pregunta, dilo en una oracion y sugiere "
    "como mejorarla: mas luz, acercar o alejar el objeto. "
    "Si no recibiste ninguna foto, dile que la foto no llego y que lo intente de nuevo. "
    "Si el audio no se entiende, pide amablemente que repita la pregunta. "
    "Formato de salida OBLIGATORIO, en una sola linea: "
    "PREGUNTA: la transcripcion RESPUESTA: tu respuesta. "
    + REGLAS_HABLA
)

FRASE_ERROR = "No pude procesar eso. Intenta de nuevo."
SALUDO_FIJO = (
    "Hola, soy tu asistente de lentes inteligentes y ya estoy listo para ayudarte. "
    "Presiona el boton rapidamente para describir lo que tenes enfrente, o mantenelo apretado para hablarme."
)


def _leer_env(nombre: str) -> str:
    valor = os.environ.get(nombre, "").strip()
    if valor:
        return valor
    ruta = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    try:
        with open(ruta, encoding="utf-8") as f:
            for linea in f:
                linea = linea.strip()
                if not linea or linea.startswith("#") or "=" not in linea:
                    continue
                k, v = linea.split("=", 1)
                if k.strip() == nombre:
                    return v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return ""


GENAI_API_KEY = _leer_env("GEMINI_API_KEY") or GENAI_API_KEY_FIJA

# =====================================================
# ESTADO GLOBAL
# =====================================================
MODELO_ACTIVO = MODELO_PREFERIDO
FFMPEG_BIN    = None
THINKING_OK   = True

ultima_foto          = None    # la que ve Gemini (mejorada)
ultima_foto_original = None    # la que mando la camara
ultima_foto_t        = 0.0
ultimo_texto         = ""
ultima_pregunta      = ""
ultima_hora          = ""
contador_fotos       = 0
contador_audios      = 0
wav_bienvenida_cache = None

app = FastAPI(title="Lentes Inteligentes")


class Sesion:
    """Une la foto y el audio de una misma pulsacion del boton."""
    def __init__(self):
        self.foto   = None
        self.evento = asyncio.Event()
        self.t      = time.time()


SESIONES: dict = {}


def obtener_sesion(sid: str) -> Sesion:
    ahora = time.time()
    for k in [k for k, s in SESIONES.items() if ahora - s.t > VIDA_SESION_S]:
        del SESIONES[k]
    s = SESIONES.get(sid)
    if s is None:
        s = SESIONES[sid] = Sesion()
    return s


# =====================================================
# GEMINI  -  llamada REST directa con REINTENTOS
# =====================================================
def _http_json(url: str, payload=None, timeout: int = 90, max_reintentos: int = 4) -> dict:
    datos = json.dumps(payload).encode("utf-8") if payload is not None else None

    for intento in range(max_reintentos):
        req = urllib.request.Request(
            url,
            data=datos,
            method="POST" if payload is not None else "GET",
            headers={
                "Content-Type": "application/json",
                "x-goog-api-key": GENAI_API_KEY,
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            # Alta demanda (429) o servicio no disponible (500/503): reintento con espera progresiva
            if e.code in [429, 500, 503] and intento < max_reintentos - 1:
                espera = 2 ** intento
                print(f"   [API] Alta demanda en Google (Error {e.code}). Reintentando en {espera}s... ({intento+1}/{max_reintentos})")
                time.sleep(espera)
                continue
            detalle = e.read().decode("utf-8", errors="replace")[:600]
            raise RuntimeError(f"HTTP {e.code} de Gemini: {detalle}") from None
        except Exception as e:
            if intento < max_reintentos - 1:
                print(f"   [API] Error de red local ({e}). Reintentando en 2s... ({intento+1}/{max_reintentos})")
                time.sleep(2)
                continue
            raise RuntimeError(f"Fallo critico de conexion: {e}") from None


def listar_modelos() -> list:
    data = _http_json(f"{API_BASE}/models?pageSize=1000", timeout=20)
    nombres = []
    for m in data.get("models", []):
        if "generateContent" in m.get("supportedGenerationMethods", []):
            nombres.append(m["name"].replace("models/", ""))
    return nombres


def generar(payload: dict, timeout: int = None) -> dict:
    """generateContent con pensamiento minimo (si el modelo lo acepta) y tiempos acotados."""
    global THINKING_OK
    url = f"{API_BASE}/models/{MODELO_ACTIVO}:generateContent"
    t = timeout or TIMEOUT_GEMINI_S
    if THINKING_LEVEL and THINKING_OK:
        con = dict(payload)
        con["generationConfig"] = dict(payload.get("generationConfig", {}),
                                       thinkingConfig={"thinkingLevel": THINKING_LEVEL})
        try:
            return _http_json(url, con, timeout=t, max_reintentos=REINTENTOS_GEMINI)
        except RuntimeError as e:
            msg = str(e)
            if msg.startswith("HTTP 400") and "thinking" in msg.lower():
                print("   [AVISO] Este modelo no acepta thinkingLevel. Sigo sin esa opcion.")
                THINKING_OK = False
            else:
                raise
    return _http_json(url, payload, timeout=t, max_reintentos=REINTENTOS_GEMINI)


def _extraer_texto_crudo(data: dict) -> str:
    candidatos = data.get("candidates", [])
    if not candidatos:
        motivo = data.get("promptFeedback", {}).get("blockReason", "sin candidatos")
        raise RuntimeError(f"Gemini no devolvio respuesta ({motivo})")
    partes = candidatos[0].get("content", {}).get("parts", [])
    texto = " ".join(p.get("text", "") for p in partes if not p.get("thought")).strip()
    if not texto:
        raise RuntimeError("Gemini devolvio respuesta vacia")
    return texto


def _extraer_texto(data: dict) -> str:
    return limpiar_texto(_extraer_texto_crudo(data))


def separar_pregunta(texto: str):
    """'PREGUNTA: ... RESPUESTA: ...' -> (pregunta, respuesta).
    Si el modelo no respeta el formato, se lee todo el texto."""
    t = texto.strip()
    sup = t.upper()
    i_r = sup.find("RESPUESTA:")
    if i_r < 0:
        return "", t
    i_p = sup.find("PREGUNTA:")
    pregunta = t[i_p + len("PREGUNTA:"):i_r].strip() if 0 <= i_p < i_r else ""
    respuesta = t[i_r + len("RESPUESTA:"):].strip()
    return pregunta, respuesta


def generar_saludo_ia() -> str:
    payload = {
        "contents": [{
            "role": "user",
            "parts": [{
                "text": (
                    "Eres el asistente de voz de unos lentes inteligentes para personas con discapacidad visual. "
                    "Acabas de encenderte. Presentate brevemente en dos oraciones calidas y naturales, "
                    "indicando que con un toque corto describes lo que hay enfrente y manteniendo presionado "
                    "escuchas preguntas por voz. Sin asteriscos ni simbolos."
                )
            }],
        }],
        "generationConfig": {"temperature": 0.5, "maxOutputTokens": 120},
    }
    data = generar(payload, timeout=15)
    return _extraer_texto(data)


def describir_imagen(jpeg_bytes: bytes) -> str:
    payload = {
        "systemInstruction": {"parts": [{"text": INSTRUCCIONES_IMAGEN}]},
        "contents": [{
            "role": "user",
            "parts": [
                {"inlineData": {
                    "mimeType": "image/jpeg",
                    "data": base64.b64encode(jpeg_bytes).decode("ascii"),
                }},
                {"text": "Que tengo frente a mi?"},
            ],
        }],
        "generationConfig": {"temperature": 0.4, "maxOutputTokens": 200},
    }
    data = generar(payload)
    return _extraer_texto(data)


def responder_audio(wav_bytes: bytes, foto_bytes, modo: str):
    """modo 'voz': pregunta general, sin foto.
    modo 'foto': pregunta sobre la foto que se acaba de sacar.
    Devuelve (pregunta_transcripta, respuesta)."""
    if modo == "foto":
        instrucciones = INSTRUCCIONES_FOTO_VOZ
        partes = [
            {"text": "Pregunta del usuario sobre la foto:"},
            {"inlineData": {"mimeType": "audio/wav",
                            "data": base64.b64encode(wav_bytes).decode("ascii")}},
        ]
        if foto_bytes:
            partes.append({"text": "Foto que acaba de sacar:"})
            partes.append({"inlineData": {"mimeType": "image/jpeg",
                                          "data": base64.b64encode(foto_bytes).decode("ascii")}})
        else:
            partes.append({"text": "La foto no llego en esta consulta."})
    else:
        instrucciones = INSTRUCCIONES_VOZ
        partes = [
            {"text": "Audio con la pregunta del usuario:"},
            {"inlineData": {"mimeType": "audio/wav",
                            "data": base64.b64encode(wav_bytes).decode("ascii")}},
        ]

    payload = {
        "systemInstruction": {"parts": [{"text": instrucciones}]},
        "contents": [{"role": "user", "parts": partes}],
        "generationConfig": {"temperature": 0.3, "maxOutputTokens": 300},
    }
    data = generar(payload)
    pregunta, respuesta = separar_pregunta(_extraer_texto_crudo(data))
    return limpiar_texto(pregunta), limpiar_texto(respuesta)


# =====================================================
# MEJORA DE LA FOTO
# =====================================================
def _gamma(img, g: float):
    lut = [round(255 * ((i / 255) ** g)) for i in range(256)]
    return img.point(lut * len(img.getbands()))


def mejorar_foto(jpeg: bytes) -> bytes:
    """Contraste, sombras, color y nitidez. Si algo falla, devuelve la original."""
    if not (MEJORAR_FOTO and HAY_PILLOW):
        return jpeg
    try:
        img = Image.open(io.BytesIO(jpeg)).convert("RGB")
        if ROTAR_FOTO:
            img = img.rotate(ROTAR_FOTO, expand=True)
        img = ImageOps.autocontrast(img, cutoff=(0.5, FOTO_CORTE_ALTO))
        if FOTO_GAMMA != 1.0:
            img = _gamma(img, FOTO_GAMMA)
        if FOTO_COLOR != 1.0:
            img = ImageEnhance.Color(img).enhance(FOTO_COLOR)
        if FOTO_NITIDEZ != 1.0:
            img = ImageEnhance.Sharpness(img).enhance(FOTO_NITIDEZ)
        salida = io.BytesIO()
        img.save(salida, format="JPEG", quality=90)
        return salida.getvalue()
    except Exception as e:
        print(f"   [FOTO] no se pudo mejorar, mando la original ({e})")
        return jpeg


async def preparar_foto(cuerpo: bytes) -> bytes:
    """Mejora la foto y la guarda para la pagina de diagnostico."""
    global ultima_foto, ultima_foto_original, ultima_foto_t
    t0 = time.time()
    foto = await asyncio.to_thread(mejorar_foto, cuerpo)
    ultima_foto_original, ultima_foto, ultima_foto_t = cuerpo, foto, time.time()
    if foto is not cuerpo:
        print(f"   [FOTO] mejorada en {(time.time()-t0)*1000:.0f} ms")
    return foto


def limpiar_texto(t: str) -> str:
    for ch in ["*", "#", "_", "`", "•", "—", "–"]:
        t = t.replace(ch, " ")
    t = t.replace("\n", " ").replace("\r", " ")
    while "  " in t:
        t = t.replace("  ", " ")
    return t.strip()


# =====================================================
# AUDIO DEL MICROFONO
# =====================================================
def nivel_audio(wav_bytes: bytes):
    """Pico y RMS del PCM (en % de la escala completa). Asume WAV de 44 bytes."""
    pcm = wav_bytes[44:]
    if len(pcm) < 4:
        return 0.0, 0.0
    if len(pcm) % 2:
        pcm = pcm[:-1]
    a = array("h", pcm)
    if sys.byteorder != "little":
        a.byteswap()
    pico = max(max(a), -min(a)) / 32768 * 100
    paso = max(1, len(a) // 8000)            # muestreo para que sea rapido
    sub = a[::paso]
    rms = (sum(x * x for x in sub) / len(sub)) ** 0.5 / 32768 * 100
    return pico, rms


def normalizar_mic(wav_bytes: bytes) -> bytes:
    """Saca el ruido grave/DC y lleva la voz a un volumen comodo para Gemini."""
    cmd = [
        FFMPEG_BIN, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "wav", "-i", "pipe:0",
        "-af", "highpass=f=80,loudnorm=I=-18:TP=-2:LRA=11",
        "-ar", str(SAMPLE_RATE), "-ac", "1", "-acodec", "pcm_s16le",
        "-f", "wav", "pipe:1",
    ]
    proc = subprocess.run(cmd, input=wav_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0 or len(proc.stdout) <= 44:
        raise RuntimeError(proc.stderr.decode("utf-8", errors="replace")[:300])
    return proc.stdout


# =====================================================
# TEXTO -> WAV PCM  (gTTS + ffmpeg)
# =====================================================
def mp3_a_pcm(mp3_bytes: bytes) -> bytes:
    """Decodifica UN fragmento mp3 a PCM s16le, sin tocarle el volumen."""
    cmd = [
        FFMPEG_BIN,
        "-hide_banner", "-loglevel", "error", "-nostdin",
        "-i", "pipe:0",
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-ar", str(SAMPLE_RATE),
        "-ac", str(CANALES),
        "pipe:1",
    ]
    proc = subprocess.run(cmd, input=mp3_bytes,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0 or not proc.stdout:
        err = proc.stderr.decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"ffmpeg fallo: {err}")
    return proc.stdout


def pico_pcm(pcm: bytes) -> float:
    """Pico de la senal, de 0.0 a 1.0."""
    if len(pcm) < 2:
        return 0.0
    a = array("h", pcm[:len(pcm) - (len(pcm) % 2)])
    if sys.byteorder != "little":
        a.byteswap()
    return max(max(a), -min(a)) / 32768


def ajustar_volumen(pcm: bytes) -> bytes:
    """Lleva el audio al nivel objetivo sin saturar."""
    if NORMALIZAR_SALIDA:
        pico = pico_pcm(pcm)
        if pico <= 0.001:
            return pcm
        factor = PICO_OBJETIVO / pico
    else:
        factor = GANANCIA

    filtros = f"volume={factor:.4f}"
    if LIMITADOR:
        filtros += ",alimiter=limit=0.95:level=disabled"
    cmd = [
        FFMPEG_BIN, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", str(CANALES), "-i", "pipe:0",
        "-af", filtros,
        "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1",
    ]
    proc = subprocess.run(cmd, input=pcm, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0 or not proc.stdout:
        print(f"   [TTS] no pude ajustar el volumen, mando el audio tal cual")
        return pcm
    return proc.stdout


def texto_a_pcm(texto: str) -> bytes:
    """gTTS parte el texto en varios pedazos (uno por coma y cada 100 caracteres),
    hace una peticion por cada uno y devuelve VARIOS mp3 independientes. Si se
    pegan y se decodifican de una sola vez, ffmpeg pierde el encabezado de cada
    pedazo ('Header missing') y mete silencios en las uniones. Por eso se
    decodifica cada pedazo por separado, en paralelo, y recien despues se unen."""
    tts = gTTS(text=texto, lang='es', tld=TLD_TTS)
    partes = [p for p in tts.stream() if p]
    if not partes:
        raise RuntimeError("gTTS no genero audio")

    if len(partes) == 1:
        crudo = mp3_a_pcm(partes[0])
    else:
        with ThreadPoolExecutor(max_workers=min(8, len(partes))) as ex:
            crudo = b"".join(ex.map(mp3_a_pcm, partes))

    pico = pico_pcm(crudo)
    pcm  = ajustar_volumen(crudo)
    modo = f"normalizado a {PICO_OBJETIVO:.0%}" if NORMALIZAR_SALIDA else f"ganancia fija {GANANCIA}"
    print(f"   [TTS] {len(partes)} fragmento(s) | pico original {pico:.0%} | {modo}")
    return pcm


def encabezado_wav(n_bytes_pcm: int) -> bytes:
    byte_rate   = SAMPLE_RATE * CANALES * BITS // 8
    block_align = CANALES * BITS // 8
    h = bytearray()
    h += b"RIFF"
    h += (36 + n_bytes_pcm).to_bytes(4, "little")
    h += b"WAVE"
    h += b"fmt "
    h += (16).to_bytes(4, "little")
    h += (1).to_bytes(2, "little")
    h += CANALES.to_bytes(2, "little")
    h += SAMPLE_RATE.to_bytes(4, "little")
    h += byte_rate.to_bytes(4, "little")
    h += block_align.to_bytes(2, "little")
    h += BITS.to_bytes(2, "little")
    h += b"data"
    h += n_bytes_pcm.to_bytes(4, "little")
    assert len(h) == 44
    return bytes(h)


async def texto_a_wav(texto: str) -> bytes:
    t0  = time.time()
    pcm = await asyncio.to_thread(texto_a_pcm, texto)
    wav = encabezado_wav(len(pcm)) + pcm
    dur = len(pcm) / (SAMPLE_RATE * CANALES * BITS / 8)
    print(f"   [TTS] {len(wav)} bytes | {dur:.1f} s | generado en {time.time()-t0:.1f} s")
    return wav


def avisar_tiempo(n_bytes: int, total: float):
    print(f"   [ENVIO] WAV {n_bytes} bytes. Total {total:.1f} s")
    if total > LIMITE_ESP32_S * 0.8:
        print(f"   [AVISO] Tardo {total:.0f} s; el ESP32 corta a los {LIMITE_ESP32_S} s (error -11). "
              f"Si se repite, Gemini esta saturado o la red esta lenta.")


def responder_wav(wav: bytes) -> Response:
    return Response(content=wav, media_type="audio/wav",
                    headers={"Content-Length": str(len(wav))})


async def wav_de_error() -> Response:
    """Si algo falla, el usuario escucha un aviso en vez de silencio."""
    try:
        return responder_wav(await texto_a_wav(FRASE_ERROR))
    except Exception as e:
        print(f"   [ERROR] tampoco se pudo generar el aviso de error: {e}")
        return Response(content=b"", status_code=500)


# =====================================================
# ENDPOINTS
# =====================================================
@app.get("/bienvenida")
async def bienvenida():
    global ultimo_texto, ultima_hora, wav_bienvenida_cache
    ultima_hora = time.strftime("%H:%M:%S")
    print(f"\n[{ultima_hora}] Solicitud de bienvenida recibida por encendido/reset del ESP32")

    if wav_bienvenida_cache:
        print("   [BIENVENIDA] Enviando saludo desde cache rapido.")
        return responder_wav(wav_bienvenida_cache)

    try:
        texto = await asyncio.to_thread(generar_saludo_ia)
    except Exception as e:
        print(f"   [AVISO] Gemini demoro en bienvenida ({e}), usando saludo integrado.")
        texto = SALUDO_FIJO

    ultimo_texto = texto
    print(f"   [BIENVENIDA] {texto}")

    try:
        wav = await texto_a_wav(texto)
        wav_bienvenida_cache = wav
        return responder_wav(wav)
    except Exception as e:
        print(f"   [ERROR bienvenida] {e}")
        return Response(content=b"", status_code=500)


@app.post("/upload_imagen")
async def upload_imagen(request: Request):
    global ultima_foto, ultima_foto_t, ultimo_texto, ultima_hora, contador_fotos

    t_total = time.time()
    cuerpo  = await request.body()

    if not cuerpo:
        print("[ERROR] POST /upload_imagen vacio")
        return Response(content=b"", status_code=400)

    contador_fotos += 1
    ultima_hora  = time.strftime("%H:%M:%S")
    print(f"\n[{ultima_hora}] FOTO #{contador_fotos} recibida (toque corto): {len(cuerpo)} bytes")
    foto = await preparar_foto(cuerpo)

    try:
        t0    = time.time()
        texto = await asyncio.to_thread(describir_imagen, foto)
        ultimo_texto = texto
        print(f"   [GEMINI imagen] ({time.time()-t0:.1f} s) {texto}")

        wav = await texto_a_wav(texto)
        avisar_tiempo(len(wav), time.time() - t_total)
        return responder_wav(wav)

    except Exception as e:
        print(f"   [ERROR imagen] {e}")
        return await wav_de_error()


@app.post("/upload_contexto")
async def upload_contexto(request: Request):
    global ultima_foto, ultima_foto_t, ultima_hora, contador_fotos
    cuerpo = await request.body()
    if not cuerpo:
        return Response(content=b"", status_code=400)

    contador_fotos += 1
    ultima_hora = time.strftime("%H:%M:%S")
    foto = await preparar_foto(cuerpo)

    sid = request.headers.get("x-sesion", "")
    if sid:
        s = obtener_sesion(sid)
        s.foto = foto
        s.evento.set()
    print(f"\n[{ultima_hora}] FOTO DE CONTEXTO guardada ({len(cuerpo)} bytes) sesion={sid or '-'}")
    return Response(content=b"ok", status_code=200)


@app.post("/upload_audio")
async def upload_audio(request: Request):
    global ultimo_texto, ultima_pregunta, ultima_hora, contador_audios

    t_total   = time.time()
    wav_bytes = await request.body()

    if not wav_bytes:
        print("[ERROR] POST /upload_audio vacio")
        return Response(content=b"", status_code=400)

    contador_audios += 1
    ultima_hora = time.strftime("%H:%M:%S")

    sid      = request.headers.get("x-sesion", "")
    hay_foto = request.headers.get("x-foto")       # "1", "0" o None
    modo     = (request.headers.get("x-modo") or "").strip().lower()

    # Que boton se uso. Firmware sin X-Modo: se deduce como antes.
    if modo not in ("voz", "foto"):
        if sid:
            modo = "foto" if hay_foto == "1" else "voz"
        else:
            modo = "foto" if (ultima_foto and time.time() - ultima_foto_t < FOTO_VIEJA_S) else "voz"

    # --- foto de ESTA pulsacion (nunca una vieja), solo en modo foto ---
    foto = None
    if modo == "foto":
        if sid:
            if hay_foto != "0":
                s = obtener_sesion(sid)
                if s.foto is None:
                    try:
                        await asyncio.wait_for(s.evento.wait(), ESPERA_FOTO_S)
                    except asyncio.TimeoutError:
                        print("   [AVISO] la foto de esta sesion no llego a tiempo")
                foto = s.foto
        elif ultima_foto and time.time() - ultima_foto_t < FOTO_VIEJA_S:
            foto = ultima_foto

    seg = max(0, len(wav_bytes) - 44) / (SAMPLE_RATE * 2)
    nombre = "BOTON FOTO (pregunta sobre la foto)" if modo == "foto" else "BOTON VOZ"
    print(f"\n[{ultima_hora}] AUDIO #{contador_audios} | {nombre} | {seg:.1f} s "
          f"| sesion={sid or '-'} | foto: {'SI' if foto else 'NO'}")

    # --- nivel y normalizacion del microfono ---
    try:
        pico, rms = nivel_audio(wav_bytes)
        aviso = "  <- MUY BAJO, revisar microfono" if pico < 3 else ""
        print(f"   [MIC] pico {pico:.0f}% | rms {rms:.1f}%{aviso}")
    except Exception as e:
        print(f"   [MIC] no pude medir el nivel: {e}")

    audio_gemini = wav_bytes
    if NORMALIZAR_MIC:
        try:
            audio_gemini = await asyncio.to_thread(normalizar_mic, wav_bytes)
        except Exception as e:
            print(f"   [MIC] no se pudo normalizar, mando el original ({e})")

    try:
        t0 = time.time()
        pregunta, texto = await asyncio.to_thread(responder_audio, audio_gemini, foto, modo)
        if not texto:
            raise RuntimeError("respuesta vacia")
        ultima_pregunta = pregunta
        ultimo_texto = texto
        print(f"   [PREGUNTA] {pregunta or '(el modelo no la transcribio)'}")
        print(f"   [GEMINI audio] ({time.time()-t0:.1f} s) {texto}")

        wav = await texto_a_wav(texto)
        avisar_tiempo(len(wav), time.time() - t_total)
        return responder_wav(wav)

    except Exception as e:
        print(f"   [ERROR audio] {e}")
        return await wav_de_error()


# =====================================================
# PAGINAS DE DIAGNOSTICO
# =====================================================
@app.get("/")
async def inicio():
    foto_html = (
        f'<img src="/ultima_foto?t={contador_fotos}" alt="ultima foto">'
        if ultima_foto else "<p>Todavia no llego ninguna foto.</p>"
    )
    html = f"""<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta http-equiv="refresh" content="5">
<title>Lentes Inteligentes</title>
<style>
 body{{font-family:system-ui,sans-serif;background:#111;color:#eee;
       margin:0;padding:24px;max-width:760px}}
 h1{{font-size:20px;margin:0 0 4px}}
 .dato{{color:#888;font-size:13px;margin-bottom:18px}}
 img{{max-width:100%;border-radius:8px;border:1px solid #333}}
 .caja{{background:#1c1c1c;border:1px solid #333;border-radius:8px;
        padding:14px;margin:14px 0;line-height:1.5}}
</style></head><body>
<h1>Lentes Inteligentes</h1>
<div class="dato">
  Modelo: {MODELO_ACTIVO} &nbsp;|&nbsp;
  Fotos: {contador_fotos} &nbsp;|&nbsp;
  Audios: {contador_audios} &nbsp;|&nbsp;
  Ultima: {ultima_hora or '-'} &nbsp;|&nbsp;
  refresca cada 5 s
</div>
{foto_html}
<div class="dato">Foto mejorada (lo que ve Gemini). <a style="color:#8ab4f8" href="/ultima_foto_original">Ver la original de la camara</a></div>
<div class="caja"><b>Ultima pregunta (lo que entendio Gemini):</b><br>{ultima_pregunta or '-'}</div>
<div class="caja"><b>Ultima respuesta de Gemini:</b><br>{ultimo_texto or '-'}</div>
</body></html>"""
    return Response(content=html, media_type="text/html; charset=utf-8")


@app.get("/ultima_foto")
async def ver_ultima_foto():
    if not ultima_foto:
        return Response(status_code=404)
    return Response(content=ultima_foto, media_type="image/jpeg")


@app.get("/ultima_foto_original")
async def ver_ultima_foto_original():
    if not ultima_foto_original:
        return Response(status_code=404)
    return Response(content=ultima_foto_original, media_type="image/jpeg")


@app.get("/ping")
async def ping():
    return Response(content="ok", media_type="text/plain")


@app.get("/probar_audio")
async def probar_audio():
    try:
        wav = await texto_a_wav(
            "Prueba de sonido. Si escuchas esta frase, el parlante funciona correctamente."
        )
        print("   [TEST] WAV de prueba entregado")
        return responder_wav(wav)
    except Exception as e:
        print(f"   [ERROR] prueba de audio: {e}")
        return Response(content=b"", status_code=500)


# =====================================================
# ARRANQUE
# =====================================================
def verificar_entorno():
    global FFMPEG_BIN, MODELO_ACTIVO

    print("=" * 60)
    print("LENTES INTELIGENTES - servidor v9 (dos botones)")
    print("=" * 60)
    print(f"Python {sys.version.split()[0]}")

    if not GENAI_API_KEY:
        print("\n[FATAL] Falta la clave de Gemini.")
        print("        Crea un archivo .env junto a servidor.py con la linea:")
        print("        GEMINI_API_KEY=tu_clave")
        print("        (o pegala en GENAI_API_KEY_FIJA dentro del codigo)")
        sys.exit(1)

    FFMPEG_BIN = FFMPEG_PATH or shutil.which("ffmpeg")
    if not FFMPEG_BIN:
        print("\n[FATAL] No encuentro ffmpeg.")
        sys.exit(1)
    print(f"ffmpeg  : OK ({FFMPEG_BIN})")
    if MEJORAR_FOTO and not HAY_PILLOW:
        print("Foto    : Pillow no esta instalado, las fotos van sin mejorar.")
        print("          Para activarlo:  python -m pip install pillow")
    else:
        print(f"Foto    : mejora {'activada' if MEJORAR_FOTO else 'desactivada'}")

    try:
        disponibles = listar_modelos()
    except Exception as e:
        print(f"\n[FATAL] No puedo hablar con Gemini.\n        {e}")
        sys.exit(1)

    if MODELO_PREFERIDO in disponibles:
        MODELO_ACTIVO = MODELO_PREFERIDO
        print(f"Gemini  : OK, usando {MODELO_ACTIVO} (pensamiento: {THINKING_LEVEL or 'por defecto'})")
    else:
        preferencias = ([m for m in disponibles if "flash-lite" in m] or
                        [m for m in disponibles if "flash" in m] or
                        disponibles)
        if not preferencias:
            print("[FATAL] Sin modelos disponibles.")
            sys.exit(1)
        MODELO_ACTIVO = preferencias[0]
        print(f"Gemini  : '{MODELO_PREFERIDO}' no disponible, usando {MODELO_ACTIVO}")

    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
    except Exception:
        ip = "TU_IP_LOCAL"

    print("-" * 60)
    print(f"IP de esta PC : {ip}")
    print(f"  /bienvenida     -> http://{ip}:{PUERTO}/bienvenida")
    print(f"  /upload_imagen  -> http://{ip}:{PUERTO}/upload_imagen")
    print(f"  /upload_contexto-> http://{ip}:{PUERTO}/upload_contexto")
    print(f"  /upload_audio   -> http://{ip}:{PUERTO}/upload_audio")
    print("=" * 60)
    print("Esperando peticiones del ESP32...\n")


if __name__ == "__main__":
    verificar_entorno()
    uvicorn.run(app, host="0.0.0.0", port=PUERTO, log_level="warning")
