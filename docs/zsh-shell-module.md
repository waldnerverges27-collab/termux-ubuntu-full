# Módulo Zsh para Termux y Ubuntu

El ajuste **Ajustes → Zsh Shell** instala Zsh en los dos entornos y muestra el estado individual del tema y de cada complemento. La instalación requiere conexión a Internet: usa `pkg` en Termux y `apt` en Ubuntu para Zsh, Git, fzf y certificados; después clona las fuentes oficiales de los complementos.

## Comportamiento

- Las sesiones nuevas del **Termux anfitrión** usan el shell elegido en `~/.termux/shell`. El módulo selecciona Zsh y conserva Bash como opción recuperable.
- El arranque normal sigue entrando automáticamente en **Ubuntu**. El lanzador PRoot usa Zsh cuando `/etc/termux-zsh/default-shell` indica `zsh`; si Zsh no está disponible, vuelve a Bash.
- El usuario root de Ubuntu también queda configurado con Zsh en `/etc/passwd`.
- **Make Bash the default / Usar Bash como predeterminado** cambia ambos valores cuando sus entornos están disponibles. No desinstala Zsh ni borra complementos, configuraciones o datos.
- Failsafe continúa ejecutando `/system/bin/sh` de Android y no lee ninguna preferencia de Zsh.
- Cambiar el predeterminado no sustituye el shell de las sesiones que ya están abiertas; crea una sesión nueva.
- La preparación de Ubuntu reutiliza un rootfs ARM64 válido. No se vuelve a extraer el rootfs para instalar este módulo.
- El instalador conserva el contenido de `~/.zshrc` fuera de su bloque delimitado. Si el archivo es un enlace simbólico, lo deja intacto y pide integrar la línea de carga manualmente.

Los clones iniciales son superficiales y se guarda su commit en `~/.local/share/termux-zsh/installed-versions.txt` en cada entorno. Si una carpeta de complemento ya existe, el instalador la conserva sin sobrescribirla; las actualizaciones de esos clones se hacen por separado.

## Tema y complementos

**Tema:** `powerlevel10k` — <https://github.com/romkatv/powerlevel10k>

- `zsh-defer` — <https://github.com/romkatv/zsh-defer>
- `zsh-autosuggestions` — <https://github.com/zsh-users/zsh-autosuggestions>
- `zsh-syntax-highlighting` — <https://github.com/zsh-users/zsh-syntax-highlighting>
- `zsh-history-substring-search` — <https://github.com/zsh-users/zsh-history-substring-search>
- `zsh-completions` — <https://github.com/zsh-users/zsh-completions>
- `fzf-tab` — <https://github.com/Aloxaf/fzf-tab>
- `zsh-you-should-use` — <https://github.com/MichaelAquilina/zsh-you-should-use>
- `zsh-autopair` — <https://github.com/hlissner/zsh-autopair>
- `zsh-better-npm-completion` — <https://github.com/lukechilds/zsh-better-npm-completion>
