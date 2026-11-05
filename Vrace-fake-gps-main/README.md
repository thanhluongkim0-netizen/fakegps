# Phân tích vRace và bản thử nghiệm cục bộ

Người dùng đã xác nhận bộ mô phỏng độc lập không đáp ứng nhu cầu và đồng ý thử bản vRace thay đổi trên Tecno Pova 5, Android 14, chưa có quyền root khả dụng. Sau khi cho phép thay bản chính thức, người dùng báo bản thử nghiệm đã khởi động và đăng nhập được. Tiếp theo là tạo app phát tuyến GPS riêng và kiểm tra phối hợp trên điện thoại; chưa xác minh tuyến được nhận hoặc máy chủ công nhận thành tích.

Mã bộ mô phỏng cũ nằm trong `simulator/`. Các tệp phân tích vRace và APK gốc được giữ nguyên tại máy và không đưa lên repository. `AGENTS.md` lưu phạm vi hiện tại, quy trình commit/push và tám mục phân tích.

APK vRace thử nghiệm và khóa ký chỉ được lưu cục bộ. Bản ký lại không thể cài đè bản vRace chính thức có chữ ký khác. Việc thay bản chính thức đã được người dùng cho phép và thực hiện theo hướng dẫn vì môi trường công cụ không chạy được ADB. App phát tuyến GPS có package riêng và được build thành artifact từ mã nguồn gốc của dự án.

## App phát tuyến GPS riêng

Mã Android gốc nằm trong [gps-controller/](gps-controller/README.md). App **Tuyến GPS · Mô phỏng** phát A → B qua GPS/network và Google Fused, có tốc độ, tạm dừng/tiếp tục và dịch vụ nền. Bản 1.1.0 chọn tuyến bằng Google Maps SDK cho Android, dùng khóa Maps và chứng thư riêng cố định. Chọn app trong mục vị trí mô phỏng của Android; không cần root. Build APK và kiểm thử emulator qua [GitHub Actions](https://github.com/duchuanle03-sys/Vrace-fake-gps/actions/workflows/gps-controller.yml). Khả năng phối hợp với vRace trên Tecno vẫn cần kiểm thử thực tế.

## Bộ APK vRace thử nghiệm

Công cụ trong [`experiments/`](experiments/README.md) tạo và ký bộ APK thử nghiệm từ đúng đầu vào vRace 2.5.5 đã phân tích, giữ nguyên APK gốc. Chỉ sửa các điểm nhận kết quả kiểm tra mock và nhãn application. Không cần root/LSPosed để chuẩn bị hoặc cài APK ký lại; cài đặt và vận hành thực tế vẫn cần kiểm thử.

```powershell
npm test
npm run test:apk
& .\experiments\build.ps1
```

APK đã ký nằm trong `artifacts/vrace-test/signed/`, kèm `build-report.json` và hướng dẫn. Bộ này vẫn cần app phát tuyến riêng và các thao tác ghi/lưu trong vRace. Chưa xác minh thành tích được máy chủ công nhận. Không đưa APK vRace hoặc khóa ký lên repository công khai.

## Kiểm thử bộ tính phiên

Cần Node.js 20 trở lên, không cần cài thư viện:

```sh
npm test
```

Bộ tính dùng đồng hồ đơn điệu, tích phân tốc độ theo thời gian thực tế, loại thời gian tạm dừng, khôi phục phiên sau khi mở lại, và lưu kết thúc một lần. Khi điện thoại khởi động lại, phiên được khôi phục ở trạng thái tạm dừng. Pace bằng `—` khi tốc độ hoặc quãng đường bằng 0.

Bộ tính này là thành phần mô phỏng độc lập đã được kiểm thử, không khiến vRace ghi nhận hoạt động.
