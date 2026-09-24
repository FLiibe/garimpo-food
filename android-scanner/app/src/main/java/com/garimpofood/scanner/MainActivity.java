package com.garimpofood.scanner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class MainActivity extends Activity {
    private static final String INGEST_URL =
            "https://sage-starlight-0f4485.netlify.app/.netlify/functions/ingest99";
    private static final String GARIMPO_URL =
            "https://sage-starlight-0f4485.netlify.app";
    private static final String CITY_URL =
            "https://99app.com/99food/sao-paulo/";

    private static final int AUTO_MAX_RESTAURANTS = 20;
    private static final long AUTO_DELAY_MS = 5000;
    private static final long PAGE_SETTLE_MS = 2300;

    private WebView webView;
    private EditText urlInput;
    private TextView status;
    private Button openButton;
    private Button scanButton;
    private Button garimpoButton;
    private Button autoScanButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> autoUrls = new ArrayList<>();

    private boolean autoMode = false;
    private boolean discovering = false;
    private boolean waitingForRestaurantPage = false;
    private boolean cancelled = false;

    private int autoIndex = 0;
    private int autoSuccess = 0;
    private int autoFailed = 0;
    private int autoProducts = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        status = findViewById(R.id.status);
        openButton = findViewById(R.id.openButton);
        scanButton = findViewById(R.id.scanButton);
        garimpoButton = findViewById(R.id.garimpoButton);
        autoScanButton = findViewById(R.id.autoScanButton);

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

                if (autoMode && discovering && isCityPage(url)) {
                    status.setText("Descobrindo restaurantes 99Food em São Paulo...");
                    handler.postDelayed(() -> {
                        if (autoMode && discovering && !cancelled) discoverRestaurantLinks();
                    }, 1600);
                    return;
                }

                if (autoMode && !discovering && waitingForRestaurantPage) {
                    waitingForRestaurantPage = false;
                    int shown = Math.min(autoIndex + 1, autoUrls.size());
                    status.setText(
                            shown + "/" + autoUrls.size() +
                            " — página carregada, lendo cardápio..."
                    );
                    handler.postDelayed(() -> {
                        if (autoMode && !cancelled) scanCurrentPage(true);
                    }, PAGE_SETTLE_MS);
                    return;
                }

                if (!autoMode) {
                    status.setText("Página carregada. Role o menu e toque em Escanear página.");
                }
            }
        });

        webView.addJavascriptInterface(new ScannerBridge(), "GarimpoAndroid");

        openButton.setOnClickListener(v -> {
            if (autoMode) return;
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) webView.loadUrl(url);
        });

        scanButton.setOnClickListener(v -> {
            if (!autoMode) scanCurrentPage(false);
        });

        garimpoButton.setOnClickListener(v -> {
            if (!autoMode) webView.loadUrl(GARIMPO_URL);
        });

        autoScanButton.setOnClickListener(v -> {
            if (autoMode) cancelAutoScan();
            else startAutoScan();
        });

        webView.loadUrl(urlInput.getText().toString());
    }

    private boolean isCityPage(String url) {
        if (url == null) return false;
        String normalized = url.split("\\?")[0];
        return normalized.equals(CITY_URL) || normalized.equals(CITY_URL.substring(0, CITY_URL.length() - 1));
    }

    private void setManualControlsEnabled(boolean enabled) {
        openButton.setEnabled(enabled);
        scanButton.setEnabled(enabled);
        garimpoButton.setEnabled(enabled);
        urlInput.setEnabled(enabled);
    }

    private void startAutoScan() {
        autoMode = true;
        discovering = true;
        waitingForRestaurantPage = false;
        cancelled = false;

        autoUrls.clear();
        autoIndex = 0;
        autoSuccess = 0;
        autoFailed = 0;
        autoProducts = 0;

        setManualControlsEnabled(false);
        autoScanButton.setText("Parar varredura");
        status.setText("Abrindo 99Food São Paulo para descobrir restaurantes...");
        webView.loadUrl(CITY_URL);
    }

    private void cancelAutoScan() {
        cancelled = true;
        autoMode = false;
        discovering = false;
        waitingForRestaurantPage = false;
        autoScanButton.setText("Escanear São Paulo — 20 restaurantes");
        setManualControlsEnabled(true);
        status.setText(
                "Varredura interrompida. " +
                autoSuccess + " restaurantes enviados, " +
                autoProducts + " produtos."
        );
    }

    private void finishAutoScan() {
        autoMode = false;
        discovering = false;
        waitingForRestaurantPage = false;
        autoScanButton.setText("Escanear São Paulo — 20 restaurantes");
        setManualControlsEnabled(true);
        status.setText(
                "Concluído: " + autoSuccess + "/" + autoUrls.size() +
                " restaurantes enviados, " + autoProducts +
                " produtos, " + autoFailed +
                " falhas. Toque em Ver Garimpo."
        );
    }

    private void discoverRestaurantLinks() {
        String script = """
            (async function() {
              const sleep = ms => new Promise(r => setTimeout(r, ms));

              for (let i = 0; i < 7; i++) {
                window.scrollBy(0, Math.max(650, window.innerHeight * 0.85));
                await sleep(420);
              }

              await sleep(500);

              const seen = new Set();
              const links = [];

              for (const a of Array.from(document.querySelectorAll('a[href]'))) {
                let u;
                try { u = new URL(a.href, location.href); } catch { continue; }

                if (u.hostname !== '99app.com' && u.hostname !== 'www.99app.com') continue;

                const cleanPath = u.pathname.replace(/\\/+/g, '/');
                if (!/^\\/99food\\/sao-paulo\\/[^/]+\\/\\d+\\/?$/.test(cleanPath)) continue;

                const normalized = 'https://99app.com' +
                  (cleanPath.endsWith('/') ? cleanPath : cleanPath + '/');

                if (seen.has(normalized)) continue;
                seen.add(normalized);
                links.push(normalized);

                if (links.length >= 40) break;
              }

              GarimpoAndroid.reportRestaurantLinks(JSON.stringify(links));
            })();
            """;

        webView.evaluateJavascript(script, null);
    }

    private void scanNextRestaurant() {
        if (!autoMode || cancelled) return;

        if (autoIndex >= autoUrls.size()) {
            finishAutoScan();
            return;
        }

        String url = autoUrls.get(autoIndex);
        waitingForRestaurantPage = true;

        status.setText(
                (autoIndex + 1) + "/" + autoUrls.size() +
                " — abrindo restaurante..."
        );

        webView.loadUrl(url);
    }

    private String buildScanScript(boolean automatic) {
        String preScroll = automatic
                ? """
                  const sleep = ms => new Promise(r => setTimeout(r, ms));
                  window.scrollTo(0, 0);
                  await sleep(250);
                  for (let i = 0; i < 6; i++) {
                    window.scrollBy(0, Math.max(550, window.innerHeight * 0.75));
                    await sleep(360);
                  }
                  await sleep(300);
                  """
                : "";

        String callback = automatic ? "reportAuto" : "report";

        return """
            (async function() {
              __PRE_SCROLL__

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
                return r.width > 0 && r.height > 0 &&
                  style.display !== 'none' && style.visibility !== 'hidden';
              }

              function textLines(el) {
                return (el?.innerText || '')
                  .split(/\\n+/)
                  .map(clean)
                  .filter(Boolean);
              }

              function nameFromCard(card) {
                const preferred = Array.from(card.querySelectorAll(
                  'h2,h3,h4,h5,[class*="title"],[class*="name"],' +
                  '[data-testid*="name"],[data-testid*="title"]'
                )).filter(isVisible);

                for (const el of preferred) {
                  const t = clean(el.innerText);
                  if (!t || t.length < 2 || t.length > 110 ||
                      t.includes('R$') || badName.test(t)) continue;
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
                    const interactive =
                      node.matches('li,article,a,button,[role="button"]') ||
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
                  const line =
                    (style.textDecorationLine || '') + ' ' +
                    (style.textDecoration || '');
                  const cls = String(el.className || '');

                  if (/line-through/i.test(line) ||
                      /old|original|from|de-price|list-price|strike/i.test(cls)) {
                    explicit = Math.max(explicit || 0, n);
                  }
                }

                if (explicit) return explicit;

                const prices = allPrices(card.innerText);
                const higher = prices.filter(n => n > promoPrice * 1.03);

                if (higher.length >= 1 && prices.length <= 4) {
                  const max = Math.max(...higher);
                  const text = clean(card.innerText);

                  if (/\\b(de|por|off|desconto|promo[cç][aã]o)\\b/i.test(text) ||
                      max >= promoPrice * 1.15) {
                    return max;
                  }
                }

                return null;
              }

              const priceElements =
                Array.from(document.querySelectorAll('body *')).filter(el => {
                  if (!isVisible(el)) return false;

                  const own = clean(
                    Array.from(el.childNodes)
                      .filter(n => n.nodeType === 3)
                      .map(n => n.textContent)
                      .join(' ')
                  );

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
                  hasDisplayedDiscount: !!(
                    originalPrice && originalPrice > promoPrice
                  )
                };

                const old = byKey.get(normalized);

                if (!old ||
                    item.price < old.price ||
                    (!!item.originalPrice && !old.originalPrice)) {
                  byKey.set(normalized, item);
                }
              }

              const items = Array.from(byKey.values())
                .sort((a, b) => {
                  const da = a.originalPrice
                    ? (a.originalPrice - a.price) / a.originalPrice
                    : 0;
                  const db = b.originalPrice
                    ? (b.originalPrice - b.price) / b.originalPrice
                    : 0;

                  return db - da || a.price - b.price;
                })
                .slice(0, 80);

              const restaurant =
                clean(document.querySelector('h1')?.innerText) ||
                clean(document.querySelector('h2')?.innerText) ||
                clean(document.title).split('|')[0] ||
                '99Food';

              GarimpoAndroid.__CALL__(JSON.stringify({
                scannerVersion: 4,
                sourceUrl: location.href,
                pageTitle: document.title,
                restaurant,
                items
              }));
            })();
            """
                .replace("__PRE_SCROLL__", preScroll)
                .replace("__CALL__", callback);
    }

    private void scanCurrentPage(boolean automatic) {
        String current = webView.getUrl();

        if (current == null || !current.contains("99app.com/99food/")) {
            if (automatic) {
                autoFailed++;
                autoIndex++;
                handler.postDelayed(this::scanNextRestaurant, AUTO_DELAY_MS);
            } else {
                status.setText("Abra primeiro uma página de restaurante 99Food.");
            }
            return;
        }

        if (!automatic) {
            status.setText("Lendo nomes, preços e promoções visíveis...");
        }

        webView.evaluateJavascript(buildScanScript(automatic), null);
    }

    private class ScannerBridge {
        @JavascriptInterface
        public void reportRestaurantLinks(String payload) {
            if (!autoMode || cancelled) return;

            try {
                JSONArray links = new JSONArray(payload);
                LinkedHashSet<String> unique = new LinkedHashSet<>();

                for (int i = 0; i < links.length(); i++) {
                    String u = links.optString(i, "");
                    if (!u.contains("99app.com/99food/sao-paulo/")) continue;
                    unique.add(u);
                    if (unique.size() >= AUTO_MAX_RESTAURANTS) break;
                }

                autoUrls.clear();
                autoUrls.addAll(unique);

                runOnUiThread(() -> {
                    if (!autoMode || cancelled) return;

                    if (autoUrls.isEmpty()) {
                        autoMode = false;
                        discovering = false;
                        autoScanButton.setText("Escanear São Paulo — 20 restaurantes");
                        setManualControlsEnabled(true);
                        status.setText(
                                "Nenhum restaurante foi encontrado na página de São Paulo."
                        );
                        return;
                    }

                    discovering = false;
                    autoIndex = 0;
                    status.setText(
                            autoUrls.size() +
                            " restaurantes encontrados. Iniciando varredura..."
                    );

                    handler.postDelayed(
                            MainActivity.this::scanNextRestaurant,
                            1200
                    );
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    autoMode = false;
                    discovering = false;
                    autoScanButton.setText("Escanear São Paulo — 20 restaurantes");
                    setManualControlsEnabled(true);
                    status.setText(
                            "Falha ao ler a lista de restaurantes: " + e.getMessage()
                    );
                });
            }
        }

        @JavascriptInterface
        public void report(String payload) {
            runOnUiThread(() -> status.setText("Enviando dados para Garimpo..."));
            new Thread(() -> {
                UploadResult result = sendPayload(payload, "android-scanner-v4-manual");

                runOnUiThread(() -> {
                    if (result.success) {
                        status.setText(
                                result.accepted + " produtos enviados. " +
                                result.displayedDiscounts +
                                " promoções com preço anterior detectadas."
                        );
                    } else {
                        status.setText("Falha no envio: " + result.error);
                    }
                });
            }).start();
        }

        @JavascriptInterface
        public void reportAuto(String payload) {
            if (!autoMode || cancelled) return;

            new Thread(() -> {
                UploadResult result = sendPayload(payload, "android-scanner-v4-auto");

                runOnUiThread(() -> {
                    if (!autoMode || cancelled) return;

                    if (result.success) {
                        autoSuccess++;
                        autoProducts += result.accepted;
                    } else {
                        autoFailed++;
                    }

                    autoIndex++;

                    if (autoIndex >= autoUrls.size()) {
                        finishAutoScan();
                    } else {
                        status.setText(
                                autoIndex + "/" + autoUrls.size() +
                                " concluídos. Aguardando 5 segundos..."
                        );

                        handler.postDelayed(
                                MainActivity.this::scanNextRestaurant,
                                AUTO_DELAY_MS
                        );
                    }
                });
            }).start();
        }
    }

    private static class UploadResult {
        boolean success;
        int accepted;
        int displayedDiscounts;
        int statusCode;
        String error = "";
    }

    private String readResponse(InputStream input) {
        if (input == null) return "";

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            );

            StringBuilder out = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                out.append(line);
            }

            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private UploadResult sendPayload(String payload, String client) {
        UploadResult result = new UploadResult();
        HttpURLConnection connection = null;

        try {
            JSONObject parsed = new JSONObject(payload);
            JSONArray items = parsed.optJSONArray("items");
            int count = items == null ? 0 : items.length();

            if (count == 0) {
                result.error = "Nenhum produto válido encontrado.";
                return result;
            }

            byte[] body = parsed.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(INGEST_URL);

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            connection.setRequestProperty("X-Garimpo-Client", client);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            result.statusCode = code;

            String responseBody = readResponse(
                    code >= 200 && code < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream()
            );

            if (code >= 200 && code < 300) {
                result.success = true;
                result.accepted = count;

                try {
                    JSONObject response = new JSONObject(responseBody);
                    result.accepted = response.optInt("accepted", count);
                    result.displayedDiscounts =
                            response.optInt("displayedDiscounts", 0);
                } catch (Exception ignored) {
                }

                return result;
            }

            result.error = "HTTP " + code;
            if (!responseBody.isEmpty()) {
                result.error += ": " +
                        responseBody.substring(
                                0,
                                Math.min(160, responseBody.length())
                        );
            }

            return result;
        } catch (Exception e) {
            result.error = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public void onBackPressed() {
        if (autoMode) {
            cancelAutoScan();
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
