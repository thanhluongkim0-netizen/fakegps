package vn.stationary.route.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static vn.stationary.route.core.RouteSession.*;

public class RouteSessionTest {
    private RouteSession route() { return new RouteSession(Arrays.asList(new Point(10, 106), new Point(10.02, 106)), 3.6, 0); }
    @Test public void holdsAtAUntilPlay() {
        RouteSession r = route(); r.advance(100_000);
        assertEquals(0, r.distanceMeters(), 0); assertEquals(State.HOLDING, r.state());
        assertEquals(10, r.position().latitude, 0); assertEquals(0, r.emittedSpeedMps(), 0);
    }
    @Test public void integratesElapsedNotTickCount() {
        RouteSession r = route(); r.play(1000); r.advance(12_500);
        assertEquals(11.5, r.distanceMeters(), 1e-9); assertEquals(11.5, r.activeSeconds(), 1e-9);
    }
    @Test public void pausesExcludeElapsedAndResumeWithoutJump() {
        RouteSession r = route(); r.play(0); r.pause(10_000); Point p = r.position();
        r.advance(100_000); assertEquals(10, r.distanceMeters(), 0); assertEquals(p.latitude, r.position().latitude, 0);
        r.play(120_000); r.advance(125_000); assertEquals(15, r.distanceMeters(), 0); assertEquals(15, r.activeSeconds(), 0);
    }
    @Test public void speedChangesSettleOldSpeed() {
        RouteSession r = route(); r.play(0); r.speed(7.2, 10_000); r.advance(15_000);
        assertEquals(20, r.distanceMeters(), 0); assertEquals(500, r.paceSeconds(), 0);
    }
    @Test public void zeroSpeedIsExplicitAndStationary() {
        RouteSession r = route(); r.speed(0, 0); r.play(0); r.advance(5000);
        assertEquals(0, r.distanceMeters(), 0); assertTrue(Double.isNaN(r.paceSeconds()));
    }
    @Test public void reachesBExactlyAndKeepsHoldingB() {
        RouteSession r = route(); r.play(0); r.advance(10_000_000);
        assertEquals(State.ARRIVED, r.state()); assertEquals(r.totalMeters(), r.distanceMeters(), 0);
        assertEquals(r.totalMeters(), r.activeSeconds(), 1e-8); assertEquals(10.02, r.position().latitude, 0);
        double t = r.activeSeconds(); r.play(20_000_000); r.advance(30_000_000);
        assertEquals(t, r.activeSeconds(), 0); assertEquals(0, r.emittedSpeedMps(), 0);
    }
    @Test public void traversesMultipleLegsWithoutShortcut() {
        Point a = new Point(0, 0), b = new Point(0, .001), c = new Point(.001, .001);
        RouteSession r = new RouteSession(Arrays.asList(a, b, c), 3.6, 0);
        r.play(0); r.advance((long) (meters(a, b) * 1000 + 50_000));
        assertEquals(.001, r.position().longitude, 1e-10); assertTrue(r.position().latitude > 0);
        assertEquals(0, r.bearing(), 1e-3);
    }
    @Test public void interpolationCrossesDateLineByShortArc() {
        Point p = interpolate(new Point(0, 179.999), new Point(0, -179.999), .5);
        assertEquals(180, Math.abs(p.longitude), 1e-9);
    }
    @Test public void polarArcCanExceedEndpointLatitudeWithoutInvalidCoordinates() {
        Point a = new Point(85, -.1), b = new Point(85, .1);
        RouteSession r = new RouteSession(Arrays.asList(a, b), 3.6, 0);
        r.play(0); r.advance((long) (r.totalMeters() / 2 * 1000));
        assertTrue(r.position().latitude > 85);
        assertEquals(a.latitude, interpolate(a, a, .5).latitude, 0);
    }
    @Test public void invalidSpeedDoesNotSettleOrMutate() {
        RouteSession r = route(); r.play(0);
        assertThrows(IllegalArgumentException.class, () -> r.speed(Double.NaN, 1000));
        assertEquals(0, r.distanceMeters(), 0); assertEquals(3.6, r.speedKmh(), 0);
    }
    @Test public void rejectsInvalidRoutesAndReversedClock() {
        assertThrows(IllegalArgumentException.class, () -> new Point(91, 0));
        assertThrows(IllegalArgumentException.class, () -> new Point(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RouteSession(Arrays.asList(new Point(0, 0), new Point(0, 0)), 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new RouteSession(Arrays.asList(new Point(0, 0), new Point(1, 0)), 8, 0));
        RouteSession r = route(); r.advance(1000);
        assertThrows(IllegalArgumentException.class, () -> r.advance(999));
    }
}
