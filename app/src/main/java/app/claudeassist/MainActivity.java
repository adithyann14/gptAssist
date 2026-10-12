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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.InputType;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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

                if (request.getUrl().toString().equals("about:blank")) {
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

                if (request.getUrl().toString().equals("about:blank")) {
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "[onReceivedError] " + error.getErrorCode() + ": " + error.getDescription() + " @ " + request.getUrl());
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Uri source = Uri.parse(url);
            Log.d(TAG,"DownloadManager: " + url);
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.addRequestHeader("Cookie", cookieManager().getCookie(url));
            request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
            request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
            request.addRequestHeader("Referer", url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
            String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
            if (filename == null) filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);  // only if getFilenameFromContentDisposition does not work and returns null
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            assert dm != null;
            dm.enqueue(request);
        });

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
        CookieManager cm = CookieManager.getInstance();
        for (Map.Entry<String, String> e : accounts.loadCookies(id).entrySet()) {
            String host = Uri.parse(e.getKey()).getHost();
            for (String pair : e.getValue().split(";")) {
                pair = pair.trim();
                if (pair.isEmpty()) continue;
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
                .setNeutralButton(keepAlive ? R.string.bg_on : R.string.bg_off, (d, w) -> {
                    keepAlive = !keepAlive;
                    settings.edit().putBoolean("keep_alive", keepAlive).apply();
                    updateKeepAliveService();
                    Toast.makeText(context, keepAlive ? R.string.bg_enabled : R.string.bg_disabled, Toast.LENGTH_SHORT).show();
                })
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
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        WebView.HitTestResult result = chatWebView.getHitTestResult();
        String url = "";
        if (result.getExtra() != null) {
            if (result.getType() == IMAGE_TYPE) {
                url = result.getExtra();
                Uri source = Uri.parse(url);
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.addRequestHeader("Cookie", cookieManager().getCookie(url));
                request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
                request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
                request.addRequestHeader("Referer", url);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
                String filename = URLUtil.guessFileName(url, null, "image/jpeg");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                assert dm != null;
                dm.enqueue(request);
            } else if (result.getType() == SRC_IMAGE_ANCHOR_TYPE || result.getType() == SRC_ANCHOR_TYPE) {
                if (result.getType() == SRC_IMAGE_ANCHOR_TYPE) {
                    // Create a background thread that has a Looper
                    HandlerThread handlerThread = new HandlerThread("HandlerThread");
                    handlerThread.start();
                    // Create a handler to execute tasks in the background thread.
                    Handler backgroundHandler = new Handler(handlerThread.getLooper());
                    Message msg = backgroundHandler.obtainMessage();
                    chatWebView.requestFocusNodeHref(msg);
                    url = (String) msg.getData().get("url");
                    Toast.makeText(this, "SRC_IMAGE: " + url, Toast.LENGTH_SHORT).show();
                } else if (result.getType() == SRC_ANCHOR_TYPE) {
                    url = result.getExtra();
                    Toast.makeText(this, "SRC_ANCHOR: " + url, Toast.LENGTH_SHORT).show();
                }
                String host = Uri.parse(url).getHost();
                if (host != null && !isAllowedHost(host)) {  //Copy URLs that are not allowed to open to clipboard
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
                }
            }
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
    }

}
