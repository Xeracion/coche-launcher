package es.fran.cochelauncher

import android.service.notification.NotificationListenerService

/**
 * Servicio vacío. Android exige que exista (y que el usuario lo autorice)
 * para que el launcher pueda leer qué canción suena en otras apps.
 */
class MediaListener : NotificationListenerService()
