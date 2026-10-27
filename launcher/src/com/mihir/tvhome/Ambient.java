package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** What the home screen and the screensaver share: the rotating wallpaper and the weather. */
final class Ambient {
    private Ambient() { }

    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;

    /**
     * Full-screen photo that crossfades to the next one every WALLPAPER_INTERVAL_MS. The photo is picked from
     * the wall clock, not a counter, so the home screen and the screensaver always show the same one.
     */
    static final class WallpaperView extends FrameLayout {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::update;
        private String[] names = new String[0];
        private ImageView back, front;
        private String shown;
        private boolean firstShow = true;

        WallpaperView(Context c) {
            super(c);
            try {
                names = c.getAssets().list("wallpapers");
            } catch (java.io.IOException ignored) { }
            back = layer();
            front = layer();
        }

        private ImageView layer() {
            ImageView v = new ImageView(getContext());
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(v, new FrameLayout.LayoutParams(-1, -1));
            return v;
        }

        /** Show the current photo and keep it in step with the clock until stop(). */
        void start() { update(); }

        void stop() { handler.removeCallbacks(tick); }

        private void update() {
            handler.removeCallbacks(tick);
            if (names.length == 0) return;
            long now = System.currentTimeMillis();
            String name = "wallpapers/" + names[(int) ((now / WALLPAPER_INTERVAL_MS) % names.length)];
            // +500 ms so the next tick lands safely inside the next interval.
            handler.postDelayed(tick, WALLPAPER_INTERVAL_MS - now % WALLPAPER_INTERVAL_MS + 500);
            if (name.equals(shown)) return;
            shown = name;
            Context c = getContext();
            new Thread(() -> {
                Bitmap bm = decode(c, name);
                handler.post(() -> show(bm));
            }).start();
        }

        private void show(Bitmap bm) {
            if (bm == null) return;
            // Swap roles: the old front becomes the back, the new photo fades in on top.
            ImageView old = front;
            front = back;
            back = old;
            front.bringToFront();
            front.setImageBitmap(bm);
            front.setAlpha(0f);
            front.animate().alpha(1f).setDuration(firstShow ? 600 : 2000)
                    .withEndAction(() -> back.setImageDrawable(null)) // release the previous photo
                    .start();
            firstShow = false;
        }

        private static Bitmap decode(Context c, String name) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            // Downsample photos larger than 1080p; a full-size camera JPEG can need 50+ MB on a 2 GB TV.
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open(name)) {
                BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1920 && o.outHeight / (o.inSampleSize * 2) >= 1080) o.inSampleSize *= 2;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = c.getAssets().open(name)) {
                return BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- weather

    /** Today's weather from Open-Meteo (free, no key), cached so both screens share one fetch. */
    static final class Weather {
        static final long MAX_AGE_MS = 30 * 60 * 1000;
        int temp, min, max, code;
        boolean day;
        long fetchedAt;

        /** "☀️  14°   18° / 7°": now, then today's high / low. */
        String line() {
            return icon() + "  " + temp + "°   " + max + "° / " + min + "°";
        }

        /** WMO weather code to an emoji. */
        String icon() {
            if (code == 0) return day ? "☀️" : "🌙";
            if (code <= 2) return day ? "🌤️" : "☁️";
            if (code == 3) return "☁️";
            if (code == 45 || code == 48) return "🌫️";
            if (code >= 95) return "⛈️";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
            if (code >= 80 || (code >= 51 && code <= 57)) return "🌦️";
            return "🌧️";
        }
    }

    // ---------------------------------------------------------------- Home Assistant

    static final long ROOMS_MAX_AGE_MS = 2 * 60 * 1000;

    /** "🏠  Living 21.5°   Bedroom 22.0°", or null when Home Assistant isn't set up or hasn't answered yet. */
    static String cachedRooms(Context c) {
        if (c.getString(R.string.ha_token).isEmpty()) return null;
        return c.getSharedPreferences("rooms", Context.MODE_PRIVATE).getString("line", null);
    }

    /** Read every ha_sensors entity if the cache is older than ROOMS_MAX_AGE_MS, then run onUpdated. */
    static void refreshRooms(Context c, Runnable onUpdated) {
        String base = c.getString(R.string.ha_url), token = c.getString(R.string.ha_token);
        SharedPreferences p = c.getSharedPreferences("rooms", Context.MODE_PRIVATE);
        if (token.isEmpty() || System.currentTimeMillis() - p.getLong("fetchedAt", 0) < ROOMS_MAX_AGE_MS) return;
        p.edit().putLong("fetchedAt", System.currentTimeMillis()).apply(); // one fetch at a time, even if it fails
        String[] sensors = c.getResources().getStringArray(R.array.ha_sensors);
        new Thread(() -> {
            StringBuilder line = new StringBuilder("🏠 ");
            for (String s : sensors) {
                String[] kv = s.split("=", 2);
                if (kv.length < 2) continue;
                try {
                    JSONObject state = new JSONObject(get(base + "/api/states/" + kv[1].trim(), token));
                    double v = Double.parseDouble(state.getString("state"));
                    line.append("  ").append(kv[0].trim()).append(' ')
                            .append(String.format(java.util.Locale.ROOT, "%.1f°", v)).append(' ');
                } catch (Exception e) {
                    // Unavailable sensor ("unavailable" state, offline, renamed): leave that room out.
                    android.util.Log.w("tvhome", "home assistant " + kv[1] + ": " + e);
                }
            }
            String text = line.toString().trim();
            p.edit().putString("line", text.equals("🏠") ? null : text).apply();
            new Handler(Looper.getMainLooper()).post(onUpdated);
        }).start();
    }

    private static String get(String url, String bearer) throws java.io.IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(5_000);
        con.setReadTimeout(5_000);
        if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = con.getInputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            con.disconnect();
        }
        return out.toString("UTF-8");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** Last fetched weather, or null if there isn't any (or no location is configured). */
    static Weather cachedWeather(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("fetchedAt") || c.getString(R.string.weather_latitude).isEmpty()) return null;
        Weather w = new Weather();
        w.temp = p.getInt("temp", 0);
        w.min = p.getInt("min", 0);
        w.max = p.getInt("max", 0);
        w.code = p.getInt("code", 0);
        w.day = p.getBoolean("day", true);
        w.fetchedAt = p.getLong("fetchedAt", 0);
        return w;
    }

    /** Fetch in the background if the cache is older than MAX_AGE_MS, then run onUpdated on the main thread. */
    static void refreshWeather(Context c, Runnable onUpdated) {
        String lat = c.getString(R.string.weather_latitude), lon = c.getString(R.string.weather_longitude);
        Weather cached = cachedWeather(c);
        if (lat.isEmpty() || (cached != null && System.currentTimeMillis() - cached.fetchedAt < Weather.MAX_AGE_MS)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto", null));
                JSONObject cur = j.getJSONObject("current"), daily = j.getJSONObject("daily");
                prefs(app).edit()
                        .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("code", cur.getInt("weather_code"))
                        .putBoolean("day", cur.getInt("is_day") == 1)
                        .putInt("max", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                        .putInt("min", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                        .putLong("fetchedAt", System.currentTimeMillis())
                        .apply();
                new Handler(Looper.getMainLooper()).post(onUpdated);
            } catch (Exception e) {
                android.util.Log.w("tvhome", "weather: " + e);
            }
        }).start();
    }
}
