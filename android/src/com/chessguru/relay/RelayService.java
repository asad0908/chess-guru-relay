package com.chessguru.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Foreground service that keeps the relay alive (wake lock + Wi-Fi lock) while it is switched on. */
public class RelayService extends Service {
    public static final int PORT = 4100;
    static final String CHANNEL = "relay";
    static final String EXTRA_SECRET = "secret";

    static volatile boolean running = false;
    static volatile String lastError = null;
    private static final LinkedList<String> LOG = new LinkedList<>();

    private RelayServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    static synchronized void addLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        LOG.addFirst(ts + "  " + line);
        while (LOG.size() > 60) LOG.removeLast();
    }

    static synchronized List<String> logLines() {
        return new ArrayList<>(LOG);
    }

    /** IPv4 addresses on this device's Wi-Fi / LAN interfaces. */
    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String secret = intent != null ? intent.getStringExtra(EXTRA_SECRET) : null;
        if (secret == null) secret = getSharedPreferences("relay", MODE_PRIVATE).getString("secret", "");
        startInForeground();
        if (server == null) {
            try {
                server = new RelayServer(PORT, secret, RelayService::addLog);
                server.start();
                running = true;
                lastError = null;
                acquireLocks();
            } catch (Exception e) {
                lastError = e.getMessage();
                addLog("could not start: " + e.getMessage());
                server = null;
                running = false;
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Relay running", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        List<String> ips = lanAddresses();
        String where = ips.isEmpty() ? "no Wi-Fi address" : "http://" + ips.get(0) + ":" + PORT;
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("chess-guru relay is on")
                .setContentText(where)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chessguru:relay");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "chessguru:relay");
        wifiLock.acquire();
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
