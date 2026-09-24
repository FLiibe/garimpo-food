import * as cheerio from "cheerio";

const CITY_URL = "https://99app.com/99food/sao-paulo/";
const MAX_RESTAURANTS = 4;
const SEED_RESTAURANTS = [
  ["https://99app.com/99food/sao-paulo/zero-onze-marmitex/5764608153342447373/", "Zero Onze - Marmitex"],
  ["https://99app.com/99food/sao-paulo/dogao-do-renzo-guarulhos/5764608346716639001/", "Dogão do Renzo - Guarulhos"],
  ["https://99app.com/99food/sao-paulo/mr-smash-hamburguer-milkshake-fritas-e-combos/5764608256648154895/", "Mr. Smash"],
  ["https://99app.com/99food/sao-paulo/pizza-mia-vila-galvao-rodizio/5764608076062396164/", "Pizza Mia - Vila Galvão"]
];

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

async function fetchHtml(url, timeoutMs = 8000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: {
        "user-agent": "Mozilla/5.0 (Linux; Android 16; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "accept-language": "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.6",
        "referer": "https://99app.com/99food/sao-paulo/"
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
    let restaurants = [];
    let discoveryMode = "city";
    let cityStatus = null;

    const city = await fetchHtml(CITY_URL);
    cityStatus = city.status;

    if (city.ok) {
      restaurants = restaurantLinks(city.text);
    }

    if (!restaurants.length) {
      restaurants = SEED_RESTAURANTS;
      discoveryMode = city.status === 429 ? "seed-fallback-after-429" : "seed-fallback";
    }

    const deals = [];
    const failures = [];

    for (const [url, name] of restaurants.slice(0, MAX_RESTAURANTS)) {
      try {
        const page = await fetchHtml(url, 8000);
        if (!page.ok) {
          failures.push({ restaurant: name, status: page.status });
        } else {
          deals.push(...extractProducts(page.text, url, name));
        }
      } catch (error) {
        failures.push({
          restaurant: name,
          error: error?.name === "AbortError" ? "timeout" : String(error)
        });
      }
      await wait(900);
    }

    if (!deals.length) {
      return Response.json({
        ok: false,
        stage: "restaurant-fetch",
        error: "Nenhum menu pôde ser lido a partir do servidor Netlify.",
        discoveryMode,
        cityStatus,
        failures,
        deals: [],
        scannedAt
      }, {
        headers: { "cache-control": "public, max-age=60, s-maxage=300" }
      });
    }

    return Response.json({
      ok: true,
      stage: "complete",
      discoveryMode,
      cityStatus,
      restaurantsFound: restaurants.length,
      restaurantsParsed: restaurants.length - failures.length,
      failures,
      deals: deals.sort((a,b) => b.score-a.score || a.price-b.price).slice(0,120),
      scannedAt
    }, {
      headers: { "cache-control": "public, max-age=60, s-maxage=300" }
    });

  } catch (error) {
    return Response.json({
      ok: false,
      stage: "runtime",
      error: error?.name === "AbortError" ? "Timeout ao acessar 99Food" : String(error),
      deals: [],
      scannedAt
    }, {
      headers: { "cache-control": "no-store" }
    });
  }
};