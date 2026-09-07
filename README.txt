ZERO79 ESPORTES - APP ANDROID

URL carregada pelo aplicativo:
https://zero79.netlify.app/

Arquitetura:
App Android (WebView) -> Netlify -> Firebase

O app NÃO copia o banco. Ele usa o mesmo sistema online e o mesmo Firebase do site.

COMO GERAR O APK NO ANDROID STUDIO
1. Instale o Android Studio.
2. Abra esta pasta como projeto.
3. Aguarde o Gradle Sync terminar.
4. No menu: Build > Build App Bundles or APKs > Build APKs.
5. O APK será criado em app/build/outputs/apk/debug/app-debug.apk

Para uso próprio, o APK debug já pode ser instalado no Android.
Para publicar na Play Store, gere uma versão assinada em:
Build > Generate Signed App Bundle / APK.

Configuração atual:
- Nome: ZERO79 Esportes
- Package: com.zero79.esportes
- URL: https://zero79.netlify.app/
- Firebase continua online via aplicação web
- JavaScript e DOM Storage habilitados
- Cookies habilitados
- Botão Voltar do Android navega no histórico do app
- Upload/seletor de arquivos suportado
- Downloads suportados
- Links externos abrem fora do app
- Orientação vertical
