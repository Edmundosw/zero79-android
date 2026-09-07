package com.zero79.esportes;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.os.Build;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://zero79.netlify.app/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new PdfBridge(this), "AndroidPdfBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectPdfDownloadBridge(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
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
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Não foi possível abrir o seletor de arquivos.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ZERO79_arquivo");
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(MainActivity.this, "Download iniciado.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            }
        });

        if (!isOnline()) {
            Toast.makeText(this, "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.", Toast.LENGTH_LONG).show();
        }

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void injectPdfDownloadBridge(WebView view) {
        String js = "javascript:(function(){" +
                "if(window.__zero79PdfBridgeInstalled)return;window.__zero79PdfBridgeInstalled=true;" +

                // html2pdf.js 0.10.1 normally ends its .save() through FileSaver.saveAs().
                // Intercept that native browser download path and send the Blob directly to Android.
                if (typeof window.saveAs === 'function') {
                    var originalSaveAs = window.saveAs;
                    window.saveAs = function(blob, name) {
                        try {
                            if (blob instanceof Blob) {
                                var fr = new FileReader();
                                fr.onloadend = function() {
                                    var x = fr.result || '', i = x.indexOf(',');
                                    AndroidPdfBridge.saveBase64(
                                        i >= 0 ? x.substring(i + 1) : x,
                                        blob.type || 'application/pdf',
                                        name || 'ZERO79.pdf'
                                    );
                                };
                                fr.onerror = function() {
                                    AndroidPdfBridge.error('Falha ao ler o PDF gerado.');
                                };
                                fr.readAsDataURL(blob);
                                return;
                            }
                        } catch (e) {
                            AndroidPdfBridge.error(String(e));
                            return;
                        }
                        return originalSaveAs.apply(this, arguments);
                    };
                }

                // Keep a reference to every Blob created by html2pdf.
                // This avoids relying on Android opening a blob: URL.
                "window.__zero79BlobStore={};" +
                "var originalCreateObjectURL=URL.createObjectURL.bind(URL);" +
                "URL.createObjectURL=function(obj){" +
                "var u=originalCreateObjectURL(obj);" +
                "try{window.__zero79BlobStore[u]=obj;}catch(e){}" +
                "return u;" +
                "};" +

                "window.__zero79SaveBlob=function(u,n){" +
                "try{" +
                "var b=window.__zero79BlobStore[u];" +
                "var send=function(blob){" +
                "var fr=new FileReader();" +
                "fr.onloadend=function(){" +
                "var x=fr.result||'',i=x.indexOf(',');" +
                "AndroidPdfBridge.saveBase64(i>=0?x.substring(i+1):x,blob.type||'application/pdf',n||'ZERO79.pdf');" +
                "};" +
                "fr.onerror=function(){AndroidPdfBridge.error('Falha ao ler o PDF.');};" +
                "fr.readAsDataURL(blob);" +
                "};" +
                "if(b){send(b);}else{" +
                "fetch(u).then(function(r){return r.blob()}).then(send).catch(function(e){AndroidPdfBridge.error(String(e));});" +
                "}" +
                "}catch(e){AndroidPdfBridge.error(String(e));}" +
                "};" +

                // Capture real user/programmatic clicks before WebView navigation occurs.
                "document.addEventListener('click',function(e){" +
                "try{" +
                "var el=e.target;" +
                "var a=el&&el.closest?el.closest('a'):null;" +
                "if(a){" +
                "var h=a.href||'';" +
                "if(h.indexOf('blob:')===0){" +
                "e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();" +
                "window.__zero79SaveBlob(h,a.getAttribute('download')||'ZERO79.pdf');" +
                "return false;" +
                "}" +
                "}" +
                "}catch(x){}" +
                "},true);" +

                // Also cover code that explicitly calls anchor.click().
                "var originalClick=HTMLAnchorElement.prototype.click;" +
                "HTMLAnchorElement.prototype.click=function(){" +
                "var a=this,h=a.href||'';" +
                "if(h.indexOf('blob:')===0){" +
                "window.__zero79SaveBlob(h,a.getAttribute('download')||'ZERO79.pdf');return;" +
                "}" +
                "return originalClick.call(a);" +
                "};" +

                // Also cover window.open(blob:...).
                "var originalOpen=window.open;" +
                "window.open=function(u,t,f){" +
                "if(typeof u==='string'&&u.indexOf('blob:')===0){" +
                "window.__zero79SaveBlob(u,'ZERO79.pdf');return null;" +
                "}" +
                "return originalOpen.call(window,u,t,f);" +
                "};" +
                "})()";

        view.evaluateJavascript(js, null);
    }


    private static class PdfBridge {
        private final Context context;
        PdfBridge(Context context) { this.context = context.getApplicationContext(); }

        @JavascriptInterface
        public void saveBase64(String base64, String mimeType, String fileName) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safeName = (fileName == null || fileName.trim().isEmpty()) ? "ZERO79.pdf" : fileName.replaceAll("[\\/:*?\"<>|]", "_");
                if (!safeName.toLowerCase().endsWith(".pdf") && "application/pdf".equalsIgnoreCase(mimeType)) safeName += ".pdf";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType == null ? "application/pdf" : mimeType);
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException("Não foi possível criar o arquivo.");
                    try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Não foi possível abrir o arquivo.");
                        out.write(bytes);
                    }
                    values.clear();
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                } else {
                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) throw new IOException("Pasta de downloads indisponível.");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Não foi possível criar a pasta.");
                    File file = new File(dir, safeName);
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                }
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                final String finalSafeName = safeName;
main.post(() -> Toast.makeText(context, "PDF salvo em Downloads: " + finalSafeName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                error(e.toString());
            }
        }

        @JavascriptInterface
        public void error(String message) {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> Toast.makeText(context, "Não foi possível salvar o PDF.", Toast.LENGTH_LONG).show());
        }
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

        if (scheme.equals("blob")) {
            if (webView != null) {
                String encoded = Base64.encodeToString(
                        uri.toString().getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                );
                String js = "(function(){var u=decodeURIComponent(escape(atob('" + encoded + "')));" +
                        "if(window.__zero79SaveBlob){window.__zero79SaveBlob(u,'ZERO79.pdf');}" +
                        "else{AndroidPdfBridge.error('Bridge do PDF não foi carregado.');}})()";
                webView.evaluateJavascript(js, null);
            }
            return true;
        }

        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals("zero79.netlify.app") || host.endsWith("firebaseapp.com") ||
                    host.endsWith("googleapis.com") || host.endsWith("gstatic.com")) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms") ||
                scheme.equals("whatsapp") || scheme.equals("intent")) {
            openExternal(uri);
            return true;
        }
        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir este link.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }
}
