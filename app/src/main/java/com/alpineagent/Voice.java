package com.alpineagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayList;
import java.util.Locale;

/** Voz: reconhecimento (SpeechRecognizer) e fala (TextToSpeech), em pt-BR. */
class Voice {
    interface Listener {
        void onPartial(String text);
        void onText(String text);
        void onError(int code);
    }

    final Activity act;
    final Listener l;
    SpeechRecognizer sr;
    TextToSpeech tts;
    boolean ttsReady;
    volatile Runnable onDone;
    String lastId = "";

    Voice(Activity a, Listener l) {
        act = a;
        this.l = l;
        tts = new TextToSpeech(a, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.forLanguageTag("pt-BR"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { if (id.equals(lastId)) finish(); }
                @Override public void onError(String id) { if (id.equals(lastId)) finish(); }
            });
            ttsReady = true;
        });
    }

    boolean available() { return SpeechRecognizer.isRecognitionAvailable(act); }

    private void finish() {
        Runnable r = onDone;
        onDone = null;
        if (r != null) r.run();
    }

    /** Começa a ouvir uma frase. */
    void listen() {
        act.runOnUiThread(() -> {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(act);
                sr.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle p) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float v) {}
                    @Override public void onBufferReceived(byte[] b) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onEvent(int t, Bundle p) {}
                    @Override public void onError(int code) { l.onError(code); }
                    @Override public void onResults(Bundle r) { String s = first(r); if (s == null) l.onError(SpeechRecognizer.ERROR_NO_MATCH); else l.onText(s); }
                    @Override public void onPartialResults(Bundle r) { String s = first(r); if (s != null) l.onPartial(s); }
                });
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sr.startListening(i);
        });
    }

    private static String first(Bundle r) {
        ArrayList<String> a = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return a == null || a.isEmpty() ? null : a.get(0);
    }

    /** Para de ouvir e de falar. */
    void stop() {
        onDone = null;
        if (tts != null) tts.stop();
        act.runOnUiThread(() -> { if (sr != null) sr.cancel(); });
    }

    static String clean(String s) {
        return s.replaceAll("(?s)```.*?```", " código omitido. ")
            .replaceAll("https?://\\S+", " link ")
            .replaceAll("[*#`_>~|]", "")
            .replaceAll("\\s+", " ").trim();
    }

    /** Fala o texto; "done" roda quando termina (ou na hora, se não houver o que falar). */
    void speak(String text, Runnable done) {
        String t = clean(text == null ? "" : text);
        if (!ttsReady || t.isEmpty()) { if (done != null) done.run(); return; }
        onDone = done;
        int max = Math.max(500, TextToSpeech.getMaxSpeechInputLength() - 100);
        int n = 0;
        for (int p = 0; p < t.length(); ) {
            int e = Math.min(t.length(), p + max);
            if (e < t.length()) { int sp = t.lastIndexOf(' ', e); if (sp > p) e = sp; }
            n++;
            String id = "u" + System.nanoTime() + "_" + n;
            if (e >= t.length()) lastId = id;
            tts.speak(t.substring(p, e), p == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
            p = e;
        }
    }

    void destroy() {
        onDone = null;
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (sr != null) sr.destroy();
    }
}
