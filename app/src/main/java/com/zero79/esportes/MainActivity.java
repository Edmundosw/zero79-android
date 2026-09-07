package com.zero79.esportes;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.util.Base64;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int STORAGE_PERMISSION_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Intercepta e converte links blob para download base64 se necessário
                if (url.startsWith("blob:")) {
                    return true;
                }
                view.loadUrl(url);
                return true;
            }
        });

        // Gerencia downloads normais e base64
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url.startsWith("data:")) {
                    baixarBase64(url, contentDisposition);
                    return;
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                        return;
                    }
                }
                baixarArquivo(url, contentDisposition, mimeType);
            }
        });

        // Script injetado para forçar o html2pdf a gerar data-url em vez de blob URL no WebView do Android
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String jsScript = "javascript:(function() {" +
                    "var origCreateObjectURL = window.URL.createObjectURL;" +
                    "window.URL.createObjectURL = function(blob) {" +
                        "if (blob.type === 'application/pdf') {" +
                            "var reader = new FileReader();" +
                            "reader.onload = function(e) {" +
                                "var a = document.createElement('a');" +
                                "a.href = e.target.result;" +
                                "a.download = 'Liberacao_Acesso_Rio.pdf';" +
                                "document.body.appendChild(a);" +
                                "a.click();" +
                                "document.body.removeChild(a);" +
                            "};" +
                            "reader.readAsDataURL(blob);" +
                            "return '#';" +
                        "}" +
                        "return origCreateObjectURL.apply(this, arguments);" +
                    "};" +
                "})();";
                view.evaluateJavascript(jsScript, null);
            }
        });

        // Carrega o site no Netlify
        webView.loadUrl("https://zero79.netlify.app/");
    }

    private void baixarBase64(String dataUrl, String contentDisposition) {
        try {
            String base64Data = dataUrl.substring(dataUrl.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
            
            String fileName = "Liberacao_Acesso_Rio.pdf";
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.replaceAll(".*filename=\"?([^\"\"]*)\"?.*", "$1");
            }

            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, fileName);
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(decodedBytes);
            fos.close();

            Toast.makeText(getApplicationContext(), "PDF salvo na pasta Downloads!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Erro ao salvar PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void baixarArquivo(String url, String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            
            String cookies = CookieManager.getInstance().getCookie(url);
            request.addRequestHeader("cookie", cookies);
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                Toast.makeText(getApplicationContext(), "Baixando arquivo PDF...", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Erro ao baixar arquivo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
