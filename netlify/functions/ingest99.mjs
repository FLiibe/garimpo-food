import { getStore } from "@netlify/blobs";
import { createHash } from "node:crypto";

const MAX_ITEMS = 80;
const MAX_INDEX = 60;
const MAX_PRICE = 9.99;
const ALLOWED_HOSTS = new Set(["99app.com", "www.99app.com"]);

const FOOD_RE = /\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|cheeseburger|x[ -]?(burger|salada|bacon|frango))\b/i;
const BLOCK_RE = /\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|borda|extra|condimento|dip|acompanhamento|acompanhamentos)\b/i;

function cleanText(value, max = 180) {
  return String(value ?? "").replace(/\s+/g, " ").trim().slice(0, max);
}

function numberOrNull(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 && n <= MAX_PRICE ? n : null;
}

function productKey(name) {
  return String(name || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function isEligible(product, price) {
  return Boolean(price && product && FOOD_RE.test(product) && !BLOCK_RE.test(product));
}

export default async (req) => {
  if (req.method !== "POST") {
    return Response.json({ ok:false, error:"Use POST." }, { status:405 });
  }

  try {
    const body = await req.json();
    const sourceUrl = cleanText(body.sourceUrl, 500);
    let parsed;
    try { parsed = new URL(sourceUrl); } catch {
      return Response.json({ ok:false, error:"URL inválida." }, { status:400 });
    }

    if (!ALLOWED_HOSTS.has(parsed.hostname) || !parsed.pathname.includes("/99food/")) {
      return Response.json({ ok:false, error:"Apenas páginas 99Food são aceitas." }, { status:400 });
    }

    const restaurant = cleanText(body.restaurant || body.pageTitle || "99Food", 120);
    let restaurantAppUrl = cleanText(body.restaurantAppUrl, 500) || null;
    if (restaurantAppUrl) {
      try {
        const u = new URL(restaurantAppUrl);
        if (u.protocol !== "https:" || u.hostname !== "oia.99app.com" ||
            !u.pathname.startsWith("/dlp9/")) restaurantAppUrl = null;
      } catch { restaurantAppUrl = null; }
    }
    const rawItems = Array.isArray(body.items) ? body.items.slice(0, MAX_ITEMS) : [];
    const best = new Map();

    for (const x of rawItems) {
      const product = cleanText(x.product || x.name, 120);
      const price = numberOrNull(x.price);
      if (!isEligible(product, price)) continue;

      let offerUrl = cleanText(x.offerUrl, 500) || null;
      if (offerUrl) {
        try {
          const u = new URL(offerUrl);
          if (!ALLOWED_HOSTS.has(u.hostname) || !u.pathname.includes("/99food/")) offerUrl = null;
        } catch { offerUrl = null; }
      }

      const item = { product, price, offerUrl, url:sourceUrl, restaurant };
      const key = productKey(product);
      const old = best.get(key);
      if (!old || item.price < old.price) best.set(key, item);
    }

    const items = [...best.values()].sort((a,b)=>a.price-b.price);
    const store = getStore("garimpo-mobile-scans");
    const pageId = createHash("sha256").update(sourceUrl).digest("hex").slice(0,24);
    const scannedAt = new Date().toISOString();

    await store.setJSON("snapshot/" + pageId, {
      id:pageId,
      sourceUrl,
      restaurant,
      restaurantAppUrl,
      scannedAt,
      scannerVersion:Number(body.scannerVersion || 6),
      client:cleanText(req.headers.get("x-garimpo-client") || "unknown", 50),
      items
    });

    let index = (await store.get("index", {type:"json"}).catch(()=>null)) || [];
    index = index.filter(x=>x.id!==pageId);
    index.unshift({id:pageId, sourceUrl, restaurant, scannedAt});
    await store.setJSON("index", index.slice(0, MAX_INDEX));

    return Response.json({
      ok:true,
      accepted:items.length,
      restaurant,
      maxPrice:MAX_PRICE
    }, {headers:{"cache-control":"no-store"}});
  } catch (error) {
    return Response.json({ok:false,error:String(error)}, {status:500});
  }
};