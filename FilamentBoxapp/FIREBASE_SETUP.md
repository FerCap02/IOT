# Configuración Firebase de FilamentBox

Android y ESP32 usan Firebase Authentication con correo y contraseña. Android también puede comunicarse directamente por USB serial con la placa. La asignación de pines y la evaluación de seguridad están en [la verificación de la rúbrica](../RUBRICA_VERIFICACION.md).

## Configuración del proyecto

1. Registrar la aplicación Android `cl.filamentbox.app` en Firebase y colocar su `google-services.json` en `app/`.
2. Verificar los valores de `app/src/main/java/cl/filamentbox/app/FirebaseConfig.kt`: API key web, application ID, project ID y URL de Realtime Database. El proyecto aplica Google Services y conserva la inicialización programática como alternativa.
3. Activar el proveedor Correo electrónico y contraseña. Crear una cuenta exclusiva para la ESP32 y cuentas diferentes para los usuarios Android.
4. Configurar la cuenta de la ESP32 en `../IOT/include/config.local.h`. Este archivo está excluido de Git; el ejemplo público es `config.example.h`.
5. Revisar `database.rules.json`, ejecutar las pruebas de autorización en el emulador y aplicar las reglas en Firebase Console.
6. Mediante una cuenta administradora en Firebase Console, registrar `filamentBox/miembros/<UID_ANDROID> = true` y `filamentBox/dispositivos/<UID_ESP32> = true`. Un cliente no puede concederse estos permisos.

No habilitar acceso general a toda la base para cualquier usuario autenticado. Las reglas preparadas permiten controlar el equipo a sus miembros y publicar sensores a la cuenta del dispositivo. Cada usuario accede a su propio perfil e historial.

## Estructura de datos

| Ruta bajo `filamentBox` | Uso |
|---|---|
| `control` | Orden completa con cinco booleanos, modo, settings e `issuedAt` en milisegundos Unix. Android genera este nodo al enviar una orden. |
| `sensores` | Última muestra, lecturas DHT con campo `valid`, voltaje UV, estado efectivo, identificador y tiempos de adquisición/recepción. |
| `muestras/<sampleId>` | Muestras de la cola ESP32, sincronizadas con una clave estable. |
| `historial/<uid>/<sampleId>` | Historial privado generado por Android; consulta de las últimas 60 lecturas. |
| `usuarios/<uid>` | Nombre del perfil y fecha de creación. Firebase Auth conserva el correo de la cuenta. |
| `miembros/<uid>` y `dispositivos/<uid>` | Permisos asignados por un administrador. |

Las órdenes nuevas vencen después de 30 segundos y requieren un reloj de teléfono correcto. Una orden pendiente de una desconexión larga no debe reactivar el equipo al restablecerse Internet. La aplicación muestra el estado efectivo enviado por ESP32; una escritura aceptada por Firebase no acredita por sí sola que el actuador haya cambiado.

## Configuración Wi-Fi y alternativas

Para configurar una red de destino predeterminada sin usar el portal, editar `../IOT/include/config.local.h` y completar:

```cpp
#define WIFI_DEFAULT_SSID "NOMBRE_DE_TU_RED"
#define WIFI_DEFAULT_PASSWORD "CLAVE_DE_TU_RED"
```

El firmware intenta primero esa red en cada arranque. Un SSID vacío conserva el uso de las credenciales guardadas por el portal. Si falla la red predeterminada, abre el portal para elegir otra; la red elegida se usa durante esa ejecución y en sus reintentos. En el siguiente reinicio vuelve a preferirse la red predeterminada si sigue definida. Recompilar y cargar el firmware después de editar estos campos. `WIFI_DEFAULT_PASSWORD` es la contraseña del router o hotspot, distinta de la clave de `FilamentBox-Setup`. Las credenciales permanecen en el archivo local excluido de Git y no se imprimen en los diagnósticos.

La red de configuración es **FilamentBox-Setup**, con contraseña **filament1234**. Conectar el teléfono a esa red y abrir **http://192.168.4.1**; si Android avisa que no tiene Internet, conservar esa conexión mientras se configura. Elegir el Wi-Fi de destino e introducir la contraseña de ese router.

La ESP32-S3 utiliza Wi-Fi de 2,4 GHz; no se conecta a una red exclusiva de 5 GHz. Se puede usar una red de router de 2,4 GHz o un punto de acceso de teléfono configurado en esa banda. [Ficha técnica de Espressif](https://documentation.espressif.com/esp32_s3_datasheet_en.pdf).

El intento inicial espera hasta 3 s; el formulario permite hasta 8 s por intento. Al quedar sin red durante 10 s, se ofrece otra vez el portal. Cada ventana dura hasta 3 minutos. Mientras el portal está abierto, WiFiManager administra las búsquedas y los intentos enviados desde el formulario; el firmware no inicia conexiones en paralelo, aunque aún no haya un teléfono conectado. Fuera del portal, cada intento manual dispone de hasta 8 s y termina antes de iniciar otro, con pausas de 2 a 10 s. Si el router vuelve mientras el portal está abierto, se puede pulsar Guardar con sus credenciales o esperar al cierre de esa ventana para el siguiente intento automático.

El mensaje “wifi is connecting, return error” indica que se intentó una operación incompatible con una conexión en curso; no acredita por sí solo que la contraseña sea incorrecta. La corrección del firmware elimina la combinación inmediata `reconnect()`/`begin()`, desactiva la reconexión paralela del core y reinicia únicamente la interfaz STA antes de un nuevo intento, conservando credenciales. Antes de guardar desde el portal también se detiene el intento anterior. Las condiciones del controlador están documentadas en la [API Wi-Fi de Espressif](https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-reference/network/esp_wifi.html).

Para cambiar la clave de configuración, editar `WIFI_SETUP_PASSWORD` en `../IOT/include/config.local.h`, mantener al menos 12 caracteres y recompilar/cargar el firmware en la ESP32. La APK no necesita recompilarse por ese cambio. Si el teléfono recuerda una clave anterior, olvidar `FilamentBox-Setup` y volver a conectarse.

El monitor serial a 115200 muestra eventos `[WIFI]`, `[NETWORK]` y `[FIREBASE]`. Una IP y señal RSSI indican conexión al router; “waiting for NTP” indica espera de reloj; un fallo de autenticación Firebase puede ocurrir con el Wi-Fi ya conectado. Los mensajes no imprimen contraseñas ni tokens. Durante una sesión USB autenticada con la app se omiten estos mensajes de texto para reservar el canal al protocolo.

La alternativa directa es **Ajustes → Comunicación serial USB → Conectar USB**, con cable OTG y la clave `SERIAL_PSK` del dispositivo. Permite controlar y leer sensores sin Wi-Fi; una sesión Android ya iniciada permite trabajar sin Internet.

## Uso sin conexión

Firebase mantiene una caché de lecturas y escrituras pendientes en el teléfono. La caché de lectura está configurada en 20 MiB; la cola de escrituras tiene otra política de almacenamiento. La primera autenticación de una cuenta necesita Internet.

Con USB OTG y una sesión Android existente, se pueden recibir muestras nuevas sin Internet. Introducir en Ajustes la clave `SERIAL_PSK` del dispositivo. Las lecturas recibidas se escriben al historial y quedan pendientes de sincronización con Firebase. El simulador de red es independiente del firmware físico; los clientes de red requieren TLS fuera de loopback.

## Compilación y comprobación

Desde Android Studio, abrir esta carpeta, ajustar la ubicación del SDK y compilar `debug`. Se requiere SDK 35 y Java compatible con el proyecto. Las tareas verificadas son `:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug`. Las pruebas físicas y la configuración del servicio deben completarse según [la matriz de verificación](../RUBRICA_VERIFICACION.md).
