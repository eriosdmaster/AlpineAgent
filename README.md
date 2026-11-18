# Alpine Agent (Android / Java)

Agente de IA com: loop de ferramentas (vários provedores), terminal Alpine Linux (proot), controle do celular
(AccessibilityService), skills (files/skills/<nome>/SKILL.md), cliente MCP (HTTP e stdio dentro do Alpine),
tarefas agendadas e voz.

## Montar
1. Baixe um **proot estático aarch64** e salve como `app/src/main/assets/proot` (sem extensão).
2. Abra no Android Studio (JDK 17), rode em um celular arm64.
3. No app: *Instalar Alpine* → *Acessibilidade* (ativar "Alpine Agent") → *Provedores* (escolha e cole a chave).

## Observações
- `targetSdk 28` é necessário para executar o proot a partir do armazenamento do app (igual ao Termux). Não serve para Play Store.
- Sem proot/Alpine, o terminal cai para o `sh` do Android.
- A chave da API fica em SharedPreferences no aparelho. Para produção, use um backend/proxy.
- Ideia de extensão: PTY real.

## Provedores
Presets: Anthropic, OpenAI, DeepSeek, Kimi (Moonshot), NVIDIA NIM, OpenRouter, Groq, Ollama/LM Studio.
Qualquer API compatível com OpenAI (`/chat/completions`) funciona em **+ Custom** (tipo "openai" + Base URL + chave + modelo;
headers extras em JSON, ex.: `{"X-Foo":"bar"}`). Tipo "anthropic" usa `/messages`. O modelo precisa suportar tool calling.
Kimi na China: use `https://api.moonshot.cn/v1`. Troque de provedor no meio da conversa: o histórico é convertido.

## MCP (HTTP e stdio) — botão *MCP*
Editor de `files/mcp.json`. Servidor com `url` = HTTP; com `command` = **stdio, rodando dentro do Alpine**:
```json
{"servers":{
  "remoto":{"url":"https://seu-servidor/mcp","headers":{"Authorization":"Bearer ..."}},
  "arquivos":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/root"],"env":{"CHAVE":"valor"}}
}}
```
- Instale o runtime antes, no terminal: `apk add --no-cache nodejs npm` (ou `python3 py3-pip`).
- O processo fica vivo entre as mensagens e é reiniciado se a config mudar ou ele cair. A 1ª partida de um `npx` pode demorar (até 90 s).
- `"disabled": true` desliga um servidor sem apagar. Erros de partida aparecem no log do chat (`⚠ MCP ...`, com o stderr).

## Tarefas agendadas — botão *Tarefas*
Formatos de horário: `daily 08:00` · `every 30` (minutos, mín. 5; `every 2h` também vale) · `once 2026-12-01 09:00`.
O agente também agenda sozinho: "todo dia às 8h resuma X" usa a ferramenta `schedule_task` (e `list_tasks`/`delete_task`).
Cada disparo roda o agente com o provedor ativo, sem histórico, e mostra o resultado numa notificação
(*Tarefas → tocar na tarefa → Ver último resultado*; log completo em `files/tasks.log`).
Alarmes sobrevivem a reboot. Para não serem adiados, desative a otimização de bateria do app (Ajustes → Apps → Alpine Agent → Bateria → Sem restrições).
O controle do celular nas tarefas exige a acessibilidade ligada e a tela desbloqueada.

## Voz
- `Voz: on/off` — fala as respostas (TTS pt-BR).
- 🎤 — modo conversa: ouve → agente → fala → ouve de novo. ⏹ para parar (encerra sozinho após 3 silêncios seguidos).
- Usa o reconhecimento do sistema (Google); pede a permissão de microfone na 1ª vez.

## Daytona (sandbox na nuvem) — botão *Daytona*
Cole a API key (app.daytona.io → API Keys). Com a key salva, o agente ganha `daytona_write_file`,
`daytona_read_file` e `daytona_list_files`, e o `shell` passa a rodar nele: um Linux na nuvem (root, internet, rápido) para builds, instalações e scripts
pesados, sem as limitações do proot. O sandbox é criado no 1º uso, reaproveitado, religado se auto-parar (15 min por padrão)
e pode ser apagado no mesmo botão. Sandboxes consomem crédito: apague os que não usa mais.
Para self-hosted, troque a API URL e o proxy URL.

## Build no GitHub (Actions) — `.github/workflows/build.yml`
Compila **debug e release**, ambos assinados com a sua keystore, e publica os APKs como artefato (e como Release ao subir uma tag `v*`).
1. `sh scripts/gen-keystore.sh` (gera `alpineagent.jks` e imprime os valores dos secrets). Guarde o `.jks`; não commite.
2. No repositório: Settings → Secrets and variables → Actions → crie `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. (Opcional) secret `PROOT_URL` = link direto de um proot estático aarch64; ou commite o binário em `app/src/main/assets/proot` (o `.gitignore` o ignora por padrão: remova a linha se quiser commitar).
4. Push em `main`/`master`, PR, "Run workflow" manual, ou `git tag v1.0 && git push --tags` para gerar o Release.
Localmente: `KEYSTORE_FILE=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... gradle assembleRelease` (sem `KEYSTORE_FILE`, o debug usa a keystore padrão do Android e o release sai sem assinatura).

## Qual shell o agente usa
Com a API key do Daytona salva, `shell` roda no sandbox na nuvem (e cai para o Alpine local se o Daytona estiver fora do ar); sem a key, `shell` é o Alpine local. Com Daytona ativo, `local_shell` continua dando acesso ao Alpine do celular.
