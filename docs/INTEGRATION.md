# Integración del rootfs en Termux (plan técnico)

## Objetivo
Termux arranca automáticamente en Ubuntu (proot) con el rootfs preinstalado.

## Pasos

### 1. Añadir el rootfs como asset
Copiar `ubuntu-rootfs.tar.xz` a `app/src/main/assets/ubuntu-rootfs.tar.xz`.
(Para APK sideload el tamaño es aceptable; para Play Store usar descarga en primer arranque.)

### 2. Extraer en primer arranque
Modificar `TermuxInstaller.setupBootstrapIfNeeded()` para, tras instalar el bootstrap de Termux,
extraer el rootfs a `$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu` si no existe.
Usar un ProgressDialog y descomprimir en un hilo (TarInputStream o ProcessBuilder con tar).

### 3. Proveer proot
El bootstrap de Termux no incluye `proot`. Opciones:
- Añadir el binario `proot` (compilado para Android, del paquete termux `proot`) al bootstrap zip.
- O descargarlo en primer arranque desde el repo de Termux.

### 4. Arranque automático en Ubuntu
En `TermuxService` / creación de sesión, si existe
`$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu`, ejecutar:
`proot-distro login ubuntu` (o `proot -r ... /bin/bash -l`) en lugar del shell por defecto.
Añadir preferencia para desactivar auto-arranque.

### 5. Variables de entorno
Dentro del proot, `/etc/profile.d/*.sh` ya configura PATH, GOROOT, RUSTUP_HOME, etc.
Asegurar `TERM=xterm-256color`, `LANG=en_US.UTF-8`.

## Limitaciones de proot
- No hay systemd, docker daemon, módulos de kernel.
- Rendimiento de I/O menor que nativo.
