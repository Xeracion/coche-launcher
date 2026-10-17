package es.fran.cochelauncher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro interno de lo que ocurre al arrancar, para afinar el vigilante sin herramientas externas. */
object Diag {

    @Synchronized
    fun append(ctx: Context, msg: String) {
        try {
            val f = File(ctx.applicationContext.filesDir, "registro.txt")
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("$stamp  $msg\n")
            if (f.length() > 40_000) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(150).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
        }
    }

    fun read(ctx: Context): String = try {
        val f = File(ctx.applicationContext.filesDir, "registro.txt")
        if (f.exists()) f.readLines().takeLast(60).joinToString("\n") else "Sin registro todavía."
    } catch (e: Exception) {
        "No se pudo leer el registro."
    }

    fun clear(ctx: Context) {
        try { File(ctx.applicationContext.filesDir, "registro.txt").delete() } catch (e: Exception) { }
    }
}
