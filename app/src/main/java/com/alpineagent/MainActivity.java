package com.alpineagent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    TextView out;
    EditText in;
    ScrollView sv;
    Button modeBtn;
    boolean termMode = false;
    Alpine alpine;
    Agent agent;
    Process term;
    OutputStream termIn;
    SharedPreferences sp;
    Providers providers;
    Scheduler scheduler;
    Voice voice;
    Button micBtn, voiceBtn;
    boolean speakReplies = false;  // fala as respostas
    boolean conv = false;          // modo conversa: ouve -> agente -> fala -> ouve...
    int silent = 0;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        App app = (App) getApplication();
        alpine = app.alpine;
        providers = app.providers;
        scheduler = app.scheduler;
        agent = app.newAgent(this::print);
        scheduler.ensureScheduled();
        voice = new Voice(this, new Voice.Listener() {
            @Override public void onPartial(String t) { in.setText(t); in.setSelection(t.length()); }
            @Override public void onText(String t) { in.setText(""); micResult(t); }
            @Override public void onError(int code) { micError(code); }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 48, 16, 16);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout bar = new LinearLayout(this);
        modeBtn = btn("Modo: Agente", v -> toggle());
        bar.addView(modeBtn);
        bar.addView(btn("Instalar Alpine", v -> installAlpine()));
        bar.addView(btn("Acessibilidade", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        bar.addView(btn("Provedores", v -> providerList()));
        bar.addView(btn("MCP", v -> mcpEditor()));
        bar.addView(btn("Tarefas", v -> taskList()));
        bar.addView(btn("Daytona", v -> daytonaEditor()));
        voiceBtn = btn("Voz: off", v -> toggleSpeak());
        bar.addView(voiceBtn);
        hs.addView(bar);
        root.addView(hs);

        sv = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        in = new EditText(this);
        in.setHint("Peça algo ao agente…");
        in.setSingleLine(false);
        row.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        micBtn = btn("🎤", v -> micClick());
        row.addView(micBtn);
        row.addView(btn("Enviar", v -> send()));
        root.addView(row);
        setContentView(root);

        print("Alpine Agent pronto.\n1) Instale o Alpine  2) Ative a acessibilidade  3) Em \"Provedores\", escolha um e informe a chave.\n");
    }

    Button btn(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    void print(String s) {
        runOnUiThread(() -> { out.append(s); sv.post(() -> sv.fullScroll(View.FOCUS_DOWN)); });
    }

    void toggle() {
        termMode = !termMode;
        modeBtn.setText(termMode ? "Modo: Terminal" : "Modo: Agente");
        in.setHint(termMode ? "Comando Alpine…" : "Peça algo ao agente…");
    }

    void send() {
        String t = in.getText().toString().trim();
        if (t.isEmpty()) return;
        in.setText("");
        if (termMode) { print("$ " + t + "\n"); termWrite(t); }
        else { print("\nVocê: " + t + "\n"); runAgent(t); }
    }

    void runAgent(String t) {
        agent.provider = providers.active();
        agent.voice = conv;
        new Thread(() -> {
            String r;
            try { r = agent.run(t); print("\nAgente: " + r + "\n"); }
            catch (Exception e) { print("\nErro: " + e + "\n"); if (conv) runOnUiThread(this::stopConv); return; }
            if (speakReplies || conv)
                voice.speak(r, () -> { if (conv) runOnUiThread(() -> { if (conv) voice.listen(); }); });
        }).start();
    }

    // ---------- voz ----------

    void toggleSpeak() {
        speakReplies = !speakReplies;
        voiceBtn.setText(speakReplies ? "Voz: on" : "Voz: off");
        if (!speakReplies) voice.stop();
    }

    void micClick() {
        if (conv) { stopConv(); return; }
        if (!voice.available()) { print("Reconhecimento de voz indisponível neste aparelho.\n"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (termMode) toggle();
        conv = true;
        silent = 0;
        micBtn.setText("⏹");
        print("[voz ligada — fale; toque ⏹ para parar]\n");
        voice.listen();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) micClick();
        else if (code == 7) print("Permissão de microfone negada.\n");
    }

    void stopConv() {
        conv = false;
        voice.stop();
        micBtn.setText("🎤");
    }

    void micResult(String t) {
        silent = 0;
        if (t.trim().isEmpty()) { micError(SpeechRecognizer.ERROR_NO_MATCH); return; }
        print("\nVocê 🎤: " + t + "\n");
        runAgent(t);
    }

    void micError(int code) {
        if (!conv) return;
        boolean quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            || code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        if (quiet && ++silent < 3) { ui.postDelayed(() -> { if (conv) voice.listen(); }, 400); return; }
        print(quiet ? "\n[voz encerrada por silêncio]\n" : "\n[erro de voz " + code + "]\n");
        stopConv();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        conv = false;
        voice.destroy();
    }

    void installAlpine() {
        new Thread(() -> {
            try { alpine.install(this::print); } catch (Exception e) { print("Erro: " + e + "\n"); }
        }).start();
    }

    void termWrite(String line) {
        try {
            if (term == null || !term.isAlive()) startTerm();
            termIn.write((line + "\n").getBytes());
            termIn.flush();
        } catch (Exception e) { print("erro: " + e + "\n"); }
    }

    void startTerm() throws IOException {
        term = alpine.spawn("/bin/sh");
        termIn = term.getOutputStream();
        InputStream is = term.getInputStream();
        new Thread(() -> {
            byte[] b = new byte[4096];
            int n;
            try { while ((n = is.read(b)) > 0) print(new String(b, 0, n)); } catch (IOException ignored) {}
            print("\n[sessão encerrada]\n");
        }).start();
    }

    void providerList() {
        String act = providers.activeId();
        String[] names = new String[providers.list.length()];
        for (int i = 0; i < names.length; i++) {
            JSONObject p = providers.list.optJSONObject(i);
            names[i] = (p.optString("id").equals(act) ? "✓ " : "") + p.optString("name") + " — " + p.optString("model");
        }
        new AlertDialog.Builder(this).setTitle("Provedores (toque para editar/usar)")
            .setItems(names, (d, i) -> providerEditor(providers.list.optJSONObject(i)))
            .setNeutralButton("+ Custom", (d, w) -> providerEditor(null))
            .setNegativeButton("Fechar", null).show();
    }

    EditText field(String hint, String val, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    void providerEditor(JSONObject p) {
        final boolean isNew = p == null;
        final JSONObject cur = isNew ? new JSONObject() : p;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 16, 32, 0);
        EditText name = field("Nome", cur.optString("name", ""), false);
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"openai (compatível)", "anthropic"}));
        type.setSelection(cur.optString("type", "openai").equals("anthropic") ? 1 : 0);
        EditText url = field("Base URL (ex: https://api.exemplo.com/v1)", cur.optString("baseUrl", ""), false);
        EditText key = field("API key", cur.optString("key", ""), true);
        EditText model = field("Modelo", cur.optString("model", ""), false);
        EditText hdr = field("Headers extras (JSON, opcional)", cur.optString("headers", ""), false);
        l.addView(name); l.addView(type); l.addView(url); l.addView(key); l.addView(model); l.addView(hdr);
        ScrollView s = new ScrollView(this);
        s.addView(l);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(isNew ? "Novo provedor" : cur.optString("name")).setView(s)
            .setPositiveButton("Salvar e usar", (d, w) -> {
                try {
                    String id = isNew ? "c" + System.currentTimeMillis() : cur.getString("id");
                    JSONObject q = new JSONObject().put("id", id)
                        .put("name", name.getText().toString().trim().isEmpty() ? "Custom" : name.getText().toString().trim())
                        .put("type", type.getSelectedItemPosition() == 1 ? "anthropic" : "openai")
                        .put("baseUrl", url.getText().toString().trim())
                        .put("key", key.getText().toString().trim())
                        .put("model", model.getText().toString().trim())
                        .put("headers", hdr.getText().toString().trim());
                    providers.save(q);
                    providers.setActive(id);
                    print("Provedor ativo: " + q.getString("name") + " (" + q.getString("model") + ")\n");
                } catch (JSONException e) { print("erro: " + e + "\n"); }
            })
            .setNegativeButton("Cancelar", null);
        if (!isNew) b.setNeutralButton("Excluir", (d, w) -> providers.delete(cur.optString("id")));
        b.show();
    }

    void mcpEditor() {
        File f = new File(getFilesDir(), "mcp.json");
        EditText e = new EditText(this);
        e.setMinLines(8);
        e.setTypeface(Typeface.MONOSPACE);
        try { e.setText(f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
            : "{\"servers\":{\n"
              + " \"remoto\":{\"url\":\"https://seu-servidor/mcp\",\"headers\":{},\"disabled\":true},\n"
              + " \"arquivos\":{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/root\"],\"env\":{}}\n"
              + "}}"); } catch (IOException ignored) {}
        new AlertDialog.Builder(this).setTitle("mcp.json").setView(e)
            .setPositiveButton("Salvar", (d, w) -> {
                try { Files.write(f.toPath(), e.getText().toString().getBytes(StandardCharsets.UTF_8)); }
                catch (IOException x) { print("erro: " + x + "\n"); }
            }).show();
    }

    // ---------- tarefas agendadas ----------

    void taskList() {
        JSONArray ts = scheduler.list();
        String[] names = new String[ts.length()];
        for (int i = 0; i < names.length; i++) {
            JSONObject t = ts.optJSONObject(i);
            names[i] = (t.optBoolean("enabled", true) ? "● " : "○ ") + t.optString("name") + " — " + t.optString("when")
                + " (próx.: " + scheduler.nextStr(t) + ")";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Tarefas agendadas")
            .setNeutralButton("+ Nova", (d, w) -> taskEditor())
            .setNegativeButton("Fechar", null);
        if (names.length > 0) b.setItems(names, (d, i) -> taskOptions(ts.optJSONObject(i)));
        else b.setMessage("Nenhuma tarefa. Toque em \"+ Nova\" ou peça ao agente: \"todo dia às 8h faça ...\".");
        b.show();
    }

    void taskEditor() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 16, 32, 0);
        EditText name = field("Nome", "", false);
        EditText prompt = field("O que o agente deve fazer", "", false);
        prompt.setSingleLine(false);
        prompt.setMinLines(3);
        EditText when = field("daily 08:00 | every 30 | once 2026-12-01 09:00", "", false);
        l.addView(name); l.addView(prompt); l.addView(when);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        new AlertDialog.Builder(this).setTitle("Nova tarefa").setView(sc)
            .setPositiveButton("Agendar", (d, w) -> {
                try {
                    JSONObject t = scheduler.add(name.getText().toString(), prompt.getText().toString().trim(), when.getText().toString());
                    print("Tarefa agendada: " + t.getString("name") + " (" + t.getString("when") + "), próxima: " + scheduler.nextStr(t) + "\n");
                } catch (Exception e) { print("erro: " + e.getMessage() + "\n"); }
            })
            .setNegativeButton("Cancelar", null).show();
    }

    void taskOptions(JSONObject t) {
        final String id = t.optString("id"), nm = t.optString("name");
        final boolean on = t.optBoolean("enabled", true);
        String[] ops = {"Executar agora", on ? "Desativar" : "Ativar", "Ver último resultado", "Excluir"};
        new AlertDialog.Builder(this).setTitle(nm).setItems(ops, (d, i) -> {
            if (i == 0) {
                startForegroundService(new Intent(this, TaskService.class).putExtra("id", id));
                print("Tarefa iniciada: " + nm + " (o resultado aparece numa notificação)\n");
            } else if (i == 1) {
                scheduler.setEnabled(id, !on);
            } else if (i == 2) {
                String r = t.optString("lastResult", "");
                new AlertDialog.Builder(this).setTitle(nm)
                    .setMessage(r.isEmpty() ? "Ainda não executou." : new java.util.Date(t.optLong("last")) + "\n\n" + r)
                    .setPositiveButton("OK", null).show();
            } else {
                scheduler.remove(id);
            }
        }).show();
    }

    // ---------- Daytona ----------

    void daytonaEditor() {
        Daytona dy = ((App) getApplication()).daytona;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 16, 32, 0);
        EditText key = field("API key (app.daytona.io → API Keys)", dy.key(), true);
        EditText api = field("API URL", dy.apiUrl(), false);
        EditText proxy = field("Toolbox proxy URL", dy.proxyUrl(), false);
        TextView info = new TextView(this);
        info.setText("Sandbox: " + (dy.sandboxId().isEmpty() ? "(criado no 1º uso)" : dy.sandboxId()));
        l.addView(key); l.addView(api); l.addView(proxy); l.addView(info);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        new AlertDialog.Builder(this).setTitle("Daytona").setView(sc)
            .setPositiveButton("Salvar", (d, w) -> {
                dy.save(key.getText().toString(), api.getText().toString(), proxy.getText().toString());
                print("Daytona " + (dy.configured() ? "ativo (ferramentas daytona_*)" : "desativado") + "\n");
            })
            .setNeutralButton("Apagar sandbox", (d, w) -> new Thread(() -> {
                try { print(dy.destroy() + "\n"); } catch (Exception e) { print("erro: " + e.getMessage() + "\n"); }
            }).start())
            .setNegativeButton("Cancelar", null).show();
    }
}
