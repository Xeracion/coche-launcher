package es.fran.puenteclaude

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * El coche pide "asistente de voz" por Bluetooth. Android lanza la orden VOICE_COMMAND,
 * que la app de Claude no recoge. Esta pantalla invisible la recoge y abre el asistente
 * predeterminado del móvil (Claude), igual que al mantener pulsado el botón lateral.
 */
class VoiceBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_MAIN) {
            Toast.makeText(this, "Puente Claude está listo. El coche abrirá tu asistente.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent?.action == Intent.ACTION_MAIN) return
        // Hay que estar delante para poder pedir el asistente; esperamos un instante.
        Handler(Looper.getMainLooper()).postDelayed({ openAssistant() }, 250)
    }

    private fun openAssistant() {
        val ok = try { showAssist(Bundle()) } catch (e: Exception) { false }
        if (!ok) {
            // Plan B: abrir la app de Claude directamente.
            val launch = packageManager.getLaunchIntentForPackage("com.anthropic.claude")
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                Toast.makeText(this, "No encuentro el asistente", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
