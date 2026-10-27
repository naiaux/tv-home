package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Screensaver: the home screen's wallpaper (same photo, same 10-minute rotation) with a large clock, the
 * date and the weather in the bottom-left corner. Any key wakes the TV back to whatever was on screen.
 */
public class Screensaver extends DreamService {
    private Ambient.WallpaperView wall;
    private TextView clock, date, weather;
    private final BroadcastReceiver tick = new MainActivity.Receiver(this::update);

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wall = new Ambient.WallpaperView(this);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        // Darker towards the bottom-left so the clock stays readable on bright photos.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{0x00000000, 0x26000000, 0x99000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        clock = text(72, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        date = text(12, "sans-serif-medium", 0xB3FFFFFF);
        date.setLetterSpacing(0.22f);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        weather = text(16, "sans-serif-light", 0xE6FFFFFF);
        info.addView(clock);
        info.addView(date);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.topMargin = dp(10);
        info.addView(weather, wp);
        // Full width: a wrap_content block is measured before the text is set and would clip "6:40" to "6:4".
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2, Gravity.START | Gravity.BOTTOM);
        ip.setMargins(dp(56), 0, 0, dp(48));
        root.addView(info, ip);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        wall.start();
        update();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        unregisterReceiver(tick);
        wall.stop();
    }

    private void update() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        Ambient.Weather w = Ambient.cachedWeather(this);
        weather.setVisibility(w == null ? View.GONE : View.VISIBLE);
        if (w != null) weather.setText(w.line());
        Ambient.refreshWeather(this, this::update);
    }

    private TextView text(int sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
