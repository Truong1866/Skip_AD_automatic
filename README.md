# ⚡ AdSkipper AI v2 — YOLO On-Device + Template Matching

## Tổng quan tính năng mới

| Tính năng | Chi tiết |
|-----------|----------|
| **YOLO On-Device** | Chạy `best.onnx` trực tiếp trên máy (không cần internet) |
| **Template Matching** | So khớp ảnh tham chiếu ở 1/4 resolution (NCC algorithm) |
| **Tần số quét** | Cố định **1Hz** (1 lần/giây) |
| **Delay hành động** | **1 giây** sau khi phát hiện mới click |
| **Chất lượng đầu vào** | Full resolution (không resize màn hình trước inference) |
| **Claude API** | Tùy chọn, chỉ dùng làm fallback cuối |

---

## 🔄 Luồng xử lý

```
Màn hình (full res, 1Hz)
        │
        ▼
┌───────────────────┐
│  1. YOLO on-device │  best.onnx → DetectionModel
│  (ưu tiên cao nhất)│  Input: 640×640 letterbox
└─────────┬─────────┘  Output: boxes + confidence
          │ fail
          ▼
┌───────────────────┐
│  2. Template Match │  NCC @ 1/4 resolution
│  (so ảnh tham chiếu)│  Multi-template support
└─────────┬─────────┘  Threshold: 75%
          │ fail
          ▼
┌───────────────────┐
│  3. Claude API     │  Chỉ khi có API key
│  (fallback cloud)  │  Resize 1080p để tiết kiệm
└─────────┬─────────┘
          │ phát hiện
          ▼
    Đợi 1 giây
          │
          ▼
    performClickRatio()
    (Accessibility Service)
```

---

## 📂 Cấu trúc project

```
AdSkipper2/
├── scripts/
│   ├── best.pt               ← Model gốc (YOLOv8)
│   └── export_model.py       ← Script export sang ONNX
├── app/src/main/
│   ├── assets/
│   │   └── best.onnx         ← ⚠️ BẠN PHẢI TẠO FILE NÀY (xem bên dưới)
│   ├── java/com/adskipper/
│   │   ├── MainActivity.java              ← UI chính
│   │   ├── YoloDetector.java              ← ONNX Runtime inference
│   │   ├── TemplateMatcher.java           ← NCC template matching 1/4
│   │   ├── ClaudeVisionClient.java        ← Cloud AI fallback
│   │   ├── AdSkipperAccessibilityService.java ← Auto click
│   │   └── ScreenCaptureService.java      ← 1Hz capture + orchestration
│   └── res/...
└── build.gradle (ONNX Runtime dep included)
```

---

## 🔧 BƯỚC QUAN TRỌNG: Export model sang ONNX

**Bắt buộc** — app Android không đọc được `.pt`, cần `.onnx`:

```bash
# 1. Cài dependencies
pip install ultralytics onnx onnxruntime

# 2. Chạy export
cd scripts/
python3 export_model.py

# 3. Copy output vào assets
cp best.onnx ../app/src/main/assets/best.onnx
```

File `best.onnx` cần nằm tại: `app/src/main/assets/best.onnx`

---

## 🚀 Build & Cài đặt

```bash
# Mở project trong Android Studio
# Sync Gradle (tự tải ONNX Runtime ~30MB)
# Build → Generate APK → Debug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Dependency chính trong build.gradle:**
```gradle
implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.17.0'
```

---

## ⚙️ Cách sử dụng Template Matching

1. **Chụp ảnh phần UI** bạn muốn click (vd: nút "Chơi", nút "OK", icon game...)
   - Có thể dùng screenshot tool, rồi crop phần cần thiết
   - Ảnh càng đặc trưng (không bị lặp lại trên màn hình) càng tốt

2. **Trong app**: Nhấn **"+ Thêm ảnh so khớp"** → chọn ảnh từ gallery

3. App sẽ tự động:
   - Scale ảnh về 1/4 để so khớp nhanh
   - Khi tìm thấy trên màn hình (NCC score ≥ 75%) → đợi 1s → click vào vị trí đó

4. Có thể thêm **nhiều template** — app so khớp tất cả, lấy kết quả tốt nhất

---

## 📊 Thông số kỹ thuật

### YoloDetector
- **Model**: best.onnx (YOLOv8n, ~6MB)
- **Input**: 640×640 letterbox, RGB normalized [0,1]
- **Format**: NCHW float32
- **Confidence threshold**: 45%
- **NMS IoU threshold**: 45%
- **Threads**: 2 intra-op, 1 inter-op

### TemplateMatcher
- **Scale**: 0.25× (1/4 resolution của cả template và màn hình)
- **Algorithm**: Normalized Cross-Correlation (NCC) 3-channel RGB
- **Step**: tw/8, th/8 (coarse) + 1px refine
- **Threshold**: 0.75 NCC score
- **Storage**: Template lưu vào internal storage, reload khi khởi động service

### ScreenCaptureService
- **Scan interval**: 1000ms (1Hz)
- **Action delay**: 1000ms sau khi phát hiện
- **Frame**: Full resolution, RGBA8888, không resize
- **Inference**: Single background thread (tránh ANR)
- **Skip**: Nếu frame trước chưa xử lý xong, bỏ qua frame mới

---

## ⚠️ Lưu ý

### ONNX Runtime vs PT
- Android KHÔNG chạy được `.pt` trực tiếp (cần PyTorch Mobile riêng, nặng 100MB+)
- ONNX Runtime Android nhẹ hơn (~30MB), hỗ trợ tốt YOLOv8
- Script `export_model.py` tự động convert với opset 12 tương thích Android

### Nếu YOLO model không detect được
Nguyên nhân có thể:
- Chưa copy `best.onnx` vào assets
- Model được train với class names khác → xem log để biết class IDs
- Confidence quá thấp → sửa `CONF_THRESHOLD` trong `YoloDetector.java`

### Pin và hiệu suất
- 1Hz là tần số hợp lý (không quá hao pin)
- YOLO inference trên Snapdragon ~50-150ms/frame
- Template matching ~5-20ms/frame tùy kích thước màn hình

---

## 🐛 Khắc phục lỗi

| Lỗi | Nguyên nhân | Giải pháp |
|-----|-------------|-----------|
| `OrtException: No model` | Thiếu best.onnx | Export và copy vào assets |
| Click không hoạt động | A11y Service off | Settings → Accessibility → ON |
| Không phát hiện X | Model không nhận diện class này | Kiểm tra class names trong YoloDetector |
| Template không khớp | Threshold quá cao | Giảm `MATCH_THRESHOLD` xuống 0.65 |
