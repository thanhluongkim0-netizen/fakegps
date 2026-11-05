package vn.stationary.route;

import android.location.Location;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Controlled source responses exercise lookup behavior without requiring a physical GPS fix. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class CurrentPositionLookupTest {
    private static final class Source implements CurrentPositionLookup.Source {
        final List<TaskCompletionSource<Location>> tasks = new ArrayList<>();
        final List<CancellationToken> tokens = new ArrayList<>();
        CurrentLocationRequest options;
        @Override public Task<Location> current(CurrentLocationRequest request, CancellationToken token) {
            options = request; tokens.add(token);
            TaskCompletionSource<Location> task = new TaskCompletionSource<>(); tasks.add(task);
            return task.getTask();
        }
    }
    private static final class Result implements CurrentPositionLookup.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        boolean searching;
        Location found;
        String error;
        @Override public void searching() { searching = true; }
        @Override public void found(Location location) { found = location; done.countDown(); }
        @Override public void failed(String message) { error = message; done.countDown(); }
        void await() throws Exception { assertTrue("Lookup completes", done.await(5, TimeUnit.SECONDS)); }
    }
    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private Location location(boolean mock) {
        Location location = new Location("gps");
        location.setLatitude(10.8784); location.setLongitude(106.7972); location.setMock(mock);
        return location;
    }
    @Test public void requestsFreshFixAndReturnsItWithoutAnyCachedLocation() throws Exception {
        Source source = new Source(); Result result = new Result();
        CurrentPositionLookup lookup = new CurrentPositionLookup(source);
        main(() -> lookup.start(result));
        assertTrue(result.searching);
        assertEquals(0, source.options.getMaxUpdateAgeMillis());
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, source.options.getPriority());
        assertTrue(source.options.getDurationMillis() > 0 && source.options.getDurationMillis() <= 30_000);
        main(() -> source.tasks.get(0).setResult(location(false)));
        result.await();
        assertNull(result.error); assertNotNull(result.found);
        assertEquals(106.7972, result.found.getLongitude(), .000001);
    }
    @Test public void timeoutAndProviderFailureGiveActionableErrors() throws Exception {
        Source source = new Source(); CurrentPositionLookup lookup = new CurrentPositionLookup(source);
        Result timeout = new Result(); main(() -> lookup.start(timeout));
        main(() -> source.tasks.get(0).setResult(null)); timeout.await();
        assertNull(timeout.found); assertTrue(timeout.error.contains("nhập tọa độ"));
        Result failure = new Result(); main(() -> lookup.start(failure));
        main(() -> source.tasks.get(1).setException(new SecurityException("permission revoked"))); failure.await();
        assertNull(failure.found); assertTrue(failure.error.contains("quyền"));
    }
    @Test public void mockPositionCannotBePresentedAsTheRealCurrentPosition() throws Exception {
        Source source = new Source(); Result result = new Result();
        CurrentPositionLookup lookup = new CurrentPositionLookup(source);
        main(() -> lookup.start(result));
        main(() -> source.tasks.get(0).setResult(location(true))); result.await();
        assertNull(result.found); assertTrue(result.error.contains("mô phỏng"));
    }
    @Test public void cancelledAndSupersededResultsAreIgnored() throws Exception {
        Source source = new Source(); CurrentPositionLookup lookup = new CurrentPositionLookup(source);
        Result cancelled = new Result(); main(() -> lookup.start(cancelled));
        main(lookup::cancel);
        assertTrue(source.tokens.get(0).isCancellationRequested());
        main(() -> source.tasks.get(0).setResult(location(false)));
        Result older = new Result(); main(() -> lookup.start(older));
        Result latest = new Result(); main(() -> lookup.start(latest));
        assertTrue(source.tokens.get(1).isCancellationRequested());
        main(() -> source.tasks.get(1).setResult(location(false)));
        main(() -> source.tasks.get(2).setResult(location(false))); latest.await();
        assertEquals(1, cancelled.done.getCount());
        assertEquals(1, older.done.getCount());
        assertNotNull(latest.found);
    }
}
