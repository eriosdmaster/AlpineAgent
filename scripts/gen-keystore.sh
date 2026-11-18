#!/bin/sh
# Gera a keystore própria e imprime os valores dos secrets do GitHub.
# Uso: sh scripts/gen-keystore.sh   (precisa do keytool/JDK)
set -e
ALIAS="${KEY_ALIAS:-alpineagent}"
FILE="alpineagent.jks"
printf "Senha da keystore (mín. 6 caracteres): "; stty -echo 2>/dev/null || true; read PASS; stty echo 2>/dev/null || true; echo
keytool -genkeypair -v -keystore "$FILE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Alpine Agent, O=Alpine Agent, C=BR"
echo
echo "Crie estes secrets em GitHub > Settings > Secrets and variables > Actions:"
echo "  KEYSTORE_PASSWORD = (a senha que você digitou)"
echo "  KEY_PASSWORD      = (a mesma senha)"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEYSTORE_BASE64   = (conteúdo abaixo)"
echo
base64 -w0 "$FILE" 2>/dev/null || base64 "$FILE" | tr -d '\n'
echo
echo
echo "GUARDE $FILE em local seguro (sem ele não dá para atualizar o app instalado). NÃO commite."
