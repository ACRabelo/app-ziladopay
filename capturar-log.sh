#!/bin/bash
# Captura o log do app Totem durante um teste e checa se o servidor do PagBank responde.
# Uso:  bash capturar-log.sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG="br.com.totem.prototipo"
cd "$(dirname "$0")" || exit 1

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "Celular nao encontrado. Conecte o S25 no USB com a depuracao ativa."; exit 1
fi

{
  echo "== $(date)"
  echo "== Servidor de login do PagBank, visto deste Mac"
  curl -sS -m 25 -o /dev/null \
    -w "http=%{http_code} ip=%{remote_ip} dns=%{time_namelookup}s conexao=%{time_connect}s tls=%{time_appconnect}s total=%{time_total}s\n" \
    https://ws.mobile.pagseguro.uol.com.br/setup/ 2>&1
  echo "== Mesmo servidor, visto do celular"
  "$ADB" shell "ping -c 2 -W 5 ws.mobile.pagseguro.uol.com.br" 2>&1 | tail -4
} > diagnostico.txt

"$ADB" logcat -c
echo
echo "Log limpo. Agora, no celular:"
echo "  1) abra o Diagnostico"
echo "  2) toque em 'Testar Bluetooth' e espere o resultado"
echo "  3) toque em 'Login PagBank', entre, e ESPERE a mensagem de erro (pode levar 1 a 2 minutos)"
echo
read -r -p "Quando a mensagem de erro aparecer na tela, aperte ENTER aqui... "

PID=$("$ADB" shell pidof -s "$PKG" | tr -d '\r')
if [ -z "$PID" ]; then echo "O app esta fechado. Abra o app e rode de novo."; exit 1; fi
"$ADB" logcat -d --pid="$PID" > logcat.txt
echo "Pronto: logcat.txt ($(wc -l < logcat.txt | tr -d ' ') linhas) e diagnostico.txt salvos. Avise o Claude."
