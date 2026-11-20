/* =====================================================================
   ESCANER DE BOTONES  -  ESP32-S3
   Sketch suelto, para diagnosticar. No usa camara, ni microfono, ni WiFi.

   QUE HACE
   1. Revisa que pines estan libres para un boton y cuales no.
   2. Despues avisa, en tiempo real, que GPIO cambia cuando apretas algo.

   COMO USARLO
   - Subilo, abri el Monitor Serie a 115200 y apreta cada boton varias veces.
   - Primero apreta el boton BOOT de la placa: tiene que aparecer GPIO 0.
     Si BOOT aparece y tus botones no, el problema es el cableado, no el pin.
   ===================================================================== */

#include <Arduino.h>

// GPIO 0 = boton BOOT de la placa (sirve de prueba: deberia funcionar siempre).
// El resto son los pines que NO usa tu circuito (ni camara, ni I2S, ni PSRAM).
const int PINES[] = {0, 1, 2, 3, 14, 40, 46, 47, 48};
const int CANT = sizeof(PINES) / sizeof(PINES[0]);

int  estado[CANT];
bool libre[CANT];
unsigned long tCambio[CANT];
int  veces[CANT];

void setup() {
  Serial.begin(115200);
  delay(2500);                       // tiempo para que el Monitor Serie se conecte

  Serial.println();
  Serial.println("==================================================");
  Serial.println("ESCANER DE BOTONES");
  Serial.println("==================================================");

  for (int i = 0; i < CANT; i++) {
    pinMode(PINES[i], INPUT_PULLUP);
    veces[i] = 0;
    tCambio[i] = 0;
  }
  delay(200);

  Serial.println("PASO 1: estado en reposo. NO aprietes ningun boton ahora.");
  Serial.println("--------------------------------------------------");
  int libres = 0;
  for (int i = 0; i < CANT; i++) {
    estado[i] = digitalRead(PINES[i]);
    libre[i]  = (estado[i] == HIGH);
    if (libre[i]) libres++;
    Serial.printf("  GPIO %-2d : %s\n", PINES[i],
                  libre[i] ? "1  libre, sirve para un boton"
                           : "0  OCUPADO (hay algo conectado a este pin) - NO sirve");
  }
  Serial.println("--------------------------------------------------");
  Serial.printf("Pines libres: %d de %d\n", libres, CANT);
  Serial.println();
  Serial.println("PASO 2: apreta y solta cada boton varias veces.");
  Serial.println("Primero apreta el boton BOOT de la placa (tiene que salir GPIO 0).");
  Serial.println("==================================================");
}

void loop() {
  for (int i = 0; i < CANT; i++) {
    int v = digitalRead(PINES[i]);
    if (v == estado[i]) continue;

    unsigned long ahora = millis();
    unsigned long dt = tCambio[i] ? ahora - tCambio[i] : 0;
    estado[i]  = v;
    tCambio[i] = ahora;

    if (v == LOW) {
      veces[i]++;
      Serial.printf("GPIO %-2d  APRETADO   (pulsacion numero %d)%s\n",
                    PINES[i], veces[i],
                    PINES[i] == 0 ? "   <- este es el boton BOOT de la placa" : "");
    } else {
      Serial.printf("GPIO %-2d  suelto     (duro %lu ms)%s\n",
                    PINES[i], dt,
                    dt < 25 ? "   <- muy corto: es un rebote" : "");
    }
  }
  delay(2);
}
