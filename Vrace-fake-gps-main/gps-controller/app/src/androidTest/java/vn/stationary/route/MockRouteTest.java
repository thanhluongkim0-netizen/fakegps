package vn.stationary.route;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Tests only this original controller on an emulator; never opens vRace or submits records. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
@SuppressLint("MissingPermission") // Runtime permissions and mock app-op are granted by shell in the test.
public final class MockRouteTest {
    private void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = new byte[1024]; while (in.read(bytes) != -1) { }
        }
    }
    private void command(Context context, String action, String payload) {
        Intent intent = new Intent(context, RouteService.class).setAction(action).putExtra("payload", payload);
        if ("hold".equals(action)) context.startForegroundService(intent); else context.startService(intent);
    }
    private void state(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expected.equals(new JSONObject(RouteService.snapshot()).getString("state"))) return;
            Thread.sleep(100);
        }
        fail("Expected " + expected + ": " + RouteService.snapshot());
    }
    private String evaluate(ActivityScenario<MainActivity> activity, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        activity.onActivity(a -> {
            WebView web = a.findViewById(R.id.route_web);
            web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); });
        });
        assertTrue("WebView responds", done.await(10, TimeUnit.SECONDS));
        return result.get();
    }
    private RouteMapActivity mapActivity() throws Exception {
        AtomicReference<RouteMapActivity> found = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (a instanceof RouteMapActivity && ((RouteMapActivity)a).map != null) found.set((RouteMapActivity)a);
            });
            if (found.get() != null) return found.get();
            Thread.sleep(100);
        }
        fail("Native Google Maps picker did not become ready"); return null;
    }
    private void tapMap(RouteMapActivity activity, double offsetY) throws Exception {
        AtomicReference<Point> target = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.view.View mapView = activity.findViewById(R.id.route_map);
            Point point = activity.map.getProjection().toScreenLocation(new LatLng(10.7769,106.7009));
            int[] origin = new int[2]; mapView.getLocationOnScreen(origin);
            int y = (int)(point.y + offsetY);
            assertTrue("Tap remains inside the map", point.x > 0 && point.x < mapView.getWidth()
                    && y > 0 && y < mapView.getHeight());
            target.set(new Point(origin[0] + point.x, origin[1] + y));
        });
        // Use the device input pipeline and real timestamps, as a user's touch would.
        Point point = target.get();
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, point.x, point.y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue("Map touch down injected", InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().injectInputEvent(down, true)); }
        finally { down.recycle(); }
        Thread.sleep(75);
        MotionEvent up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.x, point.y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue("Map touch up injected", InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().injectInputEvent(up, true)); }
        finally { up.recycle(); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void requireLoadedMap(RouteMapActivity activity) throws Exception {
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> loaded.set(activity.mapLoaded));
            if (loaded.get()) return;
            Thread.sleep(200);
        }
        fail("Google Maps never finished loading tiles; verify the API key, package, certificate and network");
    }
    @Test public void suppliesMockLocationsToGpsAndGoogleFusedWhileInBackground() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pkg = context.getPackageName();
        shell("pm grant " + pkg + " " + Manifest.permission.ACCESS_COARSE_LOCATION);
        shell("pm grant " + pkg + " " + Manifest.permission.ACCESS_FINE_LOCATION);
        shell("pm grant " + pkg + " " + Manifest.permission.POST_NOTIFICATIONS);
        shell("appops set " + pkg + " android:mock_location allow");
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(context);
        CountDownLatch gpsAtA = new CountDownLatch(1), fusedAtA = new CountDownLatch(1), fusedMoving = new CountDownLatch(1);
        AtomicReference<Location> lastGps = new AtomicReference<>();
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (l.isMock() && Math.abs(l.getLatitude() - 10.7769) < .01
                        && Math.abs(l.getLongitude() - 106.7009) < .0001) {
                    lastGps.set(l);
                    if (Math.abs(l.getLatitude() - 10.7769) < .00002) gpsAtA.countDown();
                }
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        LocationCallback callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) {
                Location l = result.getLastLocation();
                if (l != null && l.isMock() && Math.abs(l.getLatitude() - 10.7769) < .01
                        && Math.abs(l.getLongitude() - 106.7009) < .0001) {
                    if (Math.abs(l.getLatitude() - 10.7769) < .00002) fusedAtA.countDown();
                    if (l.getSpeed() > 1 && l.getLatitude() > 10.77693) fusedMoving.countDown();
                }
            }
        };
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener, Looper.getMainLooper());
            fused.requestLocationUpdates(new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(), callback, Looper.getMainLooper());
            try {
                long deadline = SystemClock.elapsedRealtime() + 10_000;
                while (SystemClock.elapsedRealtime() < deadline && !"true".equals(evaluate(activity, "typeof window.centerOn === 'function'"))) Thread.sleep(100);
                assertEquals("true", evaluate(activity, "typeof window.centerOn === 'function'"));
                evaluate(activity, "document.getElementById('coordinates').value='10.8784,  106.7972';document.getElementById('apply').click();true");
                assertEquals("One coordinate places A", "1", evaluate(activity, "points.length"));
                assertEquals(106.7972, Double.parseDouble(evaluate(activity, "lon")), .000001);
                evaluate(activity, "document.getElementById('hold').click();true");
                assertFalse("One coordinate cannot start a route", RouteService.isAlive());
                // Select through real native Google Maps touches, then return the result to the controls.
                evaluate(activity, "document.getElementById('clear').click(); zoom=16; centerOn(10.7769,106.7009); true");
                evaluate(activity, "document.getElementById('openMap').click();true");
                RouteMapActivity picker = mapActivity();
                requireLoadedMap(picker);
                tapMap(picker, 0);
                Thread.sleep(1000);
                // Default marker artwork extends above A; tap clear map space beyond it.
                tapMap(picker, -180);
                Thread.sleep(1000);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> picker.findViewById(R.id.map_done).performClick());
                long tapDeadline = SystemClock.elapsedRealtime() + 10_000;
                while (SystemClock.elapsedRealtime() < tapDeadline
                        && !"2".equals(evaluate(activity, "points.length"))) Thread.sleep(100);
                assertEquals("Two native Maps taps selected A/B", "2", evaluate(activity, "points.length"));
                assertEquals(106.7009, Double.parseDouble(evaluate(activity, "points[0][1]")), .00002);
                evaluate(activity, "document.getElementById('hold').click(); true");
                state("HOLDING");
                assertTrue("GPS receives mock A", gpsAtA.await(25, TimeUnit.SECONDS));
                assertTrue("Google Fused receives mock A", fusedAtA.await(25, TimeUnit.SECONDS));
                assertEquals(0, lastGps.get().getSpeed(), 0);
                command(context, "play", "{}");
                state("RUNNING");
                shell("input keyevent 3");
                assertTrue("Fused continues moving in background", fusedMoving.await(25, TimeUnit.SECONDS));
                assertTrue(lastGps.get().getElapsedRealtimeNanos() > 0);
                assertTrue(lastGps.get().getAccuracy() < 22);
                command(context, "pause", "{}"); state("PAUSED");
                double before = new JSONObject(RouteService.snapshot()).getDouble("distance");
                Thread.sleep(2500);
                assertEquals(before, new JSONObject(RouteService.snapshot()).getDouble("distance"), .001);
                assertEquals(0, lastGps.get().getSpeed(), 0);
                command(context, "stop", "{}"); state("IDLE");
                assertFalse(new JSONObject(RouteService.snapshot()).getBoolean("ready"));
                long stopDeadline = SystemClock.elapsedRealtime() + 5000;
                while (RouteService.isAlive() && SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(100);
                assertFalse("Foreground service stopped", RouteService.isAlive());
                try {
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, lastGps.get());
                    fail("GPS test provider was not removed");
                } catch (IllegalArgumentException expected) { }
            } finally {
                if (RouteService.isAlive()) command(context, "stop", "{}");
                manager.removeUpdates(listener); fused.removeLocationUpdates(callback);
            }
        }
    }
}
