package com.chessguru.relay;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** One screen: secret, start/stop, the address to paste into chess-guru, and a request log. */
public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#0f1115");
    private static final int CARD = Color.parseColor("#181b22");
    private static final int TEXT = Color.parseColor("#e7e9ee");
    private static final int MUTED = Color.parseColor("#8b93a5");
    private static final int ACCENT = Color.parseColor("#10b981");
    private static final int DANGER = Color.parseColor("#ef4444");

    private SharedPreferences prefs;
    private EditText secretInput;
    private TextView status;
    private TextView address;
    private TextView log;
    private Button toggle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("relay", MODE_PRIVATE);
        // A secret can be provisioned over adb: am start -n com.chessguru.relay/.MainActivity --es secret <value>
        String fromIntent = getIntent().getStringExtra(RelayService.EXTRA_SECRET);
        if (fromIntent != null && fromIntent.length() >= 24) prefs.edit().putString("secret", fromIntent).apply();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        scroll.addView(root);

        root.addView(text("♞  chess-guru relay", 24, TEXT, true));
        TextView sub = text("Lets chess-guru on your computer reach Lichess and Chess.com through this phone. Keep both on the same Wi-Fi.", 14, MUTED, false);
        sub.setPadding(0, dp(6), 0, 0);
        root.addView(sub);

        LinearLayout stateCard = card();
        status = text("", 18, TEXT, true);
        address = text("", 20, ACCENT, true);
        address.setPadding(0, dp(8), 0, 0);
        address.setTextIsSelectable(true);
        stateCard.addView(status);
        stateCard.addView(address);
        TextView hint = text("Paste this address into chess-guru → Settings → Relay URL.", 13, MUTED, false);
        hint.setPadding(0, dp(6), 0, 0);
        stateCard.addView(hint);
        toggle = button("Start relay", ACCENT);
        toggle.setOnClickListener(v -> toggleRelay());
        stateCard.addView(toggle);
        Button copy = button("Copy address", Color.parseColor("#2a2f3a"));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("relay", address.getText()));
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
        });
        stateCard.addView(copy);
        root.addView(stateCard);

        LinearLayout secretCard = card();
        secretCard.addView(text("Relay secret", 16, TEXT, true));
        secretCard.addView(text("Must match RELAY_SECRET in chess-guru's .env file (24+ characters).", 13, MUTED, false));
        secretInput = new EditText(this);
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        secretInput.setTextColor(TEXT);
        secretInput.setHintTextColor(MUTED);
        secretInput.setHint("paste secret");
        secretInput.setSingleLine(true);
        secretInput.setText(prefs.getString("secret", ""));
        secretCard.addView(secretInput);
        Button save = button("Save secret", Color.parseColor("#2a2f3a"));
        save.setOnClickListener(v -> {
            String s = secretInput.getText().toString().trim();
            if (s.length() < 24) {
                Toast.makeText(this, "The secret must be at least 24 characters", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("secret", s).apply();
            Toast.makeText(this, RelayService.running ? "Saved. Restart the relay to use it." : "Saved", Toast.LENGTH_SHORT).show();
        });
        secretCard.addView(save);
        Button gen = button("Generate new secret", Color.parseColor("#2a2f3a"));
        gen.setOnClickListener(v -> {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            secretInput.setText(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        });
        secretCard.addView(gen);
        root.addView(secretCard);

        LinearLayout batteryCard = card();
        batteryCard.addView(text("Keep running during games", 16, TEXT, true));
        batteryCard.addView(text("Allow the relay to ignore battery optimisation so Android doesn't stop it mid-game.", 13, MUTED, false));
        Button battery = button("Allow background running", Color.parseColor("#2a2f3a"));
        battery.setOnClickListener(v -> requestBatteryExemption());
        batteryCard.addView(battery);
        root.addView(batteryCard);

        LinearLayout logCard = card();
        logCard.addView(text("Recent requests", 16, TEXT, true));
        log = text("", 12, MUTED, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, dp(6), 0, 0);
        logCard.addView(log);
        root.addView(logCard);

        TextView foot = text("Only lichess.org, Lichess explorer/tablebase/database and api.chess.com are forwarded. Tokens are never stored or logged.", 12, MUTED, false);
        foot.setPadding(0, dp(14), 0, 0);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(foot);
        return scroll;
    }

    private void toggleRelay() {
        Intent i = new Intent(this, RelayService.class);
        if (RelayService.running) {
            stopService(i);
        } else {
            String s = prefs.getString("secret", "");
            String typed = secretInput.getText().toString().trim();
            if (typed.length() >= 24 && !typed.equals(s)) {
                prefs.edit().putString("secret", typed).apply();
                s = typed;
            }
            if (s.length() < 24) {
                Toast.makeText(this, "Paste the relay secret first (24+ characters)", Toast.LENGTH_LONG).show();
                return;
            }
            i.putExtra(RelayService.EXTRA_SECRET, s);
            startForegroundService(i);
        }
        handler.postDelayed(this::refresh, 400);
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        boolean on = RelayService.running;
        List<String> ips = RelayService.lanAddresses();
        status.setText(on ? "● Relay is ON" : (RelayService.lastError != null ? "● Not running: " + RelayService.lastError : "● Relay is off"));
        status.setTextColor(on ? ACCENT : (RelayService.lastError != null ? DANGER : MUTED));
        address.setText(ips.isEmpty() ? "Connect to Wi-Fi" : "http://" + ips.get(0) + ":" + RelayService.PORT);
        toggle.setText(on ? "Stop relay" : "Start relay");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? DANGER : ACCENT);
        bg.setCornerRadius(dp(12));
        toggle.setBackground(bg);
        StringBuilder sb = new StringBuilder();
        for (String l : RelayService.logLines()) sb.append(l).append('\n');
        log.setText(sb.length() == 0 ? "No requests yet." : sb.toString());
    }
}
