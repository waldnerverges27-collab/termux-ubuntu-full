# Ubuntu rootfs y terminal premium

## Integración del entorno Linux

El fork instala el rootfs de Ubuntu incluido en el APK y configura el login de
Termux para iniciar Ubuntu con `proot`. El rootfs se instala bajo
`$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu`; el acceso se puede
cambiar desde la sesión terminal como de costumbre.

El archivo `ubuntu-rootfs.tar.xz` es demasiado grande para Git. Para compilar,
cópialo a `app/src/main/assets/ubuntu-rootfs.tar.xz` desde el asset de la
release correspondiente. El APK generado incluye este archivo.

## Controles del terminal

- La barra superior abre sesiones, crea una sesión, abre la paleta de acciones,
  el explorador y los ajustes. Las sesiones se muestran en una tira que permite
  desplazamiento horizontal cuando no caben en pantalla.
- La paleta de comandos se abre con el botón de búsqueda o con `Ctrl+Shift+P`.
  Permite filtrar acciones como crear/cambiar sesión, ajustes, extra keys,
  teclado, explorador, compartir y reiniciar la terminal.
- La fila de extra keys sigue usando `TermuxExtraKeysView`; conserva `Esc`,
  `Tab`, `Ctrl`, `Alt`, flechas, `Home`, `End`, `PgUp` y `PgDn` de la
  configuración original. La configuración de `extra-keys` de
  `termux.properties` continúa siendo respetada.
- El explorador permite navegar por Ubuntu cuando el rootfs está instalado o
  por el home de Termux cuando se ejecuta una sesión normal. Es de solo
  lectura: se puede copiar una ruta o insertarla en la terminal; nunca ejecuta
  automáticamente un comando seleccionado.
- El selector de paletas cambia los acentos de la interfaz y las extra keys
  (Midnight, Ocean, Violet, Forest, Amber y Rose). Los colores del output de
  la terminal siguen bajo el control de Termux/Termux:Styling.

## Adaptabilidad

La barra utiliza controles de ancho fijo y títulos elípticos; las sesiones
pueden desplazarse horizontalmente y los botones nunca se colocan unos encima
de otros. La vista raíz respeta `fitsSystemWindows`, el manifiesto usa
`adjustResize` y Termux mantiene su ajuste de margen para el teclado virtual.
Así el notch, la barra gestual y el teclado no deben cubrir la fila de atajos.

## Compilación de la variante arm64

1. Instala el entorno Android/NDK requerido por Gradle.
2. Copia el rootfs anterior a `app/src/main/assets/ubuntu-rootfs.tar.xz`.
3. Compila el APK debug arm64 con `:app:assembleDebug` y el filtro ABI
   `arm64-v8a` si se quiere evitar generar las demás variantes.

El APK debug se firma con la clave de desarrollo de Gradle y no es una firma de
publicación. No se probó en un dispositivo físico; antes de distribuirlo, hay
que instalarlo en un teléfono y validar insets, teclado, extra keys y sesión
Ubuntu.
