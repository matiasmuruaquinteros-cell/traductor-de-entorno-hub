package com.lentesinteligentes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.lentesinteligentes.app.nucleo.Direccion
import com.lentesinteligentes.app.nucleo.GestorUbicacion
import com.lentesinteligentes.app.nucleo.Localizador
import com.lentesinteligentes.app.nucleo.Posicion
import com.lentesinteligentes.app.nucleo.Registro
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GPS y direcciones con lo que trae Android (sin Google Play Services ni claves extra):
 *  - LocationManager: ultima posicion si es reciente y precisa; si no, pide una nueva (hasta 10 s).
 *  - Geocoder: coordenadas -> calle y altura, y nombre/direccion -> coordenadas.
 */
class LocalizadorAndroid(private val ctx: Context, private val registro: Registro) : Localizador {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val hilo = Executors.newSingleThreadExecutor()
    private val idioma: Locale = Locale.forLanguageTag("es-AR")

    private fun tiene(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun precisa() = tiene(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun permisosFaltantes(): List<String> =
        if (precisa() || tiene(Manifest.permission.ACCESS_COARSE_LOCATION)) emptyList() else listOf("ubicacion")

    override fun problema(): String? {
        val activada = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!activada) return "La ubicacion del celular esta apagada. Hay que activarla en el panel de arriba del celular."
        if (!precisa()) return "La app solo tiene la ubicacion aproximada, y con eso no se en que calle estas. " +
            "En la app anda a Ajustes, toca Permitir ubicacion y elegi ubicacion precisa."
        return null
    }

    @SuppressLint("MissingPermission")
    override fun posicion(): Posicion? {
        if (!precisa()) return null
        val proveedores = lm.getProviders(true)
        // 1) Una posicion de hace menos de 20 s y buena: se usa directo
        val conocidas = proveedores.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
        conocidas.filter { edadMs(it) < 20_000 && it.accuracy <= 35f }.minByOrNull { it.accuracy }?.let { return aPosicion(it) }

        // 2) Pedir una nueva: el proveedor "fused" si existe; si no, GPS y red a la vez
        val pedir = if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in proveedores) listOf(LocationManager.FUSED_PROVIDER)
                    else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in proveedores }
        val llegaron = CopyOnWriteArrayList<Location>()
        val faltan = CountDownLatch(pedir.size)
        val oyentes = ArrayList<LocationListener>()
        for (prov in pedir) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(prov, null, hilo) { l -> if (l != null) llegaron += l; faltan.countDown() }
                } else {
                    val oyente = object : LocationListener {
                        override fun onLocationChanged(l: Location) { llegaron += l; faltan.countDown() }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) { faltan.countDown() }
                    }
                    oyentes += oyente
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(prov, oyente, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                registro.log("   [UBICACION] $prov no respondio: ${e.message}")
                faltan.countDown()
            }
        }
        val limite = SystemClock.elapsedRealtime() + ESPERA_GPS_MS
        try {
            while (SystemClock.elapsedRealtime() < limite && faltan.count > 0) {
                llegaron.minByOrNull { it.accuracy }?.let { if (it.accuracy <= 25f) return aPosicion(it) }
                faltan.await(200, TimeUnit.MILLISECONDS)
            }
            llegaron.minByOrNull { it.accuracy }?.let { return aPosicion(it) }
        } finally {
            for (o in oyentes) try { lm.removeUpdates(o) } catch (_: Exception) {}
        }
        // 3) Sin senal nueva: la ultima conocida de hasta 2 minutos
        return conocidas.filter { edadMs(it) < 120_000 }.minByOrNull { it.accuracy }?.let {
            registro.log("   [UBICACION] uso una posicion de hace ${edadMs(it) / 1000} s")
            aPosicion(it)
        }
    }

    private fun edadMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun aPosicion(l: Location) = Posicion(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 50f)

    @SuppressLint("NewApi")          // la version con aviso solo se usa en Android 13+ (ver geocodificar)
    override fun direccion(p: Posicion): Direccion? {
        val a = geocodificar(
            nuevo = { g, oyente -> g.getFromLocation(p.lat, p.lon, 1, oyente) },
            viejo = { g -> @Suppress("DEPRECATION") g.getFromLocation(p.lat, p.lon, 1) },
        )?.firstOrNull() ?: return null
        return Direccion(a.thoroughfare.orEmpty(), a.subThoroughfare.orEmpty(), a.subLocality.orEmpty(),
            a.locality ?: a.subAdminArea ?: "")
    }

    @SuppressLint("NewApi")
    override fun buscarDireccion(texto: String, cerca: Posicion): Posicion? {
        val d = CAJA_BUSQUEDA
        val lista = geocodificar(
            nuevo = { g, oyente -> g.getFromLocationName(texto, 5, cerca.lat - d, cerca.lon - d, cerca.lat + d, cerca.lon + d, oyente) },
            viejo = { g -> @Suppress("DEPRECATION") g.getFromLocationName(texto, 5, cerca.lat - d, cerca.lon - d, cerca.lat + d, cerca.lon + d) },
        )
        return lista.orEmpty().filter { it.hasLatitude() && it.hasLongitude() }
            .map { Posicion(it.latitude, it.longitude) }
            .minByOrNull { GestorUbicacion.metros(cerca, it) }
    }

    /** Geocoder: en Android 13+ con aviso (asincronico); antes, la version que bloquea. */
    private fun geocodificar(
        nuevo: (Geocoder, Geocoder.GeocodeListener) -> Unit,
        viejo: (Geocoder) -> List<Address>?,
    ): List<Address>? {
        if (!Geocoder.isPresent()) { registro.log("   [UBICACION] este celular no tiene servicio de direcciones"); return null }
        val g = Geocoder(ctx, idioma)
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                var resultado: List<Address>? = null
                val listo = CountDownLatch(1)
                nuevo(g, object : Geocoder.GeocodeListener {
                    override fun onGeocode(direcciones: MutableList<Address>) { resultado = direcciones; listo.countDown() }
                    override fun onError(mensaje: String?) {
                        registro.log("   [UBICACION] servicio de direcciones: ${mensaje ?: "error"}")
                        listo.countDown()
                    }
                })
                listo.await(ESPERA_DIRECCION_S, TimeUnit.SECONDS)
                resultado
            } else {
                viejo(g)
            }
        } catch (e: Exception) {
            registro.log("   [UBICACION] servicio de direcciones fallo: ${e.message}")
            null
        }
    }

    companion object {
        private const val ESPERA_GPS_MS = 10_000L
        private const val ESPERA_DIRECCION_S = 6L
        private const val CAJA_BUSQUEDA = 0.15          // grados (unos 15 km alrededor)

        val PERMISOS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
