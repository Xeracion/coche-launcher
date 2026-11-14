# Inicio coche

Pantalla de inicio de estilo minimalista para la radio Android del Dacia (Android 12, 1024x600).

## Qué hace

1. De fondo, la imagen de fondo de pantalla de la radio. Los botones y las tarjetas toman su color. Se puede volver a la textura propia en Ajustes > Pantalla.
2. Reloj grande con fecha y tiempo a la izquierda. En marcha, la velocidad ocupa su lugar.
3. Tarjeta de la canción en curso con anterior, pausa y siguiente. Deslizando sobre ella se cambia de canción.
4. Modo oscuro automático desde la puesta de sol hasta el amanecer, calculado cada día con el GPS de la radio.
5. Dock inferior con Teléfono, Radio, Música BT, Android Auto, el asistente del móvil y las herramientas.
6. Gestos configurables en Ajustes > Gestos: deslizar arriba (todas las apps), deslizar abajo (asistente), tocar dos veces (apagar pantalla) y mantener pulsado (cambiar fondo).

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
