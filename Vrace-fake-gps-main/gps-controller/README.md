# Tuyến GPS · Mô phỏng

App Android riêng để phát tuyến A → B qua Android GPS/network test providers và Google Fused Location mock APIs. Giao diện tiếng Việt, chọn nhiều điểm trên bản đồ, nhập tọa độ khi không có mạng, tốc độ 0–25 km/h, giữ A, phát, tạm dừng, tiếp tục, giữ B và tắt phát. Không cần root hoặc LSPosed.

App dùng cơ chế mock chính thức và giữ cờ mock của Android. Nó không sửa app khác, không bấm nút ghi/lưu trong vRace và không gửi hoạt động lên máy chủ. Khả năng vRace thử nghiệm nhận tuyến phải được kiểm tra trên điện thoại; không bảo đảm hoạt động được công nhận trong giải.

## Cài và sử dụng

1. Tải artifact **Tuyen-GPS-Android** trong lần build thành công ở [GitHub Actions](https://github.com/duchuanle03-sys/Vrace-fake-gps/actions/workflows/gps-controller.yml). Giải nén, cài **Tuyen-GPS-1.1.0.apk**. Đây là app có package riêng, không cần gỡ vRace.
2. Trong Tùy chọn nhà phát triển, mục **Ứng dụng vị trí mô phỏng**, chọn **Tuyến GPS · Mô phỏng**. Bật Vị trí và cấp quyền vị trí chính xác cho app. Cho phép thông báo để có nút tắt phát khi app ở nền.
3. Bấm **Chọn tuyến trên Google Maps**. Trong màn hình bản đồ, chạm để thêm A, điểm rẽ và B; kéo/chụm hai ngón hoặc dùng nút thu phóng, rồi bấm **Xong** để dùng tuyến. Một dòng như `10.8784, 106.7972` trong ô tọa độ đặt tâm bản đồ và điểm A; mở Maps chọn thêm B để có tuyến. Hai dòng trở lên tạo cả tuyến, mỗi dòng một cặp vĩ độ, kinh độ. Bấm **Dùng tọa độ** thay thế tuyến đang chọn bằng các điểm đã nhập. Các điểm được nối trực tiếp; không có dịch vụ tự tìm đường. Google Maps cần mạng và Google Play services; tuyến đã lưu và tọa độ nhập tay vẫn dùng được khi offline.
4. Chọn tốc độ rồi bấm **Đặt tại A / giữ vị trí**. Chờ app báo đang giữ tại A, sau đó sang vRace thử nghiệm và bắt đầu phiên.
5. Quay lại bấm **Phát tuyến**, rồi chuyển sang vRace. App tiếp tục phát qua dịch vụ foreground có thông báo.
6. Tạm dừng giữ nguyên tọa độ, tốc độ phát 0 và không cộng quãng đường/thời gian phát. Khi tới B app giữ B; dừng phiên trong vRace trước khi bấm **Tắt phát GPS**.

Trong kiểm thử phát triển, chỉ quan sát dữ liệu trong phiên; không lưu/đồng bộ hoạt động mô phỏng lên tài khoản sản xuất. Tránh buộc dừng app khi đang phát; dùng nút Tắt phát để tắt Fused mock mode và bỏ test providers. Nếu hệ thống buộc dừng tiến trình, app không tự tiếp tục phiên. Nếu mock mode không được dọn, mở lại app, đặt A rồi tắt phát; có thể cần tắt/bật Vị trí hoặc khởi động lại máy.

Android có thể giới hạn chạy nền theo thiết lập pin của hãng. Nếu tuyến bị ngắt khi chuyển app, kiểm tra thông báo dịch vụ, quyền vị trí và thiết lập pin cho app này; không tắt bảo vệ của toàn bộ điện thoại.

## Build và kiểm tra

Bản 1.1.0 thay bộ tải ô ảnh OpenStreetMap trong WebView bằng **Maps SDK for Android** và màn hình chọn/xem tuyến riêng. Ảnh lỗi người dùng cung cấp cho thấy tile không tải được, còn lớp điểm A vẫn vẽ; ảnh không xác định được lỗi HTTP/mạng cụ thể. App dùng callback tọa độ của Google Maps, giữ một polyline bằng `setPoints()` và chỉ cập nhật marker mô phỏng đang phát; không tích lũy đường vẽ mỗi nhịp. Màn hình Maps chỉ xem khi đang phát; bấm Xong mới nhận tuyến vào bộ điều khiển. Bản APK thiếu khóa Maps hiển thị hướng dẫn rõ ràng; nếu bản đồ chưa tải sau khoảng 20 giây sẽ gợi ý kiểm tra mạng/quyền Maps hoặc nhập tọa độ.

Bản 1.0.2 cho phép nhập một tọa độ để đặt A và đổi nút **Vị trí hiện tại** sang yêu cầu vị trí mới thay vì chỉ đọc cache. Nút hiển thị trạng thái chờ; yêu cầu định vị tối đa khoảng 20 giây và hủy khi rời app, sửa tuyến hoặc bắt đầu phát. Bật Vị trí, cấp quyền chính xác và tắt mô phỏng trước khi tìm vị trí thật. Trong nhà vẫn có thể không định vị được; dùng tọa độ nhập tay khi cần. Vị trí mock không được coi là vị trí thật và không dùng để đặt tâm qua nút này.

Bản 1.0.1 sửa lỗi kinh độ lệch 180° khi chạm hoặc kéo bản đồ. Tuyến đã lưu trong app không tự sửa vì không thể phân biệt chắc chắn tuyến chạm bản đồ với tuyến nhập tọa độ đúng. Sau cập nhật, dừng phát, bấm **Tuyến mới**, bấm **Vị trí hiện tại** hoặc nhập tọa độ đúng để đặt lại tâm bản đồ, rồi chọn lại A/B. Bản cập nhật không sửa dữ liệu hoạt động đã lưu trong vRace hay portal.

Cần JDK 17, Gradle 8.11.1 và Android SDK API 35 / Build Tools 35.0.0:

    gradle -p gps-controller :core:test :app:assembleDebug :app:lintDebug
    gradle -p gps-controller :app:connectedDebugAndroidTest
    node --test gps-controller/tests/*.test.mjs

Workflow dùng máy Linux của GitHub để chạy các lệnh trên và emulator Android 14 có Google APIs. Từ bản 1.1.0, CI ký bằng chứng thư riêng cố định từ GitHub Actions secret, không tạo khóa mới mỗi runner. Các bản từ 1.1.0 có thể cập nhật cùng chữ ký nếu giữ nguyên secret. Các bản 1.0.x dùng khóa debug tạm nên lần chuyển sang 1.1.0 có thể báo xung đột chữ ký. Trước khi gỡ riêng controller, sao chép tọa độ tuyến đang dùng; việc gỡ làm mất cấu hình riêng của controller. Không gỡ vRace để cập nhật controller.

Core tests kiểm tra tích phân theo thời gian thực, giữ A, pause/resume, tốc độ 0, đổi tốc độ, nhiều đoạn, đến B và tọa độ/đầu vào lỗi. Node tests chạy JavaScript giao diện thực, kiểm tra nhận tuyến từ Google Maps, tọa độ Việt Nam, tuyến lặp A–B–A–B, khóa sửa tuyến khi đang phát, nhập một/nhiều tọa độ, hủy định vị, lưu/nạp và payload Android. Android tests kiểm tra yêu cầu vị trí mới không dùng cache, kết quả rỗng/lỗi/mock và hủy yêu cầu; instrumentation chọn A/B bằng chạm MapView thật rồi bấm Xong, kiểm tra GPS/Google Fused nhận đúng tọa độ mock, chạy nền, pause và dọn provider. Các phép thử này không chứng minh Google Maps tải ô bản đồ trên Tecno hoặc định vị thật trong nhà. Test không cài hoặc mở vRace.

Quyền: vị trí chính xác cho Fused/foreground location service; mock app-op do người dùng chọn; foreground service/thông báo để điều khiển khi ở nền; wake lock trong lúc phát để duy trì nhịp; Internet chỉ cho bản đồ và các thành phần Google Play services. Không xin vị trí nền, quyền danh bạ, bước chân, storage hay quyền truy cập tài khoản.

Tuyến/cấu hình và tóm tắt phiên mô phỏng cuối lưu trong SharedPreferences riêng trên máy. Không có endpoint gửi tuyến hoặc lịch sử của app tới máy chủ riêng. SDK Google Maps kết nối dịch vụ Google để hiển thị bản đồ; app giữ logo và attribution của SDK. Các điểm được nối trực tiếp, không gửi yêu cầu tìm đường.

## Cấu hình Google Maps và chữ ký cố định

CI chờ callback `OnMapLoaded` trước khi chạm chọn A/B trên emulator Android 14 và kiểm tra tuyến được phát qua GPS/Google Fused. Đây là kiểm chứng Maps tải được trên emulator; vẫn cần thử trên Tecno. Artifact gồm APK và báo cáo kiểm tra, không yêu cầu ảnh minh chứng; test không mở vRace hoặc tài khoản sản xuất.

Khóa Maps của vRace được cấu hình cho một ứng dụng khác; Controller có package **`vn.stationary.route`**. Dùng khóa Maps SDK for Android riêng thuộc project đã có quyền sử dụng, giới hạn Android bằng package và SHA-1 chứng thư Controller. Không gửi khóa API hoặc khóa ký qua chat, không đưa vào mã nguồn/Git. Xem [hướng dẫn cấu hình của Google](https://developers.google.com/maps/documentation/android-sdk/get-api-key).

- Khóa Maps cục bộ: `.tools/gps-controller-maps-api-key.txt`, một dòng. Gradle cũng đọc biến môi trường `GPS_CONTROLLER_MAPS_KEY`. Thiếu khóa chỉ cho phép build để phát triển, không có bản đồ hoạt động; CI giao APK yêu cầu có khóa.
- Tạo và giữ khóa ký riêng bằng `node gps-controller/tools/create-signing.mjs`. File `.tools/gps-controller-signing/identity.json` bị loại khỏi Git; script chỉ in SHA-1 công khai và không thay khóa đã tồn tại.
- GitHub Actions secrets: **`GPS_CONTROLLER_MAPS_KEY`** chứa khóa Maps, **`GPS_CONTROLLER_SIGNING`** chứa nguyên nội dung JSON khóa ký cục bộ. Workflow dùng OpenSSL chuyển khóa sang PKCS12 tạm trên runner, Gradle ký APK/app kiểm thử bằng cùng chứng thư. Các file khóa không nằm trong artifact.
- SHA-1 chứng thư đã chuẩn bị: **`53:47:55:C4:A9:05:5E:BA:A3:6A:63:AD:1E:77:65:02:6C:64:7E:3C`**. Đối chiếu với `SIGNATURE.txt` của artifact khi build xong. Đổi chứng thư thì phải cập nhật quyền Maps tương ứng.

API/tài liệu: [MapView và vòng đời](https://developers.google.com/android/reference/com/google/android/gms/maps/MapView), [GoogleMap](https://developers.google.com/android/reference/com/google/android/gms/maps/GoogleMap), [Google Fused mock](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [Android test providers](https://developer.android.com/reference/android/location/LocationManager), [foreground location trên Android 14](https://developer.android.com/about/versions/14/changes/fgs-types-required).
