package vn.stationary.route.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure route/timing engine. Clock values are monotonic milliseconds, never wall time. */
public final class RouteSession {
    public enum State { HOLDING, RUNNING, PAUSED, ARRIVED }
    public static final class Point {
        public final double latitude, longitude;
        public Point(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("Tọa độ không hợp lệ (vĩ độ -90…90, kinh độ -180…180).");
            }
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
    private final List<Point> points;
    private final double[] cumulative;
    private State state = State.HOLDING;
    private double speedKmh, distance, activeMillis;
    private long lastClock;

    public RouteSession(List<Point> route, double speedKmh, long clock) {
        if (route == null || route.size() < 2 || route.size() > 300) {
            throw new IllegalArgumentException("Chọn từ 2 đến 300 điểm cho tuyến.");
        }
        points = Collections.unmodifiableList(new ArrayList<>(route));
        cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1) == null || points.get(i) == null) {
                throw new IllegalArgumentException("Tuyến thiếu tọa độ.");
            }
            double length = meters(points.get(i - 1), points.get(i));
            if (length < 0.01) throw new IllegalArgumentException("Hai điểm liên tiếp trùng nhau.");
            cumulative[i] = cumulative[i - 1] + length;
        }
        if (totalMeters() < 1 || totalMeters() > 100_000) {
            throw new IllegalArgumentException("Độ dài tuyến phải từ 1 m đến 100 km.");
        }
        validateSpeed(speedKmh);
        this.speedKmh = speedKmh;
        lastClock = clock;
    }
    public synchronized void advance(long clock) {
        if (clock < lastClock) throw new IllegalArgumentException("Đồng hồ đơn điệu bị lùi.");
        double delta = clock - lastClock;
        lastClock = clock;
        if (state != State.RUNNING) return;
        double velocity = speedKmh / 3.6;
        if (velocity > 0) {
            double usable = Math.min(delta, (totalMeters() - distance) / velocity * 1000);
            activeMillis += usable;
            distance = Math.min(totalMeters(), distance + velocity * usable / 1000);
            if (totalMeters() - distance < 1e-7) {
                distance = totalMeters();
                state = State.ARRIVED;
            }
        } else {
            activeMillis += delta;
        }
    }
    public synchronized void play(long clock) {
        advance(clock);
        if (state != State.ARRIVED) state = State.RUNNING;
    }
    public synchronized void pause(long clock) {
        advance(clock);
        if (state != State.ARRIVED) state = State.PAUSED;
    }
    public synchronized void speed(double value, long clock) {
        validateSpeed(value);
        advance(clock);
        speedKmh = value;
    }
    private static void validateSpeed(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 25) {
            throw new IllegalArgumentException("Tốc độ phải từ 0 đến 25 km/h.");
        }
    }
    public synchronized Point position() {
        if (distance >= totalMeters()) return points.get(points.size() - 1);
        int next = 1;
        while (next < cumulative.length - 1 && cumulative[next] < distance) next++;
        return interpolate(points.get(next - 1), points.get(next),
                (distance - cumulative[next - 1]) / (cumulative[next] - cumulative[next - 1]));
    }
    public synchronized float bearing() {
        if (state == State.ARRIVED) return 0;
        int next = 1;
        while (next < cumulative.length - 1 && cumulative[next] <= distance) next++;
        Point a = position(), b = points.get(next);
        double p1 = Math.toRadians(a.latitude), p2 = Math.toRadians(b.latitude);
        double dl = Math.toRadians(b.longitude - a.longitude);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
    }
    public synchronized State state() { return state; }
    public synchronized double distanceMeters() { return distance; }
    public synchronized double activeSeconds() { return activeMillis / 1000; }
    public double totalMeters() { return cumulative[cumulative.length - 1]; }
    public synchronized double speedKmh() { return speedKmh; }
    public synchronized double emittedSpeedMps() { return state == State.RUNNING ? speedKmh / 3.6 : 0; }
    public synchronized double paceSeconds() { return speedKmh > 0 ? 3600 / speedKmh : Double.NaN; }

    public static double meters(Point a, Point b) {
        double dp = Math.toRadians(b.latitude - a.latitude), dl = Math.toRadians(b.longitude - a.longitude);
        double h = Math.pow(Math.sin(dp / 2), 2) + Math.cos(Math.toRadians(a.latitude))
                * Math.cos(Math.toRadians(b.latitude)) * Math.pow(Math.sin(dl / 2), 2);
        return 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
    }
    public static Point interpolate(Point a, Point b, double fraction) {
        if (fraction <= 0) return a;
        if (fraction >= 1) return b;
        double angular = meters(a, b) / 6_371_008.8;
        if (angular < 1e-12) return a;
        double u = Math.sin((1 - fraction) * angular) / Math.sin(angular);
        double v = Math.sin(fraction * angular) / Math.sin(angular);
        double p1 = Math.toRadians(a.latitude), p2 = Math.toRadians(b.latitude);
        double l1 = Math.toRadians(a.longitude), l2 = Math.toRadians(b.longitude);
        double x = u * Math.cos(p1) * Math.cos(l1) + v * Math.cos(p2) * Math.cos(l2);
        double y = u * Math.cos(p1) * Math.sin(l1) + v * Math.cos(p2) * Math.sin(l2);
        double z = u * Math.sin(p1) + v * Math.sin(p2);
        return new Point(Math.toDegrees(Math.atan2(z, Math.hypot(x, y))), Math.toDegrees(Math.atan2(y, x)));
    }
}
