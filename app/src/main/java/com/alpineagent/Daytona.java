package com.alpineagent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Sandbox Linux na nuvem (Daytona): execução de comandos e arquivos fora do celular.
 * Um sandbox é criado sob demanda, reaproveitado entre execuções e religado se tiver auto-parado.
 * API: {apiUrl}/sandbox (gestão) e {proxyUrl}/{id}/... (toolbox: process/execute, files/*).
 */
class Daytona {
    static final String API = "https://app.daytona.io/api";
    static final String PROXY = "https://proxy.app.daytona.io/toolbox";
    final SharedPreferences sp;
    long checked = 0;

    Daytona(Context c) { sp = c.getSharedPreferences("daytona", Context.MODE_PRIVATE); }

    boolean configured() { return !sp.getString("key", "").isEmpty(); }
    String key() { return sp.getString("key", ""); }
    String apiUrl() { return sp.getString("api", API); }
    String proxyUrl() { return sp.getString("proxy", PROXY); }
    String sandboxId() { return sp.getString("sandbox", ""); }

    void save(String key, String api, String proxy) {
        sp.edit().putString("key", key.trim())
            .putString("api", api.trim().isEmpty() ? API : api.trim().replaceAll("/+$", ""))
            .putString("proxy", proxy.trim().isEmpty() ? PROXY : proxy.trim().replaceAll("/+$", "")).apply();
    }

    private byte[] http(String method, String url, String ctype, byte[] body, int readSec) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(readSec * 1000);
        c.setRequestProperty("Authorization", "Bearer " + key());
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) { byte[] b = new byte[8192]; int n; while ((n = is.read(b)) > 0) bo.write(b, 0, n); }
        if (code >= 400) throw new IOException("Daytona HTTP " + code + ": " + bo.toString("UTF-8"));
        return bo.toByteArray();
    }

    private JSONObject json(String method, String url, JSONObject body, int readSec) throws Exception {
        byte[] r = http(method, url, "application/json", body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), readSec);
        String s = new String(r, StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? new JSONObject() : new JSONObject(s);
    }

    /** Garante um sandbox existente e iniciado; devolve o id. */
    synchronized String ready() throws Exception {
        if (!configured()) throw new IllegalStateException("Daytona sem API key (botão Daytona)");
        String id = sandboxId();
        if (id.isEmpty()) {
            id = json("POST", apiUrl() + "/sandbox", new JSONObject(), 120).getString("id");
            sp.edit().putString("sandbox", id).apply();
            checked = 0;
        }
        if (System.currentTimeMillis() - checked > 60000) {
            String st;
            try { st = json("GET", apiUrl() + "/sandbox/" + id, null, 30).optString("state"); }
            catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {   // sandbox apagado: cria outro
                    sp.edit().remove("sandbox").apply();
                    return ready();
                }
                throw e;
            }
            if (!st.equals("started")) {
                if (st.equals("stopped") || st.equals("archived")) json("POST", apiUrl() + "/sandbox/" + id + "/start", null, 120);
                long end = System.currentTimeMillis() + 120000;
                while (!st.equals("started")) {
                    if (System.currentTimeMillis() > end) throw new IOException("sandbox não iniciou (estado: " + st + ")");
                    Thread.sleep(2000);
                    st = json("GET", apiUrl() + "/sandbox/" + id, null, 30).optString("state");
                    if (st.equals("error") || st.equals("build_failed")) throw new IOException("sandbox com erro: " + st);
                }
            }
            checked = System.currentTimeMillis();
        }
        return id;
    }

    static String trim(String s) {
        return s.length() <= 8000 ? s : s.substring(0, 4000) + "\n…[cortado]…\n" + s.substring(s.length() - 4000);
    }

    String exec(String command) throws Exception {
        String id = ready();
        JSONObject r = json("POST", proxyUrl() + "/" + id + "/process/execute",
            new JSONObject().put("command", command).put("timeout", 300), 330);
        String out = r.optString("result", "");
        return "exit " + r.optInt("exitCode", r.optInt("code", 0)) + "\n" + (out.isEmpty() ? "(sem saída)" : trim(out));
    }

    private String q(String path) throws UnsupportedEncodingException { return URLEncoder.encode(path, "UTF-8"); }

    String write(String path, String content) throws Exception {
        String id = ready();
        http("POST", proxyUrl() + "/" + id + "/files/upload-v2?path=" + q(path), "application/octet-stream",
            content.getBytes(StandardCharsets.UTF_8), 120);
        return "ok";
    }

    String read(String path) throws Exception {
        String id = ready();
        return trim(new String(http("GET", proxyUrl() + "/" + id + "/files/download?path=" + q(path), null, null, 120), StandardCharsets.UTF_8));
    }

    String list(String path) throws Exception {
        String id = ready();
        String r = new String(http("GET", proxyUrl() + "/" + id + "/files?path=" + q(path), null, null, 60), StandardCharsets.UTF_8).trim();
        if (!r.startsWith("[")) return trim(r);
        JSONArray a = new JSONArray(r);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject f = a.getJSONObject(i);
            sb.append(f.optBoolean("isDir") ? "d " : "- ").append(f.optString("name")).append(f.optBoolean("isDir") ? "" : "  " + f.optLong("size") + "B").append('\n');
        }
        return sb.length() == 0 ? "(vazio)" : trim(sb.toString());
    }

    /** Apaga o sandbox (e esquece o id). */
    synchronized String destroy() throws Exception {
        String id = sandboxId();
        if (id.isEmpty()) return "nenhum sandbox";
        json("DELETE", apiUrl() + "/sandbox/" + id, null, 60);
        sp.edit().remove("sandbox").apply();
        return "sandbox apagado: " + id;
    }
}
