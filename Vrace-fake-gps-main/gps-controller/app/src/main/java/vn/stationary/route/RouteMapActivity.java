package vn.stationary.route;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import vn.stationary.route.core.RouteSession;

/** Native Google Maps route picker/viewer. It neither starts mock GPS nor uploads activities. */
public final class RouteMapActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<LatLng> points = new ArrayList<>();
    private final ArrayList<Marker> routeMarkers = new ArrayList<>();
    private MapView mapView;
    GoogleMap map;
    private Polyline routeLine;
    private Marker emittedMarker;
    private TextView status;
    private Button undo, clear, current;
    private CurrentPositionLookup lookup;
    private double latitude = 10.7769, longitude = 106.7009;
    private float zoom = 16;
    private boolean viewing, resumed;
    boolean mapLoaded;
    private final Runnable tileTimeout = () -> {
        if (!mapLoaded) status.setText("Chưa tải được bản đồ Google. Kiểm tra mạng và quyền Maps của bản APK. Có thể quay lại nhập tọa độ.");
    };
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            boolean busy = RouteService.isAlive();
            undo.setEnabled(!viewing && !busy && !points.isEmpty());
            clear.setEnabled(!viewing && !busy && !points.isEmpty());
            current.setEnabled(!busy);
            if (map != null && busy) {
                try {
                    JSONObject snapshot = new JSONObject(RouteService.snapshot());
                    if (snapshot.has("latitude") && snapshot.has("longitude")) {
                        LatLng p = new LatLng(snapshot.getDouble("latitude"), snapshot.getDouble("longitude"));
                        if (emittedMarker == null) emittedMarker = map.addMarker(new MarkerOptions().position(p)
                                .title("Vị trí mô phỏng").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                        else if (!p.equals(emittedMarker.getPosition())) emittedMarker.setPosition(p);
                    }
                } catch (Exception ignored) { }
            } else if (emittedMarker != null) { emittedMarker.remove(); emittedMarker = null; }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        lookup = createLookup();
        readConfig(state == null ? getIntent().getStringExtra("config") : state.getString("selection"));
        viewing = RouteService.isAlive();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this); title.setText("Google Maps · Tuyến GPS mô phỏng");
        title.setTextSize(17); title.setTextColor(Color.rgb(21, 38, 56)); title.setPadding(18, 14, 18, 8);
        root.addView(title);
        status = new TextView(this); status.setTextSize(13); status.setPadding(18, 0, 18, 10);
        root.addView(status);
        mapView = new MapView(this); mapView.setId(R.id.route_map);
        root.addView(mapView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        undo = button("Bỏ điểm", actions, () -> { if (editable() && !points.isEmpty()) { lookup.cancel(); points.remove(points.size()-1); drawRoute(); } });
        clear = button("Tuyến mới", actions, () -> { if (editable()) { lookup.cancel(); points.clear(); drawRoute(); } });
        current = button("Vị trí thật", actions, this::locate);
        Button done = button("Xong", actions, () -> {
            setResult(RESULT_OK, new Intent().putExtra("config", selection())); finish();
        });
        done.setId(R.id.map_done);
        root.addView(actions); setContentView(root);
        mapView.onCreate(state == null ? null : state.getBundle("googleMap"));
        if (!BuildConfig.MAPS_CONFIGURED) {
            status.setText("Bản APK chưa có khóa Google Maps. Quay lại nhập tọa độ hoặc cài bản đã cấu hình Maps.");
            return;
        }
        mapView.getMapAsync(googleMap -> {
            if (isFinishing() || isDestroyed()) return;
            map = googleMap;
            map.getUiSettings().setZoomControlsEnabled(true);
            map.getUiSettings().setMapToolbarEnabled(false);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latitude, longitude), zoom));
            map.setOnCameraIdleListener(() -> {
                CameraPosition camera = map.getCameraPosition();
                latitude = camera.target.latitude; longitude = camera.target.longitude; zoom = camera.zoom;
            });
            map.setOnMapClickListener(p -> {
                if (!editable()) { status.setText("Đang phát GPS. Tắt phát trước khi sửa tuyến."); return; }
                lookup.cancel();
                if (points.size() >= 300) { status.setText("Tuyến tối đa 300 điểm."); return; }
                if (!points.isEmpty() && meters(points.get(points.size()-1), p) < .01) return;
                points.add(p); drawRoute();
            });
            map.setOnMapLoadedCallback(() -> { mapLoaded = true; handler.removeCallbacks(tileTimeout); routeHint(); });
            drawRoute();
            handler.postDelayed(tileTimeout, 20_000);
        });
        routeHint();
    }
    private boolean editable() { return !viewing && !RouteService.isAlive(); }
    private Button button(String text, LinearLayout parent, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(11); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return button;
    }
    private void readConfig(String value) {
        if (value == null || value.length() > 64_000) return;
        try {
            JSONObject config = new JSONObject(value);
            JSONArray route = config.optJSONArray("points");
            if (route != null && route.length() <= 300) {
                ArrayList<LatLng> parsed = new ArrayList<>();
                for (int i=0; i<route.length(); i++) {
                    JSONArray pair = route.getJSONArray(i);
                    if (pair.length() != 2) return;
                    double a = pair.getDouble(0), b = pair.getDouble(1);
                    if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(a)>85 || Math.abs(b)>180) return;
                    parsed.add(new LatLng(a, b));
                }
                points.addAll(parsed);
            }
            double a = config.optDouble("latitude", latitude), b = config.optDouble("longitude", longitude);
            if (Double.isFinite(a) && Double.isFinite(b) && Math.abs(a)<=85 && Math.abs(b)<=180) { latitude=a; longitude=b; }
            double z = config.optDouble("zoom", zoom);
            if (Double.isFinite(z)) zoom=(float)Math.max(3, Math.min(21, z));
        } catch (Exception ignored) { }
    }
    private String selection() {
        JSONObject result = new JSONObject(); JSONArray route = new JSONArray();
        try {
            for (LatLng point : points) route.put(new JSONArray().put(point.latitude).put(point.longitude));
            result.put("points", route).put("latitude", latitude).put("longitude", longitude).put("zoom", zoom);
        } catch (Exception ignored) { }
        return result.toString();
    }
    private void drawRoute() {
        if (map == null) return;
        // Reuse one line; redraw markers only when the selected route changes.
        if (routeLine == null) routeLine = map.addPolyline(new PolylineOptions().color(Color.rgb(23, 139, 120)).width(7));
        routeLine.setPoints(points);
        for (Marker marker : routeMarkers) marker.remove();
        routeMarkers.clear();
        for (int i=0; i<points.size(); i++) {
            String label = i==0 ? "A" : i==points.size()-1 ? "B" : "Điểm " + (i+1);
            Marker marker = map.addMarker(new MarkerOptions().position(points.get(i)).title(label)
                    .icon(BitmapDescriptorFactory.defaultMarker(i==0 ? BitmapDescriptorFactory.HUE_GREEN : BitmapDescriptorFactory.HUE_ORANGE)));
            if (marker != null) routeMarkers.add(marker);
        }
        routeHint();
    }
    private static double meters(LatLng a, LatLng b) {
        return RouteSession.meters(new RouteSession.Point(a.latitude, a.longitude), new RouteSession.Point(b.latitude, b.longitude));
    }
    private void routeHint() {
        status.setText(viewing || RouteService.isAlive() ? "Đang xem tuyến mô phỏng; chấm xanh là vị trí đang phát."
                : points.isEmpty() ? "Chạm bản đồ để chọn A, rồi thêm điểm rẽ và B. Kéo hoặc chụm hai ngón để đổi vùng."
                : points.size()==1 ? "Đã chọn A. Chạm thêm B hoặc điểm rẽ, rồi bấm Xong."
                : "Đã chọn " + points.size() + " điểm. Bấm Xong để dùng tuyến; các điểm nối trực tiếp.");
    }
    @SuppressLint("MissingPermission") // Called only after locate() checks precise permission.
    private CurrentPositionLookup createLookup() {
        return new CurrentPositionLookup((request, token) -> LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(request, token));
    }
    private void locate() {
        if (RouteService.isAlive()) { status.setText("Tắt phát GPS trước khi tìm vị trí thật."); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 10); return;
        }
        lookup.start(new CurrentPositionLookup.Listener() {
            @Override public void searching() { status.setText("Đang tìm vị trí thật, chờ tối đa khoảng 20 giây…"); }
            @Override public void found(Location location) {
                if (map != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), zoom));
                routeHint();
            }
            @Override public void failed(String message) { status.setText(message); }
        });
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code==10) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) locate();
            else status.setText("Cần quyền vị trí chính xác để tìm vị trí thật. Bạn vẫn có thể chọn tuyến trên bản đồ.");
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("selection", selection());
        Bundle mapState = new Bundle(); mapView.onSaveInstanceState(mapState); state.putBundle("googleMap", mapState);
    }
    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); resumed=true; handler.post(update); }
    @Override protected void onPause() { resumed=false; handler.removeCallbacks(update); lookup.cancel(); mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); lookup.cancel(); mapView.onDestroy(); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
}
