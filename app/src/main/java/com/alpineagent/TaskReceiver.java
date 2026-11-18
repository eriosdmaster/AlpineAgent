package com.alpineagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Recebe o alarme de uma tarefa: reagenda a próxima ocorrência e inicia o TaskService. */
public class TaskReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Scheduler.ACTION.equals(i.getAction())) return;
        App app = (App) c.getApplicationContext();
        String id = i.getStringExtra("id");
        JSONObject t = id == null ? null : app.scheduler.get(id);
        if (t == null || !t.optBoolean("enabled", true)) return;
        app.scheduler.schedule(t);   // próxima ocorrência primeiro, para a recorrência não depender da execução
        c.startForegroundService(new Intent(c, TaskService.class).putExtra("id", id));
    }

    /** Boot e atualização do app: alarmes são apagados nesses casos. Separado para não exportar o receiver do alarme. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            ((App) c.getApplicationContext()).scheduler.rescheduleAll();
        }
    }
}
