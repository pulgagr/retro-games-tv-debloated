package com.example.retrosnes;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

// Runs one SNES game full screen. The emulator thread is paced by the audio: each frame's
// samples go to a blocking AudioTrack.write, so the game runs at exactly real-time speed.
public class GameActivity extends Activity implements SurfaceHolder.Callback, Runnable, Handler.Callback {
    static { System.loadLibrary("snes"); }
    static native int nativeLoad(String romPath, String saveDir);
    static native void nativeSurface(Object surface);
    static native int nativeFrame(int buttons, short[] audio);
    static native void nativeUnload();
    static native double nativeFps();
    static native long nativeWait();

    // libretro joypad bits
    private static final int B = 1, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5,
            LEFT = 1 << 6, RIGHT = 1 << 7, A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11;
    private static final int DPAD = UP | DOWN | LEFT | RIGHT;

    private volatile boolean running;
    private volatile int keys, stick;      // buttons held on keys, and directions from sticks/hat
    private volatile int tapped;           // pressed since the last frame, so even a very short tap counts
    private Thread thread;
    private int rate;
    private TextView fps;
    private Handler ui;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Handler(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = Math.min(dm.heightPixels, dm.widthPixels), w = h * 4 / 3;   // 4:3 like a CRT
        root.addView(view, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        fps = new TextView(this);
        fps.setTextColor(0x59FFFFFF);
        fps.setTextSize(11);
        fps.setShadowLayer(3, 0, 0, Color.BLACK);
        root.addView(fps, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
        fps.setPadding(0, 6, 12, 0);
        setContentView(root);

        String rom = getIntent().getStringExtra("rom");
        try {
            File f = new File(getCacheDir(), rom);
            InputStream in = getAssets().open("roms/" + rom);
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close(); out.close();
            rate = nativeLoad(f.getPath(), getFilesDir().getPath());
        } catch (Exception e) { rate = 0; }
        if (rate == 0) { fps.setText("Could not load " + rom); fps.setTextSize(24); }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (rate == 0) return;
        nativeSurface(h.getSurface());
        running = true;
        thread = new Thread(this, "emu");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        if (thread != null) try { thread.join(); } catch (InterruptedException ignored) {}
        thread = null;
        nativeSurface(null);
    }

    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        // The screen only takes a new frame at the TV's refresh (60.0 Hz) but the NES runs at 60.1 Hz.
        // Playing the audio that much slower keeps both clocks in step, so the audio never runs dry.
        float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        int out = Math.round(rate * hz / (float) nativeFps());
        int min = AudioTrack.getMinBufferSize(out, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        // Room for ~8 frames of audio; we aim to keep about 4 queued (~67 ms)
        int target = out / 60 * 4;
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, out, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, target * 2 * 4), AudioTrack.MODE_STREAM);
        track.play();
        short[] audio = new short[8192];
        int frames = 0, underruns = gaps(track);
        long written = 0;
        long t0 = System.nanoTime(), busy = 0;
        nativeWait();
        while (running) {
            long s = System.nanoTime();
            int in = keys | stick | tapped;
            tapped = 0;
            int n = nativeFrame(in, audio);
            busy += System.nanoTime() - s;
            track.write(audio, 0, n);
            written += n / 2;
            frames++;
            // The two clocks still drift a little (the TV's real refresh is never exactly what it
            // reports), so nudge the playback speed by up to 0.5% to keep the queue near target.
            // Like RetroArch's dynamic rate control; far too small a change to hear.
            if ((frames & 7) == 0) {
                long queued = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                float adjust = Math.max(-0.005f, Math.min(0.005f, 0.005f * (queued - target) / target));
                track.setPlaybackRate(Math.round(out * (1 + adjust)));
            }
            long now = System.nanoTime();
            if (now - t0 >= 1000000000L) {
                // fps, how much of each frame the emulator itself needed (ms), and audio dropouts
                busy -= nativeWait();
                int u = gaps(track);
                ui.obtainMessage(u - underruns, Math.round(frames * 1e9f / (now - t0)), (int) (busy / frames / 100000)).sendToTarget();
                underruns = u;
                frames = 0; busy = 0; t0 = now;
            }
        }
        track.stop();
        track.release();
    }

    private static int gaps(AudioTrack t) { return Build.VERSION.SDK_INT >= 24 ? t.getUnderrunCount() : 0; }

    @Override public boolean handleMessage(Message m) {
        String t = m.arg1 + " fps  " + (m.arg2 / 10f) + " ms" + (m.what > 0 ? "  audio gaps " + m.what : "");
        fps.setText(t);
        Log.i("RetroSNES", t);
        return true;
    }

    private static int button(KeyEvent e) {
        boolean pad = (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            // Controller face buttons by position, like the SNES pad: bottom B, right A, left Y, top X
            case KeyEvent.KEYCODE_BUTTON_A: return B;
            case KeyEvent.KEYCODE_BUTTON_B: return A;
            case KeyEvent.KEYCODE_BUTTON_X: return Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return X;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_L2: return L;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: return R;
            // Some controllers send their B button as Back; only the remote's Back leaves the game
            case KeyEvent.KEYCODE_BACK: return pad ? A : 0;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_MENU: return SELECT;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return A;   // remote OK
            default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int b = button(e);
        if (b == 0) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) { keys |= b; tapped |= b; } else if (e.getAction() == KeyEvent.ACTION_UP) keys &= ~b;
        // Start + Select together also leaves the game
        if ((keys & (START | SELECT)) == (START | SELECT)) finish();
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK)
            return super.onGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (x == 0) x = e.getAxisValue(MotionEvent.AXIS_X);
        if (y == 0) y = e.getAxisValue(MotionEvent.AXIS_Y);
        int s = 0;
        if (x < -0.5f) s |= LEFT; else if (x > 0.5f) s |= RIGHT;
        if (y < -0.5f) s |= UP; else if (y > 0.5f) s |= DOWN;
        stick = s & DPAD;
        return true;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (rate != 0) nativeUnload();
    }
}
