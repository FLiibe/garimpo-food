import { getStore } from "@netlify/blobs";
import { createHash } from "node:crypto";

const MAX_ITEMS = 60;
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

function scoreItem(item, referencePrice) {
  let score = 15;
  if (item.price <= 1) score += 45;
  else if (item.price <= 3) score += 35;
  else if (item.price <= 5) score += 25;
  else if (item.price <= 10) score += 12;

  const name = item.product.toLowerCase();
  if (/\b(marmita|pizza|hamb[uú]rguer|burger|combo|pastel|a[cç]a[ií]|sushi|prato|refei[cç][aã]o|lanche|frango|carne)\b/i.test(name)) score += 15;
  if (/\b(molho|shoyu|hashi|talher|adicional|embalagem|sach[eê]|extra|borda|guardanapo)\b/i.test(name)) score -= 45;

  let discount = 0;
  if (referencePrice && referencePrice > item.price) {
    discount = ((referencePrice - item.price) / referencePrice) * 100;
    score += Math.min(35, discount * 0.4);
  }

  return {
    ...item,
    referencePrice,
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
      return Response.json({ ok:false, error:"Apenas páginas públicas 99Food são aceitas neste teste." }, { status:400 });
    }

    const restaurant = cleanText(body.restaurant || body.pageTitle || "99Food", 120);
    const rawItems = Array.isArray(body.items) ? body.items.slice(0, MAX_ITEMS) : [];

    const normalized = rawItems.map(x => ({
      product: cleanText(x.product || x.name, 160),
      price: numberOrNull(x.price),
      url: sourceUrl,
      restaurant
    })).filter(x => x.product && x.price);

    if (!normalized.length) {
      return Response.json({ ok:false, error:"Nenhum produto com preço válido foi recebido." }, { status:400 });
    }

    const store = getStore("garimpo-mobile-scans");
    const pageId = createHash("sha256").update(sourceUrl).digest("hex").slice(0, 24);
    const enriched = [];

    for (const item of normalized) {
      const productId = createHash("sha256")
        .update(sourceUrl + "|" + item.product.toLowerCase())
        .digest("hex")
        .slice(0, 32);

      const historyKey = "history/" + productId;
      const history = (await store.get(historyKey, { type:"json" }).catch(()=>null)) || [];
      const referencePrice = median(history.map(x => Number(x.price)));
      enriched.push(scoreItem(item, referencePrice));

      const nextHistory = [...history, { price:item.price, at:Date.now() }].slice(-30);
      await store.setJSON(historyKey, nextHistory);
    }

    const snapshot = {
      id: pageId,
      sourceUrl,
      restaurant,
      scannedAt: new Date().toISOString(),
      client: cleanText(req.headers.get("x-garimpo-client") || "unknown", 50),
      items: enriched
    };

    await store.setJSON("snapshot/" + pageId, snapshot);

    let index = (await store.get("index", { type:"json" }).catch(()=>null)) || [];
    index = index.filter(x => x.id !== pageId);
    index.unshift({ id:pageId, sourceUrl, restaurant, scannedAt:snapshot.scannedAt });
    index = index.slice(0, MAX_INDEX);
    await store.setJSON("index", index);

    return Response.json({
      ok:true,
      accepted: enriched.length,
      restaurant,
      best: enriched.sort((a,b)=>b.score-a.score).slice(0,5)
    }, { headers:{ "cache-control":"no-store" } });
  } catch (error) {
    return Response.json({ ok:false, error:String(error) }, { status:500 });
  }
};