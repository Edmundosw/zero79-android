package com.zero79.esportes;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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

        /*
         * Permite que o WebView seja utilizado pelo sistema de impressão
         * sem precisar alterar manualmente o tamanho da tela.
         */
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


        /*
         * Cookies necessários para Firebase.
         */
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(
                webView,
                true
        );


        /*
         * Ponte JavaScript -> Android.
         */
        webView.addJavascriptInterface(
                new PdfBridge(),
                "AndroidPdfBridge"
        );


        /*
         * Navegação.
         */
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

                /*
                 * Reinstala o botão depois que a página termina de carregar.
                 */
                installNativePdfButton();
            }
        });


        /*
         * Upload de arquivos.
         */
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

                MainActivity.this.filePathCallback =
                        filePathCallback;

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


        /*
         * Downloads normais do site.
         */
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
                                            getSystemService(
                                                    DOWNLOAD_SERVICE
                                            );

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


        /*
         * Verifica conexão.
         */
        if (!isOnline()) {

            Toast.makeText(
                    this,
                    "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.",
                    Toast.LENGTH_LONG
            ).show();
        }


        /*
         * Abre o ZERO79.
         */
        if (savedInstanceState == null) {

            webView.loadUrl(HOME_URL);

        } else {

            webView.restoreState(savedInstanceState);
        }
    }


    /**
     * Intercepta a função original salvarPDF() do site.
     *
     * A seleção dos atletas continua sendo feita pelo próprio site.
     *
     * Não acessamos getAtletasOrdenados() diretamente porque ela está
     * dentro do escopo do script module.
     */
    private void installNativePdfButton() {

        if (webView == null) {
            return;
        }


        String js =
                "javascript:(function(){" +

                /*
                 * Evita instalar duas vezes.
                 */
                "if(window.__zero79NativePdfInstalled)return;" +

                /*
                 * Verifica se o site já disponibilizou salvarPDF().
                 */
                "if(typeof window.salvarPDF!=='function')return;" +

                /*
                 * Guarda as funções originais.
                 */
                "var originalSalvarPDF=window.salvarPDF;" +
                "var originalHtml2pdf=window.html2pdf;" +

                "window.__zero79OriginalSalvarPDF=originalSalvarPDF;" +
                "window.__zero79OriginalHtml2pdf=originalHtml2pdf;" +


                /*
                 * Substitui temporariamente salvarPDF().
                 */
                "window.salvarPDF=function(){" +

                    "try{" +

                        /*
                         * Substitui html2pdf somente durante a execução
                         * da função original.
                         */
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


                        /*
                         * Executa a função original.
                         *
                         * É ela que verifica quais atletas estão
                         * selecionados.
                         */
                        "originalSalvarPDF();" +

                    "}catch(e){" +

                        "alert('Erro ao preparar o PDF: ' + e.message);" +

                    "}finally{" +

                        /*
                         * Restaura html2pdf original.
                         */
                        "window.html2pdf=originalHtml2pdf;" +

                    "}" +

                "};" +


                /*
                 * Marca como instalado.
                 */
                "window.__zero79NativePdfInstalled=true;" +

                "}())";


        webView.evaluateJavascript(js, null);
    }



    /**
     * Prepara a página para impressão.
     *
     * IMPORTANTE:
     *
     * Não fazemos webView.measure()
     * Não fazemos webView.layout()
     * Não alteramos o tamanho físico do WebView.
     *
     * Isso evita que o aplicativo fique deformado depois da impressão.
     */
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

                /*
                 * Localiza o documento oficial.
                 */
                "var doc=document.getElementById('documento-oficial');" +


                /*
                 * Documento não encontrado.
                 */
                "if(!doc){" +

                    "if(window.AndroidPdfBridge)" +

                        "AndroidPdfBridge.printError(" +
                        "'Documento oficial não encontrado.'" +
                        ");" +

                    "return false;" +

                "}" +


                /*
                 * Remove estilo anterior.
                 */
                "if(document.getElementById('zero79-print-style'))" +
                    "document.getElementById('zero79-print-style').remove();" +


                /*
                 * Cria CSS exclusivo da impressão.
                 */
                "var style=document.createElement('style');" +

                "style.id='zero79-print-style';" +


                /*
                 * CSS do documento.
                 *
                 * A4:
                 * 210mm x 297mm
                 *
                 * Fonte principal:
                 * 11px
                 *
                 * Sem borda externa.
                 * Sem sombra.
                 */
                "style.innerHTML=" +

                    "\"@page{" +
                        "size:A4 portrait;" +
                        "margin:0;" +
                    "}\"+" +

                    "\"@media print{" +

                        /*
                         * Página.
                         */
                        "html,body{" +
                            "margin:0!important;" +
                            "padding:0!important;" +
                            "background:#fff!important;" +
                        "}" +


                        /*
                         * Documento A4.
                         */
                        "#documento-oficial{" +

                            "display:block!important;" +
                            "visibility:visible!important;" +

                            "width:210mm!important;" +
                            "max-width:210mm!important;" +

                            "height:297mm!important;" +
                            "min-height:297mm!important;" +
                            "max-height:297mm!important;" +

                            /*
                             * Margem interna.
                             */
                            "padding:10mm!important;" +

                            "margin:0!important;" +

                            /*
                             * IMPORTANTE:
                             * retira a moldura da prévia.
                             */
                            "box-shadow:none!important;" +
                            "border:none!important;" +

                            "box-sizing:border-box!important;" +

                            "background:#fff!important;" +
                            "color:#000!important;" +

                            "overflow:hidden!important;" +

                        "}" +


                        /*
                         * Garante que o conteúdo interno apareça.
                         */
                        "#documento-oficial *{" +
                            "visibility:visible!important;" +
                        "}" +


                        /*
                         * Texto normal.
                         */
                        "#documento-oficial p{" +
                            "font-size:11px!important;" +
                            "line-height:1.25!important;" +
                        "}" +


                        /*
                         * Título.
                         */
                        "#documento-oficial h2{" +
                            "font-size:13px!important;" +
                            "line-height:1.15!important;" +
                            "margin-top:8px!important;" +
                            "margin-bottom:10px!important;" +
                        "}" +


                        /*
                         * Tabela.
                         */
                        "#documento-oficial table{" +
                            "width:100%!important;" +
                            "margin-top:10px!important;" +
                            "border-collapse:collapse!important;" +
                        "}" +


                        /*
                         * Células.
                         */
                        "#documento-oficial th," +
                        "#documento-oficial td{" +

                            /*
                             * Espaçamento menor para caber em uma página.
                             */
                            "padding:4px 5px!important;" +

                            "font-size:11px!important;" +

                            "line-height:1.15!important;" +

                            "border:1px solid #000!important;" +

                        "}" +


                        /*
                         * Cabeçalho da tabela.
                         */
                        "#documento-oficial th{" +
                            "font-size:11px!important;" +
                            "font-weight:bold!important;" +
                        "}" +


                        /*
                         * Área de assinatura.
                         */
                        "#documento-oficial>div:last-child{" +

                            "margin-top:20px!important;" +

                            "font-size:11px!important;" +

                            "line-height:1.2!important;" +

                        "}" +


                        /*
                         * Texto da assinatura.
                         */
                        "#documento-oficial>div:last-child p{" +

                            "font-size:11px!important;" +

                            "margin-top:0!important;" +

                            "margin-bottom:2px!important;" +

                        "}" +

                    "}\";";


                /*
                 * Adiciona o CSS ao documento.
                 */
                "document.head.appendChild(style);" +


                /*
                 * Esconde os outros elementos da página.
                 *
                 * Mantemos somente #documento-oficial para impressão.
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


                /*
                 * Guarda os elementos escondidos para restaurar depois.
                 */
                "window.__zero79HiddenElements=hidden;" +


                /*
                 * Fundo branco.
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

                    if (
                            value == null ||
                            "false".equals(value)
                    ) {

                        showPdfError(
                                "Não foi possível preparar o documento para PDF."
                        );

                        return;
                    }


                    /*
                     * Pequeno intervalo para o navegador aplicar
                     * completamente o CSS.
                     */
                    webView.postDelayed(
                            () -> startNativePrint(fileName),
                            250
                    );
                }
        );
    }



    /**
     * Abre o mecanismo nativo de impressão do Android.
     *
     * O Android fica responsável por:
     *
     * - paginação
     * - A4
     * - conversão para PDF
     * - escrita do arquivo
     *
     * Isso evita os problemas encontrados com html2pdf e PdfDocument.
     */
    private void startNativePrint(
            final String fileName
    ) {

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
                            getSystemService(
                                    Context.PRINT_SERVICE
                            );


            if (printManager == null) {

                throw new Exception(
                        "Serviço de impressão do Android não disponível."
                );
            }


            /*
             * Adapter oficial do WebView.
             */
            final PrintDocumentAdapter originalAdapter =
                    webView.createPrintDocumentAdapter(
                            fileName
                    );


            /*
             * Wrapper utilizado apenas para restaurar a página
             * depois que o Android terminar a impressão.
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

                            .setMinMargins(
                                    PrintAttributes.Margins.NO_MARGINS
                            )

                            .build();


            pdfPrinting = true;


            /*
             * Abre a tela nativa:
             *
             * "Salvar como PDF"
             */
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



    /**
     * Finaliza o processo de impressão.
     */
    private void finishPrintPreparation() {

        pdfPrinting = false;

        restorePageAfterPrint();
    }



    /**
     * Exibe erro.
     */
    private void showPdfError(
            String message
    ) {

        runOnUiThread(
                () ->
                        Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show()
        );
    }



    /**
     * Restaura todos os elementos escondidos antes da impressão.
     */
    private void restorePageAfterPrint() {

        if (webView == null) {
            return;
        }


        webView.post(
                () ->
                        webView.evaluateJavascript(

                                "(function(){" +

                                    /*
                                     * Recupera elementos escondidos.
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


                                    /*
                                     * Limpa lista.
                                     */
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



    /**
     * Controle de URLs.
     */
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


        /*
         * URLs HTTP/HTTPS.
         */
        if (
                scheme.equals("http") ||
                scheme.equals("https")
        ) {

            /*
             * Mantém o ZERO79 e Firebase dentro do WebView.
             */
            if (
                    host.equals("zero79.netlify.app") ||
                    host.endsWith("firebaseapp.com") ||
                    host.endsWith("googleapis.com") ||
                    host.endsWith("gstatic.com")
            ) {

                return false;
            }


            /*
             * Outros sites abrem externamente.
             */
            openExternal(uri);

            return true;
        }


        /*
         * Links externos.
         */
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



    /**
     * Abre aplicativo externo.
     */
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



    /**
     * Verifica conexão com internet.
     */
    private boolean isOnline() {

        ConnectivityManager cm =
                (ConnectivityManager)
                        getSystemService(
                                ConnectivityManager.class
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



    /**
     * Botão voltar.
     */
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



    /**
     * Resultado do seletor de arquivos.
     */
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
                    WebChromeClient.FileChooserParams.parseResult(
                            resultCode,
                            data
                    );


            filePathCallback.onReceiveValue(
                    results
            );


            filePathCallback = null;
        }
    }



    /**
     * Salva o estado do WebView.
     */
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



    /**
     * Ponte JavaScript -> Android.
     */
    private class PdfBridge {


        @JavascriptInterface
        public void printOfficialPdf(
                String fileName
        ) {

            runOnUiThread(
                    () -> {

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
                    }
            );
        }


        @JavascriptInterface
        public void printError(
                String message
        ) {

            runOnUiThread(
                    () ->
                            showPdfError(
                                    message
                            )
            );
        }
    }
}
