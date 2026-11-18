package com.alpineagent;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** Tarefas agendadas (files/tasks.json) disparadas por AlarmManager; cada disparo roda o agente no TaskService. */
class Scheduler {
    static final String ACTION = "com.alpineagent.RUN_TASK";
    static final String FORMATS = "daily HH:mm | every N (minutos, mín. 5; aceita 2h) | once yyyy-MM-dd HH:mm";
    final Context ctx;
    final File file, logFile;

    Scheduler(Context c) {
        ctx = c.getApplicationContext();
        file = new File(ctx.getFilesDir(), "tasks.json");
        logFile = new File(ctx.getFilesDir(), "tasks.log");
    }

    synchronized JSONArray list() {
        try { return file.exists() ? new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void save(JSONArray a) {
        try { Files.write(file.toPath(), a.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    synchronized JSONObject get(String id) {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optString("id").equals(id)) return a.optJSONObject(i);
        return null;
    }

    private void set(String id, Object... kv) {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (!t.optString("id").equals(id)) continue;
            try { for (int k = 0; k + 1 < kv.length; k += 2) t.put((String) kv[k], kv[k + 1]); } catch (JSONException ignored) {}
            save(a);
            return;
        }
    }

    /** Valida "when" e devolve os campos de agendamento. Lança IllegalArgumentException se inválido. */
    static JSONObject parse(String when) throws JSONException {
        String w = when == null ? "" : when.trim().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("^(daily|diario|diário|diariamente|todo dia)\\s+(\\d{1,2}):(\\d{2})$").matcher(w);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(2)), mi = Integer.parseInt(m.group(3));
            if (h > 23 || mi > 59) throw new IllegalArgumentException("horário inválido");
            return new JSONObject().put("type", "daily").put("hour", h).put("minute", mi)
                .put("when", String.format(Locale.ROOT, "daily %02d:%02d", h, mi));
        }
        m = Pattern.compile("^(every|cada|a cada)\\s+(\\d+)\\s*(min|minutos|m|h|horas)?$").matcher(w);
        if (m.matches()) {
            long n = Long.parseLong(m.group(2));
            String u = m.group(3);
            if (u != null && u.startsWith("h")) n *= 60;
            if (n < 5) throw new IllegalArgumentException("intervalo mínimo é 5 minutos");
            return new JSONObject().put("type", "every").put("everyMin", n).put("when", "every " + n + " min");
        }
        m = Pattern.compile("^(once|uma vez|em)\\s+(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})$").matcher(w);
        if (m.matches()) {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) - 1, Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) throw new IllegalArgumentException("essa data/hora já passou");
            return new JSONObject().put("type", "once").put("at", c.getTimeInMillis())
                .put("when", "once " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(c.getTime()));
        }
        throw new IllegalArgumentException("formato inválido. Use: " + FORMATS);
    }

    synchronized JSONObject add(String name, String prompt, String when) throws Exception {
        JSONObject t = parse(when);
        t.put("id", "t" + System.currentTimeMillis())
            .put("name", name == null || name.trim().isEmpty() ? "Tarefa" : name.trim())
            .put("prompt", prompt).put("enabled", true);
        JSONArray a = list();
        a.put(t);
        save(a);
        schedule(t);
        return t;
    }

    synchronized boolean remove(String id) {
        JSONArray a = list(), n = new JSONArray();
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            if (a.optJSONObject(i).optString("id").equals(id)) found = true; else n.put(a.opt(i));
        }
        if (found) { cancel(id); save(n); }
        return found;
    }

    synchronized void setEnabled(String id, boolean on) {
        set(id, "enabled", on);
        JSONObject t = get(id);
        if (t != null) schedule(t);
    }

    synchronized void setResult(String id, String res) {
        set(id, "last", System.currentTimeMillis(), "lastResult", res.length() > 4000 ? res.substring(0, 4000) : res);
    }

    synchronized void appendLog(String s) {
        try {
            byte[] old = logFile.exists() ? Files.readAllBytes(logFile.toPath()) : new byte[0];
            String all = new String(old, StandardCharsets.UTF_8) + s;
            if (all.length() > 100000) all = all.substring(all.length() - 50000);
            Files.write(logFile.toPath(), all.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Próximo disparo depois de "from", ou -1 se não houver. */
    static long next(JSONObject t, long from) {
        switch (t.optString("type")) {
            case "daily": {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(from);
                c.set(Calendar.HOUR_OF_DAY, t.optInt("hour"));
                c.set(Calendar.MINUTE, t.optInt("minute"));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() <= from) c.add(Calendar.DAY_OF_MONTH, 1);
                return c.getTimeInMillis();
            }
            case "every": return from + t.optLong("everyMin") * 60000L;
            case "once": { long at = t.optLong("at"); return at > from ? at : -1; }
        }
        return -1;
    }

    String nextStr(JSONObject t) {
        if (!t.optBoolean("enabled", true)) return "desativada";
        long n = next(t, System.currentTimeMillis());
        return n < 0 ? "—" : new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(n));
    }

    private PendingIntent pi(String id, int flags) {
        Intent i = new Intent(ctx, TaskReceiver.class).setAction(ACTION)
            .setData(Uri.parse("alpineagent://task/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private void cancel(String id) {
        PendingIntent p = pi(id, PendingIntent.FLAG_NO_CREATE);
        if (p != null) { ((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE)).cancel(p); p.cancel(); }
    }

    /** (Re)agenda o próximo disparo. Tarefa "once" sem próximo disparo é desativada. */
    synchronized void schedule(JSONObject t) {
        String id = t.optString("id");
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        cancel(id);
        if (!t.optBoolean("enabled", true)) return;
        long n = next(t, System.currentTimeMillis());
        if (n < 0) { set(id, "enabled", false); return; }
        PendingIntent p = pi(id, PendingIntent.FLAG_UPDATE_CURRENT);
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n, p); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n, p); }
    }

    /** Depois do boot/atualização: recria todos os alarmes. */
    synchronized void rescheduleAll() {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) schedule(a.optJSONObject(i));
    }

    /** Ao abrir o app: recria só os alarmes que sumiram (ex.: após "forçar parada"), sem adiar os existentes. */
    synchronized void ensureScheduled() {
        JSONArray a = list();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t.optBoolean("enabled", true) && pi(t.optString("id"), PendingIntent.FLAG_NO_CREATE) == null) schedule(t);
        }
    }
}
