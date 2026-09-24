import * as cheerio from "cheerio";

const CITY_URL = "https://99app.com/99food/sao-paulo/";
const MAX_RESTAURANTS = 4;

async function fetchHtml(url, timeoutMs = 7000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: {
        "user-agent": "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36",
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "accept-language": "pt-BR,pt;q=0.9,en;q=0.7",
        "cache-control": "no-cache"
      }
    });
    const text = await response.text();
    return {
      ok: response.ok,
      status: response.status,
      finalUrl: response.url,
      contentType: response.headers.get("content-type") || "",
      text
    };
  } finally {
    clearTimeout(timer);
  }
}

function restaurantLinks(doc) {
  const $ = cheerio.load(doc);
  const out = new Map();
  $("a[href]").each((_, a) => {
    const href = $(a).attr("href");
    if (!href) return;
    let u;
    try { u = new URL(href, "https://99app.com").href; } catch { return; }
    if (/\/99food\/sao-paulo\/[^/]+\/\d+\/?(?:\?.*)?$/.test(u)) {
      const name = $(a).text().replace(/\s+/g, " ").trim() ||
        new URL(u).pathname.split("/").filter(Boolean).at(-2);
      out.set(u.split("?")[0], name);
    }
  });
  return [...out.entries()].slice(0, MAX_RESTAURANTS);
}

function extractProducts(doc, url, restaurant) {
  const $ = cheerio.load(doc);
  const products = [];
  const seen = new Set();

  $("body *").each((_, el) => {
    const direct = $(el).clone().children().remove().end().text().replace(/\s+/g, " ").trim();
    if (!/R\$\s*[\d.]+,\d{2}/.test(direct)) return;

    let node = $(el);
    for (let depth = 0; depth < 4 && node.length; depth++, node = node.parent()) {
      const text = node.text().replace(/\s+/g, " ").trim();
      if (text.length < 6 || text.length > 360) continue;

      const prices = [...text.matchAll(/R\$\s*([\d.]+,\d{2})/g)]
        .map(m => Number(m[1].replace(/\./g, "").replace(",", ".")))
        .filter(n => Number.isFinite(n) && n > 0 && n < 500);

      if (!prices.length) continue;

      const price = Math.min(...prices);
      const product = text
        .replace(/R\$\s*[\d.]+,\d{2}/g, " ")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 160);

      if (!product) break;
      const key = product.toLowerCase() + "|" + price;
      if (seen.has(key)) break;
      seen.add(key);

      products.push({
        product,
        restaurant,
        price,
        score: price <= 1 ? 65 : price <= 5 ? 50 : price <= 10 ? 35 : 20,
        discount: 0,
        referencePrice: null,
        url
      });
      break;
    }
  });

  return products.slice(0, 50);
}

export default async () => {
  const scannedAt = new Date().toISOString();

  try {
    const city = await fetchHtml(CITY_URL);

    if (!city.ok) {
      return Response.json({
        ok: false,
        stage: "city-fetch",
        error: `99Food respondeu HTTP ${city.status}`,
        contentType: city.contentType,
        preview: city.text.slice(0, 180),
        deals: [],
        scannedAt
      });
    }

    const restaurants = restaurantLinks(city.text);

    if (!restaurants.length) {
      return Response.json({
        ok: false,
        stage: "restaurant-discovery",
        error: "A página abriu, mas nenhum restaurante foi encontrado no HTML.",
        cityBytes: city.text.length,
        cityTitle: cheerio.load(city.text)("title").text().trim().slice(0, 120),
        deals: [],
        scannedAt
      });
    }

    const results = await Promise.allSettled(
      restaurants.map(async ([url, name]) => {
        const page = await fetchHtml(url, 6000);
        if (!page.ok) throw new Error(`HTTP ${page.status}`);
        return extractProducts(page.text, url, name);
      })
    );

    const deals = results.flatMap(r => r.status === "fulfilled" ? r.value : []);
    const failures = results.filter(r => r.status === "rejected").map(r => String(r.reason));

    return Response.json({
      ok: true,
      stage: "complete",
      restaurantsFound: restaurants.length,
      restaurantsParsed: results.length - failures.length,
      failures,
      deals: deals.sort((a,b) => b.score-a.score || a.price-b.price).slice(0,120),
      scannedAt
    }, { headers: { "cache-control": "no-store" } });

  } catch (error) {
    return Response.json({
      ok: false,
      stage: "runtime",
      error: error?.name === "AbortError" ? "Timeout ao acessar 99Food" : String(error),
      deals: [],
      scannedAt
    });
  }
};