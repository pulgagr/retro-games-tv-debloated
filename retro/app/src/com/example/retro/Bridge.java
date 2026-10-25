package com.example.retro;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import java.io.File;

// What menu.html can call, as window.Android
public class Bridge {
    private final Activity activity;

    Bridge(Activity activity) { this.activity = activity; }

    /** raw: keep the zip as is (arcade sets); deps: other share paths it needs ("|"-separated, e.g. parent, BIOS) */
    @JavascriptInterface
    public void play(String core, String host, String share, String path, String key, String title,
                     boolean raw, String deps) {
        activity.startActivity(new Intent(activity, GameActivity.class)
                .putExtra("core", core).putExtra("host", host).putExtra("share", share)
                .putExtra("path", path).putExtra("key", key).putExtra("title", title)
                .putExtra("raw", raw).putExtra("deps", deps));
    }

    /** Keys ("console/game") of games with a resume point, one per line */
    @JavascriptInterface
    public String saved() {
        StringBuilder sb = new StringBuilder();
        File[] consoles = new File(activity.getFilesDir(), "saves").listFiles();
        if (consoles != null) for (File c : consoles) {
            File[] files = c.listFiles();
            if (files != null) for (File f : files)
                if (f.getName().endsWith(".auto"))
                    sb.append(c.getName()).append('/').append(f.getName().replaceFirst("\\.auto$", "")).append('\n');
        }
        return sb.toString();
    }
}
