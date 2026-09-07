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
import android.provider.MediaStore;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.view.View;
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

    private static final String HOME_URL =
            "https://zero79.netlify.app/";

    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;

    private ValueCallback<Uri[]> filePathCallback;

    private File pdfTempFile;

    private boolean pdfPrinting = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * Permite que o WebView seja desenhado além da área visível.
         */
        WebView.enableSlowWholeDocumentDraw();

        webView = new WebView(this);

        setContentView(webView);


        /*
         * CONFIGURAÇÕES DO WEBVIEW
         */

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            );
        }


        /*
         * COOKIES / FIREBASE
         */

        CookieManager.getInstance().setAcceptCookie(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance()
                    .setAcceptThirdPartyCookies(webView, true);
        }


        /*
         * PONTE JAVASCRIPT -> ANDROID
         */

        webView.addJavascriptInterface(
                new PdfBridge(),
                "AndroidPdfBridge"
        );


        /*
         * NAVEGAÇÃO
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
                 * Instala a interceptação do PDF depois
                 * que o site estiver carregado.
                 */
                installNativePdfButton();
            }
        });


        /*
         * UPLOAD DE ARQUIVOS
         */

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {

                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback
                            .onReceiveValue(null);
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
         * DOWNLOADS
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
         * INTERNET
         */

        if (!isOnline()) {

            Toast.makeText(
                    this,
                    "Sem conexão com a internet. O ZERO79 precisa estar online para acessar o Firebase.",
                    Toast.LENGTH_LONG
            ).show();
        }


        /*
         * CARREGA O SITE
         */

        if (savedInstanceState == null) {

            webView.loadUrl(HOME_URL);

        } else {

            webView.restoreState(savedInstanceState);
        }
    }


    /*
     * ============================================================
     * INTERCEPTAÇÃO DO SALVAR EM PDF
     * ============================================================
     */

    private void installNativePdfButton() {

        if (webView == null) {
            return;
        }


        /*
         * Não acessamos getAtletasOrdenados() diretamente.
         *
         * A própria função salvarPDF() do site verifica
         * quais atletas estão selecionados.
         *
         * Nós interceptamos somente o html2pdf().save().
         */

        String js =
                "javascript:(function(){"

                        + "if(window.__zero79NativePdfInstalled)return;"

                        + "if(typeof window.salvarPDF!=='function')return;"

                        + "var originalSalvarPDF=window.salvarPDF;"

                        + "var originalHtml2pdf=window.html2pdf;"

                        + "window.__zero79OriginalSalvarPDF=originalSalvarPDF;"

                        + "window.__zero79OriginalHtml2pdf=originalHtml2pdf;"

                        + "window.salvarPDF=function(){"

                        + "try{"

                        + "window.html2pdf=function(){"

                        + "var api={};"

                        + "api.set=function(){return api;};"

                        + "api.from=function(){return api;};"

                        + "api.save=function(){"

                        + "if(window.AndroidPdfBridge){"

                        + "window.AndroidPdfBridge.printOfficialPdf("

                        + "'Liberacao_Acesso_Rio.pdf'"

                        + ");"

                        + "}else{"

                        + "alert('Ponte Android não disponível.');"

                        + "}"

                        + "return api;"

                        + "};"

                        + "return api;"

                        + "};"

                        + "originalSalvarPDF();"

                        + "}catch(e){"

                        + "alert('Erro ao preparar o PDF: '+e.message);"

                        + "}finally{"

                        + "window.html2pdf=originalHtml2pdf;"

                        + "}"

                        + "};"

                        + "window.__zero79NativePdfInstalled=true;"

                        + "}())";


        webView.evaluateJavascript(
                js,
                null
        );
    }


    /*
     * ============================================================
     * PREPARA O DOCUMENTO
     * ============================================================
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
                "(function(){"

                        + "var doc=document.getElementById('documento-oficial');"

                        + "if(!doc){"

                        + "if(window.AndroidPdfBridge)"

                        + "AndroidPdfBridge.printError("

                        + "'Documento oficial não encontrado.'"

                        + ");"

                        + "return false;"

                        + "}"

                        + "var oldStyle=document.getElementById('zero79-print-style');"

                        + "if(oldStyle)oldStyle.remove();"

                        + "var style=document.createElement('style');"

                        + "style.id='zero79-print-style';"

                        + "style.innerHTML="

                        + "\"html,body{margin:0!important;padding:0!important;background:#fff!important;}\"+"

                        + "\"#documento-oficial{display:block!important;visibility:visible!important;width:210mm!important;min-height:297mm!important;margin:0!important;box-shadow:none!important;border:0!important;background:#fff!important;}\"+"

                        + "\"#documento-oficial *{visibility:visible!important;}\";"

                        + "document.head.appendChild(style);"

                        + "var hidden=[];"

                        + "var node=doc;"

                        + "while(node&&node!==document.body){"

                        + "var parent=node.parentElement;"

                        + "if(!parent)break;"

                        + "for(var i=0;i<parent.children.length;i++){"

                        + "var sibling=parent.children[i];"

                        + "if(sibling!==node){"

                        + "hidden.push({el:sibling,display:sibling.style.display});"

                        + "sibling.style.display='none';"

                        + "}"

                        + "}"

                        + "node=parent;"

                        + "}"

                        + "window.__zero79HiddenElements=hidden;"

                        + "document.body.style.background='#fff';"

                        + "void doc.offsetHeight;"

                        + "return true;"

                        + "})()";


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


                    webView.postDelayed(
                            () -> createNativePdf(fileName),
                            500
                    );
                }
        );
    }


    /*
     * ============================================================
     * GERA O PDF
     * ============================================================
     */

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


        /*
         * Obtém largura e altura do documento.
         */

        final String js =
                "(function(){"

                        + "var d=document.getElementById('documento-oficial');"

                        + "if(!d)return 'ERROR';"

                        + "var r=d.getBoundingClientRect();"

                        + "var w=Math.ceil(Math.max("

                        + "d.scrollWidth,"

                        + "d.offsetWidth,"

                        + "r.width"

                        + "));"

                        + "var h=Math.ceil(Math.max("

                        + "d.scrollHeight,"

                        + "d.offsetHeight,"

                        + "r.height"

                        + "));"

                        + "return String(w)+'|'+String(h);"

                        + "})()";


        webView.evaluateJavascript(
                js,
                value -> {

                    try {

                        if (value == null ||
                                "null".equals(value) ||
                                value.contains("ERROR")) {

                            throw new Exception(
                                    "Documento oficial não encontrado."
                            );
                        }


                        /*
                         * ==================================================
                         * CORREÇÃO IMPORTANTE
                         * ==================================================
                         *
                         * evaluateJavascript retorna uma string JSON.
                         *
                         * Exemplo:
                         *
                         * "443|1130"
                         *
                         * Precisamos retirar as aspas externas.
                         */

                        String dimensions = value.trim();


                        if (dimensions.startsWith("\"") &&
                                dimensions.endsWith("\"")) {

                            dimensions =
                                    dimensions.substring(
                                            1,
                                            dimensions.length() - 1
                                    );
                        }


                        /*
                         * Desfaz possíveis escapes.
                         */

                        dimensions =
                                dimensions.replace(
                                        "\\\"",
                                        "\""
                                );


                        /*
                         * Agora esperamos:
                         *
                         * 443|1130
                         */

                        String[] parts =
                                dimensions.split(
                                        "\\|"
                                );


                        if (parts.length != 2) {

                            throw new Exception(
                                    "Dimensões inválidas retornadas pelo documento: " +
                                            dimensions
                            );
                        }


                        int contentWidth =
                                Integer.parseInt(
                                        parts[0].trim()
                                );


                        int contentHeight =
                                Integer.parseInt(
                                        parts[1].trim()
                                );


                        if (contentWidth <= 0 ||
                                contentHeight <= 0) {

                            throw new Exception(
                                    "Dimensões do documento inválidas."
                            );
                        }


                        /*
                         * ==================================================
                         * A4
                         * ==================================================
                         *
                         * 595 x 842 pontos.
                         */

                        final int pageWidth = 595;

                        final int pageHeight = 842;


                        /*
                         * Escala para caber na largura do A4.
                         *
                         * Mantemos a largura proporcional.
                         */

                        final float scale =
                                (float) pageWidth /
                                        (float) contentWidth;


                        int renderedHeight =
                                Math.max(
                                        1,
                                        Math.round(
                                                contentHeight *
                                                        scale
                                        )
                                );


                        /*
                         * Mede o WebView com o tamanho real
                         * do documento.
                         */

                        int widthSpec =
                                View.MeasureSpec.makeMeasureSpec(
                                        contentWidth,
                                        View.MeasureSpec.EXACTLY
                                );


                        int heightSpec =
                                View.MeasureSpec.makeMeasureSpec(
                                        contentHeight,
                                        View.MeasureSpec.EXACTLY
                                );


                        webView.measure(
                                widthSpec,
                                heightSpec
                        );


                        webView.layout(
                                0,
                                0,
                                contentWidth,
                                contentHeight
                        );


                        /*
                         * Arquivo temporário.
                         */

                        pdfTempFile =
                                File.createTempFile(
                                        "zero79_",
                                        ".pdf",
                                        getCacheDir()
                                );


                        PdfDocument pdf =
                                new PdfDocument();


                        try {

                            /*
                             * Número de páginas.
                             */

                            int pageCount =
                                    Math.max(
                                            1,
                                            (int) Math.ceil(
                                                    (double) renderedHeight /
                                                            (double) pageHeight
                                            )
                                    );


                            for (
                                    int pageNumber = 0;
                                    pageNumber < pageCount;
                                    pageNumber++
                            ) {

                                PdfDocument.PageInfo pageInfo =
                                        new PdfDocument.PageInfo.Builder(
                                                pageWidth,
                                                pageHeight,
                                                pageNumber + 1
                                        ).create();


                                PdfDocument.Page page =
                                        pdf.startPage(
                                                pageInfo
                                        );


                                android.graphics.Canvas canvas =
                                        page.getCanvas();


                                /*
                                 * Fundo branco.
                                 */

                                canvas.drawColor(
                                        Color.WHITE
                                );


                                canvas.save();


                                /*
                                 * Escala.
                                 */

                                canvas.scale(
                                        scale,
                                        scale
                                );


                                /*
                                 * Deslocamento vertical
                                 * para cada página.
                                 */

                                float verticalOffset =
                                        (
                                                pageNumber *
                                                        pageHeight
                                        ) / scale;


                                canvas.translate(
                                        0,
                                        -verticalOffset
                                );


                                /*
                                 * Desenha o WebView.
                                 */

                                webView.draw(
                                        canvas
                                );


                                canvas.restore();


                                pdf.finishPage(
                                        page
                                );
                            }


                            /*
                             * Grava o PDF.
                             */

                            try (
                                    FileOutputStream out =
                                            new FileOutputStream(
                                                    pdfTempFile
                                            )
                            ) {

                                pdf.writeTo(out);
                            }


                        } finally {

                            pdf.close();
                        }


                        /*
                         * Verificação.
                         */

                        if (!pdfTempFile.exists() ||
                                pdfTempFile.length() == 0) {

                            throw new Exception(
                                    "O arquivo PDF foi gerado vazio."
                            );
                        }


                        /*
                         * Salva em Downloads.
                         */

                        savePdfToDownloads(
                                pdfTempFile,
                                fileName
                        );


                        /*
                         * Restaura a página.
                         */

                        restorePageAfterPrint();


                        Toast.makeText(
                                MainActivity.this,
                                "PDF salvo em Downloads/" +
                                        fileName,
                                Toast.LENGTH_LONG
                        ).show();


                        deleteTempPdf();


                        pdfPrinting = false;


                    } catch (Exception e) {

                        finishPdfError(
                                "Erro ao gerar PDF: " +
                                        e.getMessage()
                        );
                    }
                }
        );
    }


    /*
     * ============================================================
     * SALVAR PDF EM DOWNLOADS
     * ============================================================
     */

    private void savePdfToDownloads(
            File source,
            String fileName
    ) throws Exception {


        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q) {


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


                    copy(
                            in,
                            out
                    );
                }


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

                copy(
                        in,
                        out
                );
            }
        }
    }


    /*
     * ============================================================
     * COPIAR ARQUIVO
     * ============================================================
     */

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


    /*
     * ============================================================
     * RESTAURAR PÁGINA
     * ============================================================
     */

    private void restorePageAfterPrint() {

        if (webView == null) {
            return;
        }


        webView.post(
                () -> webView.evaluateJavascript(

                        "(function(){"

                                + "var h=window.__zero79HiddenElements||[];"

                                + "for(var i=0;i<h.length;i++){"

                                + "if(h[i]&&h[i].el){"

                                + "h[i].el.style.display=h[i].display||'';"

                                + "}"

                                + "}"

                                + "window.__zero79HiddenElements=[];"

                                + "var style=document.getElementById("

                                + "'zero79-print-style'"

                                + ");"

                                + "if(style)style.remove();"

                                + "document.body.style.background='';"

                                + "})()",

                        null
                )
        );
    }


    /*
     * ============================================================
     * ERRO
     * ============================================================
     */

    private void finishPdfError(
            String message
    ) {

        restorePageAfterPrint();

        deleteTempPdf();

        pdfPrinting = false;

        showPdfError(
                message
        );
    }


    private void showPdfError(
            String message
    ) {

        runOnUiThread(
                () -> Toast.makeText(
                        MainActivity.this,
                        message,
                        Toast.LENGTH_LONG
                ).show()
        );
    }


    /*
     * ============================================================
     * EXCLUI TEMPORÁRIO
     * ============================================================
     */

    private void deleteTempPdf() {

        if (pdfTempFile != null) {

            try {

                pdfTempFile.delete();

            } catch (Exception ignored) {
            }

            pdfTempFile = null;
        }
    }


    /*
     * ============================================================
     * LINKS
     * ============================================================
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


        if (
                scheme.equals("http") ||
                scheme.equals("https")
        ) {


            if (
                    host.equals(
                            "zero79.netlify.app"
                    )

                    ||

                    host.endsWith(
                            "firebaseapp.com"
                    )

                    ||

                    host.endsWith(
                            "googleapis.com"
                    )

                    ||

                    host.endsWith(
                            "gstatic.com"
                    )
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


    /*
     * ============================================================
     * LINK EXTERNO
     * ============================================================
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


    /*
     * ============================================================
     * INTERNET
     * ============================================================
     */

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


    /*
     * ============================================================
     * BOTÃO VOLTAR
     * ============================================================
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


    /*
     * ============================================================
     * RESULTADO DO UPLOAD
     * ============================================================
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
                requestCode ==
                        FILE_CHOOSER_REQUEST &&

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


    /*
     * ============================================================
     * SALVAR ESTADO
     * ============================================================
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


    /*
     * ============================================================
     * PONTE JAVASCRIPT -> ANDROID
     * ============================================================
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
                    () -> showPdfError(
                            message
                    )
            );
        }
    }
}
