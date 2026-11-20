package com.lentesinteligentes.app.nucleo

/**
 * JSON minimo, sin librerias.
 *  - escribir(): convierte Map / List / String / numeros / Boolean / null a texto JSON.
 *  - leer():     convierte texto JSON a Map<String, Any?> / List<Any?> / String / Long / Double / Boolean / null.
 *  - ruta():     navega una respuesta, por ejemplo ruta(datos, "candidates", 0, "content").
 */
object Json {

    fun escribir(valor: Any?): String {
        val sb = StringBuilder()
        escribir(valor, sb)
        return sb.toString()
    }

    private fun escribir(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> cadena(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var primero = true
                for ((k, x) in v) {
                    if (!primero) sb.append(',')
                    primero = false
                    cadena(k.toString(), sb)
                    sb.append(':')
                    escribir(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var primero = true
                for (x in v) {
                    if (!primero) sb.append(',')
                    primero = false
                    escribir(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> escribir(v.toList(), sb)
            else -> cadena(v.toString(), sb)
        }
    }

    private fun cadena(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    fun leer(texto: String): Any? {
        val l = Lector(texto)
        l.espacios()
        val v = l.valor()
        l.espacios()
        if (l.i != texto.length) throw IllegalArgumentException("JSON: texto sobrante en la posicion ${l.i}")
        return v
    }

    /** Navega por claves (String) e indices (Int). Devuelve null si algo no existe. */
    fun ruta(dato: Any?, vararg pasos: Any): Any? {
        var actual = dato
        for (p in pasos) {
            actual = when {
                p is String && actual is Map<*, *> -> actual[p]
                p is Int && actual is List<*> -> actual.getOrNull(p)
                else -> return null
            }
        }
        return actual
    }

    private class Lector(val t: String) {
        var i = 0

        fun espacios() {
            while (i < t.length && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t')) i++
        }

        fun error(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg en la posicion $i")

        fun valor(): Any? {
            if (i >= t.length) error("fin inesperado")
            return when (val c = t[i]) {
                '{' -> objeto()
                '[' -> lista()
                '"' -> cadena()
                't' -> palabra("true", true)
                'f' -> palabra("false", false)
                'n' -> palabra("null", null)
                else -> if (c == '-' || c in '0'..'9') numero() else error("caracter inesperado '$c'")
            }
        }

        private fun palabra(p: String, v: Any?): Any? {
            if (!t.startsWith(p, i)) error("se esperaba $p")
            i += p.length
            return v
        }

        private fun objeto(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            espacios()
            if (i < t.length && t[i] == '}') { i++; return m }
            while (true) {
                espacios()
                if (i >= t.length || t[i] != '"') error("se esperaba una clave")
                val k = cadena()
                espacios()
                if (i >= t.length || t[i] != ':') error("se esperaba ':'")
                i++
                espacios()
                m[k] = valor()
                espacios()
                if (i >= t.length) error("objeto sin cerrar")
                when (t[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> error("se esperaba ',' o '}'")
                }
            }
        }

        private fun lista(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            espacios()
            if (i < t.length && t[i] == ']') { i++; return l }
            while (true) {
                espacios()
                l.add(valor())
                espacios()
                if (i >= t.length) error("lista sin cerrar")
                when (t[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> error("se esperaba ',' o ']'")
                }
            }
        }

        private fun cadena(): String {
            i++ // comilla de apertura
            val sb = StringBuilder()
            while (true) {
                if (i >= t.length) error("texto sin cerrar")
                val c = t[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= t.length) error("escape incompleto")
                        when (val e = t[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > t.length) error("escape \\u incompleto")
                                sb.append(t.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("escape invalido '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun numero(): Any {
            val ini = i
            if (t[i] == '-') i++
            while (i < t.length && t[i] in '0'..'9') i++
            var decimal = false
            if (i < t.length && t[i] == '.') {
                decimal = true
                i++
                while (i < t.length && t[i] in '0'..'9') i++
            }
            if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
                decimal = true
                i++
                if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
                while (i < t.length && t[i] in '0'..'9') i++
            }
            val s = t.substring(ini, i)
            return if (decimal) s.toDouble() else (s.toLongOrNull() ?: s.toDouble())
        }
    }
}
