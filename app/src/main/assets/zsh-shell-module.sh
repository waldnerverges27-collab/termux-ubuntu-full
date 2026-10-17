#!/usr/bin/env bash
# Termux Zsh module installer. Invoked by the Android app inside Termux or Ubuntu PRoot.
set -Eeuo pipefail
umask 077
export GIT_TERMINAL_PROMPT=0

progress() {
    printf 'TERMUX_ZSH_PROGRESS:%s\n' "$1"
}

fail() {
    printf 'TERMUX_ZSH_ERROR:%s\n' "$*" >&2
    exit 1
}

TARGET="${1:-}"
ACTION="${2:-install}"

case "$TARGET" in
    termux)
        : "${PREFIX:=/data/data/com.termux/files/usr}"
        : "${HOME:=/data/data/com.termux/files/home}"
        MODULE_HOME="$HOME/.local/share/termux-zsh"
        ;;
    ubuntu)
        HOME=/root
        export HOME
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export PATH
        TMPDIR=/tmp
        export TMPDIR
        MODULE_HOME="$HOME/.local/share/termux-zsh"
        ;;
    *)
        fail "Unknown target environment: $TARGET"
        ;;
esac

PLUGIN_DIR="$MODULE_HOME/plugins"
START_MARKER='# >>> TERMUX ZSH MODULE >>>'
END_MARKER='# <<< TERMUX ZSH MODULE <<<'

write_text_atomically() {
    local destination="$1"
    local text="$2"
    local parent temporary
    parent="$(dirname "$destination")"
    mkdir -p "$parent"
    if [ -L "$destination" ] || { [ -e "$destination" ] && [ ! -f "$destination" ]; }; then
        fail "Refusing to replace the non-regular path $destination."
    fi
    temporary="$(mktemp "$parent/.termux-zsh.XXXXXX")"
    printf '%s\n' "$text" > "$temporary"
    mv -f "$temporary" "$destination"
}

ensure_plugin() {
    local name="$1"
    local repository="$2"
    local entrypoint="$3"
    local destination="$PLUGIN_DIR/$name"
    local temporary

    if [ -d "$destination/.git" ]; then
        if [ ! -e "$destination/$entrypoint" ]; then
            fail "The existing $name checkout is incomplete; it was left untouched at $destination."
        fi
        progress "$name is already present; keeping its installed revision"
        return
    fi
    if [ -e "$destination" ]; then
        fail "Refusing to overwrite the non-module path $destination. Move it aside and retry."
    fi

    temporary="$(mktemp -d "$PLUGIN_DIR/.${name}.XXXXXX")"
    if ! git clone --quiet --depth 1 --single-branch "$repository" "$temporary/repo"; then
        rm -rf -- "$temporary"
        fail "Could not download $name from $repository. Check the network and try again."
    fi
    if [ ! -e "$temporary/repo/$entrypoint" ]; then
        rm -rf -- "$temporary"
        fail "The upstream checkout for $name did not contain the expected file $entrypoint."
    fi
    mv "$temporary/repo" "$destination"
    rmdir "$temporary"
}

write_module_config() {
    mkdir -p "$MODULE_HOME" "$HOME/.cache/zsh"
    cat > "$MODULE_HOME/config.zsh" <<'TERMUX_ZSH_CONFIG'
# Managed by the Termux Zsh Shell module. Edit personal settings outside this file.
export HISTFILE="${HOME}/.zsh_history"
export HISTSIZE=100000
export SAVEHIST=100000
setopt autocd extended_history hist_ignore_all_dups hist_reduce_blanks share_history

TERMUX_ZSH_HOME="${HOME}/.local/share/termux-zsh"
TERMUX_ZSH_PLUGIN_DIR="${TERMUX_ZSH_HOME}/plugins"
export POWERLEVEL9K_DISABLE_CONFIGURATION_WIZARD=true

# Extra completion definitions must be on fpath before compinit.
fpath=("${TERMUX_ZSH_PLUGIN_DIR}/zsh-completions/src" $fpath)
autoload -Uz compinit
compinit -d "${HOME}/.cache/zsh/zcompdump-${ZSH_VERSION}"

# Theme and the small defer helper are loaded first.
[[ -r "${TERMUX_ZSH_PLUGIN_DIR}/powerlevel10k/powerlevel10k.zsh-theme" ]] && \
    source "${TERMUX_ZSH_PLUGIN_DIR}/powerlevel10k/powerlevel10k.zsh-theme"
[[ -r "${HOME}/.p10k.zsh" ]] && source "${HOME}/.p10k.zsh"
[[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-defer/zsh-defer.plugin.zsh" ]] && \
    source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-defer/zsh-defer.plugin.zsh"

# Widget and completion plugins are loaded after compinit; syntax highlighting stays last.
if [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-autopair/autopair.zsh" ]]; then
    source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-autopair/autopair.zsh"
    (( $+functions[autopair-init] )) && autopair-init
fi
if [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-history-substring-search/zsh-history-substring-search.zsh" ]]; then
    source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-history-substring-search/zsh-history-substring-search.zsh"
    bindkey '^[[A' history-substring-search-up
    bindkey '^[[B' history-substring-search-down
    bindkey '^[OA' history-substring-search-up
    bindkey '^[OB' history-substring-search-down
fi
[[ -r "${TERMUX_ZSH_PLUGIN_DIR}/fzf-tab/fzf-tab.plugin.zsh" ]] && \
    source "${TERMUX_ZSH_PLUGIN_DIR}/fzf-tab/fzf-tab.plugin.zsh"
[[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-autosuggestions/zsh-autosuggestions.zsh" ]] && \
    source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-autosuggestions/zsh-autosuggestions.zsh"

if (( $+functions[zsh-defer] )); then
    [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-you-should-use/you-should-use.plugin.zsh" ]] && \
        zsh-defer source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-you-should-use/you-should-use.plugin.zsh"
    [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-better-npm-completion/zsh-better-npm-completion.plugin.zsh" ]] && \
        zsh-defer source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-better-npm-completion/zsh-better-npm-completion.plugin.zsh"
else
    [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-you-should-use/you-should-use.plugin.zsh" ]] && \
        source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-you-should-use/you-should-use.plugin.zsh"
    [[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-better-npm-completion/zsh-better-npm-completion.plugin.zsh" ]] && \
        source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-better-npm-completion/zsh-better-npm-completion.plugin.zsh"
fi

# Keep this as the final plugin source to avoid wrapping its widgets incorrectly.
[[ -r "${TERMUX_ZSH_PLUGIN_DIR}/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]] && \
    source "${TERMUX_ZSH_PLUGIN_DIR}/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"
TERMUX_ZSH_CONFIG
}

integrate_zshrc() {
    local startup="$HOME/.zshrc"
    local temporary start_count end_count start_line end_line
    temporary="$(mktemp "$HOME/.zshrc.termux-zsh.XXXXXX")"

    if [ -L "$startup" ]; then
        rm -f -- "$temporary"
        fail "The existing $startup is a symbolic link; it was left untouched. Add the module source line to its target manually."
    fi
    if [ -e "$startup" ] && [ ! -f "$startup" ]; then
        rm -f -- "$temporary"
        fail "The existing $startup is not a regular file; it was left untouched."
    fi

    if [ -f "$startup" ]; then
        start_count="$(grep -Fxc -- "$START_MARKER" "$startup" || true)"
        end_count="$(grep -Fxc -- "$END_MARKER" "$startup" || true)"
        if [ "$start_count" -eq 0 ] && [ "$end_count" -eq 0 ]; then
            cat "$startup" > "$temporary"
        elif [ "$start_count" -eq 1 ] && [ "$end_count" -eq 1 ]; then
            start_line="$(grep -Fnx -- "$START_MARKER" "$startup" | cut -d: -f1)"
            end_line="$(grep -Fnx -- "$END_MARKER" "$startup" | cut -d: -f1)"
            if [ "$start_line" -ge "$end_line" ]; then
                rm -f -- "$temporary"
                fail "The managed Zsh block in $startup is malformed; the file was left untouched."
            fi
            awk -v start="$START_MARKER" -v end="$END_MARKER" '
                $0 == start { skipping = 1; next }
                $0 == end { skipping = 0; next }
                !skipping { print }
            ' "$startup" > "$temporary"
        else
            rm -f -- "$temporary"
            fail "The managed Zsh block in $startup is incomplete; the file was left untouched."
        fi
    else
        : > "$temporary"
    fi

    cat >> "$temporary" <<'TERMUX_ZSH_STARTUP'

# >>> TERMUX ZSH MODULE >>>
[[ -r "${HOME}/.local/share/termux-zsh/config.zsh" ]] && source "${HOME}/.local/share/termux-zsh/config.zsh"
# <<< TERMUX ZSH MODULE <<<
TERMUX_ZSH_STARTUP
    mv -f "$temporary" "$startup"
}

write_manifest() {
    local temporary plugin revision
    temporary="$(mktemp "$MODULE_HOME/.manifest.XXXXXX")"
    {
        printf 'module_version=1\n'
        printf 'zsh_version=%s\n' "$(zsh --version 2>/dev/null || true)"
        for plugin in powerlevel10k zsh-defer zsh-autosuggestions zsh-syntax-highlighting \
            zsh-history-substring-search zsh-completions fzf-tab zsh-you-should-use \
            zsh-autopair zsh-better-npm-completion; do
            revision="$(git -C "$PLUGIN_DIR/$plugin" rev-parse HEAD 2>/dev/null || true)"
            printf '%s=%s\n' "$plugin" "$revision"
        done
    } > "$temporary"
    mv -f "$temporary" "$MODULE_HOME/installed-versions.txt"
}

set_root_login_shell() {
    local shell_path="$1"
    local passwd_file=/etc/passwd
    local temporary
    [ -f "$passwd_file" ] || fail "Ubuntu does not have /etc/passwd; no shell default was changed."
    temporary="$(mktemp /etc/.passwd.termux-zsh.XXXXXX)"
    if ! awk -F: -v OFS=: -v shell="$shell_path" \
        '$1 == "root" { $7 = shell; found = 1 } { print } END { if (!found) exit 1 }' \
        "$passwd_file" > "$temporary"; then
        rm -f -- "$temporary"
        fail "Could not update the Ubuntu root shell in /etc/passwd."
    fi
    chmod 0644 "$temporary"
    mv -f "$temporary" "$passwd_file"
    if [ -f /etc/shells ] && ! grep -Fxq -- "$shell_path" /etc/shells; then
        printf '%s\n' "$shell_path" >> /etc/shells
    fi
}

install_modules() {
    if [ "$TARGET" = termux ]; then
        [ -x "$PREFIX/bin/pkg" ] || fail "Termux package manager was not found at $PREFIX/bin/pkg."
        progress "Refreshing Termux package indexes"
        pkg update -y
        progress "Installing Zsh, Git, fzf and certificates in Termux"
        pkg install -y zsh git fzf ca-certificates
    else
        [ -x /usr/bin/apt-get ] || fail "Ubuntu apt-get was not found."
        export DEBIAN_FRONTEND=noninteractive
        export APT_LISTCHANGES_FRONTEND=none
        progress "Refreshing Ubuntu package indexes"
        apt-get update
        progress "Installing Zsh, Git, fzf and certificates in Ubuntu"
        apt-get install -y --no-install-recommends zsh git fzf ca-certificates
    fi

    [ -n "$(command -v zsh || true)" ] || fail "Zsh was not available after package installation."
    [ -n "$(command -v git || true)" ] || fail "Git was not available after package installation."
    mkdir -p "$PLUGIN_DIR"

    progress "Installing Powerlevel10k theme"
    ensure_plugin powerlevel10k https://github.com/romkatv/powerlevel10k.git powerlevel10k.zsh-theme
    progress "Installing zsh-defer"
    ensure_plugin zsh-defer https://github.com/romkatv/zsh-defer.git zsh-defer.plugin.zsh
    progress "Installing zsh-autosuggestions"
    ensure_plugin zsh-autosuggestions https://github.com/zsh-users/zsh-autosuggestions.git zsh-autosuggestions.zsh
    progress "Installing zsh-syntax-highlighting"
    ensure_plugin zsh-syntax-highlighting https://github.com/zsh-users/zsh-syntax-highlighting.git zsh-syntax-highlighting.zsh
    progress "Installing zsh-history-substring-search"
    ensure_plugin zsh-history-substring-search https://github.com/zsh-users/zsh-history-substring-search.git zsh-history-substring-search.zsh
    progress "Installing zsh-completions"
    ensure_plugin zsh-completions https://github.com/zsh-users/zsh-completions.git src
    progress "Installing fzf-tab"
    ensure_plugin fzf-tab https://github.com/Aloxaf/fzf-tab.git fzf-tab.plugin.zsh
    progress "Installing zsh-you-should-use"
    ensure_plugin zsh-you-should-use https://github.com/MichaelAquilina/zsh-you-should-use.git you-should-use.plugin.zsh
    progress "Installing zsh-autopair"
    ensure_plugin zsh-autopair https://github.com/hlissner/zsh-autopair.git autopair.zsh
    progress "Installing zsh-better-npm-completion"
    ensure_plugin zsh-better-npm-completion https://github.com/lukechilds/zsh-better-npm-completion.git zsh-better-npm-completion.plugin.zsh

    write_module_config
    integrate_zshrc
    write_manifest

    if [ "$TARGET" = termux ]; then
        write_text_atomically "$HOME/.termux/shell" zsh
    else
        local zsh_path
        zsh_path="$(command -v zsh)"
        set_root_login_shell "$zsh_path"
        mkdir -p /etc/termux-zsh
        write_text_atomically /etc/termux-zsh/default-shell zsh
    fi
}

restore_bash_default() {
    if [ "$TARGET" = termux ]; then
        [ -n "$(command -v bash || true)" ] || fail "Termux Bash was not found; its default was not changed."
        write_text_atomically "$HOME/.termux/shell" bash
    else
        [ -n "$(command -v bash || true)" ] || fail "Ubuntu Bash was not found; its default was not changed."
        set_root_login_shell /bin/bash
        mkdir -p /etc/termux-zsh
        write_text_atomically /etc/termux-zsh/default-shell bash
    fi
}

case "$ACTION" in
    install)
        progress "Preparing $TARGET Zsh environment"
        install_modules
        progress "Zsh and the requested theme/plugins are ready in $TARGET"
        ;;
    restore-bash)
        progress "Restoring Bash as the default in $TARGET (Zsh files will be kept)"
        restore_bash_default
        progress "Bash is now the default in $TARGET"
        ;;
    *)
        fail "Unknown action: $ACTION"
        ;;
esac
