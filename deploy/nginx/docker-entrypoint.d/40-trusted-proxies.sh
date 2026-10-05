#!/bin/sh
# Gera a configuração de proxies confiáveis do nginx a partir de TRUSTED_PROXY_CIDRS, antes de o nginx subir.
#
#   TRUSTED_PROXY_CIDRS vazio (padrão): ninguém é proxy confiável. O nginx é a borda: X-Forwarded-For e
#     X-Forwarded-Proto enviados pelo cliente são IGNORADOS e o IP do cliente é o da conexão.
#   TRUSTED_PROXY_CIDRS="10.0.0.0/8,172.29.88.0/24": somente conexões vindas desses endereços (o balanceador do
#     provedor, ou o Caddy do compose.https.yaml) podem informar o IP real (X-Forwarded-For) e o esquema
#     (X-Forwarded-Proto). Com real_ip_recursive, vale o primeiro endereço NÃO confiável lido da direita para a
#     esquerda: valores forjados pelo cliente, à esquerda, nunca são usados.
#
# Falha fechada: um valor inválido ou que aceite qualquer origem (/0 a /7 em IPv4, /0 a /15 em IPv6, 0.0.0.0, ::)
# derruba o container na subida em vez de confiar em todo mundo.
set -eu

http_conf=/etc/nginx/trusted_proxies_http.conf
server_conf=/etc/nginx/real_ip.conf
raw="${TRUSTED_PROXY_CIDRS:-}"

fail() { echo "40-trusted-proxies: TRUSTED_PROXY_CIDRS inválido: $1" >&2; exit 1; }

valid_ipv4() {
    ip="$1"
    echo "$ip" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || return 1
    old_ifs="$IFS"; IFS=.
    # shellcheck disable=SC2086
    set -- $ip
    IFS="$old_ifs"
    for octet in "$@"; do [ "$octet" -le 255 ] || return 1; done
}

entries=""
for item in $(echo "$raw" | tr ',' ' '); do
    case "$item" in
        */*) addr="${item%/*}"; prefix="${item#*/}"
             [ -n "$prefix" ] || fail "'$item' (prefixo vazio)" ;;
        *)   addr="$item"; prefix="" ;;
    esac
    echo "$prefix" | grep -Eq '^[0-9]*$' || fail "'$item' (prefixo não numérico)"
    case "$addr" in
        *:*)
            echo "$addr" | grep -Eq '^[0-9a-fA-F:]+$' || fail "'$item' (IPv6 inválido)"
            [ "$addr" != "::" ] || fail "'$item' (aceita qualquer origem)"
            [ -n "$prefix" ] || prefix=128
            [ "$prefix" -ge 16 ] && [ "$prefix" -le 128 ] || fail "'$item' (prefixo IPv6 deve ser de 16 a 128)"
            ;;
        *)
            valid_ipv4 "$addr" || fail "'$item' (IPv4 inválido)"
            [ "$addr" != "0.0.0.0" ] || fail "'$item' (aceita qualquer origem)"
            [ -n "$prefix" ] || prefix=32
            [ "$prefix" -ge 8 ] && [ "$prefix" -le 32 ] || fail "'$item' (prefixo IPv4 deve ser de 8 a 32; /0 aceitaria qualquer origem)"
            ;;
    esac
    entries="$entries $addr/$prefix"
done

if [ -z "$entries" ]; then
    # Nenhum proxy confiável: o cabeçalho X-Forwarded-Proto do cliente é ignorado.
    cat > "$http_conf" <<'CONF'
map $http_x_forwarded_proto $forwarded_proto { default $scheme; }
CONF
    echo "# nenhum proxy confiável (TRUSTED_PROXY_CIDRS vazio): o IP do cliente é o da conexão" > "$server_conf"
    echo "40-trusted-proxies: nenhum proxy confiável; X-Forwarded-* do cliente serão ignorados."
    exit 0
fi

{
    echo '# gerado por 40-trusted-proxies.sh'
    echo 'geo $realip_remote_addr $from_trusted_proxy {'
    echo '    default 0;'
    for entry in $entries; do echo "    $entry 1;"; done
    echo '}'
    echo 'map "$from_trusted_proxy:$http_x_forwarded_proto" $forwarded_proto {'
    echo '    default $scheme;'
    echo '    "1:https" https;'
    echo '    "1:http" http;'
    echo '}'
} > "$http_conf"

{
    echo '# gerado por 40-trusted-proxies.sh'
    for entry in $entries; do echo "set_real_ip_from $entry;"; done
    echo 'real_ip_header X-Forwarded-For;'
    echo 'real_ip_recursive on;'
} > "$server_conf"
echo "40-trusted-proxies: proxies confiáveis:$entries"
