package com.alpineagent;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/** Cliente MCP via stdio: JSON-RPC delimitado por linha com um processo rodando dentro do Alpine. */
class McpStdio {
    final Process proc;
    final OutputStream out;
    final Map<Integer, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    final StringBuilder err = new StringBuilder();
    int nextId = 0;

    McpStdio(Process p) {
        proc = p;
        out = p.getOutputStream();
        new Thread(this::readOut, "mcp-out").start();
        new Thread(this::readErr, "mcp-err").start();
    }

    boolean alive() { return proc.isAlive(); }

    String stderr() { synchronized (err) { return err.toString().trim(); } }

    private void readOut() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("{")) continue;           // ruído (ex.: logs do npx) é ignorado
                JSONObject j;
                try { j = new JSONObject(line); } catch (JSONException e) { continue; }
                if (j.has("method")) { serverMsg(j); continue; }
                CompletableFuture<JSONObject> f = pending.remove(j.optInt("id", -1));
                if (f != null) f.complete(j);
            }
        } catch (IOException ignored) {}
        IOException closed = new IOException("processo MCP encerrou. stderr: " + stderr());
        for (CompletableFuture<JSONObject> f : pending.values()) f.completeExceptionally(closed);
        pending.clear();
    }

    private void readErr() {
        try (Reader r = new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8)) {
            char[] b = new char[1024];
            int n;
            while ((n = r.read(b)) > 0) {
                synchronized (err) {
                    err.append(b, 0, n);
                    if (err.length() > 2000) err.delete(0, err.length() - 2000);
                }
            }
        } catch (IOException ignored) {}
    }

    /** Pedidos do servidor para o cliente: responde ping, recusa o resto. */
    private void serverMsg(JSONObject j) {
        if (!j.has("id")) return;
        try {
            JSONObject r = new JSONObject().put("jsonrpc", "2.0").put("id", j.get("id"));
            if (j.optString("method").equals("ping")) r.put("result", new JSONObject());
            else r.put("error", new JSONObject().put("code", -32601).put("message", "method not found"));
            send(r);
        } catch (Exception ignored) {}
    }

    private synchronized void send(JSONObject o) throws IOException {
        out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    JSONObject request(String method, JSONObject params, int timeoutSec) throws Exception {
        int id;
        synchronized (this) { id = ++nextId; }
        CompletableFuture<JSONObject> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            if (!alive()) throw new IOException("processo MCP não está rodando. stderr: " + stderr());
            send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params));
            return f.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("timeout (" + timeoutSec + "s) em " + method + ". stderr: " + stderr());
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        } finally {
            pending.remove(id);
        }
    }

    void notify(String method, JSONObject params) throws Exception {
        send(new JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params));
    }

    void close() {
        try { out.close(); } catch (IOException ignored) {}
        proc.destroy();
    }
}
