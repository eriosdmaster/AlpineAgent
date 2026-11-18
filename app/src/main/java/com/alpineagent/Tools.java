package com.alpineagent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.json.*;

/** Ferramentas expostas ao modelo: shell Alpine, controle do celular, skills e MCP. */
class Tools {
    final Context ctx;
    final Alpine alpine;
    final Skills skills;
    final Mcp mcp;
    final Scheduler scheduler;
    final Daytona daytona;

    Tools(Context c, Alpine a, Skills s, Mcp m, Scheduler sc, Daytona d) { ctx = c; alpine = a; skills = s; mcp = m; scheduler = sc; daytona = d; }

    /** props no formato "nome:tipo:descrição" */
    static JSONObject def(String name, String desc, String... props) {
        try {
            JSONObject p = new JSONObject();
            JSONArray req = new JSONArray();
            for (String s : props) {
                String[] x = s.split(":", 3);
                p.put(x[0], new JSONObject().put("type", x[1]).put("description", x[2]));
                req.put(x[0]);
            }
            return new JSONObject().put("name", name).put("description", desc)
                .put("input_schema", new JSONObject().put("type", "object").put("properties", p).put("required", req));
        } catch (JSONException e) { throw new RuntimeException(e); }
    }

    JSONArray all() {
        JSONArray a = new JSONArray();
        if (daytona.configured())
            a.put(def("shell", "Executa um comando bash num sandbox Linux na nuvem (Daytona): rápido, com internet e root; persiste entre chamadas (use `cd` no comando; até 5 min). Se o Daytona estiver indisponível, cai para o Alpine local.", "command:string:comando bash"));
        else
            a.put(def("shell", "Executa um comando sh no terminal Alpine Linux e retorna a saída", "command:string:comando"));
        a.put(def("phone_screen", "Lista os elementos visíveis na tela do celular com coordenadas (x,y) do centro"));
        a.put(def("phone_tap", "Toca em uma coordenada da tela", "x:integer:coordenada x", "y:integer:coordenada y"));
        a.put(def("phone_swipe", "Desliza na tela", "x1:integer:x inicial", "y1:integer:y inicial",
            "x2:integer:x final", "y2:integer:y final", "ms:integer:duração em ms"));
        a.put(def("phone_click_text", "Clica no elemento que contém o texto", "text:string:texto do elemento"));
        a.put(def("phone_type", "Digita texto no campo focado", "text:string:texto"));
        a.put(def("phone_global", "Ação global: back, home, recents ou notifications", "action:string:back|home|recents|notifications"));
        a.put(def("open_app", "Abre um app pelo nome ou pacote", "app:string:nome ou pacote"));
        a.put(def("read_skill", "Lê as instruções completas de uma skill", "name:string:nome da skill"));
        a.put(def("write_skill", "Cria/atualiza uma skill (SKILL.md com cabeçalho description:)",
            "name:string:nome da skill", "content:string:conteúdo markdown"));
        a.put(def("schedule_task", "Agenda uma tarefa (recorrente ou única) que o agente executará sozinho depois. Formatos de when: " + Scheduler.FORMATS,
            "name:string:nome curto", "prompt:string:instrução completa e autossuficiente (não depende desta conversa)", "when:string:quando executar"));
        a.put(def("list_tasks", "Lista as tarefas agendadas"));
        a.put(def("delete_task", "Remove uma tarefa agendada", "id:string:id da tarefa"));
        if (daytona.configured()) {
            a.put(def("local_shell", "Executa um comando sh no Alpine Linux LOCAL do celular (use para o que precisa estar no aparelho)", "command:string:comando"));
            a.put(def("daytona_write_file", "Cria/sobrescreve um arquivo de texto no sandbox Daytona", "path:string:caminho no sandbox", "content:string:conteúdo"));
            a.put(def("daytona_read_file", "Lê um arquivo de texto do sandbox Daytona", "path:string:caminho no sandbox"));
            a.put(def("daytona_list_files", "Lista arquivos de um diretório do sandbox Daytona", "path:string:diretório (ex.: /home/daytona)"));
        }
        JSONArray m = mcp.tools();
        for (int i = 0; i < m.length(); i++) a.put(m.opt(i));
        return a;
    }

    String execute(String name, JSONObject in) throws Exception {
        if (name.startsWith("mcp__")) return mcp.call(name, in);
        switch (name) {
            case "shell": {
                if (!daytona.configured()) return alpine.exec(in.getString("command"), 120);
                try { return daytona.exec(in.getString("command")); }
                catch (Exception e) { return "[Daytona indisponível: " + e.getMessage() + " — rodando no Alpine local]\n" + alpine.exec(in.getString("command"), 120); }
            }
            case "local_shell": return alpine.exec(in.getString("command"), 120);
            case "read_skill": return skills.read(in.getString("name"));
            case "write_skill": skills.write(in.getString("name"), in.getString("content")); return "ok";
            case "open_app": return openApp(in.getString("app"));
            case "schedule_task": {
                JSONObject t = scheduler.add(in.getString("name"), in.getString("prompt"), in.getString("when"));
                return "agendada id=" + t.getString("id") + " (" + t.getString("when") + "), próxima: " + scheduler.nextStr(t);
            }
            case "list_tasks": {
                JSONArray a = scheduler.list();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.getJSONObject(i);
                    sb.append(t.getString("id")).append(" | ").append(t.optString("name")).append(" | ").append(t.optString("when"))
                      .append(" | próxima: ").append(scheduler.nextStr(t)).append('\n');
                }
                return sb.length() == 0 ? "nenhuma tarefa" : sb.toString();
            }
            case "daytona_write_file": return daytona.write(in.getString("path"), in.getString("content"));
            case "daytona_read_file": return daytona.read(in.getString("path"));
            case "daytona_list_files": return daytona.list(in.getString("path"));
            case "delete_task": return scheduler.remove(in.getString("id")) ? "ok" : "tarefa não encontrada";
        }
        PhoneService s = PhoneService.I;
        if (s == null) return "Serviço de acessibilidade desativado. Peça ao usuário para ativar em Ajustes > Acessibilidade > Alpine Agent.";
        switch (name) {
            case "phone_screen": return s.dump();
            case "phone_tap": return "" + s.tap(in.getInt("x"), in.getInt("y"));
            case "phone_swipe": return "" + s.swipe(in.getInt("x1"), in.getInt("y1"), in.getInt("x2"), in.getInt("y2"), in.getInt("ms"));
            case "phone_click_text": return "" + s.clickText(in.getString("text"));
            case "phone_type": return "" + s.type(in.getString("text"));
            case "phone_global": return "" + s.global(in.getString("action"));
        }
        return "ferramenta desconhecida: " + name;
    }

    private String openApp(String q) {
        PackageManager pm = ctx.getPackageManager();
        Intent m = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(m, 0)) {
            String label = r.loadLabel(pm).toString(), pkg = r.activityInfo.packageName;
            if (pkg.equals(q) || label.toLowerCase().contains(q.toLowerCase())) {
                Intent i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) continue;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Context c = PhoneService.I != null ? PhoneService.I : ctx;
                c.startActivity(i);
                return "aberto: " + label + " (" + pkg + ")";
            }
        }
        return "app não encontrado";
    }
}
