package com.adskipper;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Claude Vision AI Client - fallback khi YOLO model không load được.
 * Dùng cloud API của Anthropic để phân tích ảnh màn hình.
 */
public class ClaudeVisionClient {

    private static final String TAG = "ClaudeVisionClient";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-5";
    private static String apiKey = "";

    public static void setApiKey(String key) { apiKey = key.trim(); }
    public static boolean hasApiKey() { return !apiKey.isEmpty(); }

    public static class AdDetectionResult {
        public boolean hasCloseButton;
        public float x;
        public float y;
        public float confidence;
        public String description;

        @Override
        public String toString() {
            if (!hasCloseButton) return "Không phát hiện quảng cáo";
            return String.format("Nút X tại (%.2f, %.2f) độ chính xác: %.0f%%", x, y, confidence * 100);
        }
    }

    public static AdDetectionResult analyzeScreenshot(Bitmap screenshot) {
        AdDetectionResult result = new AdDetectionResult();
        result.hasCloseButton = false;

        if (apiKey.isEmpty()) return result;

        try {
            String base64Image = bitmapToBase64(screenshot);
            String response = callClaudeAPI(base64Image, buildPrompt());
            result = parseAIResponse(response);
        } catch (Exception e) {
            Log.e(TAG, "Claude API error: " + e.getMessage());
        }
        return result;
    }

    private static String bitmapToBase64(Bitmap bitmap) {
        // Resize để tiết kiệm token (Claude không cần full res)
        Bitmap resized = resizeBitmap(bitmap, 1080);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        if (resized != bitmap) resized.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    private static Bitmap resizeBitmap(Bitmap original, int maxDim) {
        int w = original.getWidth(), h = original.getHeight();
        if (w <= maxDim && h <= maxDim) return original;
        float scale = Math.min((float) maxDim / w, (float) maxDim / h);
        return Bitmap.createScaledBitmap(original, Math.round(w * scale), Math.round(h * scale), true);
    }

    private static String buildPrompt() {
        return "Phân tích ảnh chụp màn hình Android. Tìm nút đóng quảng cáo (X, ×, Close, Skip, Đóng, Bỏ qua).\n" +
               "Chỉ trả lời JSON thuần túy:\n" +
               "{\"has_close_button\":true/false,\"x\":0.85,\"y\":0.12,\"confidence\":0.92," +
               "\"description\":\"mô tả ngắn\"}\n" +
               "x,y là tỉ lệ 0.0-1.0 từ góc trên-trái. Nếu không thấy: has_close_button=false";
    }

    private static String callClaudeAPI(String base64Image, String prompt) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("max_tokens", 200);

        JSONArray content = new JSONArray();
        JSONObject imgContent = new JSONObject();
        imgContent.put("type", "image");
        JSONObject src = new JSONObject();
        src.put("type", "base64");
        src.put("media_type", "image/jpeg");
        src.put("data", base64Image);
        imgContent.put("source", src);
        content.put(imgContent);

        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.put(textContent);

        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", content);

        JSONArray messages = new JSONArray();
        messages.put(msg);
        body.put("messages", messages);

        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush(); os.close();

        int code = conn.getResponseCode();
        java.io.InputStream is = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
        Scanner sc = new Scanner(is, "UTF-8");
        StringBuilder sb = new StringBuilder();
        while (sc.hasNextLine()) sb.append(sc.nextLine());
        sc.close();
        conn.disconnect();

        if (code != 200) throw new Exception("HTTP " + code + ": " + sb.toString().substring(0, Math.min(200, sb.length())));
        return sb.toString();
    }

    private static AdDetectionResult parseAIResponse(String apiResponse) {
        AdDetectionResult result = new AdDetectionResult();
        result.hasCloseButton = false;
        try {
            JSONObject resp = new JSONObject(apiResponse);
            String text = resp.getJSONArray("content").getJSONObject(0).getString("text").trim();
            int s = text.indexOf('{'), e = text.lastIndexOf('}');
            if (s >= 0 && e > s) text = text.substring(s, e + 1);
            JSONObject parsed = new JSONObject(text);
            result.hasCloseButton = parsed.optBoolean("has_close_button", false);
            if (result.hasCloseButton) {
                result.x = (float) parsed.optDouble("x", 0.5);
                result.y = (float) parsed.optDouble("y", 0.1);
                result.confidence = (float) parsed.optDouble("confidence", 0.5);
                result.description = parsed.optString("description", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return result;
    }
}
