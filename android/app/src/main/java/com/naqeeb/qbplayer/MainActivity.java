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
import android.graphics.Color;
import android.media.AudioManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private PlayerView playerView;
    private ExoPlayer player;
    private LoudnessEnhancer loudnessEnhancer;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 2001;
    private WebViewAssetLoader assetLoader;
    private long backPressedTime = 0;
    private LocalVideoServer localServer;
    private int serverPort = 8998;
    private boolean userExplicitlyDisabledSubtitles = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // Start fallback streaming server if needed
        startLocalServer();

        setContentView(R.layout.activity_main);
        playerView = findViewById(R.id.player_view);
        webView = findViewById(R.id.web_view);

        // Keep PlayerView hidden until media playback begins to ensure smooth UI responsiveness
        playerView.setVisibility(View.GONE);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView != null) {
            subtitleView.setUserDefaultStyle();
            subtitleView.setUserDefaultTextSize();
            CaptionStyleCompat captionStyle = new CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    null
            );
            subtitleView.setStyle(captionStyle);
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f);
        }

        // Initialize ExoPlayer
        player = new ExoPlayer.Builder(this)
                .setSeekForwardIncrementMs(10000)
                .setSeekBackIncrementMs(10000)
                .build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativePlayStateChanged) PlayerCore.onNativePlayStateChanged(" + isPlaying + ");", null);
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    final long duration = player.getDuration();
                    mainHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativeMetadataLoaded) PlayerCore.onNativeMetadataLoaded(" + duration + ");", null);
                        }
                    });
                } else if (playbackState == Player.STATE_ENDED) {
                    mainHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativePlaybackEnded) PlayerCore.onNativePlaybackEnded();", null);
                        }
                    });
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                // Auto-select first subtitle track if available and not disabled by user
                if (!userExplicitlyDisabledSubtitles) {
                    boolean hasSelectedText = false;
                    Tracks.Group firstTextGroup = null;
                    int firstGroupIdx = -1;
                    List<Tracks.Group> groups = tracks.getGroups();
                    for (int i = 0; i < groups.size(); i++) {
                        Tracks.Group group = groups.get(i);
                        if (group.getType() == C.TRACK_TYPE_TEXT) {
                            if (firstTextGroup == null) {
                                firstTextGroup = group;
                                firstGroupIdx = i;
                            }
                            if (group.isSelected()) {
                                hasSelectedText = true;
                                break;
                            }
                        }
                    }
                    if (!hasSelectedText && firstTextGroup != null) {
                        final int gIdx = firstGroupIdx;
                        mainHandler.post(() -> selectSubtitleTrackInternal(gIdx, 0));
                    }
                }
            }
        });

        // Configure secure local asset loading
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // Configure WebView with transparent background for hardware video overlay
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

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

        // Add Android native bridge for MX Player features
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

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*"); // Allows picking any subtitle or media file
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select File"), FILE_CHOOSER_REQUEST_CODE);
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

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d(TAG, "[WebView JS] " + consoleMessage.message() + " -- From line "
                        + consoleMessage.lineNumber() + " of "
                        + consoleMessage.sourceId());
                return true;
            }
        });

        // Load the local self-contained web app
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        if (!hasStoragePermission()) {
            requestStoragePermissionsInternal();
        }

        // Handle back button press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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

    private void selectSubtitleTrackInternal(int groupIndex, int trackIndex) {
        if (groupIndex == -1) {
            userExplicitlyDisabledSubtitles = true;
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
            );
        } else {
            userExplicitlyDisabledSubtitles = false;
            Tracks tracks = player.getCurrentTracks();
            List<Tracks.Group> groups = tracks.getGroups();
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                Tracks.Group group = groups.get(groupIndex);
                TrackGroup trackGroup = group.getMediaTrackGroup();
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(trackGroup, trackIndex))
                                .build()
                );
            }
        }
    }

    private void attachCompanionSubtitles(MediaItem.Builder builder, long mediaId) {
        try {
            String videoPath = getVideoPathById(mediaId);
            if (videoPath == null || videoPath.isEmpty()) return;
            File videoFile = new File(videoPath);
            File parentDir = videoFile.getParentFile();
            if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) return;

            String baseName = videoFile.getName();
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            final String base = baseName.toLowerCase(Locale.US);

            File[] matches = parentDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase(Locale.US);
                return (lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") || lower.endsWith(".ssa"))
                        && lower.startsWith(base);
            });

            if (matches != null && matches.length > 0) {
                List<MediaItem.SubtitleConfiguration> subList = new ArrayList<>();
                for (File subFile : matches) {
                    String subName = subFile.getName().toLowerCase(Locale.US);
                    String mime = MimeTypes.APPLICATION_SUBRIP;
                    if (subName.endsWith(".vtt")) mime = MimeTypes.TEXT_VTT;
                    else if (subName.endsWith(".ass") || subName.endsWith(".ssa")) mime = MimeTypes.TEXT_SSA;

                    MediaItem.SubtitleConfiguration subConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                            .setMimeType(mime)
                            .setLabel(subFile.getName() + " [Folder]")
                            .setLanguage("und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build();
                    subList.add(subConfig);
                }
                builder.setSubtitleConfigurations(subList);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error attaching companion subtitles", e);
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

    private void startLocalServer() {
        new Thread(() -> {
            try {
                localServer = new LocalVideoServer(serverPort);
                localServer.start();
                serverPort = localServer.getPort();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start local streaming server", e);
            }
        }).start();
    }

    private void handleIntentData(Intent intent) {
        if (intent == null) return;
        Uri dataUri = intent.getData();
        if (dataUri != null) {
            String uriStr = dataUri.toString();
            String title = dataUri.getLastPathSegment();
            if (title == null) title = "Video";
            String jsCode = String.format(Locale.US,
                    "if (window.PlayerCore) { PlayerCore.loadSource('%s', '%s'); PlayerCore.play(); }",
                    escapeJs(uriStr), escapeJs(title));
            webView.evaluateJavascript(jsCode, null);
        }
    }

    private String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermissionsInternal() {
        if (hasStoragePermission()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            }, PERMISSION_REQUEST_CODE);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
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
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if (window.MediaBrowser) { window.MediaBrowser.loadFolders(); }", null);
                    }
                });
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
    protected void onPause() {
        super.onPause();
        if (player != null && !isInPictureInPictureMode()) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (loudnessEnhancer != null) {
            loudnessEnhancer.release();
            loudnessEnhancer = null;
        }
        if (localServer != null) {
            localServer.stop();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // =========================================================================
    // JavaScript Interface: Exposes native MX Player Folder & ExoPlayer features
    // =========================================================================
    public class AndroidMediaBridge {

        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }

        @JavascriptInterface
        public boolean isNativeExoPlayer() {
            return true;
        }

        @JavascriptInterface
        public void playMedia(final String url, final String mediaIdStr, final String title) {
            mainHandler.post(() -> {
                try {
                    userExplicitlyDisabledSubtitles = false;
                    Uri mediaUri = null;
                    long mediaId = -1;
                    if (mediaIdStr != null && !mediaIdStr.isEmpty()) {
                        try {
                            mediaId = Long.parseLong(mediaIdStr);
                            mediaUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                        } catch (Exception ignored) {}
                    }
                    if (mediaUri == null && url != null && !url.isEmpty()) {
                        mediaUri = Uri.parse(url);
                    }
                    if (mediaUri == null) return;

                    MediaItem.Builder builder = new MediaItem.Builder().setUri(mediaUri);
                    if (mediaId != -1) {
                        attachCompanionSubtitles(builder, mediaId);
                    }

                    playerView.setVisibility(View.VISIBLE);
                    player.setMediaItem(builder.build());
                    player.prepare();
                    player.play();
                } catch (Exception e) {
                    Log.e(TAG, "Error in playMedia", e);
                }
            });
        }

        @JavascriptInterface
        public boolean togglePlay() {
            final boolean[] playing = new boolean[1];
            runOnUiThread(() -> {
                if (player != null) {
                    if (player.isPlaying()) {
                        player.pause();
                        playing[0] = false;
                    } else {
                        player.play();
                        playing[0] = true;
                    }
                }
            });
            return playing[0];
        }

        @JavascriptInterface
        public void play() {
            runOnUiThread(() -> {
                if (player != null) player.play();
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(() -> {
                if (player != null) player.pause();
            });
        }

        @JavascriptInterface
        public void seekTo(long positionMs) {
            runOnUiThread(() -> {
                if (player != null) player.seekTo(Math.max(0, positionMs));
            });
        }

        @JavascriptInterface
        public void seekDelta(long deltaMs) {
            runOnUiThread(() -> {
                if (player != null) {
                    long cur = player.getCurrentPosition();
                    long dur = player.getDuration();
                    long target = Math.max(0, cur + deltaMs);
                    if (dur > 0) target = Math.min(target, dur);
                    player.seekTo(target);
                }
            });
        }

        @JavascriptInterface
        public long getCurrentPosition() {
            return player != null ? player.getCurrentPosition() : 0;
        }

        @JavascriptInterface
        public long getDuration() {
            return player != null ? player.getDuration() : 0;
        }

        @JavascriptInterface
        public boolean isPlaying() {
            return player != null && player.isPlaying();
        }

        @JavascriptInterface
        public void setPlaybackSpeed(float speed) {
            runOnUiThread(() -> {
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
            });
        }

        @JavascriptInterface
        public void setAspectRatio(String mode) {
            runOnUiThread(() -> {
                if (playerView != null) {
                    if ("fit".equals(mode)) {
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    } else if ("stretch".equals(mode)) {
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    } else if ("crop".equals(mode)) {
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    } else if ("16-9".equals(mode) || "4-3".equals(mode)) {
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                    } else {
                        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    }
                }
            });
        }

        @JavascriptInterface
        public void stopAndHidePlayer() {
            runOnUiThread(() -> {
                if (player != null) {
                    player.stop();
                    player.clearMediaItems();
                }
                if (playerView != null) {
                    playerView.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface
        public String getAudioTracks() {
            JSONArray result = new JSONArray();
            if (player == null) return result.toString();
            try {
                Tracks tracks = player.getCurrentTracks();
                List<Tracks.Group> groups = tracks.getGroups();
                int trackCounter = 0;
                for (int g = 0; g < groups.size(); g++) {
                    Tracks.Group group = groups.get(g);
                    if (group.getType() == C.TRACK_TYPE_AUDIO) {
                        for (int t = 0; t < group.length; t++) {
                            Format format = group.getTrackFormat(t);
                            JSONObject obj = new JSONObject();
                            obj.put("groupIndex", g);
                            obj.put("trackIndex", t);

                            String lang = format.language != null ? format.language : "und";
                            String displayLang = getLanguageDisplayName(lang);
                            String channelStr = format.channelCount > 2 ? " (5.1 Surround)" : " (Stereo)";
                            String label = format.label != null && !format.label.isEmpty()
                                    ? format.label
                                    : (displayLang + channelStr);
                            if ("und".equalsIgnoreCase(displayLang) || label.isEmpty()) {
                                label = "Audio Track " + (trackCounter + 1) + channelStr;
                            }

                            obj.put("id", trackCounter++);
                            obj.put("name", label);
                            obj.put("language", displayLang);
                            obj.put("channels", format.channelCount);
                            obj.put("mimeType", format.sampleMimeType != null ? format.sampleMimeType : "audio");
                            obj.put("isSelected", group.isTrackSelected(t));
                            result.put(obj);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting audio tracks from ExoPlayer", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public void selectAudioTrack(final int groupIndex, final int trackIndex) {
            mainHandler.post(() -> {
                try {
                    Tracks tracks = player.getCurrentTracks();
                    List<Tracks.Group> groups = tracks.getGroups();
                    if (groupIndex >= 0 && groupIndex < groups.size()) {
                        Tracks.Group group = groups.get(groupIndex);
                        TrackGroup trackGroup = group.getMediaTrackGroup();
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters()
                                        .buildUpon()
                                        .setOverrideForType(new TrackSelectionOverride(trackGroup, trackIndex))
                                        .build()
                        );
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error selecting audio track", e);
                }
            });
        }

        @JavascriptInterface
        public String getSubtitleTracks() {
            JSONArray result = new JSONArray();
            if (player == null) return result.toString();
            try {
                Tracks tracks = player.getCurrentTracks();
                List<Tracks.Group> groups = tracks.getGroups();
                int subCounter = 0;
                for (int g = 0; g < groups.size(); g++) {
                    Tracks.Group group = groups.get(g);
                    if (group.getType() == C.TRACK_TYPE_TEXT) {
                        for (int t = 0; t < group.length; t++) {
                            Format format = group.getTrackFormat(t);
                            JSONObject obj = new JSONObject();
                            obj.put("groupIndex", g);
                            obj.put("trackIndex", t);

                            String lang = format.language != null ? format.language : "und";
                            String displayLang = getLanguageDisplayName(lang);
                            String label = format.label != null && !format.label.isEmpty()
                                    ? format.label
                                    : (displayLang + " [Embedded]");
                            if ("und".equalsIgnoreCase(displayLang) || label.isEmpty()) {
                                label = "Subtitle " + (subCounter + 1);
                            }

                            obj.put("id", subCounter++);
                            obj.put("name", label);
                            obj.put("language", displayLang);
                            obj.put("isSelected", group.isTrackSelected(t));
                            result.put(obj);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting subtitle tracks from ExoPlayer", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public void selectSubtitleTrack(final int groupIndex, final int trackIndex) {
            mainHandler.post(() -> {
                try {
                    selectSubtitleTrackInternal(groupIndex, trackIndex);
                } catch (Exception e) {
                    Log.e(TAG, "Error selecting subtitle track", e);
                }
            });
        }

        @JavascriptInterface
        public void setAudioBoost(float boostLevel) {
            mainHandler.post(() -> {
                try {
                    if (boostLevel > 1.0f) {
                        if (loudnessEnhancer == null && player != null) {
                            int audioSessionId = player.getAudioSessionId();
                            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
                            }
                        }
                        if (loudnessEnhancer != null) {
                            loudnessEnhancer.setEnabled(true);
                            int gainmB = (int) ((boostLevel - 1.0f) * 2000);
                            loudnessEnhancer.setTargetGain(gainmB);
                        }
                    } else {
                        if (loudnessEnhancer != null) {
                            loudnessEnhancer.setEnabled(false);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting audio boost", e);
                }
            });
        }

        @JavascriptInterface
        public void enterPictureInPicture() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Rational aspect = new Rational(16, 9);
                        if (player != null && player.getVideoSize() != null && player.getVideoSize().width > 0) {
                            aspect = new Rational(player.getVideoSize().width, player.getVideoSize().height);
                        }
                        PictureInPictureParams params = new PictureInPictureParams.Builder()
                                .setAspectRatio(aspect)
                                .build();
                        enterPictureInPictureMode(params);
                    } catch (Exception e) {
                        Log.e(TAG, "Error entering PiP", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public String getMediaFolders() {
            JSONArray result = new JSONArray();
            if (!hasStoragePermission()) {
                mainHandler.post(() -> requestStoragePermissionsInternal());
                return result.toString();
            }

            Map<String, JSONObject> folderMap = new LinkedHashMap<>();
            Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DATA
            };

            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
                if (cursor != null) {
                    int bucketIdIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                    int bucketNameIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    int dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

                    while (cursor.moveToNext()) {
                        String bucketId = bucketIdIdx != -1 ? cursor.getString(bucketIdIdx) : "default";
                        String bucketName = bucketNameIdx != -1 ? cursor.getString(bucketNameIdx) : "Videos";
                        String dataPath = dataIdx != -1 ? cursor.getString(dataIdx) : "";

                        if (bucketName == null || bucketName.isEmpty()) {
                            if (dataPath != null && !dataPath.isEmpty()) {
                                File file = new File(dataPath);
                                File parent = file.getParentFile();
                                if (parent != null) bucketName = parent.getName();
                            }
                            if (bucketName == null || bucketName.isEmpty()) bucketName = "Videos";
                        }

                        if (!folderMap.containsKey(bucketId)) {
                            JSONObject folderObj = new JSONObject();
                            folderObj.put("id", bucketId);
                            folderObj.put("name", bucketName);
                            folderObj.put("count", 1);
                            folderObj.put("path", dataPath);
                            folderMap.put(bucketId, folderObj);
                        } else {
                            JSONObject existing = folderMap.get(bucketId);
                            if (existing != null) {
                                existing.put("count", existing.getInt("count") + 1);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying MediaStore folders", e);
            }

            for (JSONObject folder : folderMap.values()) {
                result.put(folder);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getFolderVideos(String bucketId) {
            JSONArray result = new JSONArray();
            if (!hasStoragePermission()) {
                mainHandler.post(() -> requestStoragePermissionsInternal());
                return result.toString();
            }

            Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATA
            };

            String selection = MediaStore.Video.Media.BUCKET_ID + " = ?";
            String[] selectionArgs = new String[]{bucketId};

            try (Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
                if (cursor != null) {
                    int idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                    int titleIdx = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                    int widthIdx = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                    int heightIdx = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);
                    int dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
                    int dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIdx);
                        String title = titleIdx != -1 ? cursor.getString(titleIdx) : "";
                        String name = nameIdx != -1 ? cursor.getString(nameIdx) : "";
                        long duration = durIdx != -1 ? cursor.getLong(durIdx) : 0;
                        long size = sizeIdx != -1 ? cursor.getLong(sizeIdx) : 0;
                        int width = widthIdx != -1 ? cursor.getInt(widthIdx) : 0;
                        int height = heightIdx != -1 ? cursor.getInt(heightIdx) : 0;
                        long dateModified = dateIdx != -1 ? cursor.getLong(dateIdx) : 0;
                        String dataPath = dataIdx != -1 ? cursor.getString(dataIdx) : "";

                        if (title == null || title.isEmpty()) {
                            title = (name != null && !name.isEmpty()) ? name : "Video " + id;
                        }

                        JSONObject videoObj = new JSONObject();
                        videoObj.put("id", id);
                        videoObj.put("title", title);
                        videoObj.put("name", name);
                        videoObj.put("duration", duration / 1000);
                        videoObj.put("durationStr", formatDuration(duration));
                        videoObj.put("size", size);
                        videoObj.put("sizeStr", formatSize(size));
                        videoObj.put("width", width);
                        videoObj.put("height", height);
                        videoObj.put("resolution", width > 0 && height > 0 ? (width + "x" + height) : "HD");
                        videoObj.put("dateModified", dateModified);
                        videoObj.put("path", dataPath);
                        videoObj.put("url", "http://127.0.0.1:" + serverPort + "/video?id=" + id);

                        result.put(videoObj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying folder videos", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getAllVideos() {
            JSONArray result = new JSONArray();
            if (!hasStoragePermission()) {
                mainHandler.post(() -> requestStoragePermissionsInternal());
                return result.toString();
            }

            Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATA
            };

            try (Cursor cursor = getContentResolver().query(uri, projection, null, null, MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
                if (cursor != null) {
                    int idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                    int titleIdx = cursor.getColumnIndex(MediaStore.Video.Media.TITLE);
                    int nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                    int widthIdx = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                    int heightIdx = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);
                    int dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIdx);
                        String title = titleIdx != -1 ? cursor.getString(titleIdx) : "";
                        String name = nameIdx != -1 ? cursor.getString(nameIdx) : "";
                        long duration = durIdx != -1 ? cursor.getLong(durIdx) : 0;
                        long size = sizeIdx != -1 ? cursor.getLong(sizeIdx) : 0;
                        int width = widthIdx != -1 ? cursor.getInt(widthIdx) : 0;
                        int height = heightIdx != -1 ? cursor.getInt(heightIdx) : 0;
                        String dataPath = dataIdx != -1 ? cursor.getString(dataIdx) : "";

                        if (title == null || title.isEmpty()) {
                            title = (name != null && !name.isEmpty()) ? name : "Video " + id;
                        }

                        JSONObject videoObj = new JSONObject();
                        videoObj.put("id", id);
                        videoObj.put("title", title);
                        videoObj.put("name", name);
                        videoObj.put("duration", duration / 1000);
                        videoObj.put("durationStr", formatDuration(duration));
                        videoObj.put("size", size);
                        videoObj.put("sizeStr", formatSize(size));
                        videoObj.put("width", width);
                        videoObj.put("height", height);
                        videoObj.put("resolution", width > 0 && height > 0 ? (width + "x" + height) : "HD");
                        videoObj.put("path", dataPath);
                        videoObj.put("url", "http://127.0.0.1:" + serverPort + "/video?id=" + id);

                        result.put(videoObj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying all videos", e);
            }
            return result.toString();
        }

        @JavascriptInterface
        public void setOrientation(String orientation) {
            runOnUiThread(() -> {
                if ("landscape".equalsIgnoreCase(orientation)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } else if ("portrait".equalsIgnoreCase(orientation)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                } else if ("sensor".equalsIgnoreCase(orientation)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                }
            });
        }

        @JavascriptInterface
        public void toggleOrientation() {
            runOnUiThread(() -> {
                int currentOrientation = getResources().getConfiguration().orientation;
                if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    Toast.makeText(MainActivity.this, "Landscape Mode", Toast.LENGTH_SHORT).show();
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    Toast.makeText(MainActivity.this, "Portrait Mode", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void setVolume(float volume) {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int targetVol = Math.round(volume * maxVol);
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public float getVolume() {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    return (float) cur / (float) max;
                }
            } catch (Exception ignored) {}
            return 1.0f;
        }

        @JavascriptInterface
        public void setBrightness(float brightness) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.01f, Math.min(1.0f, brightness));
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public float getBrightness() {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.screenBrightness >= 0) {
                return lp.screenBrightness;
            }
            return 1.0f;
        }

        @JavascriptInterface
        public void requestPermission() {
            mainHandler.post(() -> requestStoragePermissionsInternal());
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

    // =========================================================================
    // Local Lightweight HTTP Server with Range Support for Fallback Streaming
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
                    socket.setSoTimeout(30000);
                    java.io.InputStream in = socket.getInputStream();
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in));
                    String line = reader.readLine();
                    if (line == null) {
                        socket.close();
                        return;
                    }

                    String[] parts = line.split(" ");
                    if (parts.length < 2) {
                        socket.close();
                        return;
                    }

                    String method = parts[0];
                    String path = parts[1];

                    String rangeHeader = null;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.US).startsWith("range:")) {
                            rangeHeader = line.substring(6).trim();
                        }
                    }

                    if ("OPTIONS".equalsIgnoreCase(method)) {
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range, Content-Type, Accept-Ranges\r\n\r\n").getBytes());
                        out.flush();
                        socket.close();
                        return;
                    }

                    if (!path.startsWith("/video")) {
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                        out.flush();
                        socket.close();
                        return;
                    }

                    long mediaId = -1;
                    int idIndex = path.indexOf("id=");
                    if (idIndex != -1) {
                        String idStr = path.substring(idIndex + 3);
                        int ampIndex = idStr.indexOf('&');
                        if (ampIndex != -1) idStr = idStr.substring(0, ampIndex);
                        try {
                            mediaId = Long.parseLong(idStr);
                        } catch (Exception ignored) {}
                    }

                    if (mediaId == -1) {
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 400 Bad Request\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                        out.flush();
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
                            fis.getChannel().position(start);
                        }

                        byte[] buffer = new byte[128 * 1024];
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
                        socket.close();
                    } catch (Exception ignored2) {}
                }
            }).start();
        }
    }
}
