package vn.stationary.route;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.Criteria;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.stationary.route.core.RouteSession;
import vn.stationary.route.core.RouteSession.Point;

public final class RouteService extends Service {
    private static volatile String latest = "{\"state\":\"IDLE\",\"message\":\"Chọn tuyến rồi đặt điện thoại tại A.\",\"ready\":false}";
    private static volatile boolean alive;
    public static String snapshot() { return latest; }
    public static boolean isAlive() { return alive; }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> installed = new ArrayList<>();
    private LocationManager manager;
    private FusedLocationProviderClient fused;
    private PowerManager.WakeLock wake;
    private RouteSession session;
    private boolean ready, closing, destroyed;
    private int epoch;
    private long lastNanos;
    private int count;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ready || closing || destroyed) return;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                close("Quyền vị trí đã bị thu hồi; đã dừng phát."); return;
            }
            try {
                session.advance(SystemClock.elapsedRealtime());
                Location location = location();
                for (String provider : installed) {
                    Location copy = new Location(location); copy.setProvider(provider);
                    manager.setTestProviderLocation(provider, copy);
                }
                int current = epoch;
                fused.setMockLocation(location).addOnFailureListener(e -> {
                    if (current == epoch && !closing && !destroyed) close("Lỗi Fused: " + readable(e));
                });
                publish(message());
                if (++count % 300 == 0 && wake != null) wake.acquire(600_000);
                if (count % 2 == 0) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification());
                handler.postDelayed(this, 1000);
            } catch (SecurityException e) { close("Quyền phát vị trí không còn khả dụng: " + readable(e)); }
            catch (Exception e) { close("Dừng phát vì lỗi: " + readable(e)); }
        }
    };
    @Override public void onCreate() {
        super.onCreate(); alive = true;
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        fused = LocationServices.getFusedLocationProviderClient(this);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("route", "Phát tuyến mô phỏng", NotificationManager.IMPORTANCE_LOW));
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            destroyed = true; publish("Cần quyền vị trí chính xác trước khi mở dịch vụ."); stopSelf(); return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(1, notification());
        } catch (Exception e) { destroyed = true; publish("Không mở được dịch vụ nền: " + readable(e)); stopSelf(); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || destroyed) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if ("stop".equals(action)) { close("Đã dừng phát. Điện thoại dùng lại vị trí thật."); return START_NOT_STICKY; }
        if (closing) return START_NOT_STICKY;
        try {
            JSONObject payload = new JSONObject(intent.getStringExtra("payload") == null ? "{}" : intent.getStringExtra("payload"));
            long clock = SystemClock.elapsedRealtime();
            if ("hold".equals(action)) {
                if (session != null) return START_NOT_STICKY;
                JSONArray route = payload.getJSONArray("points");
                List<Point> points = new ArrayList<>();
                for (int i = 0; i < route.length(); i++) {
                    JSONArray pair = route.getJSONArray(i); points.add(new Point(pair.getDouble(0), pair.getDouble(1)));
                }
                session = new RouteSession(points, payload.getDouble("speed"), clock);
                publish("Đang chuẩn bị GPS và Fused…");
                for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    // API 26–30 requires removing an existing test provider before adding it.
                    if (Build.VERSION.SDK_INT < 31) { try { manager.removeTestProvider(provider); } catch (IllegalArgumentException ignored) { } }
                    addProvider(provider);
                    installed.add(provider);
                    manager.setTestProviderEnabled(provider, true);
                }
                wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":route");
                wake.setReferenceCounted(false); wake.acquire(600_000);
                int current = ++epoch;
                fused.setMockMode(true).addOnSuccessListener(unused -> {
                    if (current != epoch || closing || destroyed) {
                        // A late enable must never leave mock mode switched on after Stop.
                        try { fused.setMockMode(false); } catch (SecurityException ignored) { } return;
                    }
                    ready = true; handler.post(tick);
                }).addOnFailureListener(e -> {
                    if (current == epoch && !closing && !destroyed) close("Không bật được Fused: " + readable(e));
                });
            } else if (session != null && ready) {
                if ("play".equals(action)) session.play(clock);
                else if ("pause".equals(action)) session.pause(clock);
                else if ("speed".equals(action)) session.speed(payload.getDouble("speed"), clock);
                publish(message());
            }
        } catch (SecurityException e) {
            close("Không có quyền phát vị trí: " + readable(e));
        } catch (Exception e) {
            if (session == null || !ready) close("Không phát được: " + readable(e));
            else publish("Không đổi được cấu hình: " + readable(e));
        }
        return START_NOT_STICKY;
    }
    private Location location() {
        Point p = session.position();
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(p.latitude); l.setLongitude(p.longitude); l.setAltitude(10);
        l.setAccuracy(6); l.setSpeed((float) session.emittedSpeedMps()); l.setBearing(session.bearing());
        l.setVerticalAccuracyMeters(6); l.setSpeedAccuracyMetersPerSecond(.2f); l.setBearingAccuracyDegrees(5);
        l.setTime(System.currentTimeMillis());
        lastNanos = Math.max(SystemClock.elapsedRealtimeNanos(), lastNanos + 1);
        l.setElapsedRealtimeNanos(lastNanos);
        // Android and Google mark injected locations as mock; never hide or rewrite that flag.
        return l;
    }
    @SuppressLint("WrongConstant") // Pre-31 Criteria constants are numeric aliases of ProviderProperties.
    private void addProvider(String provider) {
        if (Build.VERSION.SDK_INT >= 31) {
            ProviderProperties properties = new ProviderProperties.Builder().setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true).setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).build();
            manager.addTestProvider(provider, properties);
        } else {
            manager.addTestProvider(provider, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        }
    }
    private String message() {
        return switch (session.state()) {
            case HOLDING -> "Đang giữ tại A. Bắt đầu phiên trong vRace, rồi bấm Phát tuyến.";
            case RUNNING -> "Đang phát tuyến qua GPS và Fused.";
            case PAUSED -> "Tạm dừng: giữ nguyên vị trí và phát tốc độ 0.";
            case ARRIVED -> "Đã tới B; vẫn giữ tại B. Dừng phiên vRace trước khi tắt phát.";
        };
    }
    private void publish(String message) {
        try {
            JSONObject value = new JSONObject();
            value.put("state", closing ? "STOPPING" : session == null || destroyed ? "IDLE" : ready ? session.state().name() : "CONNECTING");
            value.put("ready", ready && !closing && !destroyed); value.put("message", message);
            if (session != null) {
                Point p = session.position();
                value.put("latitude", p.latitude); value.put("longitude", p.longitude);
                value.put("distance", session.distanceMeters()); value.put("total", session.totalMeters());
                value.put("seconds", session.activeSeconds()); value.put("speed", session.speedKmh());
            }
            latest = value.toString();
        } catch (Exception ignored) { }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RouteService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = session == null ? "Chuẩn bị phát vị trí" : String.format(Locale.ROOT, "%.0f m · %.1f km/h · %s", session.distanceMeters(), session.speedKmh(), session.state().name());
        return new Notification.Builder(this, "route").setSmallIcon(R.drawable.ic_route)
                .setContentTitle("Tuyến GPS · Mô phỏng").setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "Tắt phát", stop).build()).build();
    }
    private static String readable(Exception e) {
        return e instanceof SecurityException ? "Kiểm tra quyền vị trí và lựa chọn Ứng dụng vị trí mô phỏng." : String.valueOf(e.getMessage());
    }
    private void removeProviders() {
        for (String provider : installed) { try { manager.removeTestProvider(provider); } catch (RuntimeException ignored) { } }
        installed.clear();
        if (wake != null && wake.isHeld()) wake.release();
    }
    private void close(String reason) {
        if (closing || destroyed) return;
        closing = true; ready = false; ++epoch; handler.removeCallbacks(tick);
        if (session != null) {
            try {
                session.pause(SystemClock.elapsedRealtime());
                publish(reason);
                getSharedPreferences("route", MODE_PRIVATE).edit().putString("lastSimulation", latest).apply();
            } catch (Exception ignored) { }
        }
        removeProviders(); publish("Đang tắt mock mode…");
        try {
            fused.setMockMode(false).addOnCompleteListener(task -> {
                if (destroyed) return;
                closing = false; session = null;
                publish(task.isSuccessful() ? reason : "Đã dừng GPS; Fused chưa xác nhận tắt. Mở lại app rồi thử lại.");
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        } catch (SecurityException e) {
            closing = false; session = null;
            publish("Đã dừng GPS; quyền bị thu hồi nên chưa xác nhận tắt Fused.");
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        handler.postDelayed(() -> {
            if (!destroyed && closing) {
                closing = false; session = null;
                publish("Đã dừng GPS; Fused chưa phản hồi. Bật lại vị trí hoặc khởi động lại máy nếu cần.");
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        }, 8000);
    }
    @Override public void onDestroy() {
        destroyed = true; ready = false; alive = false; ++epoch;
        handler.removeCallbacksAndMessages(null); removeProviders();
        if (session != null) publish("Dịch vụ đã dừng; không tự tiếp tục tuyến khi mở lại.");
        if (fused != null) { try { fused.setMockMode(false); } catch (SecurityException ignored) { } }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
