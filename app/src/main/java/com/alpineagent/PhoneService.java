package com.alpineagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Controle do celular: ver tela, tocar, deslizar, digitar, botões globais. */
public class PhoneService extends AccessibilityService {
    static volatile PhoneService I;

    @Override protected void onServiceConnected() { I = this; }
    @Override public boolean onUnbind(Intent i) { I = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    String dump() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return "(tela indisponível)";
        StringBuilder sb = new StringBuilder();
        walk(r, sb);
        return sb.length() == 0 ? "(nada visível)" : sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null || sb.length() > 12000) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null || d != null || n.isClickable()) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            sb.append(n.isClickable() ? "[clicável] " : "").append(t != null ? t : "")
              .append(d != null ? " (" + d + ")" : "")
              .append(" @").append(b.centerX()).append(',').append(b.centerY()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb);
    }

    boolean tap(int x, int y) { return swipe(x, y, x, y, 60); }

    boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x1 == x2 && y1 == y2 ? x2 + 1 : x2, y2);
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(ms, 1))).build();
        CountDownLatch l = new CountDownLatch(1);
        boolean[] ok = {false};
        dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; l.countDown(); }
            @Override public void onCancelled(GestureDescription d) { l.countDown(); }
        }, null);
        try { l.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    boolean clickText(String text) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo n : l) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    boolean type(String text) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean global(String a) {
        switch (a) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: return false;
        }
    }
}
