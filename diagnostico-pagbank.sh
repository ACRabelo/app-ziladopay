#!/bin/bash
# Coleta o diagnostico do login PagBank: rede (Mac e celular) e os logs internos da biblioteca.
# Nao precisa mexer no celular. Uso:  bash diagnostico-pagbank.sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG="br.com.totem.prototipo"
URL="https://ws.mobile.pagseguro.uol.com.br/setup/"
FMT="http=%{http_code} ip=%{remote_ip} dns=%{time_namelookup}s conexao=%{time_connect}s tls=%{time_appconnect}s total=%{time_total}s\n"
cd "$(dirname "$0")" || exit 1

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "Celular nao encontrado. Conecte o S25 no USB com a depuracao ativa."; exit 1
fi
echo "Coletando (leva cerca de 1 minuto)..."

{
  echo "== $(date)"
  echo "== [Mac] GET por IPv4"
  curl -4 -sS -m 30 -o /dev/null -w "$FMT" "$URL" 2>&1
  echo "== [Mac] GET por IPv6"
  curl -6 -sS -m 30 -o /dev/null -w "$FMT" "$URL" 2>&1
  echo "== [Mac] POST vazio (sem nenhum dado seu), inicio da resposta"
  curl -4 -sS -m 30 -X POST -H "Content-Type: application/json" -H "Accept: application/json" -d "{}" \
       -w "\n$FMT" "$URL" 2>&1 | head -c 700
  echo
  echo "== [Celular] por onde sai para a internet"
  "$ADB" shell "ip route get 1.1.1.1 2>&1 | head -1"
  echo "== [Celular] DNS privado (modo / servidor)"
  "$ADB" shell "settings get global private_dns_mode; settings get global private_dns_specifier"
  echo "== [Celular] conexao TCP na porta 443 do servidor"
  "$ADB" shell "time toybox nc -z -w 15 ws.mobile.pagseguro.uol.com.br 443; echo rc=\$?" 2>&1
  echo "== [Celular] arquivos internos do app"
  "$ADB" shell "run-as $PKG find . -type f -not -path './code_cache/*' -not -path './cache/WebView/*' -not -path './app_webview/*' -exec ls -la {} +" 2>&1 | head -200
} > diagnostico.txt

: > lib-logs.txt
"$ADB" shell "run-as $PKG find . -type f \( -name 'PSLOG*' -o -name '*.log' -o -name '*.LOG' -o -name 'EVT*' \) -not -path './app_webview/*' -not -path './cache/WebView/*'" 2>/dev/null | tr -d '\r' | while read -r f; do
  [ -z "$f" ] && continue
  echo "===== $f" >> lib-logs.txt
  "$ADB" exec-out "run-as $PKG tail -c 200000 '$f'" < /dev/null >> lib-logs.txt 2>&1
  echo >> lib-logs.txt
done

echo "Pronto: diagnostico.txt e lib-logs.txt ($(wc -c < lib-logs.txt | tr -d ' ') bytes) salvos. Avise o Claude."
