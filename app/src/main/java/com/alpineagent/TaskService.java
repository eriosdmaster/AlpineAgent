package com.alpineagent;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONObject;

/** Executa uma tarefa agendada: roda o agente em foreground service (notificação) e publica o resultado. */
public class TaskService extends Service {
    static final String CH_RUN = "run", CH_RES = "result";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Execução de tarefas", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_RES, "Resultado de tarefas", NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(1, new Notification.Builder(this, CH_RUN).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Alpine Agent").setContentText("Executando tarefa agendada…").build());

        App app = (App) getApplication();
        String id = in == null ? null : in.getStringExtra("id");
        JSONObject t = id == null ? null : app.scheduler.get(id);
        if (t == null) { stopForeground(true); stopSelf(startId); return START_NOT_STICKY; }

        PowerManager.WakeLock wl = ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alpineagent:task");
        wl.acquire(15 * 60 * 1000L);
        new Thread(() -> {
            try { run(app, t, nm); }
            finally { if (wl.isHeld()) wl.release(); stopForeground(true); stopSelf(startId); }
        }, "task-" + id).start();
        return START_NOT_STICKY;
    }

    private void run(App app, JSONObject t, NotificationManager nm) {
        String id = t.optString("id"), name = t.optString("name", "Tarefa");
        StringBuilder log = new StringBuilder();
        String res;
        try {
            Agent ag = app.newAgent(s -> { synchronized (log) { log.append(s); } });
            ag.provider = app.providers.active();
            ag.scheduled = true;
            res = ag.run(t.optString("prompt"));
        } catch (Throwable e) {
            res = "Erro: " + e;
        }
        if (res == null || res.trim().isEmpty()) res = "(sem resposta)";
        app.scheduler.setResult(id, res);
        synchronized (log) {
            app.scheduler.appendLog("== " + new java.util.Date() + " · " + name + " ==\n" + log + "=> " + res + "\n\n");
        }
        String first = res.split("\n", 2)[0];
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(Math.abs(id.hashCode()), new Notification.Builder(this, CH_RES)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(name)
            .setContentText(first).setStyle(new Notification.BigTextStyle().bigText(res))
            .setContentIntent(open).setAutoCancel(true).build());
    }
}
