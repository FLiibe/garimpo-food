package com.garimpofood.scanner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String INGEST_URL =
            "https://sage-starlight-0f4485.netlify.app/.netlify/functions/ingest99";
    private static final String GARIMPO_URL =
            "https://sage-starlight-0f4485.netlify.app";

    private WebView webView;
    private EditText urlInput;
    private TextView status;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        status = findViewById(R.id.status);
        Button openButton = findViewById(R.id.openButton);
        Button scanButton = findViewById(R.id.scanButton);
        Button garimpoButton = findViewById(R.id.garimpoButton);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 16; SM-A536B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Mobile Safari/537.36"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                urlInput.setText(url);
                status.setText("Página carregada. Toque em Escanear página.");
            }
        });

        webView.addJavascriptInterface(new ScannerBridge(), "GarimpoAndroid");

        openButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) webView.loadUrl(url);
        });

        scanButton.setOnClickListener(v -> scanCurrentPage());
        garimpoButton.setOnClickListener(v -> webView.loadUrl(GARIMPO_URL));

        webView.loadUrl(urlInput.getText().toString());
    }

    private void scanCurrentPage() {
        String current = webView.getUrl();
        if (current == null || !current.contains("99app.com/99food/")) {
            status.setText("Abra primeiro uma página de restaurante 99Food.");
            return;
        }

        status.setText("Lendo produtos e preços visíveis...");

        String script = """
            (function() {
              const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
              const priceRe = /R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})/g;
              const toNumber = s => Number(s.replace(/\\./g,'').replace(',','.'));
              const items = [];
              const seen = new Set();

              const candidates = Array.from(document.querySelectorAll('body *')).filter(el => {
                const own = clean(Array.from(el.childNodes)
                  .filter(n => n.nodeType === 3)
                  .map(n => n.textContent)
                  .join(' '));
                return own.includes('R$') && own.length < 220;
              });

              for (const el of candidates) {
                let node = el;
                for (let depth = 0; depth < 5 && node; depth++, node = node.parentElement) {
                  const text = clean(node.innerText);
                  if (text.length < 5 || text.length > 500) continue;

                  const matches = Array.from(text.matchAll(priceRe));
                  if (!matches.length) continue;
                  const prices = matches.map(m => toNumber(m[1])).filter(n => n > 0 && n < 1000);
                  if (!prices.length) continue;

                  const price = Math.min(...prices);
                  const withoutPrices = clean(text.replace(/R\\$\\s*[0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2}/g, ' '));
                  const parts = withoutPrices.split(/\\n|  +/).map(clean).filter(Boolean);
                  let product = parts[0] || withoutPrices;
                  if (product.length > 160) product = product.slice(0, 160);
                  if (!product || product.length < 2) continue;

                  const key = product.toLowerCase() + '|' + price;
                  if (seen.has(key)) break;
                  seen.add(key);
                  items.push({ product, price });
                  break;
                }
              }

              const restaurant =
                clean(document.querySelector('h1')?.innerText) ||
                clean(document.querySelector('h2')?.innerText) ||
                clean(document.title).split('|')[0] ||
                '99Food';

              GarimpoAndroid.report(JSON.stringify({
                sourceUrl: location.href,
                pageTitle: document.title,
                restaurant,
                items: items.slice(0, 60)
              }));
            })();
            """;

        webView.evaluateJavascript(script, null);
    }

    private class ScannerBridge {
        @JavascriptInterface
        public void report(String payload) {
            runOnUiThread(() -> status.setText("Enviando dados para Garimpo..."));
            new Thread(() -> upload(payload)).start();
        }
    }

    private void upload(String payload) {
        HttpURLConnection connection = null;
        try {
            JSONObject parsed = new JSONObject(payload);
            JSONArray items = parsed.optJSONArray("items");
            int count = items == null ? 0 : items.length();

            if (count == 0) {
                runOnUiThread(() -> status.setText(
                        "Nenhum preço foi encontrado nesta página. Role o menu e tente novamente."
                ));
                return;
            }

            byte[] body = parsed.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(INGEST_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Garimpo-Client", "android-scanner-v1");

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                runOnUiThread(() -> status.setText(
                        count + " itens enviados. Agora toque em Ver Garimpo."
                ));
            } else {
                runOnUiThread(() -> status.setText(
                        "O servidor Garimpo respondeu HTTP " + code + "."
                ));
            }
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("Falha no envio: " + e.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
