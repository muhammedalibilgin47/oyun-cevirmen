package com.mamibey.oyuncevirmen;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String[][] LANGS = {
            {"en-US", "İngilizce (GTA 6, çoğu oyun)"},
            {"en-GB", "İngilizce (İngiliz aksanı)"},
            {"ja-JP", "Japonca"},
            {"es-ES", "İspanyolca"},
            {"fr-FR", "Fransızca"},
            {"de-DE", "Almanca"},
            {"it-IT", "İtalyanca"},
            {"pt-BR", "Portekizce"},
            {"ru-RU", "Rusça"},
            {"ko-KR", "Korece"},
            {"zh-CN", "Çince"},
            {"pl-PL", "Lehçe"}
    };

    static final String[] PNAME = {"Ana karakter", "Kadın karakter", "Sert NPC", "Genç NPC", "Yaşlı NPC", "Telsiz / Polis"};
    static final float[] PPITCH = {0.85f, 1.25f, 0.65f, 1.10f, 0.75f, 1.00f};
    static final float[] PRATE = {1.00f, 1.02f, 0.95f, 1.10f, 0.88f, 1.12f};

    // Remote Play modunda konuşanın ses perdesine göre sabit karakter sesleri
    static final String[] BNAME = {"Kalın sesli erkek", "Erkek karakter", "Genç erkek", "Kadın karakter", "Genç kadın", "İnce ses"};
    static final float[] BPITCH = {0.70f, 0.85f, 1.00f, 1.20f, 1.35f, 1.50f};
    static final float[] BRATE = {0.95f, 1.00f, 1.08f, 1.02f, 1.08f, 1.10f};
    static final int REQ_PROJECTION = 7;

    SharedPreferences prefs;
    AudioManager am;
    SpeechRecognizer sr;
    TextToSpeech tts;
    boolean ttsReady = false;
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newFixedThreadPool(2);
    final Map<String, String> cache = new HashMap<>();
    final List<Voice> trVoices = new ArrayList<>();
    final LinkedList<String> logLines = new LinkedList<>();

    boolean running = false, listening = false, full = false;
    int npcIdx = 0, pending = 0;
    String lastFinal = "";

    TextView title, statusTv, trLine, srcLine, interimTv, errTv, logTv;
    LinearLayout big, settingsBox;
    Button startBtn;
    Spinner langSp, voiceSp;
    Switch ttsSw, npcSw, hpSw, skipSw, remoteSw;
    ParcelFileDescriptor readFd;
    boolean triedProjection = false;
    boolean remoteFailed = false;
    long lastRecogMs = 0, remoteSince = 0;

    // Her saniye durum satırını güncelle; Remote Play sesi geliyor ama tanınmıyorsa mikrofona geç
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (remoteActive()) {
                boolean loud = now - CaptureService.lastLoudMs < 1500;
                if (loud && now - lastRecogMs > 12000 && now - remoteSince > 12000) {
                    remoteFailed = true;
                    closePipe();
                    try { stopService(new Intent(MainActivity.this, CaptureService.class)); } catch (Exception ignored) { }
                    showErr("Telefonun konuşma tanıması Remote Play sesini doğrudan alamıyor. Otomatik olarak mikrofon moduna geçildi: telefonu TV / monitör hoparlörüne yakın tutun.");
                    pauseListening();
                    restartSoon(300);
                } else if (listening) {
                    statusTv.setText("Dinliyor… (Remote Play sesi: " + (loud ? "geliyor ✓" : "sessiz") + ")");
                }
            }
            h.postDelayed(this, 1000);
        }
    };
    SeekBar rateSb, volSb;

    final Runnable restartRunnable = this::startListening;

    // ------------------------------------------------------------ yaşam döngüsü

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ayarlar", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();

        tts = new TextToSpeech(this, status -> h.post(() -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("tr", "TR"));
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsReady = true;
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    showErr("Türkçe ses yüklü değil. Ayarlar > Metin okuma çıkışı > Google > Ses verilerini yükle > Türkçe.");
                }
                loadVoices();
            } else {
                showErr("Metin okuma motoru başlatılamadı. Google Metin Okuma uygulamasını yükleyin.");
            }
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                h.post(() -> {
                    if (running && !noEchoRisk()) {
                        pauseListening();
                        setStatus("Türkçe okunuyor…");
                    }
                });
            }

            @Override
            public void onDone(String id) {
                h.post(MainActivity.this::utterDone);
            }

            @Override
            public void onError(String id) {
                h.post(MainActivity.this::utterDone);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                h.post(MainActivity.this::utterDone);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopAll();
        if (sr != null) {
            try { sr.destroy(); } catch (Exception ignored) { }
        }
        if (tts != null) tts.shutdown();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (full) setFull(false);
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAll();
        else showErr("Mikrofon izni verilmedi. Ayarlar > Uygulamalar > Oyun Çevirmen > İzinler'den mikrofona izin verin.");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJECTION) return;
        if (res == RESULT_OK && data != null) {
            Intent s = new Intent(this, CaptureService.class)
                    .putExtra("code", res)
                    .putExtra("data", data);
            startForegroundService(s);
            setStatus("Remote Play sesine bağlanıyor…");
            h.postDelayed(this::startAll, 800);
        } else {
            showErr("Ses paylaşım izni verilmedi. Mikrofon moduyla devam ediliyor.");
            startAll();
        }
    }

    boolean remoteActive() {
        return !remoteFailed && remoteSw != null && remoteSw.isChecked() && CaptureService.inst != null && Build.VERSION.SDK_INT >= 33;
    }

    boolean noEchoRisk() {
        return hpSw.isChecked() || remoteActive();
    }

    // ------------------------------------------------------------ başlat / durdur

    void startAll() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
            return;
        }
        if (remoteSw.isChecked() && CaptureService.inst == null) {
            if (Build.VERSION.SDK_INT < 33) {
                showErr("Remote Play'den doğrudan ses almak Android 13 ve üstünü gerektirir. Mikrofon moduyla devam ediliyor.");
            } else if (!triedProjection) {
                triedProjection = true;
                MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
                return;
            } else {
                showErr("Remote Play sesi alınamadı. Mikrofon moduyla devam ediliyor.");
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showErr("Bu telefonda konuşma tanıma servisi yok. Play Store'dan Google uygulamasını yükleyin / güncelleyin.");
            return;
        }
        if (!remoteSw.isChecked() || CaptureService.inst != null) showErr("");
        running = true;
        npcIdx = 0;
        lastFinal = "";
        lastRecogMs = SystemClock.elapsedRealtime();
        remoteSince = lastRecogMs;
        trLine.setText("Dinliyorum… Oyunda biri konuşunca Türkçesi burada çıkacak.");
        srcLine.setText("");
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1000);
        startBtn.setText("■ Durdur");
        startBtn.setBackgroundColor(0xFFC0392B);
        muteBeeps(true);
        startListening();
    }

    void stopAll() {
        running = false;
        listening = false;
        h.removeCallbacks(restartRunnable);
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) { }
        }
        if (tts != null) tts.stop();
        pending = 0;
        triedProjection = false;
        remoteFailed = false;
        h.removeCallbacks(tick);
        closePipe();
        try { stopService(new Intent(this, CaptureService.class)); } catch (Exception ignored) { }
        if (startBtn != null) {
            startBtn.setText("▶ Başlat");
            startBtn.setBackgroundColor(0xFFFF4FA3);
            interimTv.setText("");
            setStatus("Durduruldu");
        }
        muteBeeps(false);
    }

    boolean beepsMuted = false;

    void closePipe() {
        OutputStream s = CaptureService.sink;
        CaptureService.sink = null;
        try { if (s != null) s.close(); } catch (IOException ignored) { }
        try { if (readFd != null) readFd.close(); } catch (IOException ignored) { }
        readFd = null;
    }

    void muteBeeps(boolean m) {
        if (m == beepsMuted) return;
        beepsMuted = m;
        try {
            int dir = m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ konuşma tanıma

    final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float r) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle p) { }

        @Override
        public void onPartialResults(Bundle p) {
            ArrayList<String> r = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) {
                interimTv.setText(r.get(0));
                if (!r.get(0).trim().isEmpty()) lastRecogMs = SystemClock.elapsedRealtime();
            }
        }

        @Override
        public void onResults(Bundle p) {
            ArrayList<String> r = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            interimTv.setText("");
            if (r != null && !r.isEmpty()) {
                lastRecogMs = SystemClock.elapsedRealtime();
                handleFinal(r.get(0));
            }
            next(100);
        }

        @Override
        public void onError(int e) {
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                showErr("Mikrofon izni yok.");
                stopAll();
                return;
            }
            if (e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                showErr("İnternet bağlantısı gerekiyor.");
            }
            if (e == 13 && Build.VERSION.SDK_INT >= 33) { // ERROR_LANGUAGE_UNAVAILABLE
                showErr("Telefonda bu dilin konuşma tanıma paketi yok, indiriliyor. Bitince tekrar Başlat'a basın.");
                try { if (sr != null) sr.triggerModelDownload(buildIntent()); } catch (Exception ignored) { }
                stopAll();
                return;
            }
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                try { if (sr != null) sr.destroy(); } catch (Exception ignored) { }
                sr = null;
                next(400);
                return;
            }
            next(150);
        }
    };

    void next(long ms) {
        if (running && (noEchoRisk() || pending == 0)) restartSoon(ms);
    }

    void restartSoon(long ms) {
        h.removeCallbacks(restartRunnable);
        h.postDelayed(restartRunnable, ms);
    }

    boolean srOnDevice = false;

    Intent buildIntent() {
        String lang = LANGS[Math.max(0, langSp.getSelectedItemPosition())][0];
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        return i;
    }

    void startListening() {
        if (!running) return;
        boolean remote = remoteActive();
        boolean wantOnDevice = remote && Build.VERSION.SDK_INT >= 33
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        if (sr != null && srOnDevice != wantOnDevice) {
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
        if (sr == null) {
            sr = wantOnDevice
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this);
            srOnDevice = wantOnDevice;
            sr.setRecognitionListener(listener);
        }
        if (remote && SystemClock.elapsedRealtime() - CaptureService.lastLoudMs > 20000) {
            showErr("Remote Play'den ses gelmiyor. Remote Play açık ve oyun sesi geliyor mu kontrol edin. Hâlâ gelmiyorsa Remote Play sesin alınmasına izin vermiyor demektir; ayarlardan Remote Play modunu kapatıp mikrofonla kullanın.");
        }
        String lang = LANGS[Math.max(0, langSp.getSelectedItemPosition())][0];
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        if (remote) {
            try {
                closePipe();
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readFd = pipe[0];
                CaptureService.sink = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CaptureService.RATE);
            } catch (IOException ex) {
                showErr("Remote Play sesi bağlanamadı, mikrofon kullanılıyor.");
            }
        }
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        listening = true;
        try {
            sr.startListening(i);
            setStatus("Dinliyor…");
        } catch (Exception e) {
            restartSoon(500);
        }
    }

    void pauseListening() {
        listening = false;
        h.removeCallbacks(restartRunnable);
        if (sr != null) {
            try { sr.cancel(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------ çeviri

    void handleFinal(String raw) {
        final String src = raw.trim();
        if (src.length() < 2 || src.equals(lastFinal)) return;
        lastFinal = src;
        final float f0 = remoteActive() ? CaptureService.recentPitch() : 0f;
        String code = LANGS[Math.max(0, langSp.getSelectedItemPosition())][0];
        final String sl = code.startsWith("zh") ? "zh-CN" : code.substring(0, 2);
        net.execute(() -> {
            final String tr = translate(src, sl);
            h.post(() -> {
                if (tr == null || tr.isEmpty()) {
                    showErr("Çeviri alınamadı, internet bağlantısını kontrol edin.");
                    return;
                }
                showErr("");
                trLine.setText(tr);
                srcLine.setText(src);
                String tag = speak(tr, f0);
                addLog(tag, tr, src);
            });
        });
    }

    String translate(String text, String sl) {
        String key = sl + "|" + text;
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
        }
        String out = null;
        try {
            String u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + sl
                    + "&tl=tr&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
            JSONArray a = new JSONArray(httpGet(u));
            JSONArray parts = a.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONArray p = parts.optJSONArray(i);
                if (p != null && !p.isNull(0)) sb.append(p.optString(0));
            }
            out = sb.toString();
        } catch (Exception ignored) { }
        if (out == null || out.isEmpty()) {
            try {
                String u = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8")
                        + "&langpair=" + sl + "%7Ctr";
                JSONObject o = new JSONObject(httpGet(u));
                out = o.getJSONObject("responseData").optString("translatedText", null);
            } catch (Exception ignored) { }
        }
        if (out != null && !out.isEmpty()) {
            synchronized (cache) {
                if (cache.size() > 500) cache.clear();
                cache.put(key, out);
            }
        }
        return out;
    }

    static String httpGet(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ Türkçe seslendirme

    void loadVoices() {
        trVoices.clear();
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs != null) {
                for (Voice v : vs) {
                    Locale l = v.getLocale();
                    boolean notInstalled = v.getFeatures() != null
                            && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                    if (l != null && "tr".equals(l.getLanguage()) && !notInstalled) trVoices.add(v);
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(trVoices, (x, y) -> x.getName().compareTo(y.getName()));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < trVoices.size(); i++) {
            Voice v = trVoices.get(i);
            names.add("Ses " + (i + 1) + (v.isNetworkConnectionRequired() ? " (internet)" : " (telefonda)"));
        }
        if (names.isEmpty()) names.add("Varsayılan Türkçe ses");
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSp.setAdapter(ad);
        String saved = prefs.getString("voice", "");
        for (int i = 0; i < trVoices.size(); i++) {
            if (trVoices.get(i).getName().equals(saved)) voiceSp.setSelection(i);
        }
    }

    Voice selectedVoice() {
        int pos = voiceSp.getSelectedItemPosition();
        if (pos >= 0 && pos < trVoices.size()) return trVoices.get(pos);
        return null;
    }

    String speak(String text) {
        return speak(text, 0f);
    }

    String speak(String text, float f0) {
        if (!ttsSw.isChecked() || !ttsReady) return "Altyazı";
        if (skipSw.isChecked() && pending >= 2) {
            tts.stop();
            pending = 0;
        }
        float base = 0.7f + rateSb.getProgress() / 100f;
        float vol = 0.2f + volSb.getProgress() / 100f;
        float pitch = 1f, rate = base;
        String tag = "Anlatıcı";
        if (npcSw.isChecked() && f0 > 0) {
            int k = f0 < 110 ? 0 : f0 < 140 ? 1 : f0 < 165 ? 2 : f0 < 200 ? 3 : f0 < 240 ? 4 : 5;
            pitch = BPITCH[k];
            rate = base * BRATE[k];
            tag = BNAME[k];
            if (!trVoices.isEmpty()) tts.setVoice(trVoices.get(k % trVoices.size()));
        } else if (npcSw.isChecked()) {
            int k = npcIdx % PNAME.length;
            pitch = PPITCH[k];
            rate = base * PRATE[k];
            tag = PNAME[k];
            if (!trVoices.isEmpty()) tts.setVoice(trVoices.get(npcIdx % trVoices.size()));
            npcIdx++;
        } else {
            Voice v = selectedVoice();
            if (v != null) tts.setVoice(v);
        }
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        Bundle p = new Bundle();
        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.min(1f, vol));
        pending++;
        tts.speak(text, TextToSpeech.QUEUE_ADD, p, "u" + System.nanoTime());
        return tag;
    }

    void utterDone() {
        pending = Math.max(0, pending - 1);
        if (pending == 0 && running && !listening) restartSoon(150);
    }

    void test() {
        if (!ttsReady) {
            showErr("Ses motoru henüz hazır değil, birkaç saniye sonra tekrar deneyin.");
            return;
        }
        boolean was = ttsSw.isChecked();
        ttsSw.setChecked(true);
        String[] lines = {
                "Hey, arabaya bin, polisler geliyor!",
                "Bunu yapmak zorunda değiliz, biliyorsun.",
                "Kimse kıpırdamasın!",
                "Bana bak evlat, bu şehir seni yer bitirir."
        };
        for (String l : lines) addLog(speak(l), l, "(ses testi)");
        ttsSw.setChecked(was);
    }

    // ------------------------------------------------------------ arayüz

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    TextView tv(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button btn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    void section(String name) {
        TextView t = tv(name, 14, 0xFFFF4FA3, true);
        t.setPadding(0, dp(22), 0, dp(6));
        settingsBox.addView(t);
    }

    void label(String name) {
        TextView t = tv(name, 14, 0xFF8B93A7, false);
        t.setPadding(0, dp(10), 0, dp(2));
        settingsBox.addView(t);
    }

    Switch sw(String text, boolean def, final String key) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(key, c).apply());
        settingsBox.addView(s);
        return s;
    }

    SeekBar sb(int max, final String key, int def) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(prefs.getInt(key, def));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) { prefs.edit().putInt(key, p).apply(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        settingsBox.addView(s);
        return s;
    }

    void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0D0F14);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(root);

        title = tv("🎮 Oyun Çevirmen", 22, Color.WHITE, true);
        statusTv = tv("Hazır", 13, 0xFF8B93A7, false);
        statusTv.setPadding(0, dp(2), 0, dp(12));
        root.addView(title);
        root.addView(statusTv);

        big = new LinearLayout(this);
        big.setOrientation(LinearLayout.VERTICAL);
        big.setGravity(Gravity.BOTTOM);
        big.setBackgroundColor(Color.BLACK);
        big.setPadding(dp(16), dp(16), dp(16), dp(16));
        big.setMinimumHeight(dp(170));
        trLine = tv("Başlat'a basın, telefonu TV / monitör hoparlörüne yakın tutun.", 26, Color.WHITE, true);
        srcLine = tv("", 14, 0xFF8B93A7, false);
        srcLine.setPadding(0, dp(8), 0, 0);
        interimTv = tv("", 13, 0xFF5D6475, false);
        interimTv.setTypeface(null, Typeface.ITALIC);
        big.addView(trLine);
        big.addView(srcLine);
        big.addView(interimTv);
        big.setOnClickListener(v -> { if (full) setFull(false); });
        root.addView(big);

        startBtn = btn("▶ Başlat", 0xFFFF4FA3);
        startBtn.setTextSize(18);
        startBtn.setOnClickListener(v -> { if (running) stopAll(); else startAll(); });
        root.addView(startBtn);

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(settingsBox);

        Button fullBtn = btn("⛶ Tam ekran altyazı", 0xFF2B3040);
        fullBtn.setOnClickListener(v -> setFull(true));
        settingsBox.addView(fullBtn);
        Button testBtn = btn("🔊 Ses testi", 0xFF2B3040);
        testBtn.setOnClickListener(v -> test());
        settingsBox.addView(testBtn);

        errTv = tv("", 13, 0xFFFFB020, false);
        errTv.setPadding(0, dp(8), 0, 0);
        settingsBox.addView(errTv);

        section("OYUNUN DİLİ");
        langSp = new Spinner(this);
        List<String> ln = new ArrayList<>();
        for (String[] l : LANGS) ln.add(l[1]);
        ArrayAdapter<String> la = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ln);
        la.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSp.setAdapter(la);
        langSp.setSelection(Math.min(LANGS.length - 1, prefs.getInt("lang", 0)));
        langSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != prefs.getInt("lang", 0)) {
                    prefs.edit().putInt("lang", pos).apply();
                    if (running) { pauseListening(); restartSoon(200); }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        settingsBox.addView(langSp);

        section("TÜRKÇE SES (DUBLAJ)");
        ttsSw = sw("Sesli okuma", true, "tts");
        npcSw = sw("Her replik farklı karakter sesi", true, "npc");
        label("Ana ses");
        voiceSp = new Spinner(this);
        voiceSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < trVoices.size()) prefs.edit().putString("voice", trVoices.get(pos).getName()).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        settingsBox.addView(voiceSp);
        label("Konuşma hızı");
        rateSb = sb(90, "rate2", 55);
        label("Ses düzeyi");
        volSb = sb(80, "vol", 80);
        hpSw = sw("Kulaklık takıyorum (yankı korumasını kapat)", false, "hp");
        skipSw = sw("Gecikirse eski replikleri atla", true, "skip");
        TextView hint = tv("Karakter modunda her replik farklı ton ve hızla okunur (ana karakter, kadın, sert NPC, genç, yaşlı, polis telsizi). Telefonda ne kadar çok Türkçe ses yüklüyse çeşitlilik o kadar artar.", 13, 0xFF8B93A7, false);
        hint.setPadding(0, dp(6), 0, 0);
        settingsBox.addView(hint);

        section("PS REMOTE PLAY BAĞLANTISI (DENEYSEL)");
        remoteSw = sw("Remote Play sesini doğrudan dinle (Android 13+)", false, "remote");
        TextView rh = tv("Açıkken uygulama, telefondaki PS Remote Play'in sesini mikrofon yerine doğrudan alır: oda gürültüsü karışmaz, kadın/erkek sesi ayırt edilip her konuşana sabit bir Türkçe ses verilir. Başlat'a basın, çıkan paylaşım iznini verin, sonra Remote Play'i açıp oynayın. Remote Play bu izni vermezse uygulama uyarır; o zaman bu ayarı kapatıp mikrofonla kullanın.", 13, 0xFF8B93A7, false);
        rh.setPadding(0, dp(6), 0, 0);
        settingsBox.addView(rh);

        section("SON REPLİKLER");
        logTv = tv("Henüz bir şey yok.", 14, 0xFFEEF1F6, false);
        settingsBox.addView(logTv);

        section("İPUÇLARI");
        settingsBox.addView(tv(
                "• Telefonu TV / monitör hoparlörüne 0,5–1 m yakın koyun.\n"
                        + "• Oyunda müziği ve efektleri biraz kısın, diyalog sesini açın.\n"
                        + "• En iyisi: telefona Bluetooth kulaklık takıp \"Kulaklık takıyorum\"u açın.\n"
                        + "• İnternet bağlantısı gerekir.\n"
                        + "• Daha doğal ses için: Ayarlar > Metin okuma çıkışı > Google > Ses verilerini yükle > Türkçe.",
                13, 0xFF8B93A7, false));

        setContentView(sv);
    }

    @SuppressWarnings("deprecation")
    void setFull(boolean f) {
        full = f;
        int vis = f ? View.GONE : View.VISIBLE;
        title.setVisibility(vis);
        statusTv.setVisibility(vis);
        startBtn.setVisibility(vis);
        settingsBox.setVisibility(vis);
        big.setMinimumHeight(f ? getResources().getDisplayMetrics().heightPixels : dp(170));
        trLine.setTextSize(f ? 40 : 26);
        getWindow().getDecorView().setSystemUiVisibility(f
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                : 0);
    }

    void setStatus(String s) {
        statusTv.setText(s);
        statusTv.setTextColor(running ? 0xFF35D07F : 0xFF8B93A7);
    }

    void showErr(String s) {
        errTv.setText(s == null ? "" : s);
    }

    void addLog(String tag, String tr, String src) {
        logLines.addFirst("[" + tag + "] " + tr + "\n      " + src);
        while (logLines.size() > 30) logLines.removeLast();
        logTv.setText(TextUtils.join("\n\n", logLines));
    }
}
