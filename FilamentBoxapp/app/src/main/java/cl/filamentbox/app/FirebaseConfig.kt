package cl.filamentbox.app

/**
 * Copia estos valores desde google-services.json de tu proyecto Firebase.
 * No uses la clave privada de una cuenta de servicio. La apiKey web de Firebase
 * identifica el proyecto; la seguridad real se aplica con Authentication y Rules.
 */
object FirebaseConfig {
    const val API_KEY = "AIzaSyAZj9eHLag8j1p5CdExrlHWSBzaEMxcaOg"
    const val APPLICATION_ID = "1:619314399395:android:7738ee5ec0287b777fd716"
    const val PROJECT_ID = "filament-box"
    const val DATABASE_URL = "https://filament-box-default-rtdb.firebaseio.com/"

    val isConfigured: Boolean
        get() = !API_KEY.startsWith("PEGA_AQUI") &&
            !APPLICATION_ID.startsWith("PEGA_AQUI") &&
            !PROJECT_ID.startsWith("PEGA_AQUI") &&
            !DATABASE_URL.contains("PEGA_AQUI")
}
