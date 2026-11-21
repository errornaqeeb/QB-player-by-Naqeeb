package com.naqeeb.qbplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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

    private volatile String cachedAudioTracksJson = "[]";
    private volatile String cachedSubtitleTracksJson = "[]";
    private volatile long currentPositionMs = 0;
    private volatile long durationMs = 0;
    private volatile boolean isPlayerPlaying = false;

    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    currentPositionMs = player.getCurrentPosition();
                    long d = player.getDuration();
                    if (d > 0 && d != C.TIME_UNSET) {
                        durationMs = d;
                    }
                    isPlayerPlaying = player.isPlaying();
                } catch (Exception ignored) {}
            }
            if (isPlayerPlaying) {
                mainHandler.postDelayed(this, 250);
            }
        }
    };

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
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
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

        // Initialize ExoPlayer with enhanced extractors and SeekableContentDataSource (MKV, WebM, MP4, TS, AVI, FLV, MOV, etc.)
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true);
        DataSource.Factory dataSourceFactory = () -> {
            DefaultDataSource defaultDataSource = new DefaultDataSource.Factory(MainActivity.this).createDataSource();
            return new SeekableContentDataSource(MainActivity.this, defaultDataSource);
        };
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekForwardIncrementMs(10000)
                .setSeekBackIncrementMs(10000)
                .build();
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                if (player != null) {
                    try {
                        long d = player.getDuration();
                        if (d > 0 && d != C.TIME_UNSET) {
                            durationMs = d;
                            final long finalDur = d;
                            mainHandler.post(() -> {
                                if (webView != null) {
                                    webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativeMetadataLoaded) PlayerCore.onNativeMetadataLoaded(" + finalDur + ");", null);
                                }
                            });
                        }
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                isPlayerPlaying = isPlaying;
                if (isPlaying) {
                    mainHandler.removeCallbacks(positionUpdater);
                    mainHandler.post(positionUpdater);
                }
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativePlayStateChanged) PlayerCore.onNativePlayStateChanged(" + isPlaying + ");", null);
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    if (player != null) {
                        final long duration = player.getDuration();
                        if (duration > 0 && duration != C.TIME_UNSET) {
                            durationMs = duration;
                        }
                        currentPositionMs = player.getCurrentPosition();
                        updateCachedTracks(player.getCurrentTracks());
                        final long finalDur = durationMs;
                        mainHandler.post(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativeMetadataLoaded) PlayerCore.onNativeMetadataLoaded(" + finalDur + ");", null);
                            }
                        });
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlayerPlaying = false;
                    mainHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativePlaybackEnded) PlayerCore.onNativePlaybackEnded();", null);
                        }
                    });
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                updateCachedTracks(tracks);
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
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativeTracksChanged) PlayerCore.onNativeTracksChanged();", null);
                    }
                });
            }
        });

        // Configure secure local asset loading
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // Configure WebView with transparent background for hardware video overlay
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_NONE, null);

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

    public static String getLanguageDisplayName(String code) {
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

    public static String getMimeTypeFromPath(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".ts") || lower.endsWith(".m2ts")) return "video/mp2t";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".3gp") || lower.endsWith(".3gpp")) return "video/3gpp";
        if (lower.endsWith(".wmv") || lower.endsWith(".asf")) return "video/x-ms-wmv";
        return null;
    }

    private void updateCachedTracks(Tracks tracks) {
        if (tracks == null) return;
        try {
            JSONArray audioArray = new JSONArray();
            JSONArray subArray = new JSONArray();
            List<Tracks.Group> groups = tracks.getGroups();
            int audioCounter = 0;
            int subCounter = 0;

            for (int g = 0; g < groups.size(); g++) {
                Tracks.Group group = groups.get(g);
                int groupType = group.getType();

                if (groupType == C.TRACK_TYPE_AUDIO) {
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
                            label = "Audio Track " + (audioCounter + 1) + channelStr;
                        }

                        obj.put("id", audioCounter++);
                        obj.put("name", label);
                        obj.put("language", displayLang);
                        obj.put("channels", format.channelCount);
                        obj.put("mimeType", format.sampleMimeType != null ? format.sampleMimeType : "audio");
                        obj.put("isSelected", group.isTrackSelected(t));
                        audioArray.put(obj);
                    }
                } else if (groupType == C.TRACK_TYPE_TEXT) {
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
                        subArray.put(obj);
                    }
                }
            }
            cachedAudioTracksJson = audioArray.toString();
            cachedSubtitleTracksJson = subArray.toString();
            Log.d(TAG, "Cached tracks: " + audioCounter + " audio, " + subCounter + " subtitles");
        } catch (Exception e) {
            Log.e(TAG, "Error updating cached tracks", e);
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true;
        }
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
                    currentPositionMs = 0;
                    cachedAudioTracksJson = "[]";
                    cachedSubtitleTracksJson = "[]";

                    Uri mediaUri = null;
                    long mediaId = -1;
                    if (mediaIdStr != null && !mediaIdStr.isEmpty()) {
                        try {
                            mediaId = Long.parseLong(mediaIdStr);
                            mediaUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                        } catch (Exception ignored) {}
                    }
                    if (mediaId == -1 && url != null && url.contains("id=")) {
                        try {
                            int idIdx = url.indexOf("id=");
                            String idStr = url.substring(idIdx + 3);
                            int ampIdx = idStr.indexOf('&');
                            if (ampIdx != -1) idStr = idStr.substring(0, ampIdx);
                            mediaId = Long.parseLong(idStr);
                            mediaUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                        } catch (Exception ignored) {}
                    }
                    if (mediaUri == null && url != null && !url.isEmpty()) {
                        mediaUri = Uri.parse(url);
                    }
                    if (mediaUri == null) return;

                    long fallbackDuration = 0;
                    String detectedMime = null;
                    String filePath = null;

                    if (mediaId != -1) {
                        try (Cursor c = getContentResolver().query(
                                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId),
                                new String[]{
                                        MediaStore.Video.Media.DURATION,
                                        MediaStore.Video.Media.DATA,
                                        MediaStore.Video.Media.DISPLAY_NAME,
                                        MediaStore.Video.Media.MIME_TYPE
                                },
                                null, null, null)) {
                            if (c != null && c.moveToFirst()) {
                                int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                                int dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
                                int nameCol = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                                int mimeCol = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE);
                                if (durCol != -1) fallbackDuration = c.getLong(durCol);
                                if (dataCol != -1) filePath = c.getString(dataCol);
                                if (nameCol != -1 && (filePath == null || filePath.isEmpty())) filePath = c.getString(nameCol);
                                if (mimeCol != -1) detectedMime = c.getString(mimeCol);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (filePath == null && url != null) {
                        filePath = url;
                    }

                    if (detectedMime == null || detectedMime.isEmpty() || "application/octet-stream".equals(detectedMime)) {
                        detectedMime = getMimeTypeFromPath(filePath);
                    }

                    // Prefer direct file URI for instant hardware seeking and Matroska/WebM Cues indexing
                    if (filePath != null) {
                        try {
                            File f = new File(filePath);
                            if (f.exists() && f.canRead()) {
                                mediaUri = Uri.fromFile(f);
                                Log.d(TAG, "Using direct file URI for hardware seeking: " + mediaUri);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (fallbackDuration <= 0) {
                        try {
                            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                            if (filePath != null && new File(filePath).canRead()) {
                                mmr.setDataSource(filePath);
                            } else {
                                mmr.setDataSource(MainActivity.this, mediaUri);
                            }
                            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            if (durStr != null) {
                                fallbackDuration = Long.parseLong(durStr);
                            }
                            mmr.release();
                        } catch (Exception ignored) {}
                    }

                    if (fallbackDuration > 0) {
                        durationMs = fallbackDuration;
                        final long initDur = fallbackDuration;
                        mainHandler.post(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if (window.PlayerCore && window.PlayerCore.onNativeMetadataLoaded) PlayerCore.onNativeMetadataLoaded(" + initDur + ");", null);
                            }
                        });
                    }

                    MediaItem.Builder builder = new MediaItem.Builder().setUri(mediaUri);
                    if (detectedMime != null) {
                        builder.setMimeType(detectedMime);
                    }
                    if (mediaId != -1) {
                        attachCompanionSubtitles(builder, mediaId);
                    }

                    if (playerView != null) {
                        playerView.setVisibility(View.VISIBLE);
                    }
                    if (player != null) {
                        player.setMediaItem(builder.build());
                        player.prepare();
                        player.play();
                        isPlayerPlaying = true;
                        mainHandler.removeCallbacks(positionUpdater);
                        mainHandler.post(positionUpdater);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in playMedia", e);
                }
            });
        }

        @JavascriptInterface
        public boolean togglePlay() {
            final boolean[] playing = new boolean[1];
            try {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        if (player != null) {
                            if (player.isPlaying()) {
                                player.pause();
                                isPlayerPlaying = false;
                                playing[0] = false;
                            } else {
                                player.play();
                                isPlayerPlaying = true;
                                playing[0] = true;
                                mainHandler.removeCallbacks(positionUpdater);
                                mainHandler.post(positionUpdater);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
            return playing[0];
        }

        @JavascriptInterface
        public void play() {
            mainHandler.post(() -> {
                if (player != null) {
                    player.play();
                    isPlayerPlaying = true;
                    mainHandler.removeCallbacks(positionUpdater);
                    mainHandler.post(positionUpdater);
                }
            });
        }

        @JavascriptInterface
        public void pause() {
            mainHandler.post(() -> {
                if (player != null) {
                    player.pause();
                    isPlayerPlaying = false;
                }
            });
        }

        @JavascriptInterface
        public void seekTo(final long positionMs) {
            currentPositionMs = Math.max(0, positionMs);
            mainHandler.post(() -> {
                if (player != null) {
                    long dur = player.getDuration();
                    if (dur <= 0 || dur == C.TIME_UNSET) {
                        dur = durationMs;
                    }
                    long target = Math.max(0, positionMs);
                    if (dur > 0) target = Math.min(target, dur);
                    currentPositionMs = target;
                    player.seekTo(target);
                }
            });
        }

        @JavascriptInterface
        public void seekDelta(final long deltaMs) {
            mainHandler.post(() -> {
                if (player != null) {
                    long cur = currentPositionMs > 0 ? currentPositionMs : player.getCurrentPosition();
                    if (cur < 0) cur = 0;
                    long dur = player.getDuration();
                    if (dur <= 0 || dur == C.TIME_UNSET) {
                        dur = durationMs;
                    }
                    long target = Math.max(0, cur + deltaMs);
                    if (dur > 0) target = Math.min(target, dur);
                    currentPositionMs = target;
                    player.seekTo(target);
                }
            });
        }

        @JavascriptInterface
        public long getCurrentPosition() {
            return currentPositionMs;
        }

        @JavascriptInterface
        public long getDuration() {
            if (durationMs > 0 && durationMs != C.TIME_UNSET) {
                return durationMs;
            }
            if (player != null) {
                try {
                    long d = player.getDuration();
                    if (d > 0 && d != C.TIME_UNSET) {
                        durationMs = d;
                        return d;
                    }
                } catch (Exception ignored) {}
            }
            return durationMs > 0 ? durationMs : 0;
        }

        @JavascriptInterface
        public boolean isPlaying() {
            return isPlayerPlaying;
        }

        @JavascriptInterface
        public void setPlaybackSpeed(float speed) {
            mainHandler.post(() -> {
                if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
            });
        }

        @JavascriptInterface
        public void setAspectRatio(String mode) {
            mainHandler.post(() -> {
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
            mainHandler.post(() -> {
                mainHandler.removeCallbacks(positionUpdater);
                isPlayerPlaying = false;
                currentPositionMs = 0;
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
            if ("[]".equals(cachedAudioTracksJson) && player != null) {
                try {
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    mainHandler.post(() -> {
                        try {
                            if (player != null) {
                                updateCachedTracks(player.getCurrentTracks());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
            return cachedAudioTracksJson;
        }

        @JavascriptInterface
        public void selectAudioTrack(final int groupIndex, final int trackIndex) {
            mainHandler.post(() -> {
                try {
                    if (player == null) return;
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
                        updateCachedTracks(player.getCurrentTracks());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error selecting audio track", e);
                }
            });
        }

        @JavascriptInterface
        public String getSubtitleTracks() {
            if ("[]".equals(cachedSubtitleTracksJson) && player != null) {
                try {
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    mainHandler.post(() -> {
                        try {
                            if (player != null) {
                                updateCachedTracks(player.getCurrentTracks());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
            return cachedSubtitleTracksJson;
        }

        @JavascriptInterface
        public void selectSubtitleTrack(final int groupIndex, final int trackIndex) {
            mainHandler.post(() -> {
                try {
                    selectSubtitleTrackInternal(groupIndex, trackIndex);
                    if (player != null) {
                        updateCachedTracks(player.getCurrentTracks());
                    }
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

            String selection;
            String[] selectionArgs;
            if (bucketId == null || bucketId.isEmpty() || "default".equals(bucketId)) {
                selection = MediaStore.Video.Media.BUCKET_ID + " IS NULL OR " + MediaStore.Video.Media.BUCKET_ID + " = ''";
                selectionArgs = null;
            } else {
                selection = MediaStore.Video.Media.BUCKET_ID + " = ?";
                selectionArgs = new String[]{bucketId};
            }

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
        public String getVideosInFolder(String bucketId) {
            return getFolderVideos(bucketId);
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
    // Seekable Content DataSource for Android ContentResolver (Fixes MKV/WebM Cues)
    // =========================================================================
    public static class SeekableContentDataSource extends BaseDataSource {
        private final Context context;
        private final DataSource fallbackDataSource;
        private AssetFileDescriptor afd;
        private FileInputStream inputStream;
        private FileChannel fileChannel;
        private Uri uri;
        private long bytesRemaining;
        private boolean isOpened = false;
        private boolean usingFallback = false;

        public SeekableContentDataSource(Context context, DataSource fallbackDataSource) {
            super(/* isNetwork= */ false);
            this.context = context.getApplicationContext();
            this.fallbackDataSource = fallbackDataSource;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            this.uri = dataSpec.uri;
            usingFallback = false;

            if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
                try {
                    transferInitializing(dataSpec);
                    afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
                    if (afd != null) {
                        FileDescriptor fd = afd.getFileDescriptor();
                        inputStream = new FileInputStream(fd);
                        fileChannel = inputStream.getChannel();

                        long startOffset = afd.getStartOffset();
                        long totalLength = afd.getLength();
                        if (totalLength == AssetFileDescriptor.UNKNOWN_LENGTH || totalLength <= 0) {
                            ParcelFileDescriptor pfd = afd.getParcelFileDescriptor();
                            if (pfd != null) {
                                long statSize = pfd.getStatSize();
                                if (statSize > 0) totalLength = statSize;
                            }
                        }
                        if ((totalLength == AssetFileDescriptor.UNKNOWN_LENGTH || totalLength <= 0) && fileChannel != null) {
                            try {
                                long chSize = fileChannel.size();
                                if (chSize > 0) totalLength = chSize;
                            } catch (Exception ignored) {}
                        }

                        long targetPosition = startOffset + dataSpec.position;
                        if (targetPosition > 0 && fileChannel != null) {
                            fileChannel.position(targetPosition);
                        }

                        if (dataSpec.length != C.LENGTH_UNSET) {
                            bytesRemaining = dataSpec.length;
                        } else if (totalLength > 0 && totalLength != AssetFileDescriptor.UNKNOWN_LENGTH) {
                            bytesRemaining = Math.max(0, totalLength - dataSpec.position);
                        } else {
                            bytesRemaining = C.LENGTH_UNSET;
                        }

                        isOpened = true;
                        transferStarted(dataSpec);
                        return bytesRemaining;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "SeekableContentDataSource fallback due to: " + e.getMessage());
                    cleanup();
                }
            }

            usingFallback = true;
            return fallbackDataSource.open(dataSpec);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            if (usingFallback) {
                return fallbackDataSource.read(buffer, offset, length);
            }
            if (length == 0) return 0;
            if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

            int bytesToRead = length;
            if (bytesRemaining != C.LENGTH_UNSET) {
                bytesToRead = (int) Math.min(bytesRemaining, length);
            }

            int read = inputStream != null ? inputStream.read(buffer, offset, bytesToRead) : -1;
            if (read == -1) {
                return C.RESULT_END_OF_INPUT;
            }

            if (bytesRemaining != C.LENGTH_UNSET) {
                bytesRemaining -= read;
            }
            bytesTransferred(read);
            return read;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return usingFallback ? fallbackDataSource.getUri() : uri;
        }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return usingFallback ? fallbackDataSource.getResponseHeaders() : super.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            if (usingFallback) {
                fallbackDataSource.close();
                usingFallback = false;
            }
            cleanup();
        }

        private void cleanup() {
            uri = null;
            if (isOpened) {
                isOpened = false;
                try {
                    if (inputStream != null) inputStream.close();
                } catch (Exception ignored) {}
                try {
                    if (afd != null) afd.close();
                } catch (Exception ignored) {}
                inputStream = null;
                fileChannel = null;
                afd = null;
                transferEnded();
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
                    if (mimeType == null || mimeType.isEmpty() || "application/octet-stream".equals(mimeType)) {
                        String dataPath = null;
                        try (Cursor c = getContentResolver().query(contentUri, new String[]{MediaStore.Video.Media.DATA}, null, null, null)) {
                            if (c != null && c.moveToFirst()) {
                                dataPath = c.getString(0);
                            }
                        } catch (Exception ignored) {}
                        mimeType = getMimeTypeFromPath(dataPath);
                    }
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
