package com.elitecaller.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity hosts a full-screen WebView pointed at the Elitecaller
 * website and implements every feature requested for a production-grade
 * WebView wrapper app: file upload/download, camera/microphone/geolocation
 * permission bridging, pull-to-refresh, a top loading progress bar, offline
 * detection with a retry screen, WebView history back navigation, double
 * back-press to exit, and full light/dark theme support.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TARGET_URL = "https://vishal-flask-app.onrender.com";
    private static final String TARGET_HOST = "vishal-flask-app.onrender.com";
    private static final long DOUBLE_BACK_INTERVAL_MS = 2000L;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private View offlineLayout;
    private MaterialButton retryButton;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private long lastBackPressTime = 0L;
    private boolean isCurrentlyOffline = false;
    private String pendingGeoOrigin = null;
    private GeolocationPermissions.Callback pendingGeoCallback = null;
    private PermissionRequest pendingPermissionRequest = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        progressBar = findViewById(R.id.progressBar);
        offlineLayout = findViewById(R.id.offlineLayout);
        retryButton = findViewById(R.id.retryButton);

        registerLaunchers();
        setupSwipeToRefresh();
        setupWebView();
        setupBackNavigation();

        retryButton.setOnClickListener(v -> attemptReload());

        if (isNetworkAvailable()) {
            webView.loadUrl(TARGET_URL);
        } else {
            showOfflineScreen();
        }

        registerNetworkCallback();
    }

    // ---------------------------------------------------------------------
    // Activity result launchers (file chooser + runtime permissions)
    // ---------------------------------------------------------------------

    private void registerLaunchers() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) {
                        return;
                    }
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
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
        );

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grantResults -> {
                    boolean allGranted = true;
                    for (Boolean granted : grantResults.values()) {
                        allGranted &= Boolean.TRUE.equals(granted);
                    }

                    if (pendingGeoCallback != null) {
                        boolean fineOrCoarse =
                                Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                        || Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                        pendingGeoCallback.invoke(pendingGeoOrigin, fineOrCoarse, false);
                        pendingGeoCallback = null;
                        pendingGeoOrigin = null;
                    } else if (pendingPermissionRequest != null) {
                        if (allGranted) {
                            pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                        } else {
                            pendingPermissionRequest.deny();
                            Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_SHORT).show();
                        }
                        pendingPermissionRequest = null;
                    }
                }
        );
    }

    // ---------------------------------------------------------------------
    // WebView configuration
    // ---------------------------------------------------------------------

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setGeolocationEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " ElitecallerApp/1.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Algorithmic (forced) dark mode support for the WebView content itself,
        // following the app's day/night theme.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            boolean isNightMode = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isNightMode);
        }

        webView.setWebViewClient(new ElitecallerWebViewClient());
        webView.setWebChromeClient(new ElitecallerWebChromeClient());
        webView.setDownloadListener(new ElitecallerDownloadListener());
    }

    private void setupSwipeToRefresh() {
        swipeRefreshLayout.setColorSchemeResources(
                R.color.brand_primary, R.color.brand_secondary, R.color.brand_accent);
        swipeRefreshLayout.setOnRefreshListener(this::attemptReload);
    }

    private void attemptReload() {
        if (isNetworkAvailable()) {
            hideOfflineScreen();
            webView.reload();
        } else {
            swipeRefreshLayout.setRefreshing(false);
            showOfflineScreen();
        }
    }

    // ---------------------------------------------------------------------
    // WebViewClient — page navigation, SSL, external links, offline errors
    // ---------------------------------------------------------------------

    private class ElitecallerWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();
            String scheme = uri.getScheme();

            if (host != null && host.equalsIgnoreCase(TARGET_HOST)) {
                // Keep same-site navigation inside the app.
                return false;
            }

            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                // External link: open in the user's default browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    return false;
                }
                return true;
            }

            // Non-http(s) links (tel:, mailto:, intent:, market:, etc.)
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // No app available to handle this scheme; ignore.
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            swipeRefreshLayout.setRefreshing(false);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                swipeRefreshLayout.setRefreshing(false);
                if (!isNetworkAvailable()) {
                    showOfflineScreen();
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // WebChromeClient — progress, file chooser, permissions, geolocation
    // ---------------------------------------------------------------------

    private class ElitecallerWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
                swipeRefreshLayout.setRefreshing(false);
            } else {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallbackParam,
                                          FileChooserParams fileChooserParams) {
            filePathCallback = filePathCallbackParam;
            try {
                Intent intent = fileChooserParams.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                fileChooserLauncher.launch(intent);
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            List<String> needed = new ArrayList<>();
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (needed.isEmpty()) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeoOrigin = origin;
            pendingGeoCallback = callback;
            permissionLauncher.launch(needed.toArray(new String[0]));
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> androidPermissions = new ArrayList<>();
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                    androidPermissions.add(Manifest.permission.CAMERA);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                }
            }

            if (androidPermissions.isEmpty()) {
                request.deny();
                return;
            }

            boolean allAlreadyGranted = true;
            for (String permission : androidPermissions) {
                if (!hasPermission(permission)) {
                    allAlreadyGranted = false;
                    break;
                }
            }

            if (allAlreadyGranted) {
                request.grant(request.getResources());
                return;
            }

            pendingPermissionRequest = request;
            permissionLauncher.launch(androidPermissions.toArray(new String[0]));
        }
    }

    // ---------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------

    private class ElitecallerDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) {
                    request.addRequestHeader("cookie", cookie);
                }
                request.setDescription(getString(R.string.downloading_file, guessFileName(url)));
                request.setTitle(guessFileName(url));
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.allowScanningByMediaScanner();
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessFileName(url));

                DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (downloadManager != null) {
                    downloadManager.enqueue(request);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.downloading_file, guessFileName(url)), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        }

        private String guessFileName(String url) {
            String name = android.webkit.URLUtil.guessFileName(url, null, null);
            return name != null ? name : "download_" + System.currentTimeMillis();
        }
    }

    // ---------------------------------------------------------------------
    // Back navigation: WebView history back, then double-back-to-exit
    // ---------------------------------------------------------------------

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < DOUBLE_BACK_INTERVAL_MS) {
                    finish();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(MainActivity.this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Connectivity detection + offline/retry screen
    // ---------------------------------------------------------------------

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (isCurrentlyOffline) {
                        hideOfflineScreen();
                        webView.reload();
                    }
                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isNetworkAvailable()) {
                            showOfflineScreen();
                        }
                    }, 500L);
                });
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void showOfflineScreen() {
        isCurrentlyOffline = true;
        offlineLayout.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        swipeRefreshLayout.setRefreshing(false);
    }

    private void hideOfflineScreen() {
        isCurrentlyOffline = false;
        offlineLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // Callback was already unregistered.
            }
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
