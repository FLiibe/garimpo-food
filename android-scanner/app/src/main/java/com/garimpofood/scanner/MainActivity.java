package com.garimpofood.scanner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String INGEST_URL =
            "https://sage-starlight-0f4485.netlify.app/.netlify/functions/ingest99";
    private static final String GARIMPO_URL =
            "https://sage-starlight-0f4485.netlify.app";
    private static final String DEALS_URL =
            "https://sage-starlight-0f4485.netlify.app/.netlify/functions/deals99";
    private static final String REPORT_URL =
            "https://sage-starlight-0f4485.netlify.app/.netlify/functions/report99";
    private static final String CITY_URL =
            "https://99app.com/99food/sao-paulo/";

    private static final int AUTO_MAX_RESTAURANTS = 20;
    private static final long AUTO_DELAY_MS = 700;
    private static final long PAGE_SETTLE_MS = 800;
    private static final int DIRECT_MENU_TEST_LIMIT = 50;

    private WebView webView;
    private TextView status;
    private Button locationButton;
    private Button garimpoButton;
    private Button autoScanButton;
    private Button coverageButton;
    private Button cancelScanButton;
    private View scanOverlay;
    private TextView scanOverlayText;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> autoUrls = new ArrayList<>();
    private final List<String> coveragePageUrls = new ArrayList<>();
    private final LinkedHashSet<String> coverageRestaurantUrls = new LinkedHashSet<>();

    private boolean autoMode = false;
    private boolean coverageMode = false;
    private boolean discovering = false;
    private boolean waitingForRestaurantPage = false;
    private boolean cancelled = false;

    private int autoIndex = 0;
    private int autoSuccess = 0;
    private int autoFailed = 0;
    private int autoProducts = 0;
    private int coveragePageIndex = 0;
    private int directMenuRead = 0;
    private int directMenuCheap = 0;
    private int directMenuFailed = 0;

    private String pendingOfferName = null;
    private String pendingOfferUrl = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        status = findViewById(R.id.status);
        locationButton = findViewById(R.id.locationButton);
        garimpoButton = findViewById(R.id.garimpoButton);
        autoScanButton = findViewById(R.id.autoScanButton);
        coverageButton = findViewById(R.id.coverageButton);
        cancelScanButton = findViewById(R.id.cancelScanButton);
        scanOverlay = findViewById(R.id.scanOverlay);
        scanOverlayText = findViewById(R.id.scanOverlayText);
        prefs = getSharedPreferences("garimpo999", MODE_PRIVATE);

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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("garimpo".equals(uri.getScheme()) && "offer".equals(uri.getHost())) {
                    handleIncomingOffer(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (coverageMode) {
                    status.setText("Lendo página de cobertura...");
                    handler.postDelayed(() -> {
                        if (coverageMode && !cancelled) collectCoveragePage();
                    }, 650);
                    return;
                }

                if (autoMode && discovering && isCityPage(url)) {
                    status.setText("Descobrindo restaurantes 99Food em São Paulo...");
                    updateScanOverlay("Encontrando restaurantes perto da localização definida...");
                    handler.postDelayed(() -> {
                        if (autoMode && discovering && !cancelled) discoverRestaurantLinks();
                    }, 650);
                    return;
                }

                if (autoMode && !discovering && waitingForRestaurantPage) {
                    waitingForRestaurantPage = false;
                    int shown = Math.min(autoIndex + 1, autoUrls.size());
                    status.setText(
                            shown + "/" + autoUrls.size() +
                            " — página carregada, lendo cardápio..."
                    );
                    updateScanOverlay(
                            "Analisando cardápios por completo...\n" +
                            shown + " de " + autoUrls.size() + " restaurantes"
                    );
                    handler.postDelayed(() -> {
                        if (autoMode && !cancelled) scanCurrentPage(true);
                    }, PAGE_SETTLE_MS);
                    return;
                }

                if (url != null && url.startsWith("https://garimpo.local/")) {
                    hideScanOverlay();
                    status.setText("Garimpo 9,99 carregado.");
                    return;
                }

                if (!autoMode && pendingOfferName != null && pendingOfferUrl != null &&
                        url != null && url.contains("99app.com/99food/")) {
                    status.setText("Localizando oferta: " + pendingOfferName);
                    handler.postDelayed(MainActivity.this::locatePendingOffer, 900);
                    return;
                }

                if (!autoMode) {
                    status.setText("Página carregada. Procure itens por até R$9,99.");
                }
            }
        });

        webView.addJavascriptInterface(new ScannerBridge(), "GarimpoAndroid");

        locationButton.setOnClickListener(v -> {
            if (autoMode) return;
            status.setText("Defina sua localização no 99Food. Depois toque em Escanear agora.");
            webView.loadUrl(CITY_URL);
        });

        garimpoButton.setOnClickListener(v -> {
            if (!autoMode) loadGarimpoFeed();
        });

        autoScanButton.setOnClickListener(v -> {
            if (coverageMode) return;
            if (autoMode) cancelAutoScan();
            else startAutoScan();
        });

        coverageButton.setOnClickListener(v -> {
            if (autoMode) return;
            if (coverageMode) cancelCoverageTest();
            else startCoverageTest();
        });

        cancelScanButton.setOnClickListener(v -> {
            if (coverageMode) cancelCoverageTest();
            else cancelAutoScan();
        });

        if (!handleIncomingOffer(getIntent())) {
            loadGarimpoFeed();
        }
    }

    private boolean handleIncomingOffer(Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (data == null || !"garimpo".equals(data.getScheme()) || !"offer".equals(data.getHost())) {
            return false;
        }

        String url = data.getQueryParameter("url");
        String product = data.getQueryParameter("product");
        String appUrl = data.getQueryParameter("appUrl");

        if (url == null || product == null) return false;

        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            if (host == null ||
                    !(host.equals("99app.com") || host.equals("www.99app.com")) ||
                    !parsed.getPath().contains("/99food/")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        if (isValid99AppLink(appUrl) && open99AppLink(appUrl, product.trim())) {
            return true;
        }

        pendingOfferName = product.trim();
        pendingOfferUrl = url;
        status.setText("Abrindo a oferta no 99Food...");
        webView.loadUrl(url);
        return true;
    }

    private boolean isValid99AppLink(String appUrl) {
        if (appUrl == null || appUrl.isEmpty()) return false;
        try {
            Uri u = Uri.parse(appUrl);
            String host = u.getHost();
            String path = u.getPath();
            return "https".equalsIgnoreCase(u.getScheme()) &&
                    "oia.99app.com".equalsIgnoreCase(host) &&
                    path != null && path.startsWith("/dlp9/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean open99AppLink(String appUrl, String product) {
        try {
            Intent intent99 = new Intent(Intent.ACTION_VIEW, Uri.parse(appUrl));
            intent99.addCategory(Intent.CATEGORY_BROWSABLE);
            intent99.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent99);

            pendingOfferName = null;
            pendingOfferUrl = null;
            status.setText("Abrindo " + product + " no app 99...");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String restaurantKeyFromUrl(String url) {
        if (url == null) return null;
        try {
            Uri parsed = Uri.parse(url);
            List<String> segments = parsed.getPathSegments();
            for (int i = segments.size() - 1; i >= 0; i--) {
                String part = segments.get(i);
                if (part != null && part.matches("\\d{12,}")) {
                    return "restaurant_app_" + part;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void rememberRestaurantAppLink(String sourceUrl, String appUrl) {
        if (!isValid99AppLink(appUrl)) return;
        String key = restaurantKeyFromUrl(sourceUrl);
        if (key != null) prefs.edit().putString(key, appUrl).apply();
    }

    private String getRestaurantAppLink(String sourceUrl) {
        String key = restaurantKeyFromUrl(sourceUrl);
        if (key == null) return null;
        String value = prefs.getString(key, null);
        return isValid99AppLink(value) ? value : null;
    }

    private boolean rememberAppLinkFromPayload(String payload) {
        try {
            JSONObject p = new JSONObject(payload);
            String sourceUrl = p.optString("sourceUrl", "");
            String appUrl = p.optString("restaurantAppUrl", "");
            if (!isValid99AppLink(appUrl)) return false;
            rememberRestaurantAppLink(sourceUrl, appUrl);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void locatePendingOffer() {
        if (pendingOfferName == null || pendingOfferName.isEmpty()) return;

        String encoded = JSONObject.quote(pendingOfferName);

        String script =
                "(async function(){" +
                "const needle=" + encoded + ";" +
                "const sleep=ms=>new Promise(r=>setTimeout(r,ms));" +
                "const norm=s=>(s||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'')" +
                ".toLowerCase().replace(/\\s+/g,' ').trim();" +
                "const n=norm(needle);" +
                "function find(){" +
                "const els=Array.from(document.querySelectorAll('h2,h3,h4,h5,[class*=title],[class*=name],article,li,a,button'));" +
                "return els.filter(e=>{const t=norm(e.innerText);return t&&t.includes(n);})" +
                ".sort((a,b)=>(a.innerText||'').length-(b.innerText||'').length)[0]||null;" +
                "}" +
                "window.scrollTo(0,0);await sleep(180);" +
                "let el=find();" +
                "for(let i=0;!el&&i<12;i++){" +
                "window.scrollBy(0,Math.max(500,window.innerHeight*0.75));" +
                "await sleep(240);el=find();" +
                "}" +
                "if(!el){GarimpoAndroid.offerLocateResult(false);return;}" +
                "el.scrollIntoView({behavior:'smooth',block:'center'});" +
                "const oldOutline=el.style.outline;const oldBg=el.style.backgroundColor;" +
                "el.style.outline='4px solid #111';el.style.outlineOffset='4px';el.style.backgroundColor='#fff3b0';" +
                "setTimeout(()=>{el.style.outline=oldOutline;el.style.backgroundColor=oldBg;},8000);" +
                "GarimpoAndroid.offerLocateResult(true);" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingOffer(intent);
    }

    private String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private boolean isEligibleFoodLocal(String product, double price) {
        if (product == null || product.trim().isEmpty()) return false;
        if (!(price > 0 && price <= 9.99)) return false;

        String p = product.toLowerCase(new java.util.Locale("pt", "BR"));

        boolean blocked = p.matches(
                ".*\\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|" +
                "hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|" +
                "borda|extra|condimento|dip|acompanhamento|acompanhamentos)\\b.*"
        );
        if (blocked) return false;

        return p.matches(
                ".*\\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|" +
                "burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|" +
                "bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|" +
                "cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|" +
                "cheeseburger|x[ -]?(burger|salada|bacon|frango))\\b.*"
        );
    }

    private String money(double value) {
        return String.format(new Locale("pt", "BR"), "R$ %.2f", value)
                .replace(".", ",");
    }

    private String categoryLocal(String product) {
        String p = String.valueOf(product).toLowerCase(new Locale("pt", "BR"));
        if (p.matches(".*\\b(marmita|prato|refei[cç][aã]o|yakisoba)\\b.*")) return "Refeições";
        if (p.matches(".*\\b(hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|hot[ -]?dog|cachorro[ -]?quente|cheeseburger|x[ -]?(burger|salada|bacon|frango))\\b.*")) return "Lanches";
        if (p.matches(".*\\b(frango|lingui[cç]a|carne|bife|costela|calabresa)\\b.*")) return "Carnes";
        if (p.matches(".*\\b(pizza|pastel|esfiha|coxinha|tapioca)\\b.*")) return "Pizza/Pastel";
        return "Outros";
    }

    private long scannedAtMillis(String scannedAt) {
        try { return Instant.parse(scannedAt).toEpochMilli(); }
        catch (Exception ignored) { return System.currentTimeMillis(); }
    }

    private String freshnessText(long ageMs) {
        long minutes = Math.max(0, ageMs / 60000L);
        if (minutes < 2) return "Encontrado agora";
        if (minutes < 60) return "Encontrado há " + minutes + " min";
        return "Encontrado há 1h — pode ter mudado";
    }

    private String hiddenKey(String restaurant, String product) {
        String raw = (restaurant + "|" + product).toLowerCase(Locale.ROOT);
        return "hidden_" + Integer.toHexString(raw.hashCode());
    }

    private boolean isLocallyHidden(String restaurant, String product) {
        String key = hiddenKey(restaurant, product);
        long at = prefs.getLong(key, 0L);
        if (at == 0L) return false;
        if (System.currentTimeMillis() - at < 6L * 60L * 60L * 1000L) return true;
        prefs.edit().remove(key).apply();
        return false;
    }

    private String getInstallId() {
        String id = prefs.getString("install_id", null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        prefs.edit().putString("install_id", id).apply();
        return id;
    }

    private void sendMissingReport(String product, String restaurant, String url) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject bodyJson = new JSONObject();
                bodyJson.put("product", product);
                bodyJson.put("restaurant", restaurant);
                bodyJson.put("url", url);
                bodyJson.put("installId", getInstallId());

                byte[] body = bodyJson.toString().getBytes(StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(REPORT_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
                connection.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void loadGarimpoFeed() {
        status.setText("Carregando achados até R$9,99...");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(DEALS_URL + "?t=" + System.currentTimeMillis());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");

                int code = connection.getResponseCode();
                String body = readResponse(
                        code >= 200 && code < 400
                                ? connection.getInputStream()
                                : connection.getErrorStream()
                );
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                JSONObject json = new JSONObject(body);
                JSONArray deals = json.optJSONArray("deals");
                StringBuilder cards = new StringBuilder();
                int shown = 0;
                long now = System.currentTimeMillis();
                long maxAge = 90L * 60L * 1000L;

                if (deals != null) {
                    for (int i = 0; i < deals.length() && shown < 120; i++) {
                        JSONObject d = deals.optJSONObject(i);
                        if (d == null) continue;

                        String product = d.optString("product", "").trim();
                        double price = d.optDouble("price", 0);
                        if (!isEligibleFoodLocal(product, price)) continue;

                        String restaurant = d.optString("restaurant", "99Food");
                        if (isLocallyHidden(restaurant, product)) continue;

                        String sourceUrl = d.optString("url", "");
                        String restaurantUrl = d.optString("offerUrl", sourceUrl);
                        String appUrl = d.optString("restaurantAppUrl", "");
                        if (!isValid99AppLink(appUrl)) appUrl = getRestaurantAppLink(sourceUrl);
                        if (!isValid99AppLink(appUrl)) appUrl = getRestaurantAppLink(restaurantUrl);
                        String scannedAt = d.optString("scannedAt", "");
                        long age = now - scannedAtMillis(scannedAt);
                        if (age > maxAge) continue;

                        String category = categoryLocal(product);
                        String fresh = freshnessText(age);

                        Uri.Builder deepLinkBuilder = new Uri.Builder()
                                .scheme("garimpo")
                                .authority("offer")
                                .appendQueryParameter("url", restaurantUrl)
                                .appendQueryParameter("product", product);
                        if (isValid99AppLink(appUrl)) {
                            deepLinkBuilder.appendQueryParameter("appUrl", appUrl);
                        }
                        Uri deepLink = deepLinkBuilder.build();

                        cards.append("<article class='card' data-price='")
                                .append(price)
                                .append("' data-cat='")
                                .append(htmlEscape(category))
                                .append("' data-product='")
                                .append(htmlEscape(product))
                                .append("' data-restaurant='")
                                .append(htmlEscape(restaurant))
                                .append("' data-url='")
                                .append(htmlEscape(restaurantUrl))
                                .append("'>")
                                .append("<div class='top'><b>")
                                .append(htmlEscape(category))
                                .append("</b><span>")
                                .append(htmlEscape(fresh))
                                .append("</span></div>")
                                .append("<h2>")
                                .append(htmlEscape(product))
                                .append("</h2>")
                                .append("<p class='restaurant'>")
                                .append(htmlEscape(restaurant))
                                .append("</p>")
                                .append("<div class='price'>")
                                .append(htmlEscape(money(price)))
                                .append("</div>")
                                .append("<p class='detail'>Preço encontrado agora no 99Food</p>")
                                .append("<a class='open' href='")
                                .append(htmlEscape(deepLink.toString()))
                                .append("'>Ver oferta</a>")
                                .append("<button class='report' onclick='reportMissing(this)'>Não encontrei esta oferta</button>")
                                .append("</article>");

                        shown++;
                    }
                }

                String empty = shown == 0
                        ? "<div class='empty'>Nenhum achado recente até R$9,99. Faça uma nova varredura.</div>"
                        : "";

                String html = "<!doctype html><html><head>" +
                        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>" +
                        "*{box-sizing:border-box}body{font-family:Arial,sans-serif;background:#f4f2ed;color:#111;margin:0;padding:14px}" +
                        "header{margin-bottom:14px}small{color:#666;font-weight:800;letter-spacing:.12em}.subtitle{color:#555;margin:4px 0 0;font-size:14px}" +
                        ".filters{display:flex;gap:7px;overflow:auto;padding:7px 0}.pill{border:0;border-radius:999px;padding:9px 12px;white-space:nowrap;background:#e7e3da;font-weight:700}.pill.on{background:#111;color:#fff}" +
                        ".card{background:#fff;border:1px solid #ddd8cd;border-radius:18px;padding:16px;margin:0 0 12px}.top{display:flex;justify-content:space-between;gap:10px;font-size:12px;text-transform:uppercase}.top span{color:#666;text-transform:none}" +
                        "h2{font-size:19px;margin:12px 0 4px}.restaurant{color:#666;margin:0 0 12px}.price{font-size:30px;font-weight:800}.detail{font-size:13px;color:#555}" +
                        ".open{display:block;background:#111;color:#fff;text-decoration:none;text-align:center;padding:13px;border-radius:12px;font-weight:800;margin-top:12px}" +
                        ".report{display:block;width:100%;border:0;background:transparent;color:#777;padding:11px 4px 2px;font-size:12px}.empty{background:#fff;padding:24px;border-radius:16px;text-align:center;color:#666}" +
                        "</style></head><body>" +
                        "<header><small>GARIMPO 9,99</small><h1>Comida de verdade por até R$9,99</h1><p class='subtitle'>Achados recentes na localização definida no 99Food.</p></header>" +
                        "<div class='filters priceFilters'>" +
                        "<button class='pill' data-max='.99'>Até R$0,99</button><button class='pill' data-max='4.99'>Até R$4,99</button><button class='pill on' data-max='9.99'>Até R$9,99</button></div>" +
                        "<div class='filters catFilters'>" +
                        "<button class='pill on' data-cat='Todos'>Todos</button><button class='pill' data-cat='Refeições'>Refeições</button><button class='pill' data-cat='Lanches'>Lanches</button><button class='pill' data-cat='Carnes'>Carnes</button><button class='pill' data-cat='Pizza/Pastel'>Pizza/Pastel</button></div>" +
                        "<main id='cards'>" + cards + empty + "</main>" +
                        "<script>" +
                        "let max=9.99,cat='Todos';" +
                        "function apply(){document.querySelectorAll('.card').forEach(c=>{const ok=Number(c.dataset.price)<=max&&(cat==='Todos'||c.dataset.cat===cat);c.style.display=ok?'block':'none'})}" +
                        "document.querySelectorAll('.priceFilters .pill').forEach(b=>b.onclick=()=>{document.querySelectorAll('.priceFilters .pill').forEach(x=>x.classList.remove('on'));b.classList.add('on');max=Number(b.dataset.max);apply()});" +
                        "document.querySelectorAll('.catFilters .pill').forEach(b=>b.onclick=()=>{document.querySelectorAll('.catFilters .pill').forEach(x=>x.classList.remove('on'));b.classList.add('on');cat=b.dataset.cat;apply()});" +
                        "function reportMissing(btn){const c=btn.closest('.card');GarimpoAndroid.reportMissing(JSON.stringify({product:c.dataset.product,restaurant:c.dataset.restaurant,url:c.dataset.url}));c.remove();}" +
                        "</script></body></html>";

                runOnUiThread(() -> webView.loadDataWithBaseURL(
                        "https://garimpo.local/", html, "text/html", "UTF-8", null
                ));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideScanOverlay();
                    status.setText("Falha ao carregar Garimpo: " + e.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private boolean isCityPage(String url) {
        if (url == null) return false;
        String normalized = url.split("\\?")[0];
        return normalized.equals(CITY_URL) || normalized.equals(CITY_URL.substring(0, CITY_URL.length() - 1));
    }

    private void setManualControlsEnabled(boolean enabled) {
        locationButton.setEnabled(enabled);
        garimpoButton.setEnabled(enabled);
        coverageButton.setEnabled(enabled);
    }

    private void showScanOverlay(String message) {
        if (scanOverlayText != null && message != null) {
            scanOverlayText.setText(message);
        }
        if (scanOverlay != null) {
            scanOverlay.setVisibility(View.VISIBLE);
            scanOverlay.bringToFront();
        }
    }

    private void updateScanOverlay(String message) {
        if (scanOverlay != null && scanOverlay.getVisibility() == View.VISIBLE &&
                scanOverlayText != null && message != null) {
            scanOverlayText.setText(message);
        }
    }

    private void hideScanOverlay() {
        if (scanOverlay != null) scanOverlay.setVisibility(View.GONE);
    }

    private void startCoverageTest() {
        coverageMode = true;
        cancelled = false;
        coveragePageIndex = 0;
        directMenuRead = 0;
        directMenuCheap = 0;
        directMenuFailed = 0;
        coveragePageUrls.clear();
        coverageRestaurantUrls.clear();

        String[] categories = {"marmita", "lanches", "pizza"};
        for (String category : categories) {
            for (int page = 1; page <= 4; page++) {
                coveragePageUrls.add(
                        "https://99app.com/99food/sao-paulo/categoria/" +
                        category + "/?page=" + page
                );
            }
        }

        locationButton.setEnabled(false);
        garimpoButton.setEnabled(false);
        autoScanButton.setEnabled(false);
        coverageButton.setText("Cancelar teste");
        showScanOverlay(
                "Mapeando catálogo público do 99Food...\n" +
                "0 de " + coveragePageUrls.size() + " páginas"
        );
        loadNextCoveragePage();
    }

    private void loadNextCoveragePage() {
        if (!coverageMode || cancelled) return;

        if (coveragePageIndex >= coveragePageUrls.size()) {
            finishCoverageTest();
            return;
        }

        updateScanOverlay(
                "Mapeando restaurantes...\n" +
                (coveragePageIndex + 1) + " de " + coveragePageUrls.size() +
                " páginas\n" +
                coverageRestaurantUrls.size() + " restaurantes únicos encontrados"
        );

        webView.loadUrl(coveragePageUrls.get(coveragePageIndex));
    }

    private void collectCoveragePage() {
        String script = """
            (async function() {
              const sleep = ms => new Promise(r => setTimeout(r, ms));
              const found = new Set();

              function collect() {
                for (const a of Array.from(document.querySelectorAll('a[href]'))) {
                  let u;
                  try { u = new URL(a.href, location.href); } catch { continue; }

                  if (u.hostname !== '99app.com' && u.hostname !== 'www.99app.com') continue;

                  const p = u.pathname.replace(/\\/+/g, '/');
                  const m = p.match(/^\\/99food\\/sao-paulo\\/([^/]+)\\/(\\d+)\\/?$/);
                  if (!m) continue;

                  found.add('https://99app.com' + (p.endsWith('/') ? p : p + '/'));
                }
              }

              window.scrollTo(0, 0);
              await sleep(160);
              collect();

              const scroller = document.scrollingElement || document.documentElement;
              let stable = 0;
              let previousCount = found.size;
              let previousHeight = 0;

              for (let i = 0; i < 12; i++) {
                window.scrollBy(0, Math.max(650, window.innerHeight * 0.8));
                await sleep(220);
                collect();

                const height = Math.max(
                  scroller.scrollHeight || 0,
                  document.body?.scrollHeight || 0
                );
                const atBottom =
                  (window.scrollY || scroller.scrollTop || 0) + window.innerHeight >= height - 20;

                if (found.size === previousCount &&
                    Math.abs(height - previousHeight) < 8 &&
                    atBottom) {
                  stable++;
                } else {
                  stable = 0;
                }

                previousCount = found.size;
                previousHeight = height;

                if (stable >= 2) break;
              }

              GarimpoAndroid.reportCoverageLinks(
                JSON.stringify(Array.from(found))
              );
            })();
            """;

        webView.evaluateJavascript(script, null);
    }

    private void finishCoverageTest() {
        if (!coverageMode || cancelled) return;

        int total = coverageRestaurantUrls.size();
        if (total == 0) {
            finishCatalogTest("Nenhum restaurante foi encontrado.");
            return;
        }

        updateScanOverlay(
                total + " restaurantes únicos encontrados.\n" +
                "Agora testando leitura direta de até " +
                Math.min(DIRECT_MENU_TEST_LIMIT, total) + " cardápios..."
        );

        new Thread(this::runDirectMenuTest).start();
    }

    private void runDirectMenuTest() {
        List<String> urls = new ArrayList<>(coverageRestaurantUrls);
        int limit = Math.min(DIRECT_MENU_TEST_LIMIT, urls.size());

        for (int i = 0; i < limit; i++) {
            if (!coverageMode || cancelled) return;

            String restaurantUrl = urls.get(i);
            boolean read = false;
            boolean cheap = false;
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection) new URL(restaurantUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(9000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140 Safari/537.36"
                );
                connection.setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                );
                connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
                connection.setRequestProperty("Referer", CITY_URL);

                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    String html = readResponse(connection.getInputStream());
                    if (html != null && html.length() > 500) {
                        read = true;
                        cheap = htmlHasCheapPrice(html);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            if (read) {
                directMenuRead++;
                if (cheap) directMenuCheap++;
            } else {
                directMenuFailed++;
            }

            final int shown = i + 1;
            runOnUiThread(() -> updateScanOverlay(
                    "Lendo cardápios diretamente...\n" +
                    shown + " de " + limit + "\n" +
                    directMenuRead + " lidos • " +
                    directMenuCheap + " com preço até R$9,99"
            ));

            try {
                Thread.sleep(180);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!coverageMode || cancelled) return;

        runOnUiThread(() -> finishCatalogTest(
                coverageRestaurantUrls.size() + " restaurantes únicos. " +
                directMenuRead + "/" +
                Math.min(DIRECT_MENU_TEST_LIMIT, coverageRestaurantUrls.size()) +
                " cardápios lidos diretamente; " +
                directMenuCheap + " tinham preço até R$9,99."
        ));
    }

    private boolean htmlHasCheapPrice(String html) {
        if (html == null || html.isEmpty()) return false;

        String normalized = html
                .replace("&nbsp;", " ")
                .replace("&#36;", "$")
                .replace("&dollar;", "$");

        Pattern pricePattern = Pattern.compile(
                "R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pricePattern.matcher(normalized);

        while (matcher.find()) {
            try {
                double value = Double.parseDouble(
                        matcher.group(1).replace(".", "").replace(",", ".")
                );
                if (value > 0 && value <= 9.99) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void finishCatalogTest(String message) {
        coverageMode = false;
        coverageButton.setText("Testar catálogo");
        autoScanButton.setEnabled(true);
        locationButton.setEnabled(true);
        garimpoButton.setEnabled(true);
        hideScanOverlay();
        status.setText("Teste concluído: " + message);
    }

    private void cancelCoverageTest() {
        cancelled = true;
        coverageMode = false;
        coverageButton.setText("Testar catálogo");
        autoScanButton.setEnabled(true);
        locationButton.setEnabled(true);
        garimpoButton.setEnabled(true);
        hideScanOverlay();
        status.setText(
                "Teste interrompido: " + coverageRestaurantUrls.size() +
                " restaurantes únicos; " + directMenuRead +
                " cardápios lidos diretamente."
        );
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
        showScanOverlay("Preparando a busca por ofertas até R$9,99...");
        webView.loadUrl(CITY_URL);
    }

    private void cancelAutoScan() {
        cancelled = true;
        autoMode = false;
        discovering = false;
        waitingForRestaurantPage = false;
        autoScanButton.setText("Escanear agora");
        setManualControlsEnabled(true);
        hideScanOverlay();
        status.setText(
                "Varredura interrompida. " +
                autoSuccess + " restaurantes enviados, " +
                autoProducts + " achados."
        );
    }

    private void finishAutoScan() {
        autoMode = false;
        discovering = false;
        waitingForRestaurantPage = false;
        autoScanButton.setText("Escanear agora");
        setManualControlsEnabled(true);
        updateScanOverlay("Busca concluída. Preparando os achados...");
        status.setText(
                "Concluído: " + autoSuccess + "/" + autoUrls.size() +
                " restaurantes, " + autoProducts +
                " achados. Carregando resultados..."
        );
        handler.postDelayed(this::loadGarimpoFeed, 600);
    }

    private void discoverRestaurantLinks() {
        String script = """
            (async function() {
              const sleep = ms => new Promise(r => setTimeout(r, ms));
              const seen = new Set();
              const links = [];

              function collectRestaurantLinks() {
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

                  if (links.length >= 20) return;
                }
              }

              const scroller = document.scrollingElement || document.documentElement;
              window.scrollTo(0, 0);
              await sleep(180);
              collectRestaurantLinks();

              let stablePasses = 0;
              let previousCount = links.length;
              let previousHeight = 0;

              for (let step = 0; step < 18 && links.length < 20; step++) {
                window.scrollBy(0, Math.max(700, window.innerHeight * 0.85));
                await sleep(260);
                collectRestaurantLinks();

                const height = Math.max(
                  scroller.scrollHeight || 0,
                  document.body?.scrollHeight || 0
                );
                const countStable = links.length === previousCount;
                const heightStable = Math.abs(height - previousHeight) < 8;
                const atBottom =
                  (window.scrollY || scroller.scrollTop || 0) + window.innerHeight >= height - 20;

                if (countStable && heightStable && atBottom) stablePasses++;
                else stablePasses = 0;

                previousCount = links.length;
                previousHeight = height;

                if (stablePasses >= 2) break;
              }

              GarimpoAndroid.reportRestaurantLinks(JSON.stringify(links.slice(0, 20)));
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
        updateScanOverlay(
                "Procurando ofertas por até R$9,99...\n" +
                (autoIndex + 1) + " de " + autoUrls.size() + " restaurantes"
        );

        webView.loadUrl(url);
    }

    private String buildScanScript(boolean automatic) {
        String callback = automatic ? "reportAuto" : "report";

        return """
            (async function() {
              const sleep = ms => new Promise(r => setTimeout(r, ms));
              const clean = s => (s || '').replace(/\\s+/g, ' ').trim();
              const priceRe = /R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})/g;
              const onePriceRe = /R\\$\\s*([0-9]{1,4}(?:\\.[0-9]{3})*,[0-9]{2})/;
              const toNumber = s => Number(s.replace(/\\./g, '').replace(',', '.'));
              const allPrices = text => Array.from((text || '').matchAll(priceRe))
                .map(m => toNumber(m[1]))
                .filter(n => Number.isFinite(n) && n > 0 && n < 1000);

              const badName = /^(adicionar|escolher|ver mais|a partir de|indispon[ií]vel|novo|promo[cç][aã]o)$/i;
              const addon = /\\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|borda|extra|condimento|dip|acompanhamento|acompanhamentos)\\b/i;
              const food = /\\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|cheeseburger|x[ -]?(burger|salada|bacon|frango))\\b/i;

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

              const byKey = new Map();

              function collectVisibleItems() {
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
                  if (!(promoPrice > 0 && promoPrice <= 9.99)) continue;
                  if (addon.test(product) || !food.test(product)) continue;

                  const normalized = product
                    .toLowerCase()
                    .normalize('NFD')
                    .replace(/[\\u0300-\\u036f]/g, '')
                    .replace(/[^a-z0-9]+/g, ' ')
                    .trim();

                  if (!normalized) continue;

                  let offerUrl = null;
                  const nearestLink = card.closest('a[href]') || card.querySelector('a[href]');
                  if (nearestLink) {
                    try {
                      const u = new URL(nearestLink.href, location.href);
                      if ((u.hostname === '99app.com' || u.hostname === 'www.99app.com') &&
                          u.pathname.includes('/99food/')) {
                        offerUrl = u.href;
                      }
                    } catch {}
                  }

                  const item = {
                    product,
                    price: promoPrice,
                    offerUrl
                  };

                  const old = byKey.get(normalized);
                  if (!old || item.price < old.price) {
                    byKey.set(normalized, item);
                  } else if (old && !old.offerUrl && item.offerUrl) {
                    old.offerUrl = item.offerUrl;
                  }
                }
              }

              const scroller = document.scrollingElement || document.documentElement;
              window.scrollTo(0, 0);
              await sleep(180);
              collectVisibleItems();

              let stablePasses = 0;
              let previousY = -1;
              let previousHeight = 0;

              for (let step = 0; step < 18; step++) {
                const viewport = Math.max(520, window.innerHeight * 0.72);
                window.scrollBy(0, viewport);
                await sleep(220);
                collectVisibleItems();

                const y = Math.round(window.scrollY || scroller.scrollTop || 0);
                const height = Math.max(
                  scroller.scrollHeight || 0,
                  document.body?.scrollHeight || 0
                );
                const atBottom = y + window.innerHeight >= height - 20;
                const noMovement = Math.abs(y - previousY) < 8;
                const noGrowth = Math.abs(height - previousHeight) < 8;

                if (atBottom && noMovement && noGrowth) stablePasses++;
                else stablePasses = 0;

                previousY = y;
                previousHeight = height;

                if (stablePasses >= 2) break;
              }

              await sleep(180);
              collectVisibleItems();

              const items = Array.from(byKey.values())
                .sort((a, b) => a.price - b.price)
                .slice(0, 80);

              const restaurant =
                clean(document.querySelector('h1')?.innerText) ||
                clean(document.querySelector('h2')?.innerText) ||
                clean(document.title).split('|')[0] ||
                '99Food';

              GarimpoAndroid.__CALL__(JSON.stringify({
                scannerVersion: 80,
                sourceUrl: location.href,
                pageTitle: document.title,
                restaurant,
                items
              }));
            })();
            """
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
            status.setText("Procurando comidas por até R$9,99...");
        }

        webView.evaluateJavascript(buildScanScript(automatic), null);
    }

    private class ScannerBridge {
        @JavascriptInterface
        public void reportMissing(String payload) {
            try {
                JSONObject p = new JSONObject(payload);
                String product = p.optString("product", "");
                String restaurant = p.optString("restaurant", "");
                String url = p.optString("url", "");
                if (!product.isEmpty()) {
                    prefs.edit().putLong(hiddenKey(restaurant, product), System.currentTimeMillis()).apply();
                    sendMissingReport(product, restaurant, url);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void offerLocateResult(boolean found) {
            runOnUiThread(() -> {
                if (found) {
                    status.setText("Oferta localizada e destacada: " + pendingOfferName);
                } else {
                    status.setText("Produto não encontrado automaticamente nesta página.");
                }
                pendingOfferName = null;
                pendingOfferUrl = null;
            });
        }

        @JavascriptInterface
        public void reportCoverageLinks(String payload) {
            if (!coverageMode || cancelled) return;

            try {
                JSONArray links = new JSONArray(payload);
                for (int i = 0; i < links.length(); i++) {
                    String u = links.optString(i, "");
                    if (u.matches("https://99app\\.com/99food/sao-paulo/[^/]+/\\d+/?")) {
                        coverageRestaurantUrls.add(u);
                    }
                }

                runOnUiThread(() -> {
                    if (!coverageMode || cancelled) return;

                    coveragePageIndex++;

                    updateScanOverlay(
                            "Mapeando restaurantes...\n" +
                            coveragePageIndex + " de " + coveragePageUrls.size() +
                            " páginas concluídas\n" +
                            coverageRestaurantUrls.size() + " restaurantes únicos encontrados"
                    );

                    handler.postDelayed(
                            MainActivity.this::loadNextCoveragePage,
                            450
                    );
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    coverageMode = false;
                    coverageButton.setText("Testar cobertura");
                    autoScanButton.setEnabled(true);
                    locationButton.setEnabled(true);
                    garimpoButton.setEnabled(true);
                    hideScanOverlay();
                    status.setText("Falha no teste de cobertura: " + e.getMessage());
                });
            }
        }

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
                        autoScanButton.setText("Escanear agora");
                        setManualControlsEnabled(true);
                        hideScanOverlay();
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
                    autoScanButton.setText("Escanear agora");
                    setManualControlsEnabled(true);
                    hideScanOverlay();
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
                UploadResult result = sendPayload(payload, "garimpo-999-v8-manual");

                runOnUiThread(() -> {
                    if (result.success) {
                        status.setText(
                                result.accepted + " achados até R$9,99 enviados."
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
                UploadResult result = sendPayload(payload, "garimpo-999-v8-auto");

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
                                " concluídos. Continuando..."
                        );
                        updateScanOverlay(
                                "Procurando ofertas por até R$9,99...\n" +
                                autoIndex + " de " + autoUrls.size() + " restaurantes analisados"
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
                result.success = true;
                result.accepted = 0;
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
        if (coverageMode) {
            cancelCoverageTest();
            return;
        }

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
