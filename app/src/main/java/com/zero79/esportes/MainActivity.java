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
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://zero79.netlify.app/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Controle da geração do PDF
    private File pdfTempFile;
    private ParcelFileDescriptor pdfParcelFileDescriptor;
    private PrintDocumentAdapter pdfPrintAdapter;
    private boolean pdfPrinting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ============================================================
        // WEBVIEW
        // ============================================================

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

        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(
                webView,
                true
        );

        // Ponte JavaScript -> Android
        webView.addJavascriptInterface(
                new PdfBridge(),
                "AndroidPdfBridge"
        );

        // ============================================================
        // WEBVIEW CLIENT
        // ============================================================

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                super.onPageFinished(view, url);

                // Substitui somente a função salvarPDF()
                installNativePdfButton();
            }
        });

        // ============================================================
        // FILE CHOOSER
        // ============================================================

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {

                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }

                MainActivity.this.filePathCallback = filePathCallback;

                try {

                    Intent intent =
                            fileChooserParams.createIntent();

                    startActivityForResult(
                            intent,
                            FILE_CHOOSER_REQUEST
                    );

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

        // ============================================================
        // DOWNLOADS NORMAIS DO SITE
        // ============================================================

        webView.setDownloadListener(
                new DownloadListener() {

                    @Override
                    public void onDownloadStart(
                            String url,
                            String userAgent,
                            String contentDisposition,
                            String mimetype,
                            long contentLength
                    ) {

                        try {

                            android.app.DownloadManager.Request request =
                                    new android.app.DownloadManager.Request(
                                            Uri.parse(url)
                                    );

                            request.setMimeType(mimetype);

                            request.addRequestHeader(
                                    "User-Agent",
                                    userAgent
                            );

                            request.setNotificationVisibility(
                                    android.app.DownloadManager.Request
                                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            );

                            request.setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    "ZERO79_arquivo"
                            );

                            android.app.DownloadManager dm =
                                    (android.app.DownloadManager)
                                            getSystemService(DOWNLOAD_SERVICE);

                            dm.enqueue(request);

                            Toast.makeText(
                                    MainActivity.this,
                                    "Download iniciado.",
                                    Toast.LENGTH_SHORT
                            ).show();

                        } catch (Exception e) {

                            openExternal(Uri.parse(url));
                        }
                    }
                }
        );

        // ============================================================
        // INTERNET
        // ============================================================

        if (!isOnline()) {

            Toast.makeText(
                    this,
                    "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.",
                    Toast.LENGTH_LONG
            ).show();
        }

        // ============================================================
        // CARREGAR SITE
        // ============================================================

        if (savedInstanceState == null) {

            webView.loadUrl(HOME_URL);

        } else {

            webView.restoreState(savedInstanceState);
        }
    }

    // =================================================================
    // SUBSTITUI O salvarPDF() DO SITE
    // =================================================================

    private void installNativePdfButton() {

        if (webView == null) {
            return;
        }

        String js =
                "javascript:(function(){" +

                // Evita instalar duas vezes
                "if(window.__zero79NativePdfInstalled)return;" +

                "window.__zero79NativePdfInstalled=true;" +

                // Substitui somente salvarPDF()
                "window.salvarPDF=function(){" +

                "try{" +

                // Pega os atletas marcados
                "var a=(typeof getAtletasOrdenados==='function')" +
                "?getAtletasOrdenados().filter(function(x){return x.treinaHoje;})" +
                ":[];" +

                // Pega acompanhantes marcados
                "var c=(typeof getAcompanhantesOrdenados==='function')" +
                "?getAcompanhantesOrdenados().filter(function(x){return x.treinaHoje;})" +
                ":[];" +

                // Nenhum selecionado
                "if(a.length===0&&c.length===0){" +
                "alert('Selecione ao menos um atleta!');" +
                "return;" +
                "}" +

                // Chama Android
                "if(window.AndroidPdfBridge){" +
                "AndroidPdfBridge.printOfficialPdf('Liberacao_Acesso_Rio.pdf');" +
                "}else{" +
                "alert('Ponte Android não disponível.');" +
                "}" +

                "}catch(e){" +

                "alert('Erro ao preparar o PDF: '+e.message);" +

                "}" +

                "};" +

                "}())";

        webView.evaluateJavascript(
                js,
                null
        );
    }

    // =================================================================
    // PREPARA SOMENTE O DOCUMENTO PARA IMPRESSÃO
    // =================================================================

    private void preparePageForPrint(
            final String fileName
    ) {

        if (webView == null) {

            showPdfError(
                    "WebView não disponível."
            );

            return;
        }

        String js =
                "(function(){" +

                // Localiza o documento que já foi preenchido
                "var doc=document.getElementById('documento-oficial');" +

                // Se não encontrou
                "if(!doc){" +

                "AndroidPdfBridge.printError(" +
                "'Documento oficial não encontrado.'" +
                ");" +

                "return;" +

                "}" +

                // Remove CSS antigo, se existir
                "var oldStyle=" +
                "document.getElementById('zero79-print-style');" +

                "if(oldStyle)oldStyle.remove();" +

                // Cria CSS de impressão
                "var style=document.createElement('style');" +

                "style.id='zero79-print-style';" +

                "style.innerHTML=" +

                "'@page{" +
                "size:A4 portrait;" +
                "margin:0;" +
                "}' +

                '@media print{" +

                'html,body{' +
                'margin:0!important;' +
                'padding:0!important;' +
                'background:#fff!important;' +
                '}' +

                '#documento-oficial{' +
                'display:block!important;' +
                'visibility:visible!important;' +
                'width:210mm!important;' +
                'min-height:297mm!important;' +
                'margin:0!important;' +
                'box-shadow:none!important;' +
                'border:0!important;' +
                '}' +

                '#documento-oficial *{' +
                'visibility:visible!important;' +
                '}' +

                '}'";

        // Adiciona CSS ao documento
        js +=
                ";" +
                "document.head.appendChild(style);" +

                // Guarda elementos escondidos
                "var hidden=[];" +

                // Começa pelo documento oficial
                "var node=doc;" +

                // Sobe até o body
                "while(node&&node!==document.body){" +

                "var parent=node.parentElement;" +

                "if(!parent)break;" +

                // Esconde irmãos do elemento atual
                "for(var i=0;i<parent.children.length;i++){" +

                "var sibling=parent.children[i];" +

                "if(sibling!==node){" +

                "hidden.push({" +
                "el:sibling," +
                "display:sibling.style.display" +
                "});" +

                "sibling.style.display='none';" +

                "}" +

                "}" +

                "node=parent;" +

                "}" +

                // Marca que estamos imprimindo
                "document.body.setAttribute(" +
                "'data-zero79-printing','1'" +
                ");" +

                // Guarda lista para restaurar depois
                "window.__zero79HiddenElements=hidden;" +

                // Fundo branco
                "document.body.style.background='#fff';" +

                "return true;" +

                "})()";

        webView.evaluateJavascript(
                js,
                value -> {

                    if (value == null ||
                            "false".equals(value)) {

                        showPdfError(
                                "Não foi possível preparar o documento para PDF."
                        );

                        return;
                    }

                    // Aguarda o WebView recalcular o layout
                    webView.postDelayed(
                            () -> createNativePdf(fileName),
                            400
                    );
                }
        );
    }

    // =================================================================
    // GERA O PDF NATIVAMENTE
    // =================================================================

    private void createNativePdf(
            final String fileName
    ) {

        if (pdfPrinting) {

            showPdfError(
                    "Já existe uma geração de PDF em andamento."
            );

            return;
        }

        pdfPrinting = true;

        try {

            // ---------------------------------------------------------
            // Arquivo temporário
            // ---------------------------------------------------------

            pdfTempFile = File.createTempFile(
                    "zero79_",
                    ".pdf",
                    getCacheDir()
            );

            // ---------------------------------------------------------
            // Abre arquivo para receber PDF
            // ---------------------------------------------------------

            pdfParcelFileDescriptor =
                    ParcelFileDescriptor.open(
                            pdfTempFile,
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_TRUNCATE
                    );

            // ---------------------------------------------------------
            // ADAPTADOR NATIVO DO ANDROID
            // ---------------------------------------------------------

            pdfPrintAdapter =
                    webView.createPrintDocumentAdapter(
                            fileName
                    );

            // ---------------------------------------------------------
            // A4
            // ---------------------------------------------------------

            PrintAttributes attributes =
                    new PrintAttributes.Builder()

                            .setMediaSize(
                                    PrintAttributes.MediaSize.ISO_A4
                            )

                            .setResolution(
                                    new PrintAttributes.Resolution(
                                            "zero79",
                                            "ZERO79",
                                            300,
                                            300
                                    )
                            )

                            .setMinMargins(
                                    PrintAttributes.Margins.NO_MARGINS
                            )

                            .build();

            // ---------------------------------------------------------
            // LAYOUT
            // ---------------------------------------------------------

            pdfPrintAdapter.onLayout(

                    null,

                    attributes,

                    new CancellationSignal(),

                    new PrintDocumentAdapter.LayoutResultCallback() {

                        @Override
                        public void onLayoutFinished(
                                PrintDocumentInfo info,
                                boolean changed
                        ) {

                            if (pdfPrintAdapter == null ||
                                    pdfParcelFileDescriptor == null) {

                                finishPdfError(
                                        "Adaptador de impressão indisponível."
                                );

                                return;
                            }

                            // -------------------------------------------------
                            // ESCREVE O PDF
                            // -------------------------------------------------

                            pdfPrintAdapter.onWrite(

                                    new PageRange[]{
                                            PageRange.ALL_PAGES
                                    },

                                    pdfParcelFileDescriptor,

                                    new CancellationSignal(),

                                    new PrintDocumentAdapter.WriteResultCallback() {

                                        @Override
                                        public void onWriteFinished(
                                                PageRange[] pages
                                        ) {

                                            finishPdfSuccess(
                                                    fileName
                                            );
                                        }

                                        @Override
                                        public void onWriteFailed(
                                                CharSequence error
                                        ) {

                                            finishPdfError(
                                                    error != null
                                                            ? error.toString()
                                                            : "Falha ao gerar o PDF."
                                            );
                                        }

                                        @Override
                                        public void onWriteCancelled() {

                                            finishPdfError(
                                                    "Geração do PDF cancelada."
                                            );
                                        }
                                    }
                            );
                        }

                        @Override
                        public void onLayoutFailed(
                                CharSequence error
                        ) {

                            finishPdfError(
                                    error != null
                                            ? error.toString()
                                            : "Falha ao preparar o PDF."
                            );
                        }

                        @Override
                        public void onLayoutCancelled() {

                            finishPdfError(
                                    "Preparação do PDF cancelada."
                            );
                        }
                    },

                    null
            );

        } catch (Exception e) {

            finishPdfError(
                    "Erro ao gerar PDF: " + e.getMessage()
            );
        }
    }

    // =================================================================
    // PDF GERADO COM SUCESSO
    // =================================================================

    private void finishPdfSuccess(
            String fileName
    ) {

        try {

            closePdfDescriptor();

            // Verifica se realmente existe conteúdo
            if (pdfTempFile == null ||
                    !pdfTempFile.exists() ||
                    pdfTempFile.length() == 0) {

                throw new Exception(
                        "O arquivo PDF foi gerado vazio."
                );
            }

            // Salva em Downloads
            savePdfToDownloads(
                    pdfTempFile,
                    fileName
            );

            // Restaura a página
            restorePageAfterPrint();

            runOnUiThread(() ->
                    Toast.makeText(
                            MainActivity.this,
                            "PDF salvo em Downloads/" + fileName,
                            Toast.LENGTH_LONG
                    ).show()
            );

        } catch (Exception e) {

            restorePageAfterPrint();

            runOnUiThread(() ->
                    Toast.makeText(
                            MainActivity.this,
                            "Erro ao salvar PDF: " +
                                    e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show()
            );

        } finally {

            deleteTempPdf();

            pdfPrintAdapter = null;

            pdfPrinting = false;
        }
    }

    // =================================================================
    // ERRO NO PDF
    // =================================================================

    private void finishPdfError(
            String message
    ) {

        closePdfDescriptor();

        restorePageAfterPrint();

        deleteTempPdf();

        pdfPrintAdapter = null;

        pdfPrinting = false;

        showPdfError(message);
    }

    private void showPdfError(
            String message
    ) {

        runOnUiThread(() ->
                Toast.makeText(
                        MainActivity.this,
                        message,
                        Toast.LENGTH_LONG
                ).show()
        );
    }

    // =================================================================
    // FECHA ARQUIVO TEMPORÁRIO
    // =================================================================

    private void closePdfDescriptor() {

        if (pdfParcelFileDescriptor != null) {

            try {

                pdfParcelFileDescriptor.close();

            } catch (Exception ignored) {
            }

            pdfParcelFileDescriptor = null;
        }
    }

    // =================================================================
    // APAGA TEMPORÁRIO
    // =================================================================

    private void deleteTempPdf() {

        if (pdfTempFile != null) {

            try {

                pdfTempFile.delete();

            } catch (Exception ignored) {
            }

            pdfTempFile = null;
        }
    }

    // =================================================================
    // SALVA EM DOWNLOADS
    // =================================================================

    private void savePdfToDownloads(
            File source,
            String fileName
    ) throws Exception {

        // -------------------------------------------------------------
        // Android 10+
        // -------------------------------------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    fileName
            );

            values.put(
                    MediaStore.Downloads.MIME_TYPE,
                    "application/pdf"
            );

            values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
            );

            values.put(
                    MediaStore.Downloads.IS_PENDING,
                    1
            );

            Uri uri =
                    getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                    );

            if (uri == null) {

                throw new Exception(
                        "Não foi possível criar o arquivo em Downloads."
                );
            }

            try {

                try (
                        InputStream in =
                                new FileInputStream(source);

                        OutputStream out =
                                getContentResolver()
                                        .openOutputStream(uri)
                ) {

                    if (out == null) {

                        throw new Exception(
                                "Não foi possível abrir o destino do PDF."
                        );
                    }

                    copy(in, out);
                }

                // Libera o arquivo
                ContentValues done =
                        new ContentValues();

                done.put(
                        MediaStore.Downloads.IS_PENDING,
                        0
                );

                getContentResolver().update(
                        uri,
                        done,
                        null,
                        null
                );

            } catch (Exception e) {

                getContentResolver().delete(
                        uri,
                        null,
                        null
                );

                throw e;
            }

        } else {

            // ---------------------------------------------------------
            // Android antigo
            // ---------------------------------------------------------

            File dir =
                    getExternalFilesDir(
                            Environment.DIRECTORY_DOWNLOADS
                    );

            if (dir == null) {

                throw new Exception(
                        "Pasta de Downloads indisponível."
                );
            }

            if (!dir.exists() &&
                    !dir.mkdirs()) {

                throw new Exception(
                        "Não foi possível criar a pasta de Downloads."
                );
            }

            File destination =
                    new File(
                            dir,
                            fileName
                    );

            try (
                    InputStream in =
                            new FileInputStream(source);

                    OutputStream out =
                            new FileOutputStream(destination)
            ) {

                copy(in, out);
            }
        }
    }

    // =================================================================
    // COPIA ARQUIVO
    // =================================================================

    private void copy(
            InputStream in,
            OutputStream out
    ) throws Exception {

        byte[] buffer =
                new byte[8192];

        int read;

        while (
                (read = in.read(buffer)) != -1
        ) {

            out.write(
                    buffer,
                    0,
                    read
            );
        }

        out.flush();
    }

    // =================================================================
    // RESTAURA A PÁGINA
    // =================================================================

    private void restorePageAfterPrint() {

        if (webView == null) {
            return;
        }

        webView.post(() ->
                webView.evaluateJavascript(

                        "(function(){" +

                        "var h=" +
                        "window.__zero79HiddenElements||[];" +

                        // Restaura elementos escondidos
                        "for(var i=0;i<h.length;i++){" +

                        "if(h[i]&&h[i].el){" +

                        "h[i].el.style.display=" +
                        "h[i].display||'';" +

                        "}" +

                        "}" +

                        "window.__zero79HiddenElements=[];" +

                        // Remove CSS de impressão
                        "var style=" +
                        "document.getElementById(" +
                        "'zero79-print-style'" +
                        ");" +

                        "if(style)style.remove();" +

                        // Remove marca
                        "document.body.removeAttribute(" +
                        "'data-zero79-printing'" +
                        ");" +

                        "})()",

                        null
                )
        );
    }

    // =================================================================
    // LINKS
    // =================================================================

    private boolean handleUrl(
            Uri uri
    ) {

        String scheme =
                uri.getScheme() == null
                        ? ""
                        : uri.getScheme().toLowerCase();

        String host =
                uri.getHost() == null
                        ? ""
                        : uri.getHost().toLowerCase();

        if (
                scheme.equals("http") ||
                scheme.equals("https")
        ) {

            if (
                    host.equals("zero79.netlify.app") ||
                    host.endsWith("firebaseapp.com") ||
                    host.endsWith("googleapis.com") ||
                    host.endsWith("gstatic.com")
            ) {

                return false;
            }

            openExternal(uri);

            return true;
        }

        if (
                scheme.equals("mailto") ||
                scheme.equals("tel") ||
                scheme.equals("sms") ||
                scheme.equals("whatsapp") ||
                scheme.equals("intent")
        ) {

            openExternal(uri);

            return true;
        }

        return false;
    }

    private void openExternal(
            Uri uri
    ) {

        try {

            startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            uri
                    )
            );

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Não foi possível abrir este link.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    // =================================================================
    // INTERNET
    // =================================================================

    private boolean isOnline() {

        ConnectivityManager cm =
                (ConnectivityManager)
                        getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );

        if (cm == null) {
            return false;
        }

        Network network =
                cm.getActiveNetwork();

        if (network == null) {
            return false;
        }

        NetworkCapabilities capabilities =
                cm.getNetworkCapabilities(
                        network
                );

        return capabilities != null &&
                capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                );
    }

    // =================================================================
    // BOTÃO VOLTAR
    // =================================================================

    @Override
    public void onBackPressed() {

        if (
                webView != null &&
                webView.canGoBack()
        ) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }

    // =================================================================
    // FILE CHOOSER
    // =================================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (
                requestCode == FILE_CHOOSER_REQUEST &&
                filePathCallback != null
        ) {

            Uri[] results =
                    WebChromeClient.FileChooserParams
                            .parseResult(
                                    resultCode,
                                    data
                            );

            filePathCallback.onReceiveValue(
                    results
            );

            filePathCallback = null;
        }
    }

    // =================================================================
    // ESTADO
    // =================================================================

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {

        if (webView != null) {
            webView.saveState(outState);
        }

        super.onSaveInstanceState(
                outState
        );
    }

    // =================================================================
    // PONTE JAVASCRIPT -> ANDROID
    // =================================================================

    private class PdfBridge {

        @JavascriptInterface
        public void printOfficialPdf(
                String fileName
        ) {

            runOnUiThread(() -> {

                if (pdfPrinting) {

                    Toast.makeText(
                            MainActivity.this,
                            "Já existe uma geração de PDF em andamento.",
                            Toast.LENGTH_SHORT
                    ).show();

                    return;
                }

                preparePageForPrint(
                        fileName
                );
            });
        }

        @JavascriptInterface
        public void printError(
                String message
        ) {

            runOnUiThread(() ->
                    showPdfError(message)
            );
        }
    }
}
