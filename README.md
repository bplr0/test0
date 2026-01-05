# RectGPT52WebView (Android)

Questa è una piccola app Android con **WebView + selezione a rettangolo + GPT (model: `gpt-5.2`)**.

## Come ottenere l'APK debug (gratis, senza installare Android Studio)

Metodo più semplice: **GitHub Actions** (gratuito).

1. Crea un nuovo repository su GitHub.
2. Carica il contenuto di questa cartella (tutto il progetto).
3. Vai su **Actions** -> workflow **Build Debug APK** -> **Run workflow**.
4. A fine build, scarica l'artefatto **app-debug.apk**.

## Uso

- Apri l'app.
- Premi **Key** e incolla la tua OpenAI API key.
- Incolla l'URL di un Google Form e premi **Go**.
- **Tieni premuto** e trascina per disegnare il rettangolo: il testo dentro viene inviato a GPT e la risposta appare in alto.

Note:
- Login Google: l'app usa un WebView “vero” e accetta cookie di terze parti, quindi l'accesso resta nel WebView.
- L'API key è salvata localmente in `SharedPreferences`.
