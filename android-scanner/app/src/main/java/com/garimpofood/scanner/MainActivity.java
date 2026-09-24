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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
                status.setText("Página carregada. Role o menu e toque em Escanear página.");
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

        status.setText("Lendo nomes, preços e promoções visíveis...");

        String script = """
            (function() {
              const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
              const priceRe = /R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})/g;
              const onePriceRe = /R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})/;
              const toNumber = s => Number(s.replace(/\\./g, '').replace(',', '.'));
              const allPrices = text => Array.from((text || '').matchAll(priceRe))
                .map(m => toNumber(m[1]))
                .filter(n => Number.isFinite(n) && n > 0 && n < 1000);

              const badName = /^(adicionar|escolher|ver mais|a partir de|indispon[ií]vel|novo|promo[cç][aã]o)$/i;
              const addon = /\\b(molho|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|borda|extra)\\b/i;

              function isVisible(el) {
                if (!el || !el.getBoundingClientRect) return false;
                const r = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
              }

              function textLines(el) {
                return (el?.innerText || '')
                  .split(/\\n+/)
                  .map(clean)
                  .filter(Boolean);
              }

              function nameFromCard(card) {
                const preferred = Array.from(card.querySelectorAll(
                  'h2,h3,h4,h5,[class*="title"],[class*="name"],[data-testid*="name"],[data-testid*="title"]'
                )).filter(isVisible);

                for (const el of preferred) {
                  const t = clean(el.innerText);
                  if (!t || t.length < 2 || t.length > 110 || t.includes('R$') || badName.test(t)) continue;
                  return t;
                }

                const lines = textLines(card);
                for (const line of lines) {
                  if (line.includes('R$')) continue;
                  if (line.length < 2 || line.length > 110 || badName.test(line)) continue;
                  if (/^\\d+[xX]?$/.test(line)) continue;
                  return line;
                }
                return '';
              }

              function findCard(priceEl) {
                let node = priceEl;
                let fallback = priceEl.parentElement;
                for (let depth = 0; depth < 7 && node; depth++, node = node.parentElement) {
                  const text = clean(node.innerText);
                  if (!text || text.length > 900) continue;
                  const prices = allPrices(text);
                  const name = nameFromCard(node);
                  if (name && prices.length >= 1) {
                    fallback = node;
                    const interactive = node.matches('li,article,a,button,[role="button"]') ||
                      !!node.querySelector('img');
                    if (interactive && text.length < 650) return node;
                  }
                }
                return fallback;
              }

              function originalPriceFromCard(card, promoPrice) {
                let explicit = null;

                for (const el of Array.from(card.querySelectorAll('*'))) {
                  const txt = clean(el.innerText);
                  const m = txt.match(onePriceRe);
                  if (!m) continue;
                  const n = toNumber(m[1]);
                  if (!(n > promoPrice)) continue;

                  const style = getComputedStyle(el);
                  const line = (style.textDecorationLine || '') + ' ' + (style.textDecoration || '');
                  const cls = String(el.className || '');
                  if (/line-through/i.test(line) || /old|original|from|de-price|list-price|strike/i.test(cls)) {
                    explicit = Math.max(explicit || 0, n);
                  }
                }

                if (explicit) return explicit;

                const prices = allPrices(card.innerText);
                const higher = prices.filter(n => n > promoPrice * 1.03);
                if (higher.length >= 1 && prices.length <= 4) {
                  const max = Math.max(...higher);
                  const text = clean(card.innerText);
                  if (/\\b(de|por|off|desconto|promo[cç][aã]o)\\b/i.test(text) || max >= promoPrice * 1.15) {
                    return max;
                  }
                }
                return null;
              }

              const priceElements = Array.from(document.querySelectorAll('body *')).filter(el => {
                if (!isVisible(el)) return false;
                const own = clean(Array.from(el.childNodes)
                  .filter(n => n.nodeType === 3)
                  .map(n => n.textContent)
                  .join(' '));
                return onePriceRe.test(own) && own.length <= 80;
              });

              const byKey = new Map();

              for (const priceEl of priceElements) {
                const own = clean(priceEl.innerText);
                const ownMatch = own.match(onePriceRe);
                if (!ownMatch) continue;
                const ownPrice = toNumber(ownMatch[1]);
                if (!(ownPrice > 0 && ownPrice < 1000)) continue;

                const card = findCard(priceEl);
                if (!card) continue;

                const product = nameFromCard(card);
                if (!product || product.length < 2) continue;

                const prices = allPrices(card.innerText);
                if (!prices.length) continue;

                const promoPrice = Math.min(...prices);
                const originalPrice = originalPriceFromCard(card, promoPrice);
                const normalized = product
                  .toLowerCase()
                  .normalize('NFD')
                  .replace(/[\\u0300-\\u036f]/g, '')
                  .replace(/[^a-z0-9]+/g, ' ')
                  .trim();

                if (!normalized || addon.test(product)) continue;

                const item = {
                  product,
                  price: promoPrice,
                  originalPrice: originalPrice,
                  hasDisplayedDiscount: !!(originalPrice && originalPrice > promoPrice),
                  sourceText: clean(card.innerText).slice(0, 260)
                };

                const old = byKey.get(normalized);
                if (!old || item.price < old.price ||
                    (!!item.originalPrice && !old.originalPrice)) {
                  byKey.set(normalized, item);
                }
              }

              const items = Array.from(byKey.values())
                .sort((a, b) => {
                  const da = a.originalPrice ? (a.originalPrice - a.price) / a.originalPrice : 0;
                  const db = b.originalPrice ? (b.originalPrice - b.price) / b.originalPrice : 0;
                  return db - da || a.price - b.price;
                })
                .slice(0, 80);

              const restaurant =
                clean(document.querySelector('h1')?.innerText) ||
                clean(document.querySelector('h2')?.innerText) ||
                clean(document.title).split('|')[0] ||
                '99Food';

              GarimpoAndroid.report(JSON.stringify({
                scannerVersion: 2,
                sourceUrl: location.href,
                pageTitle: document.title,
                restaurant,
                items
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

    private String readResponse(InputStream input) {
        if (input == null) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            return out.toString();
        } catch (Exception ignored) {
            return "";
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
                        "Nenhum produto válido foi encontrado. Role o menu e tente novamente."
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
            connection.setRequestProperty("X-Garimpo-Client", "android-scanner-v2");

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            String responseBody = readResponse(
                    code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream()
            );

            if (code >= 200 && code < 300) {
                int accepted = count;
                int directDiscounts = 0;
                try {
                    JSONObject response = new JSONObject(responseBody);
                    accepted = response.optInt("accepted", count);
                    directDiscounts = response.optInt("displayedDiscounts", 0);
                } catch (Exception ignored) {}

                final int acceptedFinal = accepted;
                final int discountsFinal = directDiscounts;
                runOnUiThread(() -> status.setText(
                        acceptedFinal + " produtos enviados. " +
                        discountsFinal + " promoções com preço anterior detectadas."
                ));
            } else {
                final String detail = responseBody.length() > 180
                        ? responseBody.substring(0, 180)
                        : responseBody;
                runOnUiThread(() -> status.setText(
                        "Garimpo respondeu HTTP " + code + ". " + detail
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
