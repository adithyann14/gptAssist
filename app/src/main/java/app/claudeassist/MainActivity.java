/*
Copyright (c) 2017-2019 Divested Computing Group
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package app.claudeassist;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;
import android.webkit.ValueCallback;
import android.net.Uri;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.URLUtilCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private WebView chatWebView = null;
    private FrameLayout webContainer = null;
    private ImageButton restrictedButton = null;
    private WebSettings chatWebSettings = null;
    private final Context context = this;
    private SwipeTouchListener swipeTouchListener;
    private String TAG ="claudeAssist";
    private final String urlToLoad = "https://claude.ai/";

    private AccountStore accounts;
    // true if the system WebView supports separate profiles (own cookies/storage per profile)
    private boolean multiProfile = false;
    // restriction state of the active account; read from WebView threads
    private volatile boolean restricted = false;

    // Hosts whose cookies are copied when switching accounts on WebViews without profile support
    private static final String[] COOKIE_SNAPSHOT_URLS = {
            "https://claude.ai/", "https://claude.com/", "https://anthropic.com/"
    };

    private static final ArrayList<String> allowedDomains = new ArrayList<String>();

    private SharedPreferences settings;
    private boolean keepAlive = true;   // keep process alive in background (default on)

    private ValueCallback<Uri[]> mUploadMessage;
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final static int BACKUP_REQUEST_CODE = 2;
    private final static int RESTORE_REQUEST_CODE = 3;
    private final static int STORAGE_PERMISSION_CODE = 101;

    private char[] pendingBackupPassphrase = null;  // chosen before the save dialog opens
    private Runnable pendingDownload = null;         // retried after storage permission (Android 9 and older)
    private final Set<String> blobTokens = Collections.synchronizedSet(new HashSet<String>());
    /** File names the page asked for via <a download="...">, keyed by the link's URL (blob:, data: or http). */
    private final Map<String, String> downloadNames = Collections.synchronizedMap(new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 50; }
    });
    private static final String DOWNLOAD_NAME_HOOK =
            "(function(){if(window.__caHook)return;window.__caHook=1;var B=window.ClaudeAssistBridge;"
          + "if(!B||!B.name)return;"
          + "function rec(a){try{if(a&&a.hasAttribute&&a.hasAttribute('download')){var n=a.getAttribute('download');"
          + "if(n&&a.href)B.name(a.href,n);}}catch(e){}}"
          + "var oc=HTMLAnchorElement.prototype.click;"
          + "HTMLAnchorElement.prototype.click=function(){rec(this);return oc.apply(this,arguments);};"
          + "var od=EventTarget.prototype.dispatchEvent;"
          + "EventTarget.prototype.dispatchEvent=function(e){if(e&&e.type==='click'&&this instanceof HTMLAnchorElement)rec(this);return od.apply(this,arguments);};"
          + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;rec(a);},true);"
          + "})();";
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void onPause() {
        if (chatWebView != null) {
            cookieManager().flush();
            if (!multiProfile) snapshotCookies(accounts.currentId());
        }
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accounts = new AccountStore(this);
        settings = getSharedPreferences("claude_settings", MODE_PRIVATE);
        keepAlive = settings.getBoolean("keep_alive", true);
        multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);

        webContainer = findViewById(R.id.webContainer);
        restrictedButton = findViewById(R.id.restricted);

        //Restrict what gets loaded
        initURLs();

        // Tap: toggle restricted / unrestricted for the active account
        restrictedButton.setOnClickListener(v -> {
            restricted = !restricted;
            accounts.setRestricted(accounts.currentId(), restricted);
            updateButton();
            Toast.makeText(context, restricted ? R.string.urls_restricted : R.string.all_urls, Toast.LENGTH_SHORT).show();
            chatWebView.reload();
        });
        // Long press: switch / add / manage accounts
        restrictedButton.setOnLongClickListener(v -> {
            showAccountDialog();
            return true;
        });

        createWebView();
        Uri start = startUriFromIntent(getIntent());
        chatWebView.loadUrl(start != null ? start.toString() : urlToLoad);

        updateKeepAliveService();
        if (keepAlive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 124); // only for the notification
        }

        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
    }

    /** Starts/stops the foreground service that keeps the WebView alive in the background. */
    private void updateKeepAliveService() {
        Intent service = new Intent(this, KeepAliveService.class);
        if (keepAlive) {
            try {
                startForegroundService(service);
            } catch (Exception e) {
                Log.w(TAG, "Could not start keep-alive service", e);
            }
        } else {
            stopService(service);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = startUriFromIntent(intent);
        if (uri != null && chatWebView != null) chatWebView.loadUrl(uri.toString());
    }

    /** Links such as the e-mail sign-in link open inside the active account. */
    private Uri startUriFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        if (data == null || !"https".equals(data.getScheme()) || data.getHost() == null) return null;
        String host = data.getHost();
        if (host.equals("claude.ai") || host.endsWith(".claude.ai")) return data;
        return null;
    }

    // ------------------------------------------------------------------ accounts

    /** Cookie manager that belongs to the active account's profile (or the global one without profiles). */
    private CookieManager cookieManager() {
        if (multiProfile) {
            try {
                return profileFor(accounts.current()).getCookieManager();
            } catch (Exception e) {
                Log.w(TAG, "No profile cookie manager", e);
            }
        }
        return CookieManager.getInstance();
    }

    private Profile profileFor(AccountStore.Account account) {
        return ProfileStore.getInstance().getOrCreateProfile(account.profileName());
    }

    private void updateButton() {
        restrictedButton.setImageDrawable(getDrawable(restricted ? R.drawable.restricted : R.drawable.unrestricted));
        restrictedButton.setContentDescription(accounts.current().name);
    }

    /** Creates a fresh WebView bound to the active account's profile. */
    private void createWebView() {
        AccountStore.Account account = accounts.current();
        restricted = account.restricted;

        WebView webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (multiProfile) {
            try {
                profileFor(account); // make sure it exists
                WebViewCompat.setProfile(webView, account.profileName()); // must happen before the first load
            } catch (Exception e) {
                Log.e(TAG, "Could not set profile " + account.profileName(), e);
            }
        }
        chatWebView = webView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // keep the renderer at high priority even when the app is not visible
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        webContainer.addView(webView, 0);
        registerForContextMenu(webView);

        //Set cookie options
        CookieManager cm = cookieManager();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);

        webView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.")) {  //this error occurs when user copies to clipboard
                    Toast.makeText(context, R.string.error_copy,Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
                    }
                }
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }

                mUploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (request.getResources().length > 0 && request.getResources()[0].equals(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            // Request the permission from the user
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123);
                            // A more robust solution would involve storing 'request' and calling request.grant() in onRequestPermissionsResult.
                        }
                    } else {
                        request.deny();
                    }
                } else {
                    request.grant(request.getResources()); // For older Android versions, permissions are granted at install time
                }
            }

        });  //needed to share link

        webView.setWebViewClient(new WebViewClient() {
            //Keep these in sync!
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (!restricted) return null;

                if (request.getUrl().toString().equals("about:blank") || isAllowedBlob(request.getUrl().toString())) {
                    return null;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs that aren't HTTPS
                }
                String host = request.getUrl().getHost();
                if (!isAllowedHost(host)) {
                    Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + host);
                    if (request.isForMainFrame()) notifyIfSocialLogin(view, host);
                    if (request.getUrl().toString().contains("gravatar.com/avatar/")) {
                        AssetManager assetManager = getAssets();
                        try {
                            InputStream inputStream = assetManager.open("avatar.png");
                            return new WebResourceResponse("image/png","UTF-8",inputStream);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs not on ALLOWLIST
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!restricted) return false;

                if (request.getUrl().toString().equals("about:blank") || isAllowedBlob(request.getUrl().toString())) {
                    return false;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return true; //Deny URLs that aren't HTTPS
                }
                String host = request.getUrl().getHost();
                if (!isAllowedHost(host)) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + host);
                    notifyIfSocialLogin(view, host);
                    return true; //Deny URLs not on ALLOWLIST
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    String host = Uri.parse(url).getHost();
                    if (host != null && isAllowedHost(host)) view.evaluateJavascript(DOWNLOAD_NAME_HOOK, null);
                } catch (Exception e) {
                    Log.w(TAG, "download name hook failed", e);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "[onReceivedError] " + error.getErrorCode() + ": " + error.getDescription() + " @ " + request.getUrl());
                }
            }
        });

        webView.addJavascriptInterface(new DownloadBridge(), "ClaudeAssistBridge");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimetype));

        //Set more options
        chatWebSettings = webView.getSettings();
        //Enable some WebView features
        chatWebSettings.setJavaScriptEnabled(true);
        chatWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        chatWebSettings.setDomStorageEnabled(true);
        //Disable some WebView features
        chatWebSettings.setAllowContentAccess(false);
        chatWebSettings.setAllowFileAccess(false);
        chatWebSettings.setBuiltInZoomControls(false);
        chatWebSettings.setDatabaseEnabled(false);
        chatWebSettings.setDisplayZoomControls(false);
        chatWebSettings.setSaveFormData(false);
        chatWebSettings.setGeolocationEnabled(false);
        chatWebSettings.setUserAgentString(modUserAgent()); //Needed for login via Google

        swipeTouchListener = new SwipeTouchListener(context) {
            public void onSwipeBottom() {
                if (!chatWebView.canScrollVertically(0)) {
                    restrictedButton.setVisibility(View.VISIBLE);
                }
            }
            public void onSwipeTop(){
                restrictedButton.setVisibility(View.GONE);
            }
        };
        webView.setOnTouchListener(swipeTouchListener);

        updateButton();
    }

    private void destroyWebView() {
        if (chatWebView == null) return;
        cookieManager().flush();
        unregisterForContextMenu(chatWebView);
        webContainer.removeView(chatWebView);
        chatWebView.stopLoading();
        chatWebView.destroy();
        chatWebView = null;
    }

    private void switchAccount(int id) {
        if (id == accounts.currentId() || accounts.find(id) == null) return;

        if (!multiProfile) snapshotCookies(accounts.currentId());
        destroyWebView();
        accounts.setCurrent(id);

        if (multiProfile) {
            startAccountSession();
        } else {
            // One shared cookie jar: wipe it and load this account's saved cookies
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(ok -> runOnUiThread(() -> {
                restoreCookies(id);
                startAccountSession();
            }));
        }
    }

    private void startAccountSession() {
        createWebView();
        chatWebView.loadUrl(urlToLoad);
        Toast.makeText(context, getString(R.string.switched_to, accounts.current().name), Toast.LENGTH_SHORT).show();
    }

    private void snapshotCookies(int id) {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String url : COOKIE_SNAPSHOT_URLS) snapshot.put(url, cm.getCookie(url));
        accounts.saveCookies(id, snapshot);
    }

    private void restoreCookies(int id) {
        applyCookies(CookieManager.getInstance(), accounts.loadCookies(id));
    }

    /** Writes cookies (url -> "a=b; c=d") into a cookie manager. Only known Claude hosts are accepted. */
    private void applyCookies(CookieManager cm, Map<String, String> cookiesByUrl) {
        for (Map.Entry<String, String> e : cookiesByUrl.entrySet()) {
            boolean known = false;
            for (String url : COOKIE_SNAPSHOT_URLS) if (url.equals(e.getKey())) known = true;
            if (!known || e.getValue() == null) continue;
            String host = Uri.parse(e.getKey()).getHost();
            for (String pair : e.getValue().split(";")) {
                pair = pair.replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
                if (pair.indexOf('=') <= 0) continue;
                cm.setCookie(e.getKey(), pair + "; Domain=" + host + "; Path=/; Secure; Max-Age=31536000");
            }
        }
        cm.flush();
    }

    // ------------------------------------------------------------------ account dialogs

    private void showAccountDialog() {
        final List<AccountStore.Account> list = accounts.list();
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            AccountStore.Account a = list.get(i);
            labels[i] = (a.id == accounts.currentId() ? "✓  " : "      ") + a.name
                    + (a.restricted ? "  (" + getString(R.string.restricted_tag) + ")" : "");
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.accounts_title)
                .setItems(labels, (d, which) -> switchAccount(list.get(which).id))
                .setPositiveButton(R.string.add_account, (d, w) -> promptAddAccount())
                .setNeutralButton(R.string.more, (d, w) -> showMoreMenu())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // list view only exists once the dialog is shown
        dialog.getListView().setOnItemLongClickListener((parent, view, position, itemId) -> {
            dialog.dismiss();
            showAccountOptions(list.get(position));
            return true;
        });
    }

    private void showAccountOptions(final AccountStore.Account account) {
        String[] options = {
                getString(R.string.rename),
                getString(R.string.sign_out_clear),
                getString(R.string.delete_account)
        };
        new AlertDialog.Builder(this)
                .setTitle(account.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) promptRename(account);
                    else if (which == 1) clearAccountData(account);
                    else confirmDelete(account);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptText(int titleRes, String initial, int positiveRes, final TextCallback callback) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        FrameLayout holder = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(holder)
                .setPositiveButton(positiveRes, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) callback.onText(text);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface TextCallback { void onText(String text); }

    private void promptAddAccount() {
        String suggestion = getString(R.string.account_default_name, accounts.list().size() + 1);
        promptText(R.string.add_account, suggestion, R.string.add, text -> {
            AccountStore.Account created = accounts.add(text);
            switchAccount(created.id);
        });
    }

    private void promptRename(final AccountStore.Account account) {
        promptText(R.string.rename, account.name, android.R.string.ok, text -> {
            accounts.rename(account.id, text);
            if (account.id == accounts.currentId()) updateButton();
        });
    }

    /** Removes cookies and site storage of one account only. */
    private void clearAccountData(AccountStore.Account account) {
        if (account.id == accounts.currentId()) {
            resetChat();
        } else if (multiProfile) {
            try {
                Profile p = profileFor(account);
                p.getCookieManager().removeAllCookies(null);
                p.getCookieManager().flush();
                p.getWebStorage().deleteAllData();
            } catch (Exception e) {
                Log.w(TAG, "Could not clear profile", e);
            }
        } else {
            accounts.clearCookies(account.id);
        }
        Toast.makeText(context, getString(R.string.account_cleared, account.name), Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(final AccountStore.Account account) {
        if (accounts.list().size() <= 1) {
            Toast.makeText(context, R.string.cannot_delete_last, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_account)
                .setMessage(getString(R.string.delete_confirm, account.name))
                .setPositiveButton(R.string.delete_account, (d, w) -> deleteAccount(account))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(AccountStore.Account account) {
        if (accounts.list().size() <= 1) return;
        if (account.id == accounts.currentId()) {
            for (AccountStore.Account other : accounts.list()) {
                if (other.id != account.id) {
                    switchAccount(other.id);
                    break;
                }
            }
        }
        if (multiProfile) {
            try {
                Profile p = profileFor(account);
                p.getCookieManager().removeAllCookies(null);
                p.getCookieManager().flush();
                p.getWebStorage().deleteAllData();
            } catch (Exception e) {
                Log.w(TAG, "Could not clear profile", e);
            }
            try {
                ProfileStore.getInstance().deleteProfile(account.profileName());
            } catch (Exception e) {
                Log.w(TAG, "Profile still in use, data was cleared instead", e);
            }
        }
        accounts.remove(account.id); // also drops saved cookie snapshot
    }

    // ------------------------------------------------------------------ downloads

    private void toastUi(final int resId, final Object... args) {
        runOnUiThread(() -> Toast.makeText(context, getString(resId, args), Toast.LENGTH_SHORT).show());
    }

    /** blob: URLs created by claude.ai itself may be fetched by the page. */
    private boolean isAllowedBlob(String url) {
        if (!url.startsWith("blob:")) return false;
        return isAllowedHost(Uri.parse(url.substring(5)).getHost());
    }

    private void handleDownload(final String url, final String userAgent, final String contentDisposition, final String mimetype) {
        if (url == null) return;
        runWithStoragePermission(() -> {
            try {
                boolean opaque = url.startsWith("blob:") || url.startsWith("data:");
                String name = null;
                if (contentDisposition != null && !contentDisposition.isEmpty()) {
                    name = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
                }
                if (name == null) name = downloadNames.remove(url);   // from <a download="name.ext">
                if (name == null) {
                    if (opaque) {
                        String ext = mimetype == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(stripMimeParams(mimetype));
                        name = "claude-download" + (ext != null ? "." + ext : "");
                    } else {
                        name = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);
                    }
                }
                final String filename = safeFileName(name);
                final String mime = resolveMime(filename, mimetype);

                if (url.startsWith("blob:")) {
                    downloadBlob(url, filename, mime);
                } else if (url.startsWith("data:")) {
                    saveDataUrl(url, filename, mime);
                } else if (url.startsWith("https://") || url.startsWith("http://")) {
                    downloadHttp(url, userAgent, filename, mime);
                } else {
                    Toast.makeText(context, R.string.download_unsupported, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + url, e);
                Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Android 9 and older need WRITE_EXTERNAL_STORAGE to write into Downloads. */
    private void runWithStoragePermission(Runnable action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = action;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            return;
        }
        action.run();
    }

    private void downloadHttp(String url, String userAgent, String filename, String mime) {
        String cookie = cookieManager().getCookie(url);   // may be null
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager not available");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            request.addRequestHeader("Referer", url);
            request.setTitle(filename);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            dm.enqueue(request);
            Toast.makeText(context, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // DownloadManager missing/disabled or refused: download it ourselves
            Log.w(TAG, "DownloadManager failed, using built-in downloader", e);
            downloadHttpDirect(url, userAgent, cookie, filename, mime);
        }
    }

    /** Own downloader (https only). The cookie is never sent to a different host after a redirect. */
    private void downloadHttpDirect(final String url, final String userAgent, final String cookie, final String filename, final String mime) {
        Toast.makeText(context, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String origHost = new URL(url).getHost();
                String current = url;
                boolean ok = false;
                for (int hop = 0; hop < 6 && !ok; hop++) {
                    URL u = new URL(current);
                    if (!"https".equals(u.getProtocol())) throw new IOException("only https is supported");
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(30000);
                    if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
                    if (cookie != null && u.getHost().equals(origHost)) conn.setRequestProperty("Cookie", cookie);
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (location == null) throw new IOException("redirect without location");
                        current = new URL(u, location).toString();
                    } else if (code == 200) {
                        ok = true;
                    } else {
                        throw new IOException("HTTP " + code);
                    }
                }
                if (!ok) throw new IOException("too many redirects");
                try (InputStream in = conn.getInputStream(); OutputStream out = openDownloadStream(filename, mime)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                toastUi(R.string.download_saved, filename);
            } catch (Exception e) {
                Log.e(TAG, "Direct download failed", e);
                toastUi(R.string.download_failed);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** Reads a blob: URL inside the page (only the page can) and hands the bytes to the bridge. */
    private void downloadBlob(String url, String filename, String mime) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format(Locale.US, "%02x", b));
        blobTokens.add(token.toString());
        String js = "(function(){var B=window.ClaudeAssistBridge,T=" + JSONObject.quote(token.toString())
                + ",F=" + JSONObject.quote(filename) + ",M=" + JSONObject.quote(mime) + ";"
                + "try{var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url) + ",true);x.responseType='blob';"
                + "x.onload=function(){var r=new FileReader();"
                + "r.onloadend=function(){B.save(T,String(r.result),F,(x.response&&x.response.type)||M);};"
                + "r.readAsDataURL(x.response);};"
                + "x.onerror=function(){B.fail(T);};x.send();}catch(e){B.fail(T);}})();";
        chatWebView.evaluateJavascript(js, null);
        Toast.makeText(context, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
    }

    private void saveDataUrl(final String url, final String filename, final String mime) {
        new Thread(() -> {
            try {
                int comma = url.indexOf(',');
                if (comma < 0) throw new IOException("malformed data URL");
                boolean base64 = url.substring(0, comma).contains(";base64");
                String payload = url.substring(comma + 1);
                byte[] bytes = base64 ? Base64.decode(payload, Base64.DEFAULT) : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
                writeToDownloads(filename, mime, bytes);
                toastUi(R.string.download_saved, filename);
            } catch (Exception e) {
                Log.e(TAG, "Saving data URL failed", e);
                toastUi(R.string.download_failed);
            }
        }).start();
    }

    /**
     * Receives blob contents from the page. Only calls carrying a one-time token that the app
     * created for a download are accepted, so other websites cannot write files.
     */
    private class DownloadBridge {
        @JavascriptInterface
        public void save(String token, String dataUrl, String filename, String mime) {
            if (token == null || !blobTokens.remove(token) || dataUrl == null) return;
            try {
                int comma = dataUrl.indexOf(',');
                if (comma < 0) throw new IOException("malformed data URL");
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                String name = safeFileName(filename);
                if (name.toLowerCase(Locale.US).endsWith(".bin")) {
                    String ext = sniffExtension(bytes);
                    if (ext != null) name = name.substring(0, name.length() - 4) + ext;
                }
                writeToDownloads(name, resolveMime(name, mime), bytes);
                toastUi(R.string.download_saved, name);
            } catch (Exception e) {
                Log.e(TAG, "Saving blob failed", e);
                toastUi(R.string.download_failed);
            }
        }

        @JavascriptInterface
        public void name(String url, String name) {
            if (url == null || name == null || url.length() > 2048 || name.length() > 255) return;
            downloadNames.put(url, name);
        }

        @JavascriptInterface
        public void fail(String token) {
            if (token != null && blobTokens.remove(token)) toastUi(R.string.download_failed);
        }
    }

    private void writeToDownloads(String filename, String mime, byte[] bytes) throws IOException {
        try (OutputStream out = openDownloadStream(filename, mime)) {
            out.write(bytes);
        }
    }

    private OutputStream openDownloadStream(String filename, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            OutputStream out = uri == null ? null : getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("could not create file in Downloads");
            return out;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Downloads folder not available");
        File file = new File(dir, filename);
        String base = filename, ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) { base = filename.substring(0, dot); ext = filename.substring(dot); }
        for (int i = 1; file.exists() && i < 1000; i++) file = new File(dir, base + " (" + i + ")" + ext);
        return new FileOutputStream(file);
    }

    /** Last resort when no file name is known: guess the extension from the file's first bytes. */
    private static String sniffExtension(byte[] b) {
        if (b == null || b.length < 4) return null;
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return ".pdf";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return ".png";
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return ".jpg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return ".gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b.length > 11 && b[8] == 'W' && b[9] == 'E') return ".webp";
        if (b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) return ".zip";   // also docx/xlsx/pptx/apk
        if ((b[0] & 0xff) == 0x1F && (b[1] & 0xff) == 0x8B) return ".gz";
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return ".mp3";
        if (b.length > 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return ".mp4";
        return null;
    }

    private static String stripMimeParams(String mime) {
        int semi = mime.indexOf(';');
        return (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase(Locale.US);
    }

    private static String resolveMime(String filename, String mime) {
        if (mime != null && !mime.trim().isEmpty()) {
            String m = stripMimeParams(mime);
            if (!m.equals("application/octet-stream") && m.contains("/")) return m;
        }
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substring(dot + 1).toLowerCase(Locale.US));
            if (guess != null) return guess;
        }
        return "application/octet-stream";
    }

    private static String safeFileName(String name) {
        if (name == null) name = "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 120) name = name.substring(0, 120);
        return name.isEmpty() ? "download" : name;
    }

    // ------------------------------------------------------------------ more menu, backup, restore

    private void showMoreMenu() {
        String[] items = {
                getString(keepAlive ? R.string.bg_on : R.string.bg_off),
                getString(R.string.backup_all),
                getString(R.string.restore_backup)
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) toggleKeepAlive();
                    else if (which == 1) startBackup();
                    else startRestore();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleKeepAlive() {
        keepAlive = !keepAlive;
        settings.edit().putBoolean("keep_alive", keepAlive).apply();
        updateKeepAliveService();
        Toast.makeText(context, keepAlive ? R.string.bg_enabled : R.string.bg_disabled, Toast.LENGTH_SHORT).show();
    }

    private void promptPassphrase(int titleRes, int messageRes, final TextCallback callback) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint(R.string.passphrase_hint);
        FrameLayout holder = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setView(holder)
                .setPositiveButton(android.R.string.ok, (d, w) -> callback.onText(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startBackup() {
        promptPassphrase(R.string.backup_title, R.string.backup_pass_msg, pass -> {
            pendingBackupPassphrase = pass.toCharArray();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "claudeassist-backup-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".cabk");
            startActivityForResult(intent, BACKUP_REQUEST_CODE);
        });
    }

    /** Everything that is needed to bring all accounts back: names, restriction flags, session cookies. */
    private byte[] buildBackup() throws JSONException {
        if (!multiProfile) snapshotCookies(accounts.currentId());
        JSONArray array = new JSONArray();
        List<AccountStore.Account> list = accounts.list();
        int currentIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            AccountStore.Account a = list.get(i);
            if (a.id == accounts.currentId()) currentIndex = i;
            Map<String, String> cookies = new LinkedHashMap<>();
            if (multiProfile) {
                try {
                    CookieManager cm = profileFor(a).getCookieManager();
                    for (String url : COOKIE_SNAPSHOT_URLS) cookies.put(url, cm.getCookie(url));
                } catch (Exception e) {
                    Log.w(TAG, "Could not read cookies of " + a.name, e);
                }
            } else {
                cookies = accounts.loadCookies(a.id);
            }
            JSONObject cookieJson = new JSONObject();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (e.getValue() != null) cookieJson.put(e.getKey(), e.getValue());
            }
            JSONObject o = new JSONObject();
            o.put("name", a.name);
            o.put("restricted", a.restricted);
            o.put("cookies", cookieJson);
            array.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("app", "claudeAssist");
        root.put("version", 1);
        root.put("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        root.put("current", currentIndex);
        root.put("keepAlive", keepAlive);
        root.put("accounts", array);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeBackup(final Uri uri) {
        final char[] pass = pendingBackupPassphrase;
        pendingBackupPassphrase = null;
        final byte[] plain;
        try {
            plain = buildBackup();
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                byte[] out = (pass != null && pass.length > 0) ? BackupCodec.encrypt(plain, pass) : plain;
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new IOException("cannot open backup file");
                    os.write(out);
                }
                toastUi(R.string.backup_saved);
            } catch (Exception e) {
                Log.e(TAG, "Backup failed", e);
                toastUi(R.string.backup_failed);
            }
        }).start();
    }

    private void startRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, RESTORE_REQUEST_CODE);
    }

    private void readBackup(final Uri uri) {
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open file");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes.write(buf, 0, n);
                    if (bytes.size() > 20 * 1024 * 1024) throw new IOException("file too large");
                }
                final byte[] data = bytes.toByteArray();
                runOnUiThread(() -> onBackupBytes(data));
            } catch (Exception e) {
                Log.e(TAG, "Reading backup failed", e);
                toastUi(R.string.restore_invalid);
            }
        }).start();
    }

    private void onBackupBytes(final byte[] data) {
        if (BackupCodec.isEncrypted(data)) {
            promptPassphrase(R.string.restore_pass_title, R.string.restore_pass_msg, pass -> new Thread(() -> {
                try {
                    final byte[] plain = BackupCodec.decrypt(data, pass.toCharArray());
                    runOnUiThread(() -> confirmRestore(plain));
                } catch (GeneralSecurityException e) {
                    toastUi(R.string.restore_wrong_pass);
                }
            }).start());
        } else {
            confirmRestore(data);
        }
    }

    private void confirmRestore(byte[] plain) {
        final JSONObject root;
        final int count;
        try {
            root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (!"claudeAssist".equals(root.optString("app"))) throw new JSONException("not a claudeAssist backup");
            count = root.getJSONArray("accounts").length();
            if (count == 0) throw new JSONException("no accounts");
        } catch (JSONException e) {
            Toast.makeText(context, R.string.restore_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(getString(R.string.restore_confirm_msg, count))
                .setPositiveButton(R.string.restore_action, (d, w) -> applyRestore(root))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyRestore(JSONObject root) {
        final List<String> names = new ArrayList<>();
        final List<Boolean> restrictedFlags = new ArrayList<>();
        final List<Map<String, String>> cookieMaps = new ArrayList<>();
        final int current;
        final boolean backupKeepAlive;
        try {
            JSONArray array = root.getJSONArray("accounts");
            for (int i = 0; i < array.length() && i < 50; i++) {
                JSONObject o = array.getJSONObject(i);
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) name = getString(R.string.account_default_name, i + 1);
                if (name.length() > 60) name = name.substring(0, 60);
                names.add(name);
                restrictedFlags.add(o.optBoolean("restricted", false));
                Map<String, String> map = new LinkedHashMap<>();
                JSONObject cookies = o.optJSONObject("cookies");
                if (cookies != null) {
                    for (String url : COOKIE_SNAPSHOT_URLS) if (cookies.has(url)) map.put(url, cookies.optString(url));
                }
                cookieMaps.add(map);
            }
            if (names.isEmpty()) throw new JSONException("no accounts");
            current = root.optInt("current", 0);
            backupKeepAlive = root.optBoolean("keepAlive", keepAlive);
        } catch (JSONException e) {
            Toast.makeText(context, R.string.restore_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        List<AccountStore.Account> old = accounts.list();
        destroyWebView();
        clearAllAccountData(old, () -> {
            List<AccountStore.Account> created = accounts.replaceAll(names, restrictedFlags, current);
            for (int i = 0; i < created.size() && i < cookieMaps.size(); i++) {
                if (multiProfile) {
                    try {
                        applyCookies(profileFor(created.get(i)).getCookieManager(), cookieMaps.get(i));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not restore cookies", e);
                    }
                } else {
                    accounts.saveCookies(created.get(i).id, cookieMaps.get(i));
                }
            }
            if (!multiProfile) restoreCookies(accounts.currentId());   // active account goes into the shared jar
            keepAlive = backupKeepAlive;
            settings.edit().putBoolean("keep_alive", keepAlive).apply();
            updateKeepAliveService();
            createWebView();
            chatWebView.loadUrl(urlToLoad);
            Toast.makeText(context, R.string.restore_done, Toast.LENGTH_LONG).show();
        });
    }

    /** Wipes cookies and site data of the given accounts, then runs {@code then} on the UI thread. */
    private void clearAllAccountData(List<AccountStore.Account> list, final Runnable then) {
        if (multiProfile) {
            for (AccountStore.Account a : list) {
                try {
                    Profile p = profileFor(a);
                    p.getCookieManager().removeAllCookies(null);
                    p.getCookieManager().flush();
                    p.getWebStorage().deleteAllData();
                } catch (Exception e) {
                    Log.w(TAG, "Could not clear " + a.name, e);
                }
                try {
                    ProfileStore.getInstance().deleteProfile(a.profileName());
                } catch (Exception e) {
                    Log.w(TAG, "Profile still in use, data was cleared instead", e);
                }
            }
            then.run();
        } else {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(ok -> runOnUiThread(then));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Shown when a sign-in page of Google/Apple/Microsoft is blocked in restricted mode. */
    private void notifyIfSocialLogin(final WebView view, String host) {
        if (host == null) return;
        if (host.equals("login.microsoftonline.com") || host.equals("accounts.google.com") || host.equals("appleid.apple.com")) {
            view.post(() -> Toast.makeText(context, context.getString(R.string.error_microsoft_google), Toast.LENGTH_LONG).show());
        }
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (chatWebView != null && chatWebView.canGoBack() && !"about:blank".equals(chatWebView.getUrl())) {
                        chatWebView.goBack();
                    } else {
                        finish();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Signs the active account out: clears only its cookies and site data. */
    public void resetChat()  {
        chatWebView.clearCache(true);
        chatWebView.clearFormData();
        chatWebView.clearHistory();
        chatWebView.clearMatches();
        chatWebView.clearSslPreferences();
        CookieManager cm = cookieManager();
        cm.removeAllCookies(null);
        cm.flush();
        if (multiProfile) {
            try {
                profileFor(accounts.current()).getWebStorage().deleteAllData();
            } catch (Exception e) {
                Log.w(TAG, "Could not clear profile storage", e);
            }
        } else {
            WebStorage.getInstance().deleteAllData();
            accounts.clearCookies(accounts.currentId());
        }
        chatWebView.loadUrl(urlToLoad);
    }

    private static void initURLs() {
        if (!allowedDomains.isEmpty()) return;
        //Allowed Domains
        allowedDomains.add("claude.ai");
        allowedDomains.add("claude.com");
        allowedDomains.add("anthropic.com");
        allowedDomains.add("claudeusercontent.com");   //artifacts / generated content
        allowedDomains.add("cloudflare.com");          //bot check on the sign-in page
    }

    /** Exact match or real subdomain (so "evilclaude.ai" does not pass for "claude.ai"). */
    private static boolean isAllowedHost(String host) {
        if (host == null) return false;
        for (String domain : allowedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    String dataString = intent.getDataString();
                    if (dataString != null) {
                        result = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        } else if (requestCode == BACKUP_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && intent != null && intent.getData() != null) {
                writeBackup(intent.getData());
            } else {
                pendingBackupPassphrase = null;
            }
        } else if (requestCode == RESTORE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && intent != null && intent.getData() != null) {
                readBackup(intent.getData());
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (chatWebView == null) return;
        WebView.HitTestResult result = chatWebView.getHitTestResult();
        String extra = result.getExtra();
        if (extra == null) return;
        int type = result.getType();
        if (type == IMAGE_TYPE) {
            handleDownload(extra, chatWebSettings.getUserAgentString(), null, null);
        } else if (type == SRC_ANCHOR_TYPE) {
            handleLinkLongPress(extra);
        } else if (type == SRC_IMAGE_ANCHOR_TYPE) {
            // The link target arrives asynchronously, on the main thread
            Handler handler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    String url = msg.getData().getString("url");
                    if (url != null) handleLinkLongPress(url);
                }
            };
            chatWebView.requestFocusNodeHref(handler.obtainMessage());
        }
    }

    private void handleLinkLongPress(String url) {
        if (url == null) return;
        Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        String host = Uri.parse(url).getHost();
        if (host != null && !isAllowedHost(host)) {  //Copy URLs that are not allowed to open to clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
        }
    }

    public String modUserAgent(){

        String newPrefix = "Mozilla/5.0 (X11; Linux "+ System.getProperty("os.arch") +")";

        String newUserAgent=WebSettings.getDefaultUserAgent(context);
        String prefix = newUserAgent.substring(0, newUserAgent.indexOf(")") + 1);
         try {
                newUserAgent=newUserAgent.replace(prefix,newPrefix);
            } catch (Exception e) {
                e.printStackTrace();
            }
         return newUserAgent;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Microphone permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Microphone permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
        // Handle other permission requests if any (like FILE_CHOOSER_REQUEST_CODE)
        if (requestCode == 100) { // This is the request code for READ_EXTERNAL_STORAGE from onShowFileChooser
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted for file access
            } else {
                Toast.makeText(context, "Storage permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == STORAGE_PERMISSION_CODE) {
            Runnable retry = pendingDownload;
            pendingDownload = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (retry != null) retry.run();
            } else {
                Toast.makeText(context, R.string.download_need_storage, Toast.LENGTH_LONG).show();
            }
        }
    }

}
