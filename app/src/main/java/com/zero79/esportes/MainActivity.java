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
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

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
                injectAndroidPdfFunctions(view);
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
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
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
                    Toast.makeText(
                            MainActivity.this,
                            "Não foi possível abrir o seletor de arquivos.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimetype,
                    long contentLength) {

                try {
                    DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(url));

                    request.setMimeType(
                            mimetype == null ? "application/octet-stream" : mimetype
                    );
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "ZERO79_arquivo"
                    );

                    DownloadManager dm =
                            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(
                                MainActivity.this,
                                "Download iniciado.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            }
        });

        if (!isOnline()) {
            Toast.makeText(
                    this,
                    "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.",
                    Toast.LENGTH_LONG
            ).show();
        }

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /*
     * SOLUÇÃO DEFINITIVA DO PDF:
     *
     * O index.html continua exatamente como está.
     * No Android, substituímos salvarPDF() para usar:
     *
     * html2pdf -> outputPdf('blob') -> FileReader -> Base64 -> Java/MediaStore
     *
     * Assim o WebView nunca tenta abrir ou navegar para blob:.
     */
    private void injectAndroidPdfFunctions(WebView view) {
        String js =
                "(function(){" +
                "try{" +
                "if(window.__zero79AndroidPdfInstalled){return;}" +
                "window.__zero79AndroidPdfInstalled=true;" +

                "window.__zero79AndroidSendPdf=function(blob,fileName){" +
                "try{" +
                "if(!blob){AndroidPdfBridge.error('PDF vazio.');return;}" +
                "var reader=new FileReader();" +
                "reader.onloadend=function(){" +
                "try{" +
                "var result=reader.result||'';" +
                "var comma=result.indexOf(',');" +
                "var base64=comma>=0?result.substring(comma+1):result;" +
                "if(!base64){AndroidPdfBridge.error('PDF sem conteúdo.');return;}" +
                "AndroidPdfBridge.saveBase64(base64,'application/pdf',fileName||'ZERO79.pdf');" +
                "}catch(e){AndroidPdfBridge.error(String(e));}" +
                "};" +
                "reader.onerror=function(){AndroidPdfBridge.error('Falha ao ler o PDF gerado.');};" +
                "reader.readAsDataURL(blob);" +
                "}catch(e){AndroidPdfBridge.error(String(e));}" +
                "};" +

                "window.salvarPDF=function(){" +
                "try{" +
                "var atletas=getAtletasOrdenados().filter(function(a){return a.treinaHoje;});" +
                "var acompanhantes=getAcompanhantesOrdenados().filter(function(a){return a.treinaHoje;});" +
                "if(atletas.length===0&&acompanhantes.length===0){" +
                "alert('Selecione ao menos um atleta!');return;" +
                "}" +
                "var element=document.getElementById('documento-oficial');" +
                "if(!element){AndroidPdfBridge.error('Documento oficial não encontrado.');return;}" +
                "if(typeof html2pdf!=='function'){AndroidPdfBridge.error('Biblioteca PDF não carregada.');return;}" +
                "var opt={" +
                "margin:0," +
                "filename:'Liberacao_Acesso_Rio.pdf'," +
                "image:{type:'jpeg',quality:0.98}," +
                "html2canvas:{scale:2,useCORS:true}," +
                "jsPDF:{unit:'mm',format:'a4',orientation:'portrait'}" +
                "};" +
                "html2pdf().set(opt).from(element).outputPdf('blob')" +
                ".then(function(blob){" +
                "window.__zero79AndroidSendPdf(blob,'Liberacao_Acesso_Rio.pdf');" +
                "})" +
                ".catch(function(e){AndroidPdfBridge.error(String(e));});" +
                "}catch(e){AndroidPdfBridge.error(String(e));}" +
                "};" +

                "window.salvarPDFCompeticao=function(){" +
                "try{" +
                "var select=document.getElementById('select-competicao-alvo');" +
                "var nomeComp=select?select.value:'';" +
                "var atletas=getAtletasOrdenados().filter(function(a){return a.competicoes&&a.competicoes[nomeComp];});" +
                "if(atletas.length===0){" +
                "alert('Selecione ao menos um atleta participante para esta competição!');return;" +
                "}" +
                "var div=document.createElement('div');" +
                "div.className='a4-preview';" +
                "div.innerHTML=" +
                "'<div style=\"text-align:center;margin-bottom:20px;\">'+" +
                "'<h1 style=\"font-size:28px;font-weight:900;font-style:italic;color:#003853;margin:0;\">ZERO<span style=\"color:#82c324;\">79</span></h1>'+" +
                "'<p style=\"font-size:11px;font-weight:bold;letter-spacing:4px;color:#003853;margin-top:4px;\">ESPORTES</p>'+" +
                "'</div>'+" +
                "'<h2 style=\"text-align:center;font-size:15px;font-weight:bold;margin-bottom:18px;text-decoration:underline;\">RELAÇÃO DE ATLETAS - '+nomeComp.toUpperCase()+'</h2>'+" +
                "'<table><thead><tr><th>ATLETA</th><th>DATA DE NASCIMENTO</th><th>CPF/RG</th></tr></thead><tbody>'+" +
                "atletas.map(function(a){return '<tr><td>'+a.nome+'</td><td>'+(a.dataNascimento||'')+'</td><td>'+(a.cpf||'')+'</td></tr>';}).join('')+" +
                "'</tbody></table>'+" +
                "'<div style=\"margin-top:50px;font-size:13px;\"><p>Responsável Técnico: Edmundo Júnior - CREF 005911-G/SE</p></div>';" +
                "document.body.appendChild(div);" +
                "var opt={" +
                "margin:0," +
                "filename:'Inscritos_'+nomeComp+'.pdf'," +
                "image:{type:'jpeg',quality:0.98}," +
                "html2canvas:{scale:2,useCORS:true}," +
                "jsPDF:{unit:'mm',format:'a4',orientation:'portrait'}" +
                "};" +
                "html2pdf().set(opt).from(div).outputPdf('blob')" +
                ".then(function(blob){" +
                "window.__zero79AndroidSendPdf(blob,'Inscritos_'+nomeComp+'.pdf');" +
                "if(div.parentNode){div.parentNode.removeChild(div);}" +
                "})" +
                ".catch(function(e){" +
                "if(div.parentNode){div.parentNode.removeChild(div);}" +
                "AndroidPdfBridge.error(String(e));" +
                "});" +
                "}catch(e){AndroidPdfBridge.error(String(e));}" +
                "};" +

                "}catch(e){try{AndroidPdfBridge.error(String(e));}catch(ignore){}}" +
                "})();";

        view.evaluateJavascript(js, null);
    }

    private static class PdfBridge {
        private final Context context;

        PdfBridge(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public void saveBase64(String base64, String mimeType, String fileName) {
            try {
                if (base64 == null || base64.trim().isEmpty()) {
                    throw new IOException("Conteúdo do PDF vazio.");
                }

                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                if (bytes.length == 0) {
                    throw new IOException("PDF sem bytes.");
                }

                String safeName =
                        (fileName == null || fileName.trim().isEmpty())
                                ? "ZERO79.pdf"
                                : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");

                if (!safeName.toLowerCase().endsWith(".pdf")) {
                    safeName += ".pdf";
                }

                String mime =
                        (mimeType == null || mimeType.trim().isEmpty())
                                ? "application/pdf"
                                : mimeType;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues values =
                            new android.content.ContentValues();

                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = context.getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                    );

                    if (uri == null) {
                        throw new IOException(
                                "Não foi possível criar o arquivo em Downloads."
                        );
                    }

                    try {
                        try (OutputStream out =
                                     context.getContentResolver().openOutputStream(uri)) {

                            if (out == null) {
                                throw new IOException(
                                        "Não foi possível abrir o arquivo em Downloads."
                                );
                            }

                            out.write(bytes);
                            out.flush();
                        }
                    } catch (Exception e) {
                        try {
                            context.getContentResolver().delete(uri, null, null);
                        } catch (Exception ignored) {
                        }
                        throw e;
                    }

                    android.content.ContentValues done =
                            new android.content.ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);

                    context.getContentResolver().update(
                            uri,
                            done,
                            null,
                            null
                    );

                } else {
                    File dir =
                            context.getExternalFilesDir(
                                    Environment.DIRECTORY_DOWNLOADS
                            );

                    if (dir == null) {
                        throw new IOException(
                                "Pasta de downloads indisponível."
                        );
                    }

                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException(
                                "Não foi possível criar a pasta."
                        );
                    }

                    File file = new File(dir, safeName);

                    try (FileOutputStream out =
                                 new FileOutputStream(file)) {
                        out.write(bytes);
                        out.flush();
                    }
                }

                final String finalSafeName = safeName;

                android.os.Handler main =
                        new android.os.Handler(
                                android.os.Looper.getMainLooper()
                        );

                main.post(() ->
                        Toast.makeText(
                                context,
                                "PDF salvo em Downloads: " + finalSafeName,
                                Toast.LENGTH_LONG
                        ).show()
                );

            } catch (Exception e) {
                error(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }

        @JavascriptInterface
        public void error(String message) {
            android.os.Handler main =
                    new android.os.Handler(
                            android.os.Looper.getMainLooper()
                    );

            main.post(() ->
                    Toast.makeText(
                            context,
                            "Não foi possível salvar o PDF.",
                            Toast.LENGTH_LONG
                    ).show()
            );
        }
    }

    private boolean handleUrl(Uri uri) {
        String scheme =
                uri.getScheme() == null
                        ? ""
                        : uri.getScheme().toLowerCase();

        String host =
                uri.getHost() == null
                        ? ""
                        : uri.getHost().toLowerCase();

        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals("zero79.netlify.app")
                    || host.endsWith("firebaseapp.com")
                    || host.endsWith("googleapis.com")
                    || host.endsWith("gstatic.com")) {
                return false;
            }

            openExternal(uri);
            return true;
        }

        if (scheme.equals("mailto")
                || scheme.equals("tel")
                || scheme.equals("sms")
                || scheme.equals("whatsapp")
                || scheme.equals("intent")) {

            openExternal(uri);
            return true;
        }

        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Não foi possível abrir este link.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(
                        Context.CONNECTIVITY_SERVICE
                );

        if (cm == null) {
            return false;
        }

        Network network = cm.getActiveNetwork();

        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities =
                cm.getNetworkCapabilities(network);

        return capabilities != null
                && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                );
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
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST
                && filePathCallback != null) {

            Uri[] results =
                    WebChromeClient.FileChooserParams.parseResult(
                            resultCode,
                            data
                    );

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
