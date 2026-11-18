package com.mamibey.oyuncevirmen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * PS Remote Play (veya başka bir uygulama) tarafından çalınan sesi doğrudan yakalar.
 * Ses, konuşma tanımaya bir boru (pipe) üzerinden verilir; ayrıca konuşanın ses perdesi
 * ölçülerek kadın / erkek / kalın / ince ses ayrımı yapılır.
 */
public class CaptureService extends Service {

    static final int RATE = 16000;

    static volatile CaptureService inst;
    static volatile OutputStream sink;
    static volatile long lastLoudMs = 0;

    MediaProjection mp;
    AudioRecord rec;
    Thread th;
    volatile boolean run = false;

    // son birkaç saniyedeki ses perdesi ölçümleri
    final float[] f0Val = new float[80];
    final long[] f0Time = new long[80];
    int f0Pos = 0;
    final Object lock = new Object();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("cap", "Remote Play sesi", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "cap")
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Oyun Çevirmen")
                .setContentText("Remote Play sesi dinleniyor ve Türkçeye çevriliyor")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        if (Build.VERSION.SDK_INT < 29 || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            mp = mpm.getMediaProjection(code, data);
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, null);

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(RATE * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            rec.startRecording();
        } catch (SecurityException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        run = true;
        lastLoudMs = SystemClock.elapsedRealtime();
        th = new Thread(this::loop, "ses-yakalama");
        th.start();
        inst = this;
        return START_NOT_STICKY;
    }

    void loop() {
        byte[] buf = new byte[3200]; // 100 ms
        short[] pcm = new short[1600];
        while (run) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) continue;
            OutputStream s = sink;
            if (s != null) {
                try {
                    s.write(buf, 0, n);
                } catch (IOException e) {
                    sink = null;
                }
            }
            int count = n / 2;
            for (int i = 0; i < count; i++) {
                pcm[i] = (short) ((buf[2 * i] & 0xff) | (buf[2 * i + 1] << 8));
            }
            analyze(pcm, count);
        }
    }

    void analyze(short[] x, int n) {
        double e = 0;
        for (int i = 0; i < n; i++) e += (double) x[i] * x[i];
        double rms = Math.sqrt(e / Math.max(1, n));
        if (rms < 300) return;
        lastLoudMs = SystemClock.elapsedRealtime();

        // otokorelasyon ile temel frekans (70–400 Hz)
        int win = Math.min(n, 1024);
        int minLag = RATE / 400, maxLag = RATE / 70;
        if (win <= maxLag + 64) return;
        double best = 0;
        int bestLag = -1;
        double e0 = 0;
        for (int i = 0; i < win - maxLag; i++) e0 += (double) x[i] * x[i];
        if (e0 <= 0) return;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double c = 0, el = 0;
            for (int i = 0; i < win - maxLag; i++) {
                c += (double) x[i] * x[i + lag];
                el += (double) x[i + lag] * x[i + lag];
            }
            if (el <= 0) continue;
            double r = c / Math.sqrt(e0 * el);
            if (r > best) {
                best = r;
                bestLag = lag;
            }
        }
        if (best < 0.55 || bestLag <= 0) return;
        float f0 = (float) RATE / bestLag;
        synchronized (lock) {
            f0Val[f0Pos] = f0;
            f0Time[f0Pos] = SystemClock.elapsedRealtime();
            f0Pos = (f0Pos + 1) % f0Val.length;
        }
    }

    /** Son 3 saniyedeki konuşmanın ortanca ses perdesi (Hz). Ölçüm yoksa 0. */
    static float recentPitch() {
        CaptureService c = inst;
        if (c == null) return 0;
        long now = SystemClock.elapsedRealtime();
        float[] tmp = new float[c.f0Val.length];
        int k = 0;
        synchronized (c.lock) {
            for (int i = 0; i < c.f0Val.length; i++) {
                if (c.f0Time[i] > 0 && now - c.f0Time[i] < 3000) tmp[k++] = c.f0Val[i];
            }
        }
        if (k < 3) return 0;
        Arrays.sort(tmp, 0, k);
        return tmp[k / 2];
    }

    @Override
    public void onDestroy() {
        run = false;
        inst = null;
        try { if (th != null) th.join(300); } catch (InterruptedException ignored) { }
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        try { if (mp != null) mp.stop(); } catch (Exception ignored) { }
        OutputStream s = sink;
        sink = null;
        try { if (s != null) s.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }
}
