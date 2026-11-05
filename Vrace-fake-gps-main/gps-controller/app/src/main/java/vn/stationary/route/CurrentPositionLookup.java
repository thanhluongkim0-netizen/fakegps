package vn.stationary.route;

import android.location.Location;
import android.os.Build;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Task;

/** One foreground location request; cancelled or superseded results never move the map. */
final class CurrentPositionLookup {
    interface Source {
        Task<Location> current(CurrentLocationRequest request, CancellationToken token);
    }
    interface Listener {
        void searching();
        void found(Location location);
        void failed(String message);
    }
    private final Source source;
    private CancellationTokenSource pending;

    CurrentPositionLookup(Source source) { this.source = source; }

    void start(Listener listener) {
        cancel();
        CancellationTokenSource request = new CancellationTokenSource();
        pending = request;
        listener.searching();
        CurrentLocationRequest options = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0)
                .setDurationMillis(20_000)
                .build();
        try {
            source.current(options, request.getToken()).addOnCompleteListener(task -> {
                if (pending != request) return;
                pending = null;
                if (task.isCanceled()) {
                    listener.failed("Đã hủy tìm vị trí. Bạn có thể bấm thử lại.");
                } else if (!task.isSuccessful()) {
                    listener.failed("Không lấy được vị trí. Kiểm tra quyền Vị trí chính xác và thử lại.");
                } else {
                    Location location = task.getResult();
                    if (location == null) {
                        listener.failed("Chưa định vị được sau khi chờ. Thử nơi thoáng hơn hoặc nhập tọa độ để đặt A.");
                        return;
                    }
                    boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
                    if (mock) {
                        listener.failed("Vị trí nhận được là mô phỏng. Tắt phát GPS rồi thử lại hoặc nhập tọa độ để đặt A.");
                        return;
                    }
                    listener.found(location);
                }
            });
        } catch (RuntimeException e) {
            if (pending != request) return;
            pending = null;
            request.cancel();
            listener.failed("Không bắt đầu được định vị. Kiểm tra quyền Vị trí chính xác và thử lại.");
        }
    }

    void cancel() {
        CancellationTokenSource request = pending;
        pending = null;
        if (request != null) request.cancel();
    }
}
