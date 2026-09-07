package com.zero79.esportes;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
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


public class MainActivity extends Activity {
    private static final String HOME_URL = "https://zero79.netlify.app/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private boolean pdfPrinting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Permite que o WebView seja desenhado além da área visível, necessário
        // para transformar o documento completo em PDF.
        WebView.enableSlowWholeDocumentDraw();

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

        webView.addJavascriptInterface(new PdfBridge(), "AndroidPdfBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativePdfButton();
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
                    Toast.makeText(MainActivity.this,
                            "Não foi possível abrir o seletor de arquivos.",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    android.app.DownloadManager.Request request =
                            new android.app.DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, "ZERO79_arquivo");
                    android.app.DownloadManager dm =
                            (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(MainActivity.this,
                            "Download iniciado.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    openExternal(Uri.parse(url));
                }
            }
        });

        if (!isOnline()) {
            Toast.makeText(this,
                    "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.",
                    Toast.LENGTH_LONG).show();
        }

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /**
     * Substitui SOMENTE o botão de salvar o documento de liberação.
     * A seleção dos atletas continua sendo feita pelo próprio site.
     */
    private void installNativePdfButton() {
        if (webView == null) return;

        /*
         * IMPORTANTE:
         * getAtletasOrdenados() e getAcompanhantesOrdenados() pertencem ao
         * escopo do <script type="module"> do site e, portanto, NÃO ficam
         * disponíveis diretamente para JavaScript injetado pelo WebView.
         *
         * A versão anterior tentava acessar essas funções diretamente e por
         * isso sempre enxergava 0 selecionados.
         *
         * Aqui mantemos a função original salvarPDF() do site. Ela própria
         * verifica quais atletas estão selecionados. Interceptamos apenas
         * html2pdf() durante essa chamada: quando o site chega ao ponto em
         * que faria a conversão para PDF, chamamos a ponte nativa Android.
         */
        String js = "javascript:(function(){" +
                "if(window.__zero79NativePdfInstalled)return;" +
                "if(typeof window.salvarPDF!=='function')return;" +
                "var originalSalvarPDF=window.salvarPDF;" +
                "var originalHtml2pdf=window.html2pdf;" +
                "window.__zero79OriginalSalvarPDF=originalSalvarPDF;" +
                "window.__zero79OriginalHtml2pdf=originalHtml2pdf;" +
                "window.salvarPDF=function(){" +
                    "try{" +
                        "window.html2pdf=function(){" +
                            "var api={};" +
                            "api.set=function(){return api;};" +
                            "api.from=function(){return api;};" +
                            "api.save=function(){" +
                                "if(window.AndroidPdfBridge){window.AndroidPdfBridge.printOfficialPdf('Liberacao_Acesso_Rio.pdf');}" +
                                "else{alert('Ponte Android não disponível.');}" +
                                "return api;" +
                            "};" +
                            "return api;" +
                        "};" +
                        "originalSalvarPDF();" +
                    "}catch(e){alert('Erro ao preparar o PDF: ' + e.message);}" +
                    "finally{" +
                        "window.html2pdf=originalHtml2pdf;" +
                    "}" +
                "};" +
                "window.__zero79NativePdfInstalled=true;" +
                "}())";

        webView.evaluateJavascript(js, null);
    }
    /**
     * Prepara somente o conteúdo do documento oficial para a impressão.
     *
     * IMPORTANTE: não alteramos o tamanho/measure/layout do WebView.
     * Isso evita que a interface do aplicativo fique "espremida" depois do PDF.
     */
    private void preparePageForPrint(final String fileName) {
        if (webView == null) {
            showPdfError("WebView não disponível.");
            return;
        }

        String js = "(function(){" +
                "var doc=document.getElementById('documento-oficial');" +
                "if(!doc){if(window.AndroidPdfBridge)AndroidPdfBridge.printError('Documento oficial não encontrado.');return false;}" +
                "if(document.getElementById('zero79-print-style'))document.getElementById('zero79-print-style').remove();" +
                "var style=document.createElement('style');" +
                "style.id='zero79-print-style';" +
                "style.innerHTML=\"@page{size:A4 portrait;margin:0;}\"+" +
                "\"@media print{html,body{margin:0!important;padding:0!important;background:#fff!important;}#documento-oficial{display:block!important;visibility:visible!important;width:210mm!important;max-width:210mm!important;min-height:297mm!important;margin:0!important;box-shadow:none!important;border:0!important;background:#fff!important;}#documento-oficial *{visibility:visible!important;}}\";" +
                "document.head.appendChild(style);" +
                "var hidden=[];" +
                "var node=doc;" +
                "while(node&&node!==document.body){" +
                    "var parent=node.parentElement;" +
                    "if(!parent)break;" +
                    "for(var i=0;i<parent.children.length;i++){" +
                        "var sibling=parent.children[i];" +
                        "if(sibling!==node){hidden.push({el:sibling,display:sibling.style.display,visibility:sibling.style.visibility});sibling.style.display='none';}" +
                    "}" +
                    "node=parent;" +
                "}" +
                "window.__zero79HiddenElements=hidden;" +
                "document.body.style.background='#fff';" +
                "void doc.offsetHeight;" +
                "return true;" +
                "})()";

        webView.evaluateJavascript(js, value -> {
            if (value == null || "false".equals(value)) {
                showPdfError("Não foi possível preparar o documento para PDF.");
                return;
            }
            webView.postDelayed(() -> startNativePrint(fileName), 250);
        });
    }

    /**
     * Usa o mecanismo oficial de impressão do Android para converter o WebView
     * em PDF. O próprio Android cuida de layout, paginação e escrita do arquivo.
     *
     * O usuário verá a tela de impressão e poderá escolher "Salvar como PDF".
     */
    private void startNativePrint(final String fileName) {
        if (webView == null) {
            finishPrintPreparation();
            showPdfError("WebView não disponível.");
            return;
        }

        try {
            PrintManager printManager =
                    (PrintManager) getSystemService(Context.PRINT_SERVICE);

            if (printManager == null) {
                throw new Exception("Serviço de impressão do Android não disponível.");
            }

            final PrintDocumentAdapter originalAdapter =
                    webView.createPrintDocumentAdapter(fileName);

            PrintDocumentAdapter restoringAdapter = new PrintDocumentAdapter() {
                @Override
                public void onStart() {
                    originalAdapter.onStart();
                }

                @Override
                public void onLayout(
                        PrintAttributes oldAttributes,
                        PrintAttributes newAttributes,
                        CancellationSignal cancellationSignal,
                        LayoutResultCallback callback,
                        Bundle extras) {
                    originalAdapter.onLayout(
                            oldAttributes,
                            newAttributes,
                            cancellationSignal,
                            callback,
                            extras);
                }

                @Override
                public void onWrite(
                        PageRange[] pages,
                        ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal,
                        WriteResultCallback callback) {
                    originalAdapter.onWrite(
                            pages,
                            destination,
                            cancellationSignal,
                            callback);
                }

                @Override
                public void onFinish() {
                    try {
                        originalAdapter.onFinish();
                    } finally {
                        finishPrintPreparation();
                    }
                }
            };

            PrintAttributes attributes = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new PrintAttributes.Resolution(
                            "zero79_pdf", "ZERO79 PDF", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            pdfPrinting = true;
            printManager.print(fileName, restoringAdapter, attributes);

            Toast.makeText(this,
                    "Documento aberto para impressão. Escolha 'Salvar como PDF'.",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            finishPrintPreparation();
            showPdfError("Erro ao abrir a impressão: " + e.getMessage());
        }
    }

    /** Restaura a página depois que o sistema de impressão terminar. */
    private void finishPrintPreparation() {
        pdfPrinting = false;
        restorePageAfterPrint();
    }

    private void showPdfError(String message) {
        runOnUiThread(() -> Toast.makeText(
                MainActivity.this,
                message,
                Toast.LENGTH_LONG).show());
    }

    /**
     * Restaura exatamente os elementos que foram escondidos para a impressão.
     * Não mexemos em measure/layout do WebView, portanto a interface não fica
     * deformada depois do PDF.
     */
    private void restorePageAfterPrint() {
        if (webView == null) return;

        webView.post(() -> webView.evaluateJavascript(
                "(function(){" +
                        "var h=window.__zero79HiddenElements||[];" +
                        "for(var i=0;i<h.length;i++){if(h[i]&&h[i].el){h[i].el.style.display=h[i].display||'';h[i].el.style.visibility=h[i].visibility||'';}}" +
                        "window.__zero79HiddenElements=[];" +
                        "var style=document.getElementById('zero79-print-style');" +
                        "if(style)style.remove();" +
                        "document.body.style.background='';" +
                        "})()",
                null));
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

        if (scheme.equals("http") || scheme.equals("https")) {
            if (host.equals("zero79.netlify.app") ||
                    host.endsWith("firebaseapp.com") ||
                    host.endsWith("googleapis.com") ||
                    host.endsWith("gstatic.com")) {
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
            Toast.makeText(this,
                    "Não foi possível abrir este link.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    /** Ponte JavaScript -> Android. */
    private class PdfBridge {
        @JavascriptInterface
        public void printOfficialPdf(String fileName) {
            runOnUiThread(() -> {
                if (pdfPrinting) {
                    Toast.makeText(MainActivity.this,
                            "Já existe uma geração de PDF em andamento.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                preparePageForPrint(fileName);
            });
        }

        @JavascriptInterface
        public void printError(String message) {
            runOnUiThread(() -> showPdfError(message));
        }
    }
}
