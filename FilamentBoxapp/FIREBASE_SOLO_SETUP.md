# Autenticación e historial de FilamentBox con Firebase

La aplicación usa Firebase Authentication con correo y contraseña para registro, inicio de sesión y recuperación. El historial y el perfil se guardan en Realtime Database por UID, con persistencia local del SDK para trabajar sin Internet. `data/AppDb.kt` permanece como código legado y no participa en la sesión actual.

La configuración vigente, permisos y rutas se describen en [FIREBASE_SETUP.md](FIREBASE_SETUP.md). Utilizar `database.rules.json` y los roles de miembro/dispositivo; no conceder acceso general a toda cuenta autenticada.

Las cuentas SQLite antiguas no se importan automáticamente a Firebase. El cambio de correo requiere reautenticación y verificación. La conexión USB serial permite recibir nuevas lecturas aunque Firebase esté desconectado; las escrituras pendientes se sincronizan al recuperar Internet.

La evaluación de los 14 criterios, los resultados automáticos y las pruebas físicas pendientes están en [RUBRICA_VERIFICACION.md](../RUBRICA_VERIFICACION.md).
