# vRace 2.5.5 · Bản thử nghiệm cục bộ

Đây là bản sao vRace thay đổi để thử luồng nhận vị trí mô phỏng trên Android 14 không root. Bản này vẫn cần một app phát tuyến GPS riêng. Nó không tự tạo tuyến, không tự bấm Bắt đầu/Kết thúc/Lưu trong vRace và không chứng minh máy chủ sẽ công nhận thành tích.

## Những thay đổi đã thực hiện

- Thay đúng bốn lần lấy kết quả kiểm tra mock trong hai phương thức: `TrackActivity.lambda$checkFirstLocation$61` và `GpsTrackingService$2.onLocationResult`. Hai nhánh API 31+ gọi qua helper R8 `vrace_connect.i.a(Location)`; đã kiểm tra DEX thật thay vì chỉ dựa vào mã Java JADX.
- Mặc định giữ nguyên độ dài DEX, offset nhánh, vùng try/catch và mọi byte ngoài bốn vị trí cùng checksum/signature DEX. Tùy chọn sửa lớp vẽ bên dưới thay thêm 12 byte. Không thay accuracy, công thức distance/pace, giới hạn tốc độ hoặc phần gọi máy chủ.
- Đổi nhãn application thành **vRace · Thử nghiệm**. Giữ nguyên package `fpt.vne.vrace` và phiên bản 2.5.5/225.
- Ký lại một APK chính và hai split bằng cùng chứng thư thử nghiệm cục bộ. Khóa ký nằm trong `.tools/`, APK trong `artifacts/`; cả hai bị loại khỏi Git.
- Tùy chọn dùng khóa Maps SDK for Android riêng từ file cục bộ, chỉ thay metadata Maps trong manifest. Không in khóa hoặc đưa khóa vào báo cáo/Git; báo cáo xuất SHA-1 chứng thư để cấu hình quyền Maps.

Chỉ `AndroidManifest.xml` và `classes3.dex` có nội dung thay đổi trong APK chính, ngoài việc bỏ chữ ký cũ. Nội dung tài nguyên trong hai split được giữ nguyên. Công cụ từ chối APK khác với ba SHA-256 đầu vào đã pin.

## Tạo và kiểm tra lại

Cần Node.js 20+ và Python 3.10+, không cần tải thư viện hoặc Android SDK. Trong PowerShell, tại thư mục repository:

```powershell
npm test
npm run test:apk
& .\experiments\build.ps1
```

Đầu vào mặc định: `../platform-tools/vrace-apks/`. Đầu ra: `artifacts/vrace-test/signed/`, và gói `artifacts/vrace-test/vRace-test-2.5.5.zip` chứa ba APK, báo cáo và hướng dẫn. Giải nén ZIP trước khi cài bộ split. Có thể truyền `-InputDirectory` và `-OutputDirectory` cho script.

Script xác minh chữ ký v2 của cả ba APK gốc trước khi sửa, kiểm tra checksum DEX và nội dung ZIP, ký bản thử nghiệm, rồi xác minh lại chữ ký, hash nội dung và căn chỉnh các entry không nén. `build-report.json` ghi hash, chứng thư, vị trí sửa và trạng thái kiểm thử. Khóa cục bộ được giữ lại để những lần tạo sau dùng cùng chứng thư.

Định dạng được đối chiếu với tài liệu AOSP về [DEX](https://source.android.com/docs/core/runtime/dex-format), [bytecode](https://source.android.com/docs/core/runtime/dalvik-bytecode), [binary resources](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h) và [chữ ký APK v2](https://source.android.com/docs/security/features/apksigning/v2). Việc xác minh ở đây do công cụ cục bộ thực hiện, chưa phải kết quả của Android Package Installer hoặc `apksigner` chính thức.

## Bản thử nghiệm giảm tích lũy đường vẽ

Mã vRace gốc gọi `GoogleMap.addPolyline()` với toàn bộ tọa độ mỗi lần cập nhật ở `TrackActivity.lambda$updateTrack$44`, không giữ hoặc xóa đường vẽ cũ trong phiên. Đây là lỗi tích lũy lớp hiển thị đã xác nhận trong mã; chưa xác minh nó là nguyên nhân làm chậm toàn bộ Tecno Pova 5 của người dùng.

Tùy chọn `-BoundedMap` thay thêm **12 byte** trong phương thức vẽ: dọn lớp bản đồ trước khi thêm đường mới, giữ nguyên màu xanh, độ rộng và toàn bộ tọa độ. Theo [GoogleMap.clear()](https://developers.google.com/android/reference/com/google/android/gms/maps/GoogleMap#clear()), thao tác này xóa các overlay hiển thị. Màn hình ghi chạy được khảo sát chỉ thêm polyline; tùy chọn không xóa dữ liệu phiên hoặc polyline lưu trong hoạt động. Nó giữ một đường vẽ thay vì thêm một đường cho mỗi callback. Đường dài vẫn cần dựng và vẽ lại; mức độ cải thiện, nhấp nháy khi vẽ lại và tính tương thích cần kiểm tra trên máy thật.

Tùy chọn này tách biệt với bản thử nghiệm cũ. Tạo vào thư mục riêng để giữ bộ APK trước làm điểm quay lại:

```powershell
& .\experiments\build.ps1 -BoundedMap `
  -OutputDirectory .\artifacts\vrace-render-test `
  -MapsApiKeyFile .\.tools\maps-api-key.txt
```

Báo cáo ghi `bounded_map_overlays: true` và `phone_performance_verified: false`. Công cụ kiểm tra hash toàn bộ phương thức vẽ trước khi sửa; thay đổi vẫn giữ nguyên kích thước DEX, nhánh và bảng ngoại lệ. Ngoài bốn vị trí mock cũ và checksum DEX, tùy chọn chỉ cho phép thay 12 byte vẽ bản đồ; không sửa mã lưu, tính distance/pace hay gọi backend. Kiểm thử chạy phần bytecode vẽ thật với Maps giả lập qua 1.000 lần cập nhật tuyến A → B → gần A → B: bản cũ giữ 1.000 đường, bản sửa giữ một đường có cùng tọa độ, màu và độ rộng. Đây không phải đo FPS hoặc bộ nhớ renderer Android.

Nếu đang dùng bản vRace thử nghiệm cùng chứng thư, cài cập nhật cả ba split bằng:

```powershell
& '..\platform-tools\adb.exe' install-multiple -r `
  '.\artifacts\vrace-render-test\signed\base.apk' `
  '.\artifacts\vrace-render-test\signed\split_config.vi.apk' `
  '.\artifacts\vrace-render-test\signed\split_config.xxhdpi.apk'
```

Nếu xung đột chữ ký, dừng để kiểm tra, không gỡ app hoặc xóa dữ liệu. APK trước vẫn ở `artifacts/vrace-test/`. Thử lại cùng tuyến và thời lượng, quan sát độ mượt cả màn hình ghi chạy lẫn ứng dụng khác; không lưu/đồng bộ hoạt động thử lên tài khoản sản xuất. Khi đo bằng ADB, chỉ lấy số liệu CPU/bộ nhớ/nhiệt độ, không cần log tài khoản:

```powershell
& '..\platform-tools\adb.exe' shell dumpsys meminfo fpt.vne.vrace
& '..\platform-tools\adb.exe' shell dumpsys meminfo vn.stationary.route
& '..\platform-tools\adb.exe' shell dumpsys cpuinfo
& '..\platform-tools\adb.exe' shell dumpsys thermalservice
```

Đối chiếu trước khi phát tuyến và lúc chậm với cùng thời gian chạy. Nếu vẫn chậm khi vRace không chạy, hoặc CPU/nhiệt tăng từ tiến trình khác, cần điều tra riêng thay vì kết luận do polyline.

## Cài đặt trên điện thoại

Người dùng đã báo cài được bản thử nghiệm, mở app, đăng nhập và nhận GPS trên Tecno Pova 5 / Android 14. Công cụ phát triển chưa trực tiếp kiểm thử trên máy đó. Bản thử nghiệm có cùng package nhưng chữ ký khác vRace chính thức, nên không thể cài đè bản chính thức. Thay bản chính thức thường phải gỡ bản đó, mất dữ liệu cục bộ chưa đồng bộ và đăng nhập lại; cần được người dùng chấp thuận trước thao tác này. Bản cập nhật thử nghiệm dùng lại chứng thư cục bộ, nên có thể cài bằng `install-multiple -r` khi đang dùng đúng bản thử nghiệm.

Bộ thử nghiệm gồm cả ba APK; không chỉ mở `base.apk` để cài riêng. Sau khi việc thay app đã được chấp thuận và điện thoại không còn package có chữ ký xung đột, lệnh cài bộ split là:

```powershell
& '..\platform-tools\adb.exe' install-multiple -r `
  '.\artifacts\vrace-test\signed\base.apk' `
  '.\artifacts\vrace-test\signed\split_config.vi.apk' `
  '.\artifacts\vrace-test\signed\split_config.xxhdpi.apk'
```

Nếu báo xung đột chữ ký, dừng để kiểm tra; script không tự gỡ app hoặc xóa dữ liệu. Giữ bộ APK gốc ở `../platform-tools/vrace-apks/` để có thể cài lại bản gốc sau khi xử lý xung đột chữ ký tương ứng. Cài lại APK không khôi phục dữ liệu đã bị xóa khi gỡ app.

## Bản đồ xám và lỗi Google Maps Authorization failure

Người dùng cung cấp log `Google Android Maps SDK: Authorization failure`. Log xác nhận Maps từ chối cấp quyền, nhưng không tự xác định cấu hình Cloud nào sai. Việc ký lại đổi chứng thư trong khi giữ khóa Maps gốc là nguyên nhân đáng nghi; không có quyền thay cấu hình project của vRace. Lỗi này khác với thiếu dữ liệu polyline và không chứng minh portal đã nhận đủ lộ trình.

Đối với bản thử nghiệm, dùng khóa Maps riêng thuộc project bạn quản lý hoặc được chủ project cho phép. [Google yêu cầu cấu hình Maps SDK và billing](https://developers.google.com/maps/documentation/android-sdk/get-api-key); kiểm tra điều kiện tài khoản và chi phí trong Cloud Console trước khi bật billing.

1. Trong [Google Cloud Console](https://console.cloud.google.com/), tạo/chọn project riêng, thiết lập billing theo yêu cầu của Google và bật **Maps SDK for Android**.
2. Trong **APIs & Services → Credentials**, tạo API key. Chọn **Application restrictions → Android apps**, thêm package `fpt.vne.vrace` và SHA-1 của bản thử nghiệm. Lấy SHA-1 từ `outputs[].signers[].certificateSha1` trong `build-report.json` của bản build mới, hoặc chạy:

   ```powershell
   $verification = & node .\experiments\apk_v2.mjs verify .\artifacts\vrace-test\signed\base.apk | ConvertFrom-Json
   $verification.signers[0].certificateSha1
   ```

3. Chọn **API restrictions → Restrict key → Maps SDK for Android**, rồi lưu. Dùng chứng thư ký thử nghiệm, không dùng hash SHA-256 của APK hoặc chứng thư vRace chính thức. Xem [hướng dẫn giới hạn khóa của Google](https://developers.google.com/maps/api-security-best-practices).
4. Tạo file `.tools/maps-api-key.txt`, chỉ chứa khóa trên một dòng. Có thể mở bằng `notepad .\.tools\maps-api-key.txt`. `.tools/` đã bị loại khỏi Git. Không gửi khóa qua chat, không lưu khóa trong README hay commit. Khóa được nhúng trong APK để SDK sử dụng, vì vậy vẫn phải giới hạn theo ứng dụng/API.
5. Build bản cục bộ bằng:

   ```powershell
   & .\experiments\build.ps1 -MapsApiKeyFile .\.tools\maps-api-key.txt
   ```

   Không truyền tùy chọn này sẽ giữ cấu hình Maps gốc. Bộ build kiểm tra nội dung sau khi ký với cùng file khóa; nó không gọi Maps hay xác nhận quyền Cloud. Không mất/thay khóa ký `.tools/vrace-test-signing/identity.json`.
6. Cài lại cả ba APK bằng lệnh phía trên, mở bản đồ với mạng hoạt động và kiểm tra log cấp quyền. Chỉ kiểm tra hiển thị trong phiên thử nghiệm; không lưu/đồng bộ hoạt động mô phỏng lên tài khoản sản xuất. Sửa bản đồ nền không xác minh hoạt động thể chất hoặc khả năng portal nhận lộ trình.

Nếu cần kiểm tra log, chỉ lấy dòng lỗi, không chia sẻ API key hoặc thông tin tài khoản:

```powershell
& '..\platform-tools\adb.exe' logcat -d -v brief |
  Select-String 'Authorization failure|API key not found'
```

## Kiểm thử đầu tiên

1. Kiểm tra app mở được, nhãn Thử nghiệm đúng và đăng nhập được trước khi thử phiên.
2. Chọn app phát GPS của bạn trong mục vị trí mô phỏng của Developer Options, rồi chuẩn bị tuyến thử A–B.
3. Mở màn hình ghi chạy của vRace thử nghiệm, bắt đầu và quan sát tuyến, distance, speed/pace. Việc phát tuyến và việc bắt đầu phiên vRace là hai thao tác riêng.
4. Quan sát tạm dừng và tiếp tục. Kết thúc phát tuyến không tự kết thúc phiên vRace; cần dùng nút của vRace. Trong kiểm thử phát triển đầu tiên, không bấm Lưu/đồng bộ hoạt động mô phỏng lên tài khoản sản xuất.
5. Ghi lại lỗi cài đặt, mở app, đăng nhập, cảnh báo GPS hoặc crash để xác định bước cần sửa. Không gửi token, mật khẩu hoặc toàn bộ log tài khoản.

Chưa có kiểm thử trực tiếp trên Tecno Pova 5 và chưa có kiểm thử backend. APK được tạo thành công không đồng nghĩa ứng dụng chạy thành công, upload thành công hoặc hoạt động hợp lệ trong giải.
