package com.example.retro;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;

// The console and game menu: assets/menu.html in a WebView, with Bridge as "Android" for starting games
public class MenuActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF12121C);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/menu.html");
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A game may have been saved or finished: update the "Continue" badges
        web.evaluateJavascript("typeof refreshSaved == 'function' && refreshSaved()", null);
    }

    /** Buttons the web page has no key codes for, passed to its pad(): favorite, surprise, letter jumps */
    private static String padButton(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_MENU: return "y";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_MEDIA_REWIND: return "l";
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "r";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        String b = padButton(k);
        if (b != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN)
                web.evaluateJavascript("typeof pad == 'function' && pad('" + b + "')", null);
            return true;
        }
        // Controller A picks, B goes back
        if (k == KeyEvent.KEYCODE_BUTTON_A)
            return super.dispatchKeyEvent(new KeyEvent(e.getAction(), KeyEvent.KEYCODE_ENTER));
        if (k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK) {
            // Game list -> console picker -> exit
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (web.canGoBack()) web.goBack(); else finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
