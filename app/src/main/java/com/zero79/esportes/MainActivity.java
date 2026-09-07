package com.zero79.esportes;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
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

import org.json.JSONObject;


public class MainActivity extends Activity {

    private static final String HOME_URL = "https://zero79.netlify.app/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private boolean pdfPrinting = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Permite que o WebView seja desenhado além da área visível.
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

                // Instala a interceptação do botão PDF
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

                    Intent intent = fileChooserParams.createIntent();

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
        // DOWNLOADS
        // ============================================================

        webView.setDownloadListener(new DownloadListener() {

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
        });


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
        // CARREGAMENTO
        // ============================================================

        if (savedInstanceState == null) {

            webView.loadUrl(HOME_URL);

        } else {

            webView.restoreState(savedInstanceState);
        }
    }


    // ================================================================
    // INTERCEPTAÇÃO DO BOTÃO "SALVAR EM PDF"
    // ================================================================

    private void installNativePdfButton() {

        if (webView == null) {
            return;
        }


        /*
         * IMPORTANTE:
         *
         * getAtletasOrdenados() e getAcompanhantesOrdenados()
         * estão dentro do <script type="module"> do site.
         *
         * Portanto, eles não ficam disponíveis diretamente
         * para JavaScript injetado pelo WebView.
         *
         * Por isso NÃO tentamos verificar os atletas aqui.
         *
         * Mantemos a função original salvarPDF() do site.
         * Ela mesma verifica os atletas selecionados.
         *
         * Interceptamos apenas o html2pdf().
         *
         * Quando o site chega em:
         *
         *     .save()
         *
         * chamamos a impressão nativa do Android.
         */

        String js =
                "javascript:(function(){" +

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

                                        "if(window.AndroidPdfBridge){" +

                                            "window.AndroidPdfBridge.printOfficialPdf(" +
                                                "'Liberacao_Acesso_Rio.pdf'" +
                                            ");" +

                                        "}else{" +

                                            "alert('Ponte Android não disponível.');" +

                                        "}" +

                                        "return api;" +

                                    "};" +

                                    "return api;" +

                                "};" +

                                "originalSalvarPDF();" +

                            "}catch(e){" +

                                "alert('Erro ao preparar o PDF: ' + e.message);" +

                            "}finally{" +

                                "window.html2pdf=originalHtml2pdf;" +

                            "}" +

                        "};" +

                        "window.__zero79NativePdfInstalled=true;" +

                "}())";


        webView.evaluateJavascript(js, null);
    }


    // ================================================================
    // PREPARAÇÃO DA PÁGINA PARA IMPRESSÃO
    // ================================================================

    private void preparePageForPrint(final String fileName) {

        if (webView == null) {

            showPdfError(
                    "WebView não disponível."
            );

            return;
        }


        /*
         * A4:
         *
         * 210 mm x 297 mm
         *
         * Margem:
         *
         * 25 mm em cada lado
         *
         * Área útil:
         *
         * 160 mm x 247 mm
         *
         * Isso deixa o conteúdo realmente com 2,5 cm
         * de distância das bordas da folha.
         */

        String printCss =

                "@page{" +
                        "size:A4 portrait;" +
                        "margin:0;" +
                "}" +

                "@media print{" +

                    "html,body{" +
                        "margin:0!important;" +
                        "padding:0!important;" +
                        "background:#fff!important;" +
                        "width:210mm!important;" +
                        "min-width:210mm!important;" +
                    "}" +

                    /*
                     * Documento oficial
                     */
                    "#documento-oficial{" +

                        "display:block!important;" +
                        "visibility:visible!important;" +

                        "width:210mm!important;" +
                        "min-width:210mm!important;" +
                        "max-width:210mm!important;" +

                        "height:297mm!important;" +
                        "min-height:297mm!important;" +
                        "max-height:297mm!important;" +

                        /*
                         * 2,5 cm = 25 mm
                         */
                        "padding:25mm!important;" +

                        "margin:0!important;" +

                        "box-sizing:border-box!important;" +

                        /*
                         * REMOVE COMPLETAMENTE A BORDA
                         */
                        "border:0!important;" +
                        "border-width:0!important;" +
                        "border-style:none!important;" +
                        "outline:0!important;" +
                        "outline-style:none!important;" +

                        /*
                         * REMOVE SOMBRA
                         */
                        "box-shadow:none!important;" +

                        /*
                         * REMOVE CANTOS ARREDONDADOS
                         */
                        "border-radius:0!important;" +

                        "background:#fff!important;" +
                        "color:#000!important;" +

                        /*
                         * Não deixa criar uma segunda página
                         */
                        "overflow:hidden!important;" +
                    "}" +


                    /*
                     * Todos os elementos internos ficam visíveis.
                     */
                    "#documento-oficial *{" +
                        "visibility:visible!important;" +
                    "}" +


                    /*
                     * Texto geral
                     */
                    "#documento-oficial p{" +
                        "font-size:11px!important;" +
                        "line-height:1.20!important;" +
                        "margin-top:0!important;" +
                        "margin-bottom:3px!important;" +
                    "}" +


                    /*
                     * Título
                     */
                    "#documento-oficial h2{" +
                        "font-size:13px!important;" +
                        "line-height:1.10!important;" +
                        "margin-top:6px!important;" +
                        "margin-bottom:8px!important;" +
                    "}" +


                    /*
                     * Tabela
                     */
                    "#documento-oficial table{" +
                        "width:100%!important;" +
                        "margin-top:8px!important;" +
                        "border-collapse:collapse!important;" +
                    "}" +


                    /*
                     * Células da tabela.
                     *
                     * A borda da tabela continua,
                     * porque ela é necessária para separar
                     * os atletas.
                     */
                    "#documento-oficial th," +
                    "#documento-oficial td{" +
                        "padding:3px 4px!important;" +
                        "font-size:11px!important;" +
                        "line-height:1.10!important;" +
                    "}" +


                    /*
                     * Cabeçalho da tabela
                     */
                    "#documento-oficial th{" +
                        "font-size:11px!important;" +
                        "font-weight:bold!important;" +
                    "}" +


                    /*
                     * Área de assinatura/rodapé
                     */
                    "#documento-oficial>div:last-child{" +
                        "margin-top:15px!important;" +
                        "font-size:11px!important;" +
                        "line-height:1.15!important;" +
                    "}" +


                    "#documento-oficial>div:last-child p{" +
                        "font-size:11px!important;" +
                        "line-height:1.15!important;" +
                        "margin-top:0!important;" +
                        "margin-bottom:2px!important;" +
                    "}" +

                "}";


        try {

            /*
             * JSONObject.quote() transforma o CSS em uma string
             * JavaScript válida, evitando problemas com aspas.
             */

            String cssJs =
                    JSONObject.quote(printCss);


            String js =

                    "(function(){" +

                        "var doc=document.getElementById('documento-oficial');" +

                        "if(!doc){" +

                            "if(window.AndroidPdfBridge)" +

                                "AndroidPdfBridge.printError(" +
                                    JSONObject.quote(
                                            "Documento oficial não encontrado."
                                    ) +
                                ");" +

                            "return false;" +
                        "}" +


                        /*
                         * Remove estilo anterior.
                         */
                        "var oldStyle=document.getElementById('zero79-print-style');" +

                        "if(oldStyle){" +
                            "oldStyle.remove();" +
                        "}" +


                        /*
                         * Cria novo CSS de impressão.
                         */
                        "var style=document.createElement('style');" +

                        "style.id='zero79-print-style';" +

                        "style.textContent=" +
                            cssJs +
                        ";" +

                        "document.head.appendChild(style);" +


                        /*
                         * Esconde todos os elementos que estão
                         * fora do documento oficial.
                         */
                        "var hidden=[];" +

                        "var node=doc;" +

                        "while(node&&node!==document.body){" +

                            "var parent=node.parentElement;" +

                            "if(!parent)break;" +

                            "for(var i=0;i<parent.children.length;i++){" +

                                "var sibling=parent.children[i];" +

                                "if(sibling!==node){" +

                                    "hidden.push({" +

                                        "el:sibling," +

                                        "display:sibling.style.display," +

                                        "visibility:sibling.style.visibility" +

                                    "});" +

                                    "sibling.style.display='none';" +

                                "}" +

                            "}" +

                            "node=parent;" +

                        "}" +


                        "window.__zero79HiddenElements=hidden;" +


                        /*
                         * Fundo branco para impressão.
                         */
                        "document.body.style.background='#fff';" +


                        /*
                         * Força atualização do DOM.
                         */
                        "void doc.offsetHeight;" +


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


                        /*
                         * Pequeno atraso para o WebView aplicar
                         * completamente o CSS antes da impressão.
                         */

                        webView.postDelayed(
                                () -> startNativePrint(fileName),
                                300
                        );
                    }
            );

        } catch (Exception e) {

            showPdfError(
                    "Erro ao preparar o PDF: " + e.getMessage()
            );
        }
    }


    // ================================================================
    // IMPRESSÃO NATIVA ANDROID
    // ================================================================

    private void startNativePrint(final String fileName) {

        if (webView == null) {

            finishPrintPreparation();

            showPdfError(
                    "WebView não disponível."
            );

            return;
        }


        try {

            PrintManager printManager =
                    (PrintManager)
                            getSystemService(Context.PRINT_SERVICE);


            if (printManager == null) {

                throw new Exception(
                        "Serviço de impressão do Android não disponível."
                );
            }


            final PrintDocumentAdapter originalAdapter =
                    webView.createPrintDocumentAdapter(
                            fileName
                    );


            /*
             * Adapter intermediário.
             *
             * Ele apenas delega a impressão para o WebView
             * e restaura a página quando o Android termina.
             */

            PrintDocumentAdapter restoringAdapter =
                    new PrintDocumentAdapter() {

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
                                Bundle extras
                        ) {

                            originalAdapter.onLayout(
                                    oldAttributes,
                                    newAttributes,
                                    cancellationSignal,
                                    callback,
                                    extras
                            );
                        }


                        @Override
                        public void onWrite(
                                PageRange[] pages,
                                ParcelFileDescriptor destination,
                                CancellationSignal cancellationSignal,
                                WriteResultCallback callback
                        ) {

                            originalAdapter.onWrite(
                                    pages,
                                    destination,
                                    cancellationSignal,
                                    callback
                            );
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


            /*
             * Configuração A4.
             */

            PrintAttributes attributes =
                    new PrintAttributes.Builder()

                            .setMediaSize(
                                    PrintAttributes.MediaSize.ISO_A4
                            )

                            .setResolution(
                                    new PrintAttributes.Resolution(
                                            "zero79_pdf",
                                            "ZERO79 PDF",
                                            300,
                                            300
                                    )
                            )

                            /*
                             * As margens são controladas pelo CSS:
                             * 25 mm em cada lado.
                             */
                            .setMinMargins(
                                    PrintAttributes.Margins.NO_MARGINS
                            )

                            .build();


            pdfPrinting = true;


            printManager.print(
                    fileName,
                    restoringAdapter,
                    attributes
            );


            Toast.makeText(
                    this,
                    "Documento aberto para impressão. Escolha 'Salvar como PDF'.",
                    Toast.LENGTH_LONG
            ).show();


        } catch (Exception e) {

            finishPrintPreparation();

            showPdfError(
                    "Erro ao abrir a impressão: " +
                            e.getMessage()
            );
        }
    }


    // ================================================================
    // FINALIZAÇÃO
    // ================================================================

    private void finishPrintPreparation() {

        pdfPrinting = false;

        restorePageAfterPrint();
    }


    // ================================================================
    // RESTAURAÇÃO DA PÁGINA
    // ================================================================

    private void restorePageAfterPrint() {

        if (webView == null) {
            return;
        }


        webView.post(() ->

                webView.evaluateJavascript(

                        "(function(){" +

                            /*
                             * Restaura os elementos escondidos.
                             */
                            "var h=window.__zero79HiddenElements||[];" +

                            "for(var i=0;i<h.length;i++){" +

                                "if(h[i]&&h[i].el){" +

                                    "h[i].el.style.display=" +
                                        "h[i].display||'';" +

                                    "h[i].el.style.visibility=" +
                                        "h[i].visibility||'';" +
                                "}" +

                            "}" +


                            "window.__zero79HiddenElements=[];" +


                            /*
                             * Remove CSS de impressão.
                             */
                            "var style=" +
                                "document.getElementById(" +
                                    "'zero79-print-style'" +
                                ");" +

                            "if(style)style.remove();" +


                            /*
                             * Restaura fundo.
                             */
                            "document.body.style.background='';" +

                        "})()",

                        null
                )
        );
    }


    // ================================================================
    // TRATAMENTO DE LINKS
    // ================================================================

    private boolean handleUrl(Uri uri) {

        String scheme =
                uri.getScheme() == null
                        ? ""
                        : uri.getScheme().toLowerCase();


        String host =
                uri.getHost() == null
                        ? ""
                        : uri.getHost().toLowerCase();


        if (scheme.equals("http") ||
                scheme.equals("https")) {


            /*
             * Domínios que devem permanecer dentro do WebView.
             */

            if (host.equals("zero79.netlify.app") ||
                    host.endsWith("firebaseapp.com") ||
                    host.endsWith("googleapis.com") ||
                    host.endsWith("gstatic.com")) {

                return false;
            }


            /*
             * Outros sites abrem externamente.
             */

            openExternal(uri);

            return true;
        }


        if (scheme.equals("mailto") ||
                scheme.equals("tel") ||
                scheme.equals("sms") ||
                scheme.equals("whatsapp") ||
                scheme.equals("intent")) {

            openExternal(uri);

            return true;
        }


        return false;
    }


    // ================================================================
    // ABRIR LINK EXTERNO
    // ================================================================

    private void openExternal(Uri uri) {

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


    // ================================================================
    // INTERNET
    // ================================================================

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
                cm.getNetworkCapabilities(network);


        return capabilities != null &&
                capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                );
    }


    // ================================================================
    // BOTÃO VOLTAR
    // ================================================================

    @Override
    public void onBackPressed() {

        if (webView != null &&
                webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }


    // ================================================================
    // FILE CHOOSER RESULT
    // ================================================================

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


        if (requestCode == FILE_CHOOSER_REQUEST &&
                filePathCallback != null) {

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


    // ================================================================
    // SALVAR ESTADO
    // ================================================================

    @Override
    protected void onSaveInstanceState(
            Bundle outState
    ) {

        if (webView != null) {

            webView.saveState(
                    outState
            );
        }


        super.onSaveInstanceState(
                outState
        );
    }


    // ================================================================
    // PONTE JAVASCRIPT -> ANDROID
    // ================================================================

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


    // ================================================================
    // ERROS
    // ================================================================

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
}
