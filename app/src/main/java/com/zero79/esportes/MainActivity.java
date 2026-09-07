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

            // Localiza o documento oficial
            "var doc=document.getElementById('documento-oficial');" +

            // Verifica se existe
            "if(!doc){" +

            "if(window.AndroidPdfBridge){" +
            "AndroidPdfBridge.printError(" +
            "'Documento oficial não encontrado.'" +
            ");" +
            "}" +

            "return false;" +

            "}" +

            // Remove estilo anterior, caso exista
            "var oldStyle=document.getElementById(" +
            "'zero79-print-style'" +
            ");" +

            "if(oldStyle){" +
            "oldStyle.remove();" +
            "}" +

            // Cria estilo temporário
            "var style=document.createElement('style');" +

            "style.id='zero79-print-style';" +

            // CSS de impressão
            "style.textContent=" +

            "'@page{size:A4 portrait;margin:0;}" +

            "@media print{" +

            "html,body{" +
            "margin:0!important;" +
            "padding:0!important;" +
            "background:#fff!important;" +
            "}" +

            "#documento-oficial{" +
            "display:block!important;" +
            "visibility:visible!important;" +
            "width:210mm!important;" +
            "min-height:297mm!important;" +
            "margin:0!important;" +
            "padding:0!important;" +
            "box-shadow:none!important;" +
            "border:0!important;" +
            "}" +

            "#documento-oficial *{" +
            "visibility:visible!important;" +
            "}" +

            "}'" +

            ";" +

            // Adiciona o CSS
            "document.head.appendChild(style);" +

            // Lista dos elementos escondidos
            "var hidden=[];" +

            // Começa no documento oficial
            "var node=doc;" +

            // Sobe pela árvore até o body
            "while(node&&node!==document.body){" +

            "var parent=node.parentElement;" +

            "if(!parent)break;" +

            // Esconde todos os irmãos
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

            // Continua subindo
            "node=parent;" +

            "}" +

            // Guarda os elementos para restaurar depois
            "window.__zero79HiddenElements=hidden;" +

            // Marca a página
            "document.body.setAttribute(" +
            "'data-zero79-printing'," +
            "'1'" +
            ");" +

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

                // Pequeno intervalo para o WebView
                // recalcular o layout
                webView.postDelayed(
                        () -> createNativePdf(fileName),
                        500
                );
            }
    );
}
