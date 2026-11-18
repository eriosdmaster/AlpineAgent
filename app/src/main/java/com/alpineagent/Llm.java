package com.alpineagent;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.Iterator;

/**
 * Camada de provedores. O histórico interno usa o formato Anthropic (blocos text/tool_use/tool_result);
 * o adaptador "openai" converte de/para /chat/completions (DeepSeek, Kimi, NVIDIA, Groq, OpenRouter, custom...).
 */
class Llm {
    static JSONObject call(JSONObject p, String system, JSONArray msgs, JSONArray tools) throws Exception {
        return p.optString("type", "openai").equals("anthropic")
            ? anthropic(p, system, msgs, tools) : openai(p, system, msgs, tools);
    }

    static String url(String base, String suffix) {
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.endsWith(suffix) ? base : base + suffix;
    }

    private static JSONObject post(String url, JSONObject p, boolean anthropic, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(20000); c.setReadTimeout(180000);
        c.setRequestProperty("content-type", "application/json");
        String key = p.optString("key", "");
        if (anthropic) {
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else if (!key.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + key);
        }
        String extra = p.optString("headers", "").trim();
        if (!extra.isEmpty()) {
            try {
                JSONObject h = new JSONObject(extra);
                for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
            } catch (JSONException ignored) {}
        }
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        String txt = Alpine.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new IOException(p.optString("name") + " HTTP " + code + ": " + txt);
        return new JSONObject(txt);
    }

    private static JSONObject anthropic(JSONObject p, String system, JSONArray msgs, JSONArray tools) throws Exception {
        JSONObject body = new JSONObject().put("model", p.getString("model")).put("max_tokens", 4096)
            .put("system", system).put("messages", msgs).put("tools", tools);
        return post(url(p.getString("baseUrl"), "/messages"), p, true, body);
    }

    private static JSONObject openai(JSONObject p, String system, JSONArray msgs, JSONArray tools) throws Exception {
        JSONArray ot = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            ot.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", t.getString("name")).put("description", t.optString("description", ""))
                .put("parameters", t.getJSONObject("input_schema"))));
        }
        JSONObject body = new JSONObject().put("model", p.getString("model"))
            .put("messages", toOpenAi(system, msgs));
        if (ot.length() > 0) body.put("tools", ot);
        JSONObject r = post(url(p.getString("baseUrl"), "/chat/completions"), p, false, body);

        JSONObject msg = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray content = new JSONArray();
        String txt = msg.isNull("content") ? "" : msg.optString("content", "");
        if (!txt.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", txt));
        JSONArray tc = msg.optJSONArray("tool_calls");
        if (tc != null) {
            for (int i = 0; i < tc.length(); i++) {
                JSONObject t = tc.getJSONObject(i), fn = t.getJSONObject("function");
                String args = fn.isNull("arguments") ? "" : fn.optString("arguments", "");
                JSONObject input;
                try { input = new JSONObject(args.trim().isEmpty() ? "{}" : args); } catch (JSONException e) { input = new JSONObject(); }
                content.put(new JSONObject().put("type", "tool_use")
                    .put("id", t.optString("id", "call_" + System.nanoTime() + "_" + i))
                    .put("name", fn.getString("name")).put("input", input));
            }
        }
        return new JSONObject().put("content", content);
    }

    private static JSONArray toOpenAi(String system, JSONArray msgs) throws JSONException {
        JSONArray o = new JSONArray();
        o.put(new JSONObject().put("role", "system").put("content", system));
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            String role = m.getString("role");
            Object c = m.get("content");
            if (c instanceof String) { o.put(new JSONObject().put("role", role).put("content", c)); continue; }
            JSONArray blocks = (JSONArray) c;
            if (role.equals("user")) {
                StringBuilder ut = new StringBuilder();
                for (int j = 0; j < blocks.length(); j++) {
                    JSONObject b = blocks.getJSONObject(j);
                    if (b.getString("type").equals("tool_result"))
                        o.put(new JSONObject().put("role", "tool").put("tool_call_id", b.getString("tool_use_id"))
                            .put("content", b.optString("content", "")));
                    else if (b.getString("type").equals("text")) ut.append(b.getString("text"));
                }
                if (ut.length() > 0) o.put(new JSONObject().put("role", "user").put("content", ut.toString()));
            } else {
                StringBuilder text = new StringBuilder();
                JSONArray calls = new JSONArray();
                for (int j = 0; j < blocks.length(); j++) {
                    JSONObject b = blocks.getJSONObject(j);
                    if (b.getString("type").equals("text")) text.append(b.getString("text"));
                    else if (b.getString("type").equals("tool_use"))
                        calls.put(new JSONObject().put("id", b.getString("id")).put("type", "function")
                            .put("function", new JSONObject().put("name", b.getString("name"))
                                .put("arguments", b.getJSONObject("input").toString())));
                }
                JSONObject am = new JSONObject().put("role", "assistant")
                    .put("content", text.length() > 0 ? (Object) text.toString() : JSONObject.NULL);
                if (calls.length() > 0) am.put("tool_calls", calls);
                o.put(am);
            }
        }
        return o;
    }
}
