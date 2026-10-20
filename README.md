# Laze — kiểm thử phát video và điều hướng TikTok

APK Android Kotlin/XML, Android 7.0 trở lên. Mỗi lần bấm **Bắt đầu một phiên kiểm thử** chạy một phiên có giới hạn, ghi log rồi dừng. Bật Accessibility không tự khởi chạy phiên.

## Tải APK kiểm thử

[Tải Laze-kiem-thu-debug.apk](https://github.com/pthaodotcom/Lazetiktokkhach2010/raw/refs/heads/main/releases/Laze-kiem-thu-debug.apk) (khoảng 5,6 MiB).

APK được lưu cùng mã nguồn tại `releases/Laze-kiem-thu-debug.apk`, ký bằng debug key để cài thử. Đã chạy 20 unit test và Android lint không có lỗi; chưa xác minh luồng với TikTok thực tế trên thiết bị.

## Luồng phiên

`INIT → CHECK_TIKTOK → OPEN_TEST_VIDEO → VERIFY_PLAYBACK → WAIT_PLAYBACK_END → RECORD_PLAYBACK_RESULT → NAVIGATION_TEST → VERIFY_NAVIGATION → SAVE_LOGS → STOP`

Sau khi xác minh phát xong A, APK kiểm tra 2 hoặc 3 lần chuyển sang video khác. Sau tổng thời gian chờ cấu hình 30–40 giây (tính từ lúc xác minh video khác đầu tiên, gồm thời gian chuyển giữa các video), APK mở lại link A, xác nhận mô tả và tiến trình đang chạy, lưu log rồi dừng. Không đếm lần quay lại thành một lượt phát hoàn thành mới.

## Cách dùng

1. Cài APK, mở Laze, bấm **Mở cài đặt Trợ năng**, bật Laze rồi quay lại app.
2. Nhập link A dạng `https://www.tiktok.com/@tacgia/video/123456789`. Bỏ query chia sẻ sau `?`; link rút gọn chưa được hỗ trợ.
3. Nhập 8–200 ký tự riêng của mô tả A mà TikTok đang hiển thị. Chọn đoạn không xuất hiện trong các video khác.
4. Chọn 2 hoặc 3 video khác, tổng thời gian chờ 30–40 giây, giới hạn chờ A từ 30 đến 900 giây.
5. Bấm **Bắt đầu một phiên kiểm thử**. Nút **Dừng kiểm thử** nổi trên TikTok giúp hủy phiên. Quay lại Laze để xem kết quả và **Xuất log JSON** bằng hộp thoại lưu file của Android.

## Điều kiện xác minh và giới hạn

- Chỉ hỗ trợ gói TikTok `com.zhiliaoapp.musically`; không hỗ trợ TikTok Lite/Douyin, YouTube hay Instagram trong luồng này.
- Accessibility phải cung cấp một SeekBar/ProgressBar khả kiến, nằm ở nửa dưới màn hình, đủ rộng và có RangeInfo hợp lệ. Nhiều thanh phù hợp cùng lúc hoặc thiếu dữ liệu đều được coi là chưa xác minh được.
- A được đối chiếu bằng đoạn mô tả do người dùng nhập, không phải bằng API xác thực ID video. Link được mở trực tiếp trong gói TikTok. Mô tả trùng hoặc các node cũ còn trên màn hình có thể làm nhận diện sai; cần kiểm tra với phiên bản TikTok thực tế.
- Các video khác phải có mô tả/tác giả qua resource ID có nghĩa (`caption`, `description`, `author`, `username`, ...). Định danh được băm SHA-256; không dùng thay đổi số like hay thời gian làm bằng chứng đổi video. ID của TikTok có thể bị làm rối hoặc thay đổi, khi đó APK dừng vì không đủ bằng chứng.
- Xác nhận phát khi tiến trình từ vùng đầu (≤5%) tăng qua ít nhất hai mẫu. Phát hoàn thành khi đã quan sát vùng đầu và thấy cuối dải (≥99,9%), hoặc thấy ≥98% rồi về ≤5%; trường hợp thứ hai được ghi rõ là **suy luận phát lại** (`NEAR_END_THEN_REPLAY_INFERRED`). Đây là bằng chứng từ thanh tiến trình, không phải xác nhận từ hệ thống TikTok.
- Tiến trình nhảy quá 25 điểm phần trăm giữa hai mẫu hoặc gián đoạn quan sát quá 2,5 giây không được tính hoàn thành. Video rất ngắn có thể bị từ chối vì không đủ mẫu. Đây là lựa chọn thận trọng của bản kiểm thử.
- Poll mỗi 500 ms để phát hiện đứng hình mà không phụ thuộc Accessibility event. Tiến trình không thay đổi quá 15 giây, lỗi mở video, mất dữ liệu quá 5 giây, lỗi vuốt, mất foreground, lỗi lưu log hoặc quá hạn sẽ kết thúc phiên.
- Nhận diện một số dialog, màn nhập liệu, thông báo lỗi mạng/đăng nhập và video không khả dụng bằng node khả kiến. Không có quyền xem nội bộ TikTok; không bảo đảm nhận diện mọi popup, CAPTCHA, live, ảnh hoặc quảng cáo. Không tự đóng hay vượt qua các màn hình này.
- Điều hướng chỉ được tính sau khi định danh mới ổn định qua ít nhất hai mẫu và tiến trình tăng. Callback vuốt thành công không được tính là đổi video.
- Dừng phiên hủy mọi bước tiếp theo và bỏ qua callback cũ. Một gesture 350 ms đã gửi cho Android có thể hoàn tất. Service bị ngắt/process bị hủy không tự tiếp tục.

## Log và Analytics

Log mới nhất nằm trong bộ nhớ riêng `files/latest-session.json`; các phiên kết thúc được giữ tại `files/sessions/<sessionId>.json`. Nút xuất lấy bản chụp log mới nhất, không cần quyền đọc/ghi toàn bộ bộ nhớ. Log chứa cấu hình, mốc thời gian, trạng thái, mã lỗi, số lượt phát đã xác nhận/hoàn thành, bằng chứng hoàn thành, số lần chuyển video đã xác minh và kết quả quay lại A. Mỗi sự kiện trạng thái có bằng chứng màn hình gần nhất khi có dữ liệu.

`analyticsViews` luôn là `null`: APK không tích hợp TikTok Analytics. Đối chiếu thủ công bằng video và thời gian phiên trong log. Không quy đổi số phiên thành view, không có chế độ lặp vô hạn, không bảo đảm ngưỡng 30–40 giây làm tăng view.

## Build và kiểm tra trên Windows

Cần JDK 17, Android SDK Platform 34 và kết nối mạng để Gradle tải dependency lần đầu. Tạo `local.properties` trỏ SDK của máy:

```properties
sdk.dir=C:/Android/Sdk
```

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Bản debug dùng để cài thử; kích thước APK chưa được tối ưu như release. Repo có Gradle Wrapper 8.2.1 kèm checksum phân phối.

Các unit test dùng mẫu màn hình giả để kiểm tra state machine: không tự chạy khi chưa bắt đầu, video chưa phát/đứng, tiến trình nhảy/gián đoạn, 95% chưa được tính xong, bằng chứng replay, sai video, popup/mạng, lỗi gesture, xác minh đổi video, lịch chờ cho 2/3 video, quay lại A và dừng. Chúng không chứng minh adapter đọc được TikTok thực tế.

Trước khi dùng trên thiết bị, kiểm tra một phiên bằng video thử được phép sử dụng, một phiên mất mạng, popup, màn hình không có thanh tiến trình và thao tác Dừng. Chỉ kết luận tương thích TikTok sau khi chạy các bước này trên phiên bản ứng dụng cụ thể.

Tài liệu API Android: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [package visibility](https://developer.android.com/training/package-visibility/declaring).
