package com.alpineagent;

import org.json.*;

/** Loop do agente: pensa -> chama ferramentas -> observa -> repete. */
class Agent {
    interface Log { void log(String s); }

    final Tools tools;
    final Skills skills;
    final Log log;
    JSONObject provider;
    boolean voice;      // usuário fala e ouve a resposta
    boolean scheduled;  // execução sem usuário (tarefa agendada)
    final JSONArray history = new JSONArray();

    Agent(Tools t, Skills s, Log l) { tools = t; skills = s; log = l; }

    String run(String user) throws Exception {
        if (provider == null) throw new IllegalStateException("Nenhum provedor configurado");
        log.log("[" + provider.optString("name") + " · " + provider.optString("model") + "]\n");
        tools.mcp.refresh();
        for (String w : tools.mcp.report) log.log("⚠ MCP " + w + "\n");
        history.put(new JSONObject().put("role", "user").put("content", user));
        String system = "Você é um agente autônomo rodando em um celular Android, falando português.\n"
            + "Você pode executar comandos em um Linux Alpine (ferramenta shell), controlar o celular "
            + "(phone_*, open_app), usar skills, ferramentas MCP e agendar tarefas (schedule_task).\n"
            + "Para controlar apps: use phone_screen para ver a tela, depois toque/digite, e confira o resultado.\n"
            + "Ao aprender um procedimento útil, salve com write_skill.\n"
            + "Skills disponíveis (use read_skill para detalhes):\n" + skills.index();
        if (tools.daytona.configured()) system += "\nA ferramenta shell roda num sandbox Linux na nuvem (Daytona; arquivos: daytona_*). Para algo que precisa estar no celular (Alpine local, MCP stdio), use local_shell.";
        if (voice) system += "\nO usuário fala por voz e ouve sua resposta: responda curto (1 a 3 frases), em fala natural, sem markdown, listas, emojis ou URLs.";
        if (scheduled) system += "\nEsta é uma tarefa agendada e ninguém está acompanhando: execute até o fim sem fazer perguntas e termine com um resumo curto do resultado.";
        for (int step = 0; step < 30; step++) {
            JSONObject r = Llm.call(provider, system, history, tools.all());
            JSONArray content = r.getJSONArray("content");
            history.put(new JSONObject().put("role", "assistant").put("content", content));
            JSONArray results = new JSONArray();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.getJSONObject(i);
                if (b.getString("type").equals("text")) {
                    text.append(b.getString("text"));
                } else if (b.getString("type").equals("tool_use")) {
                    log.log("🔧 " + b.getString("name") + " " + b.getJSONObject("input") + "\n");
                    String out;
                    try { out = tools.execute(b.getString("name"), b.getJSONObject("input")); }
                    catch (Exception e) { out = "erro: " + e; }
                    log.log("→ " + (out.length() > 400 ? out.substring(0, 400) + "…" : out) + "\n");
                    results.put(new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", b.getString("id")).put("content", out));
                }
            }
            if (results.length() == 0) return text.toString();
            history.put(new JSONObject().put("role", "user").put("content", results));
        }
        return "(limite de passos atingido)";
    }
}
