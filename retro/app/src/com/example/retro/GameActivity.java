package com.example.retro;

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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

// Runs one game full screen. The emulator thread is paced by the audio (each frame's samples go to a
// blocking AudioTrack.write) and by the screen. Back opens a pause menu with save states.
// Saves live in files/saves/<console>/<game>: .srm (in-game battery save), .state (manual save),
// .auto (where you left off; loaded automatically next time).
public class GameActivity extends Activity implements SurfaceHolder.Callback, Runnable, Handler.Callback,
        View.OnClickListener, View.OnFocusChangeListener {
    static { System.loadLibrary("frontend"); }
    static native int nativeLoad(String corePath, String romPath, String sysDir);
    static native void nativeSurface(Object surface);
    static native int nativeFrame(int buttons, short[] audio);
    static native double nativeFps();
    static native double nativeAspect();
    static native long nativeWait();
    static native boolean nativeSaveState(String path);
    static native boolean nativeLoadState(String path);
    static native void nativeSram(String path, boolean save);
    static native void nativeReset();
    static native void nativeUnload();

    // libretro joypad bits
    private static final int B = 1, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5,
            LEFT = 1 << 6, RIGHT = 1 << 7, A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11;
    private static final int DPAD = UP | DOWN | LEFT | RIGHT;
    private static final int MSG_FPS = 0, MSG_STATUS = 1, MSG_READY = 2, MSG_FAILED = 3, MSG_HIDE_NOTE = 4;
    private static final String[] MENU = {"Resume", "Save state", "Load state", "Restart game", "Quit"};

    private final Object lock = new Object();   // held while a frame runs; save/load take it too
    private volatile boolean running, paused, ready;
    private volatile int keys, stick, tapped;
    private Thread thread;
    private int rate;
    private boolean surface;
    private Handler ui;
    private SurfaceView screen;
    private TextView fps, status, note;
    private LinearLayout menu;
    private File srm, state, auto;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Handler(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView view = screen = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, size(4 / 3f));

        fps = text(11, 0x59FFFFFF);
        fps.setPadding(0, 6, 12, 0);
        root.addView(fps, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
        status = text(24, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setText(getIntent().getStringExtra("title"));
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        note = text(18, Color.WHITE);
        note.setBackgroundColor(0xCC000000);
        note.setPadding(24, 12, 24, 12);
        note.setVisibility(View.GONE);
        root.addView(note, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(0xE6101018);
        menu.setPadding(40, 30, 40, 30);
        TextView head = text(20, 0xFFFFC107);
        head.setText(getIntent().getStringExtra("title"));
        head.setPadding(20, 0, 20, 16);
        menu.addView(head);
        for (int i = 0; i < MENU.length; i++) {
            TextView item = text(22, Color.WHITE);
            item.setText(MENU[i]);
            item.setTag(i);
            item.setPadding(20, 12, 20, 12);
            item.setFocusable(true);
            item.setClickable(true);
            item.setOnClickListener(this);
            item.setOnFocusChangeListener(this);
            menu.addView(item);
        }
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        String key = getIntent().getStringExtra("key");          // "console/game"
        File saves = new File(getFilesDir(), "saves/" + key.substring(0, key.indexOf('/')));
        saves.mkdirs();
        String game = key.substring(key.indexOf('/') + 1);
        srm = new File(saves, game + ".srm");
        state = new File(saves, game + ".state");
        auto = new File(saves, game + ".auto");

        File roms = new File(getFilesDir(), "roms/" + key.substring(0, key.indexOf('/')));
        new Thread(new Loader(this, getIntent().getStringExtra("host"), getIntent().getStringExtra("share"),
                getIntent().getStringExtra("path"), roms, game, getIntent().getBooleanExtra("raw", false),
                getIntent().getStringExtra("deps")), "loader").start();
    }

    /** Largest centered box of this width/height ratio that fits the screen */
    private FrameLayout.LayoutParams size(float aspect) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = Math.min(dm.heightPixels, dm.widthPixels), w = Math.round(h * aspect);
        if (w > dm.widthPixels) { w = dm.widthPixels; h = Math.round(w / aspect); }
        return new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(3, 0, 0, Color.BLACK);
        return t;
    }

    // --- Called by Loader, on its thread ---

    void status(String s) { ui.obtainMessage(MSG_STATUS, s).sendToTarget(); }

    void failed(String s) { ui.obtainMessage(MSG_FAILED, s).sendToTarget(); }

    void loaded(File rom) {
        String core = getApplicationInfo().nativeLibraryDir + "/lib" + getIntent().getStringExtra("core") + ".so";
        rate = nativeLoad(core, rom.getPath(), getFilesDir().getPath());
        if (rate == 0) {
            rom.delete();   // maybe a bad download: fetch it again next time
            failed("This game didn't load.\n\nPress Back.");
            return;
        }
        nativeSram(srm.getPath(), false);
        boolean resumed = auto.exists() && nativeLoadState(auto.getPath());
        ui.obtainMessage(MSG_READY, resumed ? 1 : 0, 0).sendToTarget();
    }

    // --- Emulator thread ---

    private void start() {
        if (!ready || !surface || thread != null) return;
        running = true;
        thread = new Thread(this, "emu");
        thread.start();
    }

    private void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        if (thread != null) try { thread.join(); } catch (InterruptedException ignored) {}
        thread = null;
    }

    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        // The screen takes a new frame at the TV's refresh (60.0 Hz) but consoles run at e.g. 60.1 Hz.
        // Playing the audio that much slower keeps both clocks in step, so the audio never runs dry.
        float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        int out = Math.round(rate * hz / (float) nativeFps());
        int min = AudioTrack.getMinBufferSize(out, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int target = out / 60 * 4;   // aim for ~4 frames (~67 ms) of audio queued, room for 8
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, out, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, target * 2 * 4), AudioTrack.MODE_STREAM);
        track.play();
        short[] audio = new short[8192];
        int frames = 0, underruns = gaps(track);
        long t0 = System.nanoTime(), busy = 0, written = 0;
        nativeWait();
        while (running) {
            int n;
            synchronized (lock) {
                if (paused) {
                    track.pause();
                    while (paused && running) try { lock.wait(); } catch (InterruptedException ignored) {}
                    track.play();
                    t0 = System.nanoTime(); frames = 0; busy = 0; nativeWait();
                    if (!running) break;
                }
                int in = keys | stick | tapped;
                tapped = 0;
                long s = System.nanoTime();
                n = nativeFrame(in, audio);
                busy += System.nanoTime() - s;
            }
            track.write(audio, 0, n);
            written += n / 2;
            frames++;
            // The clocks still drift a little (the TV's real refresh is never exactly what it reports),
            // so nudge the playback speed by up to 0.5% to keep the queue near target, like RetroArch's
            // dynamic rate control. Far too small a change to hear.
            if ((frames & 7) == 0) {
                long queued = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                float adjust = Math.max(-0.005f, Math.min(0.005f, 0.005f * (queued - target) / target));
                track.setPlaybackRate(Math.round(out * (1 + adjust)));
            }
            long now = System.nanoTime();
            if (now - t0 >= 1000000000L) {
                busy -= nativeWait();
                int u = gaps(track);
                ui.obtainMessage(MSG_FPS, Math.round(frames * 1e9f / (now - t0)), (int) (busy / frames / 100000),
                        u - underruns).sendToTarget();
                underruns = u; frames = 0; busy = 0; t0 = now;
            }
        }
        track.stop();
        track.release();
    }

    private static int gaps(AudioTrack t) { return Build.VERSION.SDK_INT >= 24 ? t.getUnderrunCount() : 0; }

    // --- UI thread ---

    @Override public boolean handleMessage(Message m) {
        switch (m.what) {
            case MSG_FPS:
                String t = m.arg1 + " fps  " + (m.arg2 / 10f) + " ms" + ((Integer) m.obj > 0 ? "  audio gaps " + m.obj : "");
                fps.setText(t);
                Log.i("Retro", t);
                break;
            case MSG_STATUS: status.setText(getIntent().getStringExtra("title") + "\n" + m.obj); break;
            case MSG_FAILED: status.setText((String) m.obj); break;
            case MSG_READY:
                status.setVisibility(View.GONE);
                ready = true;
                // Home consoles are shown 4:3 like on a CRT TV; handhelds (Game Boy, Game Gear: 10:9)
                // keep their own nearly square screen instead of being stretched
                float aspect = (float) nativeAspect();
                if (aspect < 1.15f) screen.setLayoutParams(size(aspect));
                if (m.arg1 == 1) note("Continuing where you left off  ·  Back for menu");
                start();
                break;
            case MSG_HIDE_NOTE: note.setVisibility(View.GONE); break;
        }
        return true;
    }

    private void note(String s) {
        note.setText(s);
        note.setVisibility(View.VISIBLE);
        ui.removeMessages(MSG_HIDE_NOTE);
        ui.sendEmptyMessageDelayed(MSG_HIDE_NOTE, 3000);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        nativeSurface(h.getSurface());
        surface = true;
        start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        stop();
        surface = false;
        nativeSurface(null);
    }

    private void showMenu(boolean show) {
        if (!ready) return;
        paused = show;
        synchronized (lock) { lock.notifyAll(); }
        menu.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            menu.getChildAt(3).setEnabled(state.exists());            // "Load state"
            menu.getChildAt(3).setAlpha(state.exists() ? 1f : 0.4f);
            menu.getChildAt(1).requestFocus();
        }
        keys = stick = tapped = 0;
    }

    @Override public void onClick(View v) {
        int item = (Integer) v.getTag();
        synchronized (lock) {     // the emulator thread is waiting: safe to touch the game
            switch (item) {
                case 1:
                    boolean ok = nativeSaveState(state.getPath());
                    nativeSram(srm.getPath(), true);
                    note(ok ? "Saved" : "This game can't be saved");
                    break;
                case 2: note(nativeLoadState(state.getPath()) ? "Loaded" : "Couldn't load the save"); break;
                case 3: nativeReset(); auto.delete(); note("Restarted"); break;
                case 4: saveAll(); finish(); return;
            }
        }
        showMenu(false);
    }

    @Override public void onFocusChange(View v, boolean has) {
        v.setBackgroundColor(has ? 0xFF26A69A : 0);
    }

    /** Remembers where you are (and the in-game save), so the game continues there next time */
    private void saveAll() {
        if (!ready) return;
        synchronized (lock) {
            nativeSram(srm.getPath(), true);
            nativeSaveState(auto.getPath());
        }
    }

    @Override protected void onPause() {
        super.onPause();
        // Leaving (Home, or the TV going to sleep): stop the game and remember the spot
        if (ready && !paused) showMenu(true);
        saveAll();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stop();
        if (ready) nativeUnload();
    }

    private static int button(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            // Face buttons by position, like the SNES pad: bottom B, right A, left Y, top X.
            // Cores map these to their console (NES: A/B, Master System: 2/1).
            case KeyEvent.KEYCODE_BUTTON_A: return B;
            case KeyEvent.KEYCODE_BUTTON_B: return A;
            case KeyEvent.KEYCODE_BUTTON_X: return Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return X;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_L2: return L;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: return R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_MENU: return SELECT;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return A;   // remote OK
            default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!ready) {   // still downloading or loading: Back leaves
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_B) {
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (menu.getVisibility() == View.VISIBLE) {
            // Pause menu: normal focus navigation; A picks, B / Back / Start resumes
            if (k == KeyEvent.KEYCODE_BUTTON_A)
                return super.dispatchKeyEvent(new KeyEvent(e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER));
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BUTTON_START) {
                if (e.getAction() == KeyEvent.ACTION_UP) showMenu(false);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        int b = button(e);
        if (b == 0) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (e.getAction() == KeyEvent.ACTION_UP) showMenu(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) { keys |= b; tapped |= b; }
        else if (e.getAction() == KeyEvent.ACTION_UP) keys &= ~b;
        // Start + Select together also opens the menu (Back on the remote or controller does too)
        if ((keys & (START | SELECT)) == (START | SELECT)) showMenu(true);
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || paused)
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
}
