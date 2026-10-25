package com.example.retrosnes;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Arrays;

// Test menu: one focusable row per ROM in assets/roms
// (No anonymous classes: this d8 version crashes on them when built with JDK 21's javac)
public class MainActivity extends Activity implements View.OnClickListener, View.OnFocusChangeListener {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(80, 40, 80, 40);
        TextView title = row("Retro SNES");
        title.setTextColor(0xFF7E57C2);
        title.setFocusable(false);
        list.addView(title);

        String[] roms;
        try { roms = getAssets().list("roms"); } catch (Exception e) { roms = new String[0]; }
        Arrays.sort(roms);
        for (String rom : roms) {
            if (!rom.toLowerCase().matches(".*\\.(sfc|smc)$")) continue;
            TextView t = row(rom.replaceFirst("(?i)\\.(sfc|smc)$", "").replaceFirst("^zz_", "").replace('_', ' '));
            t.setTag(rom);
            t.setOnClickListener(this);
            list.addView(t);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF12121C);
        scroll.addView(list);
        setContentView(scroll);
        if (list.getChildCount() > 1) list.getChildAt(1).requestFocus();
    }

    private TextView row(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setPadding(24, 14, 24, 14);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener(this);
        return t;
    }

    @Override public void onClick(View v) {
        startActivity(new Intent(this, GameActivity.class).putExtra("rom", (String) v.getTag()));
    }

    @Override public void onFocusChange(View v, boolean has) {
        v.setBackgroundColor(has ? 0xFF7E57C2 : 0);
    }
}
