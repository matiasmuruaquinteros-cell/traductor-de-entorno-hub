package com.lentesinteligentes.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lentesinteligentes.app.nucleo.Ajustes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes guardados en el celular. */
object Preferencias {
    private const val ARCHIVO = "ajustes"
    const val PUERTO_POR_DEFECTO = 8000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    fun leer(ctx: Context): Ajustes {
        val p = prefs(ctx)
        val d = Ajustes()
        return Ajustes(
            modelo = p.getString("modelo", null)?.trim()?.ifEmpty { null } ?: d.modelo,
            rotacion = p.getInt("rotacion", d.rotacion),
            mejorarFoto = p.getBoolean("mejorarFoto", d.mejorarFoto),
            volumen = p.getFloat("volumen", d.volumen.toFloat()).toDouble(),
            normalizarMic = p.getBoolean("normalizarMic", d.normalizarMic),
            saludoIa = p.getBoolean("saludoIa", d.saludoIa),
            memoria = p.getBoolean("memoria", d.memoria),
            velocidadVoz = p.getFloat("velocidadVoz", d.velocidadVoz),
            tonoVoz = p.getFloat("tonoVoz", d.tonoVoz),
            motorVoz = p.getString("motorVoz", d.motorVoz) ?: d.motorVoz,
            nombreVoz = p.getString("nombreVoz", d.nombreVoz) ?: d.nombreVoz,
            modoLive = p.getBoolean("modoLive", d.modoLive),
            micAbierto = p.getBoolean("micAbierto", d.micAbierto),
            modeloLive = p.getString("modeloLive", null)?.trim()?.ifEmpty { null } ?: d.modeloLive,
            vozLive = p.getString("vozLive", d.vozLive) ?: d.vozLive,
            llamadas = p.getBoolean("llamadas", d.llamadas),
            ubicacion = p.getBoolean("ubicacion", d.ubicacion),
        )
    }

    fun guardar(ctx: Context, a: Ajustes) {
        prefs(ctx).edit()
            .putString("modelo", a.modelo)
            .putInt("rotacion", a.rotacion)
            .putBoolean("mejorarFoto", a.mejorarFoto)
            .putFloat("volumen", a.volumen.toFloat())
            .putBoolean("normalizarMic", a.normalizarMic)
            .putBoolean("saludoIa", a.saludoIa)
            .putBoolean("memoria", a.memoria)
            .putFloat("velocidadVoz", a.velocidadVoz)
            .putFloat("tonoVoz", a.tonoVoz)
            .putString("motorVoz", a.motorVoz)
            .putString("nombreVoz", a.nombreVoz)
            .putBoolean("modoLive", a.modoLive)
            .putBoolean("micAbierto", a.micAbierto)
            .putString("modeloLive", a.modeloLive)
            .putString("vozLive", a.vozLive)
            .putBoolean("llamadas", a.llamadas)
            .putBoolean("ubicacion", a.ubicacion)
            .apply()
    }

    fun puerto(ctx: Context): Int = prefs(ctx).getInt("puerto", PUERTO_POR_DEFECTO)
    fun guardarPuerto(ctx: Context, puerto: Int) { prefs(ctx).edit().putInt("puerto", puerto).apply() }
}

/**
 * La clave de Gemini se guarda cifrada con una llave del Android Keystore
 * (la llave nunca sale del chip de seguridad del celular). No queda escrita en el codigo.
 */
object ClaveSegura {
    private const val ALIAS = "lentes_clave_gemini"
    private const val ARCHIVO = "seguro"

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun guardar(ctx: Context, clave: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        val cifrada = c.doFinal(clave.trim().toByteArray(Charsets.UTF_8))
        val texto = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cifrada, Base64.NO_WRAP)
        ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().putString("clave", texto).apply()
    }

    fun leer(ctx: Context): String {
        val texto = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).getString("clave", null) ?: return ""
        return try {
            val (iv, cifrada) = texto.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, iv))
            String(c.doFinal(cifrada), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun hayClave(ctx: Context) = leer(ctx).isNotEmpty()
}
