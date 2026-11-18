package com.alpineagent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Lista de provedores (presets + customizados) guardada em SharedPreferences. */
class Providers {
    final SharedPreferences sp;
    JSONArray list;

    Providers(Context c) {
        sp = c.getSharedPreferences("providers", Context.MODE_PRIVATE);
        try { list = new JSONArray(sp.getString("list", "[]")); } catch (JSONException e) { list = new JSONArray(); }
        if (list.length() == 0) seed(c);
    }

    /** type: "openai" (compatível com OpenAI /chat/completions) ou "anthropic" (/messages) */
    static JSONObject mk(String id, String name, String type, String url, String model) {
        try {
            return new JSONObject().put("id", id).put("name", name).put("type", type)
                .put("baseUrl", url).put("key", "").put("model", model).put("headers", "");
        } catch (JSONException e) { throw new RuntimeException(e); }
    }

    private void seed(Context c) {
        JSONObject a = mk("anthropic", "Anthropic (Claude)", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5");
        try { a.put("key", c.getSharedPreferences("cfg", 0).getString("key", "")); } catch (JSONException ignored) {}
        list.put(a);
        list.put(mk("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4.1"));
        list.put(mk("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat"));
        list.put(mk("kimi", "Kimi (Moonshot)", "openai", "https://api.moonshot.ai/v1", "kimi-k2-0905-preview"));
        list.put(mk("nvidia", "NVIDIA NIM", "openai", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"));
        list.put(mk("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"));
        list.put(mk("groq", "Groq", "openai", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"));
        list.put(mk("local", "Ollama / LM Studio (rede local)", "openai", "http://192.168.0.10:11434/v1", "llama3.1"));
        persist();
        sp.edit().putString("active", "anthropic").apply();
    }

    void persist() { sp.edit().putString("list", list.toString()).apply(); }

    String activeId() { return sp.getString("active", "anthropic"); }

    void setActive(String id) { sp.edit().putString("active", id).apply(); }

    JSONObject active() {
        for (int i = 0; i < list.length(); i++)
            if (list.optJSONObject(i).optString("id").equals(activeId())) return list.optJSONObject(i);
        return list.optJSONObject(0);
    }

    void save(JSONObject p) {
        for (int i = 0; i < list.length(); i++) {
            if (list.optJSONObject(i).optString("id").equals(p.optString("id"))) {
                try { list.put(i, p); } catch (JSONException ignored) {}
                persist();
                return;
            }
        }
        list.put(p);
        persist();
    }

    void delete(String id) {
        JSONArray n = new JSONArray();
        for (int i = 0; i < list.length(); i++)
            if (!list.optJSONObject(i).optString("id").equals(id)) n.put(list.opt(i));
        list = n;
        persist();
        if (activeId().equals(id) && list.length() > 0) setActive(list.optJSONObject(0).optString("id"));
    }
}
