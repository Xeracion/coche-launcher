package es.fran.cochelauncher

import android.content.Context

/** Gestos de la pantalla de inicio y la acción que hace cada uno. */
object Gestures {

    /** Gesto: clave de ajuste, nombre visible y acción por defecto. */
    data class G(val key: String, val title: String, val default: String)

    val all = listOf(
        G("g_up", "Deslizar hacia arriba", "apps"),
        G("g_down", "Deslizar hacia abajo", "assistant"),
        G("g_double", "Tocar dos veces en una zona vacía", "blackout"),
        G("g_long", "Mantener pulsado en una zona vacía", "wallpaper"),
    )

    /** Acciones posibles, en el orden en que salen en la lista. */
    val actions = linkedMapOf(
        "none" to "Nada",
        "apps" to "Abrir todas las apps",
        "blackout" to "Apagar la pantalla",
        "assistant" to "Abrir el asistente del móvil",
        "auto" to "Abrir Android Auto",
        "phone" to "Abrir el teléfono",
        "theme" to "Cambiar modo día/noche",
        "settings" to "Abrir los ajustes",
        "wallpaper" to "Cambiar la imagen de fondo",
    )

    fun action(ctx: Context, g: G): String =
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).getString(g.key, g.default) ?: g.default

    fun set(ctx: Context, g: G, action: String) {
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).edit().putString(g.key, action).apply()
    }

    fun byKey(key: String) = all.first { it.key == key }
}
