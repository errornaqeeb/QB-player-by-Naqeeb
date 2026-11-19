package com.naqeeb.qbplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QBPlayer";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 2001;
    private WebViewAssetLoader assetLoader;
    private long backPressedTime = 0;
    private LocalVideoServer localServer;
    private int serverPort = 8998;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during video playback
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Route hardware volume buttons to media stream
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Immersive sticky fullscreen
        hideSystemUI();

        // Start local video streaming server
        startLocalServer();

        webView = new WebView(this);
        setContentView(webView);

        // Configure secure local asset loading
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Add Android native bridge for MX Player MediaStore folders and rotation
        webView.addJavascriptInterface(new AndroidMediaBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handleIntentData(getIntent());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // Load the local self-contained web app
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        checkAndRequestPermissions();

        // Handle back button press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Check if webview can handle back (e.g. exit player to folder view)
                webView.evaluateJavascript("window.onNativeBackPressed ? window.onNativeBackPressed() : false", value -> {
                    if ("true".equals(value)) {
                        return; // Handled by JS
                    }
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        Toast.makeText(MainActivity.this, "Press BACK again to exit QB Player", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void startLocalServer() {
        try {
            localServer = new LocalVideoServer(serverPort);
            localServer.start();
            Log.d(TAG, "Local video streaming server started on port " + serverPort);
        } catch (Exception e) {
            try {
                // Fallback to dynamic port
                localServer = new LocalVideoServer(0);
                localServer.start();
                serverPort = localServer.getPort();
                Log.d(TAG, "Local video streaming server started on dynamic port " + serverPort);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to start local streaming server", ex);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentData(intent);
    }

    private void handleIntentData(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && webView != null) {
            String url = data.toString();
            String script = "if (window.PlaylistManager) { window.PlaylistManager.addUrl('" + url + "', 'Opened Video'); }";
            webView.evaluateJavascript(script, null);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Enter Picture-in-Picture when user presses Home or swipes up
        enterPiP();
    }

    private void enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                Rational aspectRatio = new Rational(16, 9);
                builder.setAspectRatio(aspectRatio);
                enterPictureInPictureMode(builder.build());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                }, PERMISSION_REQUEST_CODE);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                }, PERMISSION_REQUEST_CODE);
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = false;
            for (int res : grantResults) {
                if (res == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) {
                // Notify webview to reload media folders
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (window.MediaBrowser) { window.MediaBrowser.loadFolders(); }", null));
                }
            } else {
                Toast.makeText(this, "Storage permission is required to list your video folders", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (localServer != null) {
            localServer.stop();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // =========================================================================
    // JavaScript Interface: Exposes native MX Player Folder & Video features
    // =========================================================================
    public class AndroidMediaBridge {

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> checkAndRequestPermissions());
        }

        @JavascriptInterface
        public String getMediaFolders() {
            JSONArray foldersArray = new JSONArray();
            try {
                Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] projection = new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.DATA
                };
                String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

                Cursor cursor = getContentResolver().query(uri, projection, null, null, sortOrder);
                if (cursor != null) {
                    Map<String, JSONObject> folderMap = new LinkedHashMap<>();

                    int bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                    int bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    int dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

                    while (cursor.moveToNext()) {
                        String bucketId = bucketIdCol != -1 ? cursor.getString(bucketIdCol) : "default";
                        String bucketName = bucketNameCol != -1 ? cursor.getString(bucketNameCol) : "Videos";
                        String path = dataCol != -1 ? cursor.getString(dataCol) : "";

                        if (bucketName == null || bucketName.isEmpty()) {
                            bucketName = "Videos";
                        }

                        if (!folderMap.containsKey(bucketId)) {
                            JSONObject folderObj = new JSONObject();
                            folderObj.put("id", bucketId);
                            folderObj.put("name", bucketName);
                            folderObj.put("count", 1);
                            folderObj.put("path", path != null && path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
                            folderMap.put(bucketId, folderObj);
                        } else {
                            JSONObject existing = folderMap.get(bucketId);
                            existing.put("count", existing.getInt("count") + 1);
                        }
                    }
                    cursor.close();

                    for (JSONObject obj : folderMap.values()) {
                        foldersArray.put(obj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying media folders", e);
            }
            return foldersArray.toString();
        }

        @JavascriptInterface
        public String getVideosInFolder(String bucketId) {
            JSONArray videosArray = new JSONArray();
            try {
                Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] projection = new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.TITLE,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.DATE_MODIFIED
                };
                String selection = MediaStore.Video.Media.BUCKET_ID + " = ?";
                String[] selectionArgs = new String[]{bucketId};
                String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

                Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
                if (cursor != null) {
                    int idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                    int durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                    int widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                    int heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String title = nameCol != -1 ? cursor.getString(nameCol) : "";
                        if (title == null || title.isEmpty()) {
                            title = titleCol != -1 ? cursor.getString(titleCol) : "Video";
                        }
                        String path = dataCol != -1 ? cursor.getString(dataCol) : "";
                        long duration = durationCol != -1 ? cursor.getLong(durationCol) : 0;
                        long size = sizeCol != -1 ? cursor.getLong(sizeCol) : 0;
                        int width = widthCol != -1 ? cursor.getInt(widthCol) : 0;
                        int height = heightCol != -1 ? cursor.getInt(heightCol) : 0;

                        JSONObject videoObj = new JSONObject();
                        videoObj.put("id", id);
                        videoObj.put("title", title);
                        videoObj.put("path", path);
                        videoObj.put("duration", duration / 1000); // in seconds
                        videoObj.put("durationStr", formatDuration(duration));
                        videoObj.put("size", size);
                        videoObj.put("sizeStr", formatSize(size));
                        videoObj.put("width", width);
                        videoObj.put("height", height);
                        videoObj.put("resolution", height > 0 ? height + "p" : "HD");
                        videoObj.put("url", "http://127.0.0.1:" + serverPort + "/media?id=" + id);

                        videosArray.put(videoObj);
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying videos in folder " + bucketId, e);
            }
            return videosArray.toString();
        }

        @JavascriptInterface
        public String getAllVideos() {
            JSONArray videosArray = new JSONArray();
            try {
                Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] projection = new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.TITLE,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT
                };
                String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

                Cursor cursor = getContentResolver().query(uri, projection, null, null, sortOrder);
                if (cursor != null) {
                    int idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                    int durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                    int widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                    int heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String title = nameCol != -1 ? cursor.getString(nameCol) : "";
                        if (title == null || title.isEmpty()) {
                            title = titleCol != -1 ? cursor.getString(titleCol) : "Video";
                        }
                        String path = dataCol != -1 ? cursor.getString(dataCol) : "";
                        long duration = durationCol != -1 ? cursor.getLong(durationCol) : 0;
                        long size = sizeCol != -1 ? cursor.getLong(sizeCol) : 0;
                        int width = widthCol != -1 ? cursor.getInt(widthCol) : 0;
                        int height = heightCol != -1 ? cursor.getInt(heightCol) : 0;

                        JSONObject videoObj = new JSONObject();
                        videoObj.put("id", id);
                        videoObj.put("title", title);
                        videoObj.put("path", path);
                        videoObj.put("duration", duration / 1000);
                        videoObj.put("durationStr", formatDuration(duration));
                        videoObj.put("size", size);
                        videoObj.put("sizeStr", formatSize(size));
                        videoObj.put("width", width);
                        videoObj.put("height", height);
                        videoObj.put("resolution", height > 0 ? height + "p" : "HD");
                        videoObj.put("url", "http://127.0.0.1:" + serverPort + "/media?id=" + id);

                        videosArray.put(videoObj);
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying all videos", e);
            }
            return videosArray.toString();
        }

        @JavascriptInterface
        public void setOrientation(String mode) {
            runOnUiThread(() -> {
                if ("landscape".equalsIgnoreCase(mode)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } else if ("portrait".equalsIgnoreCase(mode)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                }
            });
        }

        @JavascriptInterface
        public void toggleOrientation() {
            runOnUiThread(() -> {
                int currentOrientation = getResources().getConfiguration().orientation;
                if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    Toast.makeText(MainActivity.this, "Portrait", Toast.LENGTH_SHORT).show();
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    Toast.makeText(MainActivity.this, "Landscape", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void setVolume(float volume) {
            runOnUiThread(() -> {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int targetVol = Math.round(Math.max(0f, Math.min(1.0f, volume)) * maxVol);
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public float getVolume() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    return (float) currentVol / (float) maxVol;
                }
            } catch (Exception ignored) {}
            return 1.0f;
        }

        @JavascriptInterface
        public String getAudioTracks(String mediaIdStr) {
            JSONArray result = new JSONArray();
            try {
                long mediaId = Long.parseLong(mediaIdStr);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(contentUri, "r")) {
                    if (pfd != null) {
                        MediaExtractor extractor = new MediaExtractor();
                        extractor.setDataSource(pfd.getFileDescriptor());
                        int trackCount = extractor.getTrackCount();
                        int audioIndex = 0;
                        for (int i = 0; i < trackCount; i++) {
                            MediaFormat format = extractor.getTrackFormat(i);
                            String mime = format.getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("audio/")) {
                                JSONObject trackObj = new JSONObject();
                                trackObj.put("id", audioIndex);
                                trackObj.put("extractorIndex", i);

                                String lang = format.containsKey(MediaFormat.KEY_LANGUAGE) ? format.getString(MediaFormat.KEY_LANGUAGE) : "und";
                                String title = format.containsKey("title") ? format.getString("title") : "";
                                int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

                                String displayLang = getLanguageDisplayName(lang);
                                String label = !title.isEmpty() ? title : (displayLang + (channels > 2 ? " (5.1 Surround)" : " (Stereo)"));
                                if (label.isEmpty() || label.startsWith("und")) {
                                    label = "Audio Track " + (audioIndex + 1);
                                }

                                trackObj.put("name", label);
                                trackObj.put("language", displayLang);
                                trackObj.put("channels", channels);
                                trackObj.put("mime", mime);
                                trackObj.put("isDefault", audioIndex == 0);
                                result.put(trackObj);
                                audioIndex++;
                            }
                        }
                        extractor.release();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting audio tracks", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getSubtitleTracks(String mediaIdStr) {
            JSONArray result = new JSONArray();
            try {
                long mediaId = Long.parseLong(mediaIdStr);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);

                // 1. Query embedded subtitle tracks using MediaExtractor
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(contentUri, "r")) {
                    if (pfd != null) {
                        MediaExtractor extractor = new MediaExtractor();
                        extractor.setDataSource(pfd.getFileDescriptor());
                        int trackCount = extractor.getTrackCount();
                        int subIndex = 0;
                        for (int i = 0; i < trackCount; i++) {
                            MediaFormat format = extractor.getTrackFormat(i);
                            String mime = format.getString(MediaFormat.KEY_MIME);
                            if (mime != null && (mime.startsWith("text/") || mime.contains("subrip") || mime.contains("vtt") || mime.contains("subtitle") || mime.contains("ssa") || mime.contains("ass"))) {
                                JSONObject subObj = new JSONObject();
                                subObj.put("id", subIndex);
                                subObj.put("extractorIndex", i);
                                subObj.put("type", "embedded");

                                String lang = format.containsKey(MediaFormat.KEY_LANGUAGE) ? format.getString(MediaFormat.KEY_LANGUAGE) : "und";
                                String title = format.containsKey("title") ? format.getString("title") : "";
                                String displayLang = getLanguageDisplayName(lang);
                                String label = !title.isEmpty() ? title : (displayLang + " [Embedded]");

                                subObj.put("name", label);
                                subObj.put("language", displayLang);
                                subObj.put("mime", mime);
                                result.put(subObj);
                                subIndex++;
                            }
                        }
                        extractor.release();
                    }
                }

                // 2. Query companion subtitle files (.srt, .vtt) in same folder
                String videoPath = getVideoPathById(mediaId);
                if (videoPath != null && !videoPath.isEmpty()) {
                    File videoFile = new File(videoPath);
                    File parentDir = videoFile.getParentFile();
                    if (parentDir != null && parentDir.exists() && parentDir.isDirectory()) {
                        String baseName = videoFile.getName();
                        int dot = baseName.lastIndexOf('.');
                        if (dot > 0) baseName = baseName.substring(0, dot);
                        final String base = baseName.toLowerCase(Locale.US);

                        File[] matches = parentDir.listFiles((dir, name) -> {
                            String lower = name.toLowerCase(Locale.US);
                            return (lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") || lower.endsWith(".ssa"))
                                    && lower.startsWith(base);
                        });

                        if (matches != null) {
                            for (File subFile : matches) {
                                JSONObject extObj = new JSONObject();
                                extObj.put("id", result.length());
                                extObj.put("extractorIndex", -1);
                                extObj.put("type", "external_file");
                                extObj.put("path", subFile.getAbsolutePath());
                                extObj.put("name", subFile.getName() + " [Folder]");
                                extObj.put("language", "Local");
                                result.put(extObj);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting subtitle tracks", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getSubtitleContent(String mediaIdStr, int extractorIndex, String filePath) {
            // Read from external companion file
            if (filePath != null && !filePath.isEmpty()) {
                try {
                    File file = new File(filePath);
                    if (file.exists()) {
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] data = new byte[(int) file.length()];
                            fis.read(data);
                            return new String(data, StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error reading companion subtitle file", e);
                }
            }

            // Extract from embedded track
            try {
                long mediaId = Long.parseLong(mediaIdStr);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(contentUri, "r")) {
                    if (pfd != null) {
                        MediaExtractor extractor = new MediaExtractor();
                        extractor.setDataSource(pfd.getFileDescriptor());
                        extractor.selectTrack(extractorIndex);

                        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                        StringBuilder srtBuilder = new StringBuilder();
                        int cueIndex = 1;

                        while (true) {
                            buffer.clear();
                            int sampleSize = extractor.readSampleData(buffer, 0);
                            if (sampleSize <= 0) break;

                            long timeUs = extractor.getSampleTime();
                            byte[] bytes = new byte[sampleSize];
                            buffer.get(bytes);

                            String text = new String(bytes, StandardCharsets.UTF_8).trim();
                            text = text.replace("\u0000", "").trim();

                            if (!text.isEmpty()) {
                                long startTimeMs = timeUs / 1000;
                                long endTimeMs = startTimeMs + 3500; // default 3.5s duration

                                srtBuilder.append(cueIndex++).append("\n");
                                srtBuilder.append(formatSrtTime(startTimeMs)).append(" --> ").append(formatSrtTime(endTimeMs)).append("\n");
                                srtBuilder.append(text).append("\n\n");
                            }

                            if (!extractor.advance()) break;
                        }

                        extractor.release();
                        return srtBuilder.toString();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting embedded subtitles", e);
            }
            return "";
        }

        @JavascriptInterface
        public String prepareAudioTrack(String mediaIdStr, int extractorIndex) {
            try {
                long mediaId = Long.parseLong(mediaIdStr);
                return extractAudioTrack(mediaId, extractorIndex);
            } catch (Exception e) {
                return "";
            }
        }

        private String getLanguageDisplayName(String code) {
            if (code == null || code.isEmpty() || "und".equalsIgnoreCase(code)) return "Default";
            switch (code.toLowerCase(Locale.US)) {
                case "hin": return "Hindi";
                case "eng": return "English";
                case "spa": return "Spanish";
                case "fra": case "fre": return "French";
                case "deu": case "ger": return "German";
                case "ita": return "Italian";
                case "jpn": return "Japanese";
                case "kor": return "Korean";
                case "zho": case "chi": return "Chinese";
                case "rus": return "Russian";
                case "ara": return "Arabic";
                case "tam": return "Tamil";
                case "tel": return "Telugu";
                case "urd": return "Urdu";
                case "ben": return "Bengali";
                case "pan": return "Punjabi";
                default:
                    try {
                        Locale loc = new Locale(code);
                        String name = loc.getDisplayLanguage(Locale.US);
                        if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(code)) {
                            return name;
                        }
                    } catch (Exception ignored) {}
                    return code.toUpperCase(Locale.US);
            }
        }

        private String getVideoPathById(long mediaId) {
            String path = null;
            try {
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.Video.Media.DATA}, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                        if (idx != -1) path = cursor.getString(idx);
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {}
            return path;
        }

        private String formatSrtTime(long millis) {
            long hrs = millis / 3600000;
            long mins = (millis % 3600000) / 60000;
            long secs = (millis % 60000) / 1000;
            long ms = millis % 1000;
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", hrs, mins, secs, ms);
        }

        private String formatDuration(long millis) {
            long totalSeconds = millis / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            if (hours > 0) {
                return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
            } else {
                return String.format(Locale.US, "%02d:%02d", minutes, seconds);
            }
        }

        private String formatSize(long bytes) {
            if (bytes <= 0) return "0 MB";
            if (bytes < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            } else if (bytes < 1024 * 1024 * 1024) {
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            } else {
                return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
            }
        }
    }

    public String extractAudioTrack(long mediaId, int extractorIndex) {
        File outFile = new File(getCacheDir(), "audio_" + mediaId + "_" + extractorIndex + ".m4a");
        if (outFile.exists() && outFile.length() > 1024) {
            return "http://127.0.0.1:" + serverPort + "/audio?id=" + mediaId + "&track=" + extractorIndex;
        }

        try {
            Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(contentUri, "r")) {
                if (pfd == null) return "";
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(pfd.getFileDescriptor());
                extractor.selectTrack(extractorIndex);
                MediaFormat format = extractor.getTrackFormat(extractorIndex);

                File tempFile = new File(getCacheDir(), "temp_audio_" + mediaId + "_" + extractorIndex + ".m4a");
                if (tempFile.exists()) tempFile.delete();

                MediaMuxer muxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int muxerTrackIndex = muxer.addTrack(format);
                muxer.start();

                ByteBuffer buffer = ByteBuffer.allocate(128 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    info.offset = 0;
                    info.size = sampleSize;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxerTrackIndex, buffer, info);
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                extractor.release();

                tempFile.renameTo(outFile);
                return "http://127.0.0.1:" + serverPort + "/audio?id=" + mediaId + "&track=" + extractorIndex;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting audio track " + extractorIndex, e);
            return "";
        }
    }

    // =========================================================================
    // Local Lightweight HTTP Server with Range Support for 60fps Video Streaming
    // =========================================================================
    private class LocalVideoServer {
        private final int port;
        private ServerSocket serverSocket;
        private Thread serverThread;
        private volatile boolean running = false;

        public LocalVideoServer(int port) {
            this.port = port;
        }

        public int getPort() {
            return serverSocket != null ? serverSocket.getLocalPort() : port;
        }

        public void start() throws Exception {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            running = true;
            serverThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        handleClient(socket);
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
        }

        public void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
        }

        private void handleClient(Socket socket) {
            new Thread(() -> {
                ParcelFileDescriptor pfd = null;
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line = reader.readLine();
                    if (line == null) {
                        socket.close();
                        return;
                    }

                    String[] requestParts = line.split(" ");
                    if (requestParts.length < 2) {
                        socket.close();
                        return;
                    }

                    String method = requestParts[0];
                    String path = requestParts[1];
                    String rangeHeader = null;

                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.US).startsWith("range:")) {
                            rangeHeader = line.substring(6).trim();
                        }
                    }

                    // Handle CORS Preflight OPTIONS
                    if ("OPTIONS".equalsIgnoreCase(method)) {
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 204 No Content\r\n"
                                + "Access-Control-Allow-Origin: *\r\n"
                                + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                                + "Access-Control-Allow-Headers: Range, Content-Type, Accept-Ranges\r\n"
                                + "Access-Control-Max-Age: 86400\r\n"
                                + "Connection: close\r\n\r\n").getBytes());
                        out.flush();
                        socket.close();
                        return;
                    }

                    // Extract media id: /media?id=12345
                    long mediaId = -1;
                    if (path.contains("id=")) {
                        String idStr = path.substring(path.indexOf("id=") + 3);
                        int amp = idStr.indexOf('&');
                        if (amp != -1) idStr = idStr.substring(0, amp);
                        try {
                            mediaId = Long.parseLong(idStr);
                        } catch (Exception ignored) {}
                    }

                    if (mediaId == -1) {
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                        out.flush();
                        socket.close();
                        return;
                    }

                    if (path.startsWith("/audio")) {
                        int trackIndex = 0;
                        if (path.contains("track=")) {
                            String trackStr = path.substring(path.indexOf("track=") + 6);
                            int amp = trackStr.indexOf('&');
                            if (amp != -1) trackStr = trackStr.substring(0, amp);
                            try { trackIndex = Integer.parseInt(trackStr); } catch (Exception ignored) {}
                        }

                        File audioFile = new File(getCacheDir(), "audio_" + mediaId + "_" + trackIndex + ".m4a");
                        if (!audioFile.exists() || audioFile.length() < 1024) {
                            extractAudioTrack(mediaId, trackIndex);
                        }

                        if (!audioFile.exists() || audioFile.length() == 0) {
                            OutputStream out = socket.getOutputStream();
                            out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                            out.flush();
                            socket.close();
                            return;
                        }

                        long fileLength = audioFile.length();
                        long start = 0;
                        long end = fileLength - 1;

                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            Pattern pattern = Pattern.compile("bytes=(\\d+)-(\\d*)");
                            Matcher matcher = pattern.matcher(rangeHeader);
                            if (matcher.find()) {
                                start = Long.parseLong(matcher.group(1));
                                String endStr = matcher.group(2);
                                if (endStr != null && !endStr.isEmpty()) {
                                    end = Long.parseLong(endStr);
                                }
                            }
                        }

                        if (start >= fileLength) {
                            OutputStream out = socket.getOutputStream();
                            String response = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + fileLength + "\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
                            out.write(response.getBytes());
                            out.flush();
                            socket.close();
                            return;
                        }

                        long contentLength = (end - start) + 1;
                        OutputStream out = socket.getOutputStream();

                        StringBuilder header = new StringBuilder();
                        if (rangeHeader != null) {
                            header.append("HTTP/1.1 206 Partial Content\r\n");
                            header.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n");
                        } else {
                            header.append("HTTP/1.1 200 OK\r\n");
                        }
                        header.append("Content-Type: audio/mp4\r\n");
                        header.append("Content-Length: ").append(contentLength).append("\r\n");
                        header.append("Accept-Ranges: bytes\r\n");
                        header.append("Access-Control-Allow-Origin: *\r\n");
                        header.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
                        header.append("Access-Control-Allow-Headers: Range, Content-Type, Accept-Ranges\r\n");
                        header.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
                        header.append("Connection: close\r\n\r\n");

                        out.write(header.toString().getBytes());

                        if ("HEAD".equalsIgnoreCase(method)) {
                            out.flush();
                            socket.close();
                            return;
                        }

                        try (FileInputStream fis = new FileInputStream(audioFile)) {
                            if (start > 0) {
                                fis.getChannel().position(start);
                            }

                            byte[] buffer = new byte[64 * 1024];
                            long bytesRemaining = contentLength;

                            while (bytesRemaining > 0) {
                                int toRead = (int) Math.min(buffer.length, bytesRemaining);
                                int read = fis.read(buffer, 0, toRead);
                                if (read == -1) break;
                                out.write(buffer, 0, read);
                                bytesRemaining -= read;
                            }

                            out.flush();
                        }
                        socket.close();
                        return;
                    }

                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                    pfd = getContentResolver().openFileDescriptor(contentUri, "r");
                    if (pfd == null) {
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                        out.flush();
                        socket.close();
                        return;
                    }

                    String mimeType = getContentResolver().getType(contentUri);
                    if (mimeType == null || mimeType.isEmpty()) {
                        mimeType = "video/mp4";
                    }

                    long fileLength = pfd.getStatSize();
                    long start = 0;
                    long end = fileLength - 1;

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        Pattern pattern = Pattern.compile("bytes=(\\d+)-(\\d*)");
                        Matcher matcher = pattern.matcher(rangeHeader);
                        if (matcher.find()) {
                            start = Long.parseLong(matcher.group(1));
                            String endStr = matcher.group(2);
                            if (endStr != null && !endStr.isEmpty()) {
                                end = Long.parseLong(endStr);
                            }
                        }
                    }

                    if (start >= fileLength) {
                        OutputStream out = socket.getOutputStream();
                        String response = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + fileLength + "\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
                        out.write(response.getBytes());
                        out.flush();
                        pfd.close();
                        socket.close();
                        return;
                    }

                    long contentLength = (end - start) + 1;
                    OutputStream out = socket.getOutputStream();

                    StringBuilder header = new StringBuilder();
                    if (rangeHeader != null) {
                        header.append("HTTP/1.1 206 Partial Content\r\n");
                        header.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n");
                    } else {
                        header.append("HTTP/1.1 200 OK\r\n");
                    }
                    header.append("Content-Type: ").append(mimeType).append("\r\n");
                    header.append("Content-Length: ").append(contentLength).append("\r\n");
                    header.append("Accept-Ranges: bytes\r\n");
                    header.append("Access-Control-Allow-Origin: *\r\n");
                    header.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
                    header.append("Access-Control-Allow-Headers: Range, Content-Type, Accept-Ranges\r\n");
                    header.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
                    header.append("Connection: close\r\n\r\n");

                    out.write(header.toString().getBytes());

                    if ("HEAD".equalsIgnoreCase(method)) {
                        out.flush();
                        pfd.close();
                        socket.close();
                        return;
                    }

                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                        if (start > 0) {
                            try {
                                fis.getChannel().position(start);
                            } catch (Exception e) {
                                long skipped = 0;
                                while (skipped < start) {
                                    long s = fis.skip(start - skipped);
                                    if (s <= 0) break;
                                    skipped += s;
                                }
                            }
                        }

                        byte[] buffer = new byte[64 * 1024];
                        long bytesRemaining = contentLength;

                        while (bytesRemaining > 0) {
                            int toRead = (int) Math.min(buffer.length, bytesRemaining);
                            int read = fis.read(buffer, 0, toRead);
                            if (read == -1) break;
                            out.write(buffer, 0, read);
                            bytesRemaining -= read;
                        }

                        out.flush();
                    }
                    pfd.close();
                    socket.close();
                } catch (Exception ignored) {
                    try {
                        if (pfd != null) pfd.close();
                        if (socket != null && !socket.isClosed()) socket.close();
                    } catch (Exception ignored2) {}
                }
            }).start();
        }
    }
}
