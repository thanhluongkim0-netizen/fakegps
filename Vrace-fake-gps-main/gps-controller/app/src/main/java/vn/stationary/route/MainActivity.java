package vn.stationary.route;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import com.google.android.gms.location.LocationServices;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private WebView web;
    private String pendingCommand;
    private String pendingPayload;
    private CurrentPositionLookup positionLookup;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setId(R.id.route_web);
        positionLookup = createPositionLookup();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        try (InputStream input = getAssets().open("index.html")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            web.loadDataWithBaseURL("https://appassets.androidplatform.net/", new String(output.toByteArray(), StandardCharsets.UTF_8), "text/html", "UTF-8", null);
        } catch (Exception e) { toast("Không mở được giao diện: " + e.getMessage()); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private boolean ensureLocation(String command, String payload) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return true;
        pendingCommand = command; pendingPayload = payload;
        requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, 10);
        return false;
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            String command = pendingCommand, payload = pendingPayload;
            pendingCommand = null; pendingPayload = null;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if ("center".equals(command)) center(); else if (command != null) send(command, payload);
            } else toast("Cần bật Vị trí chính xác để phát tuyến. Bạn có thể cấp lại trong cài đặt ứng dụng.");
        }
    }
    @SuppressLint("MissingPermission") // The source is invoked only after center() verifies fine location permission.
    private CurrentPositionLookup createPositionLookup() {
        return new CurrentPositionLookup((request, token) ->
                LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(request, token));
    }
    private void center() {
        if (!ensureLocation("center", "{}")) return;
        if (RouteService.isAlive()) {
            locationStatus(false, "Tắt phát GPS trước khi tìm vị trí thật."); return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean enabled = Build.VERSION.SDK_INT >= 28 ? manager.isLocationEnabled()
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!enabled) {
            locationStatus(false, "Bật Vị trí trên điện thoại rồi thử lại.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
        }
        positionLookup.start(new CurrentPositionLookup.Listener() {
            @Override public void searching() { locationStatus(true, "Đang tìm vị trí mới, chờ tối đa khoảng 20 giây…"); }
            @Override public void found(Location location) {
                web.evaluateJavascript("currentPositionFound(" + location.getLatitude() + "," + location.getLongitude() + ")", null);
            }
            @Override public void failed(String message) { locationStatus(false, message); }
        });
    }
    private void locationStatus(boolean searching, String message) {
        if (!isFinishing() && !isDestroyed())
            web.evaluateJavascript("locationStatus(" + searching + "," + JSONObject.quote(message) + ")", null);
    }
    private void send(String command, String payload) {
        if (!command.matches("hold|play|pause|speed|stop")) return;
        if ("hold".equals(command)) {
            positionLookup.cancel();
            locationStatus(false, "");
            if (!ensureLocation(command, payload)) return;
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean enabled = Build.VERSION.SDK_INT >= 28 ? locationManager.isLocationEnabled()
                    : locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!enabled) {
                toast("Bật Vị trí trên điện thoại rồi thử lại.");
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
            }
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                toast("Chọn Tuyến GPS · Mô phỏng trong mục Ứng dụng vị trí mô phỏng, rồi quay lại.");
                openDeveloperSettings(); return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 11);
            }
            Intent intent = new Intent(this, RouteService.class).setAction(command).putExtra("payload", payload);
            try { startForegroundService(intent); } catch (RuntimeException e) { toast("Không khởi động được: " + e.getMessage()); }
        } else if (RouteService.isAlive()) {
            startService(new Intent(this, RouteService.class).setAction(command).putExtra("payload", payload));
        } else toast("Chưa phát vị trí. Bấm Đặt tại A trước.");
    }
    private void openDeveloperSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }
    public final class Bridge {
        @JavascriptInterface public String config() { return getSharedPreferences("route", MODE_PRIVATE).getString("config", "{}"); }
        @JavascriptInterface public String snapshot() { return RouteService.snapshot(); }
        @JavascriptInterface public void saveConfig(String value) {
            if (value.length() > 64_000) return;
            try { new JSONObject(value); getSharedPreferences("route", MODE_PRIVATE).edit().putString("config", value).apply(); }
            catch (Exception ignored) { }
        }
        @JavascriptInterface public void command(String action, String payload) {
            if (payload.length() < 64_000) runOnUiThread(() -> send(action, payload));
        }
        @JavascriptInterface public void settings() { runOnUiThread(() -> openDeveloperSettings()); }
        @JavascriptInterface public void currentLocation() { runOnUiThread(() -> center()); }
        @JavascriptInterface public void cancelLocation() { runOnUiThread(() -> positionLookup.cancel()); }
        @JavascriptInterface public void openMap(String config) {
            if (config.length() > 64_000) return;
            runOnUiThread(() -> {
                positionLookup.cancel();
                startActivityForResult(new Intent(MainActivity.this, RouteMapActivity.class)
                        .putExtra("config", config), 20);
            });
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 20 && resultCode == RESULT_OK && data != null) {
            String selection = data.getStringExtra("config");
            if (selection != null && selection.length() <= 64_000)
                web.evaluateJavascript("applyMapSelection(" + JSONObject.quote(selection) + ")", null);
        }
    }
    @Override public void onStop() {
        positionLookup.cancel();
        locationStatus(false, "");
        super.onStop();
    }
    @Override public void onDestroy() {
        positionLookup.cancel();
        web.removeJavascriptInterface("Android"); web.destroy(); super.onDestroy();
    }
}
