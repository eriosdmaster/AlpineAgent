package com.alpineagent;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Cliente MCP. Config em files/mcp.json:
 * {"servers":{
 *   "remoto":{"url":"https://...","headers":{"Authorization":"Bearer ..."}},
 *   "local":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/root"],"env":{"K":"V"}}
 * }}
 * Servidores com "command" (stdio) rodam DENTRO do Alpine e ficam vivos entre as execuções.
 * Use "disabled": true para desligar um servidor sem apagá-lo.
 */
class Mcp {
    final File cfg;
    final Alpine alpine;
    final Map<String, JSONObject> servers = new LinkedHashMap<>();
    final Map<String, String> sessions = new HashMap<>();
    final Map<String, String[]> index = new HashMap<>();
    final Map<String, McpStdio> procs = new HashMap<>();
    final Map<String, String> procCfg = new HashMap<>();
    final List<String> report = new ArrayList<>();   // erros da última atualização
    JSONArray defs = new JSONArray();
    int id = 0;

    Mcp(Context c, Alpine a) { cfg = new File(c.getFilesDir(), "mcp.json"); alpine = a; }

    synchronized JSONArray tools() { return defs; }

    private static boolean off(JSONObject s, String name) {
        JSONObject o = s.optJSONObject(name);
        return o == null || o.optBoolean("disabled", false);
    }

    private static JSONObject initParams() throws JSONException {
        return new JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject().put("name", "alpine-agent").put("version", "1.0"));
    }

    synchronized void refresh() {
        servers.clear(); sessions.clear(); index.clear(); report.clear(); defs = new JSONArray();
        JSONObject s = null;
        try {
            if (cfg.exists())
                s = new JSONObject(new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8)).optJSONObject("servers");
        } catch (Exception e) { report.add("mcp.json inválido: " + e.getMessage()); }

        // encerra processos de servidores removidos/desativados
        for (Iterator<Map.Entry<String, McpStdio>> it = procs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, McpStdio> e = it.next();
            if (s == null || off(s, e.getKey())) { e.getValue().close(); procCfg.remove(e.getKey()); it.remove(); }
        }
        if (s == null) return;

        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String name = it.next();
            try {
                if (off(s, name)) continue;
                JSONObject sc = s.getJSONObject(name);
                servers.put(name, sc);
                if (sc.has("command")) {
                    ensureStdio(name, sc);
                } else {
                    rpc(name, "initialize", initParams(), false, 60);
                    rpc(name, "notifications/initialized", new JSONObject(), true, 60);
                }
                JSONArray ts = rpc(name, "tools/list", new JSONObject(), false, 60)
                    .getJSONObject("result").getJSONArray("tools");
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject t = ts.getJSONObject(i);
                    String full = ("mcp__" + name + "__" + t.getString("name")).replaceAll("[^a-zA-Z0-9_-]", "_");
                    if (full.length() > 64) full = full.substring(0, 64);
                    index.put(full, new String[]{name, t.getString("name")});
                    defs.put(new JSONObject().put("name", full)
                        .put("description", t.optString("description", ""))
                        .put("input_schema", t.optJSONObject("inputSchema") != null
                            ? t.getJSONObject("inputSchema")
                            : new JSONObject().put("type", "object")));
                }
            } catch (Exception e) {
                report.add(name + ": " + e.getMessage());
            }
        }
    }

    /** Sobe o processo stdio dentro do Alpine (se ainda não estiver rodando com a mesma config) e faz o handshake. */
    private void ensureStdio(String name, JSONObject sc) throws Exception {
        String sig = sc.toString();
        McpStdio st = procs.get(name);
        if (st != null && st.alive() && sig.equals(procCfg.get(name))) return;
        if (st != null) { st.close(); procs.remove(name); }

        List<String> cmd = new ArrayList<>();
        JSONObject env = sc.optJSONObject("env");
        if (env != null)
            for (Iterator<String> it = env.keys(); it.hasNext(); ) { String k = it.next(); cmd.add(k + "=" + env.getString(k)); }
        cmd.add(sc.getString("command"));
        JSONArray a = sc.optJSONArray("args");
        if (a != null) for (int i = 0; i < a.length(); i++) cmd.add(a.getString(i));

        McpStdio n = new McpStdio(alpine.open(false, cmd.toArray(new String[0])));
        try {
            n.request("initialize", initParams(), 90);   // 1ª vez pode demorar (npx baixando pacote)
            n.notify("notifications/initialized", new JSONObject());
        } catch (Exception e) { n.close(); throw e; }
        procs.put(name, n);
        procCfg.put(name, sig);
    }

    synchronized String call(String full, JSONObject args) throws Exception {
        String[] st = index.get(full);
        if (st == null) return "ferramenta MCP desconhecida";
        McpStdio p = procs.get(st[0]);
        if (p != null && !p.alive()) return "servidor MCP '" + st[0] + "' parou. stderr: " + p.stderr();
        JSONObject r = rpc(st[0], "tools/call", new JSONObject().put("name", st[1]).put("arguments", args), false, 120);
        if (r.has("error")) return "erro MCP: " + r.getJSONObject("error");
        JSONObject res = r.getJSONObject("result");
        JSONArray c = res.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (res.optBoolean("isError")) sb.append("erro: ");
        if (c != null) for (int i = 0; i < c.length(); i++) {
            JSONObject b = c.getJSONObject(i);
            sb.append(b.optString("type").equals("text") ? b.optString("text", "") : "[" + b.optString("type") + "]").append('\n');
        }
        return sb.toString();
    }

    private JSONObject rpc(String server, String method, JSONObject params, boolean notify, int timeoutSec) throws Exception {
        McpStdio st = procs.get(server);
        if (st != null) {
            if (notify) { st.notify(method, params); return null; }
            return st.request(method, params, timeoutSec);
        }
        return http(server, method, params, notify);
    }

    private JSONObject http(String server, String method, JSONObject params, boolean notify) throws Exception {
        JSONObject s = servers.get(server);
        HttpURLConnection c = (HttpURLConnection) new URL(s.getString("url")).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        JSONObject h = s.optJSONObject("headers");
        if (h != null) for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
        String sid = sessions.get(server);
        if (sid != null) c.setRequestProperty("Mcp-Session-Id", sid);
        JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params);
        if (!notify) body.put("id", ++id);
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String ns = c.getHeaderField("Mcp-Session-Id");
        if (ns != null) sessions.put(server, ns);
        if (notify) return null;
        String txt = Alpine.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        String ct = c.getContentType();
        if (ct != null && ct.contains("event-stream")) {
            for (String line : txt.split("\n")) {
                if (!line.startsWith("data:")) continue;
                try {
                    JSONObject j = new JSONObject(line.substring(5).trim());
                    if (j.has("result") || j.has("error")) return j;
                } catch (JSONException ignored) {}
            }
            throw new IOException("resposta SSE vazia");
        }
        return new JSONObject(txt);
    }
}
