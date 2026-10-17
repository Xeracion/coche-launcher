package es.fran.cochelauncher

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Servidor web mínimo que ofrece un archivo en la wifi (puerto 8080). */
object WifiShare {
    private const val PORT = 8080
    @Volatile private var server: ServerSocket? = null
    @Volatile private var file: File? = null
    val running get() = server != null

    fun start(f: File) {
        file = f
        if (server != null) return
        val s = ServerSocket(PORT)
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    Thread { serve(client) }.start()
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            try {
                val reader = sock.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return
                // Leemos (y descartamos) las cabeceras.
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val out = sock.getOutputStream()
                val f = file
                val path = first.split(" ").getOrNull(1) ?: "/"
                if (f != null && f.exists() && (path == "/" || path.startsWith("/apps_fabrica"))) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
                        "Content-Disposition: attachment; filename=\"apps_fabrica.zip\"\r\n" +
                        "Content-Length: ${f.length()}\r\nConnection: close\r\n\r\n").toByteArray())
                    f.inputStream().use { it.copyTo(out) }
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
            }
        }
    }

    /** Dirección en la wifi, o null si no hay wifi. */
    fun address(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (!ni.name.startsWith("wlan")) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return "http://${a.hostAddress}:$PORT"
                }
            }
        } catch (e: Exception) {
        }
        return null
    }
}
