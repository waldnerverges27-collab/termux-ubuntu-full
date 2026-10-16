# Ubuntu rootfs y terminal premium

## Integración del entorno Linux

El fork instala el rootfs ARM64 de Ubuntu incluido en el APK y abre una sesión
Ubuntu automáticamente al iniciar Termux. El rootfs queda bajo
`$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu`; el comando
`$PREFIX/bin/login` lo inicia con PRoot en modo fake-root (`-0`) y un entorno
guest limpio (`env -i`, `HOME=/root` y un `PATH` de Ubuntu). El script limpia
`LD_PRELOAD` antes de arrancar PRoot. El shell de recuperación de Termux sigue
disponible desde la sesión failsafe.

El archivo `ubuntu-rootfs.tar.xz` es grande y está excluido de Git a propósito.
Los scripts en `rootfs/` construyen Ubuntu 26.04 ARM64 en `/opt`, validan el
marcador y empaquetan el rootfs. Tras generarlo, colócalo en
`app/src/main/assets/ubuntu-rootfs.tar.xz` para compilar; Gradle lo almacena sin
aplicarle una segunda compresión. El APK resultante sí incluye el archivo.

## Controles del terminal

- La barra superior abre sesiones, crea una sesión, abre la paleta de acciones,
  el explorador y los ajustes. Las sesiones se muestran en una tira que permite
  desplazamiento horizontal cuando no caben en pantalla.
- La paleta de comandos se abre con el botón de búsqueda o con `Ctrl+Shift+P`.
  Permite filtrar acciones como crear/cambiar sesión, ajustes, extra keys,
  teclado, explorador, compartir y reiniciar la terminal. El explorador de
  archivos también se puede abrir directamente con `Alt+F`.
- La fila de extra keys sigue usando `TermuxExtraKeysView`; conserva `Esc`,
  `Tab`, `Ctrl`, `Alt`, flechas, `Home`, `End`, `PgUp` y `PgDn` de la
  configuración original. La configuración de `extra-keys` de
  `termux.properties` continúa siendo respetada.
- El explorador navega por el entorno de la sesión activa (Ubuntu o el home
  de Termux) y permite cambiar al almacenamiento compartido de Android. El acceso
  compartido se solicita solo cuando el usuario
  lo elige y usa el flujo de permisos existente de Termux. En Ubuntu se monta en
  `/mnt/shared` al iniciar una sesión nueva. Incluye búsqueda sin distinguir
  mayúsculas/minúsculas en la carpeta actual y una opción para mostrar archivos
  ocultos (nombres que empiezan por punto). Es de solo lectura: se puede copiar
  una ruta o insertarla en la terminal; nunca ejecuta automáticamente un comando
  seleccionado.
- Los controles y mensajes añadidos por este fork tienen traducción al español
  cuando el idioma del dispositivo está configurado en español.
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

1. Para recuperar el entorno completo, ejecuta `bash scripts/setup_android_env.sh`;
   Android SDK, cachés, rootfs y artefactos grandes quedan bajo `/opt`. Si el
   checkout privado debe restaurarse, el script acepta `GITHUB_TOKEN` desde el
   entorno (permiso de lectura/escritura en el repo) y nunca lo guarda en disco.
   También puedes preparar manualmente el host Android/NDK y las dependencias
   `debootstrap`, `qemu-user-static` y `binfmt-support`.
2. Genera el archivo de Ubuntu ARM64 con `bash rootfs/build-rootfs.sh`. Si una
   construcción se interrumpe, continúa con `bash rootfs/resume-rootfs.sh`; el
   empaquetador valida `dpkg --audit`, `dpkg --print-architecture` y el
   marcador antes de retirar el árbol expandido.
3. En este entorno de compilación, enlaza o copia
   `/opt/ubuntu-rootfs.tar.xz` a
   `app/src/main/assets/ubuntu-rootfs.tar.xz`. El archivo se ignora en Git por
   su tamaño; no se debe añadir el APK ni el rootfs comprimido a un commit.
4. Construye el APK ARM64 universal con
   `TERMUX_ABIS=arm64-v8a TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 ./gradlew --no-daemon :app:assembleDebug`.
   El build rechaza otras ABI porque el rootfs y PRoot incluidos son ARM64.

El rootfs incluye herramientas de desarrollo instaladas desde el archivo actual
de Ubuntu y versiones estables actuales de Node.js LTS, Go, Gradle y Rust; el
marcador `/etc/termux-ubuntu-rootfs` registra las versiones seleccionadas. La
extracción inicial necesita varios GB de almacenamiento libre. El APK debug se
firma con la clave de desarrollo del proyecto, no con una clave de publicación.
Antes de distribuirlo, instala y valida el APK en un dispositivo arm64 real,
incluidos teclado, insets, extra keys y login de Ubuntu.
