# Inicio coche

Pantalla de inicio de estilo minimalista para la radio Android del Dacia (Android 12, 1024x600).

## Qué hace

1. Cuatro accesos grandes: Teléfono, Radio, Música BT y Android Auto (AndroidLink).
2. Reloj, fecha y tarjeta de la canción en curso con anterior, pausa y siguiente.
3. Modo oscuro automático desde la puesta de sol hasta el amanecer, calculado cada día con el GPS de la radio. El botón de modo cambia entre automático, oscuro fijo y claro fijo.
4. Botón para apagar la pantalla: la deja en negro con el brillo al mínimo; la música sigue sonando. Se enciende tocando en cualquier sitio.
5. Barra inferior con cuatro accesos secundarios (por defecto Maps, Navi, DSP y Ajustes coche) y el cajón con todas las apps.

Mantener pulsado cualquier acceso permite cambiar la app que abre.

## Instalar en la radio

1. Abre Chrome en la radio y entra en la página de versiones del repositorio (Releases).
2. Descarga `InicioCoche.apk` de la versión más reciente y ábrelo. Si lo pide, permite instalar apps desde Chrome.
3. Pulsa el botón de inicio de la radio y elige "Inicio coche" como pantalla de inicio, con la opción "Siempre".
4. Al abrirla por primera vez, acepta el permiso de ubicación (para calcular el amanecer y el anochecer).
5. Toca la tarjeta de música y activa "Inicio coche" en la lista para que se vea el título de la canción.

Para volver al launcher de fábrica: Ajustes > Ajuste Android > Aplicaciones > Aplicaciones predeterminadas > Aplicación de inicio.

## Compilación

Cada cambio en la rama `main` lo compila GitHub Actions y publica una versión nueva con el APK firmado siempre con la misma clave, así que las actualizaciones se instalan encima sin perder la configuración.
