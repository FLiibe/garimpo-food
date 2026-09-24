import { getStore } from "@netlify/blobs";
import { createHash } from "node:crypto";

const MAX_ITEMS = 80;
const MAX_INDEX = 40;
const ALLOWED_HOSTS = new Set(["99app.com", "www.99app.com"]);

function cleanText(value, max = 180) {
  return String(value ?? "").replace(/\s+/g, " ").trim().slice(0, max);
}

function numberOrNull(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 && n < 1000 ? n : null;
}

function median(values) {
  const arr = values.filter(Number.isFinite).sort((a,b)=>a-b);
  if (!arr.length) return null;
  const m = Math.floor(arr.length / 2);
  return arr.length % 2 ? arr[m] : (arr[m-1] + arr[m]) / 2;
}

function productKey(name) {
  return String(name || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function looksLikeAddon(name, sourceText = "") {
  const haystack = `${name || ""} ${sourceText || ""}`;
  if (/^(acompanhamento|acompanhamentos|adicional|adicionais|molho|molhos)$/i.test(String(name || "").trim())) return true;
  return /\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|borda|extra|condimento|dip)\b/i.test(haystack);
}

function scoreItem(item, referencePrice, referenceSource) {
  let score = 12;

  if (item.price <= 1) score += 40;
  else if (item.price <= 3) score += 30;
  else if (item.price <= 5) score += 22;
  else if (item.price <= 10) score += 10;

  const name = item.product.toLowerCase();
  if (/\b(marmita|pizza|hamb[uú]rguer|burger|combo|pastel|a[cç]a[ií]|sushi|prato|refei[cç][aã]o|lanche|frango|carne)\b/i.test(name)) score += 14;
  if (looksLikeAddon(name)) score -= 55;

  let discount = 0;
  if (referencePrice && referencePrice > item.price) {
    discount = ((referencePrice - item.price) / referencePrice) * 100;
    score += Math.min(42, discount * 0.48);
    if (referenceSource === "displayed") score += 5;
  }

  return {
    ...item,
    referencePrice: referencePrice || null,
    referenceSource: referenceSource || null,
    discount,
    score: Math.max(0, Math.min(100, Math.round(score)))
  };
}

export default async (req) => {
  if (req.method !== "POST") {
    return Response.json({ ok: false, error: "Use POST." }, { status: 405 });
  }

  try {
    const body = await req.json();
    const sourceUrl = cleanText(body.sourceUrl, 500);
    let parsed;
    try { parsed = new URL(sourceUrl); } catch {
      return Response.json({ ok:false, error:"URL inválida." }, { status:400 });
    }

    if (!ALLOWED_HOSTS.has(parsed.hostname) || !parsed.pathname.includes("/99food/")) {
      return Response.json({
        ok:false,
        error:"Apenas páginas públicas 99Food são aceitas neste teste."
      }, { status:400 });
    }

    const restaurant = cleanText(body.restaurant || body.pageTitle || "99Food", 120);
    const rawItems = Array.isArray(body.items) ? body.items.slice(0, MAX_ITEMS) : [];

    const bestByName = new Map();

    for (const x of rawItems) {
      const product = cleanText(x.product || x.name, 120);
      const price = numberOrNull(x.price);
      const originalPriceRaw = numberOrNull(x.originalPrice);
      const originalPrice = originalPriceRaw && originalPriceRaw > price ? originalPriceRaw : null;
      const sourceText = cleanText(x.sourceText, 320);
      let offerUrl = cleanText(x.offerUrl, 500) || null;
      if (offerUrl) {
        try {
          const u = new URL(offerUrl);
          if (!ALLOWED_HOSTS.has(u.hostname) || !u.pathname.includes("/99food/")) offerUrl = null;
        } catch { offerUrl = null; }
      }

      if (!product || !price || looksLikeAddon(product, sourceText)) continue;

      const normalized = {
        product,
        price,
        originalPrice,
        hasDisplayedDiscount: Boolean(originalPrice),
        sourceText,
        offerUrl,
        url: sourceUrl,
        restaurant
      };

      const key = productKey(product);
      if (!key) continue;

      const old = bestByName.get(key);
      if (!old ||
          normalized.price < old.price ||
          (normalized.originalPrice && !old.originalPrice)) {
        bestByName.set(key, normalized);
      }
    }

    const normalized = [...bestByName.values()];

    if (!normalized.length) {
      return Response.json({
        ok:false,
        error:"Nenhum produto com preço válido foi recebido."
      }, { status:400 });
    }

    const store = getStore("garimpo-mobile-scans");
    const pageId = createHash("sha256").update(sourceUrl).digest("hex").slice(0, 24);
    const enriched = [];

    for (const item of normalized) {
      const productId = createHash("sha256")
        .update(sourceUrl + "|" + productKey(item.product))
        .digest("hex")
        .slice(0, 32);

      const historyKey = "history/" + productId;
      const history = (await store.get(historyKey, { type:"json" }).catch(()=>null)) || [];
      const higherHistory = history
        .map(x => Number(x.price))
        .filter(n => Number.isFinite(n) && n > item.price * 1.15);
      const historicReference = higherHistory.length >= 2 ? median(higherHistory) : null;

      let referencePrice = null;
      let referenceSource = null;

      if (item.originalPrice && item.originalPrice > item.price) {
        referencePrice = item.originalPrice;
        referenceSource = "displayed";
      } else if (historicReference) {
        referencePrice = historicReference;
        referenceSource = "history";
      }

      enriched.push(scoreItem(item, referencePrice, referenceSource));

      const nextHistory = [
        ...history,
        { price:item.price, originalPrice:item.originalPrice || null, at:Date.now() }
      ].slice(-30);

      await store.setJSON(historyKey, nextHistory);
    }

    const snapshot = {
      id: pageId,
      sourceUrl,
      restaurant,
      scannedAt: new Date().toISOString(),
      scannerVersion: Number(body.scannerVersion || 1),
      client: cleanText(req.headers.get("x-garimpo-client") || "unknown", 50),
      items: enriched
    };

    await store.setJSON("snapshot/" + pageId, snapshot);

    let index = (await store.get("index", { type:"json" }).catch(()=>null)) || [];
    index = index.filter(x => x.id !== pageId);
    index.unshift({ id:pageId, sourceUrl, restaurant, scannedAt:snapshot.scannedAt });
    index = index.slice(0, MAX_INDEX);
    await store.setJSON("index", index);

    const ranked = [...enriched].sort((a,b)=>b.score-a.score || b.discount-a.discount);

    return Response.json({
      ok:true,
      accepted: enriched.length,
      displayedDiscounts: enriched.filter(x => x.referenceSource === "displayed").length,
      restaurant,
      best: ranked.slice(0,5)
    }, { headers:{ "cache-control":"no-store" } });
  } catch (error) {
    return Response.json({ ok:false, error:String(error) }, { status:500 });
  }
};