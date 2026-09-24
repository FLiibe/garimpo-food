import { getStore } from "@netlify/blobs";

const FOOD_RE = /\b(marmita|pizza|hamb[uú]rguer|burger|combo|pastel|a[cç]a[ií]|sushi|prato|refei[cç][aã]o|lanche|frango|carne|hot dog|cachorro quente|esfiha|coxinha|tapioca|yakisoba|chicken|whopper|sand[uí]che|batata|nugget)\b/i;
const ADDON_RE = /\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|borda|extra|condimento|dip)\b/i;

function classify(item) {
  const combined = `${item.product || ""} ${item.sourceText || ""}`;
  const genericAddon = /^(acompanhamento|acompanhamentos|adicional|adicionais|molho|molhos)$/i.test(String(item.product || "").trim());
  if (genericAddon || ADDON_RE.test(combined)) return null;
  const isFood = FOOD_RE.test(item.product || "");
  const verified = Boolean(item.referencePrice && item.referencePrice > item.price && item.referenceSource);
  const discount = verified
    ? ((item.referencePrice - item.price) / item.referencePrice) * 100
    : Number(item.discount || 0);

  let score = 18;
  if (isFood) score += 15;

  if (item.price <= 1) score += 55;
  else if (item.price <= 3) score += 45;
  else if (item.price <= 5) score += 35;
  else if (item.price <= 10) score += 20;
  else if (item.price <= 15) score += 8;

  if (verified) {
    if (discount >= 90) score += 35;
    else if (discount >= 80) score += 30;
    else if (discount >= 70) score += 25;
    else if (discount >= 50) score += 18;
    else if (discount >= 30) score += 10;
  }

  score = Math.max(0, Math.min(100, Math.round(score)));

  let classification = "normal";
  if (verified && discount >= 70) classification = "verified_extreme";
  else if (verified && discount >= 40) classification = "verified_discount";
  else if (isFood && item.price <= 3) classification = "extreme_price";
  else if (isFood && item.price <= 5) classification = "very_low_price";
  else if (score >= 70) classification = "interesting";

  return {
    ...item,
    discount,
    score,
    classification,
    verifiedDiscount: verified
  };
}

export default async () => {
  try {
    const store = getStore("garimpo-mobile-scans");
    const index = (await store.get("index", { type:"json" }).catch(()=>null)) || [];
    const recent = index.slice(0, 25);
    const snapshots = await Promise.all(
      recent.map(x => store.get("snapshot/" + x.id, { type:"json" }).catch(()=>null))
    );

    const cutoff = Date.now() - 12 * 60 * 60 * 1000;
    const deals = snapshots
      .filter(Boolean)
      .filter(s => Date.parse(s.scannedAt) >= cutoff)
      .flatMap(s => (s.items || []).map(item => classify({
        ...item,
        scannedAt: s.scannedAt,
        restaurant: s.restaurant,
        url: s.sourceUrl
      })).filter(Boolean))
      .sort((a,b) => (b.score||0)-(a.score||0) || (b.discount||0)-(a.discount||0) || a.price-b.price)
      .slice(0, 200);

    return Response.json({
      ok:true,
      source:"android-scanner",
      snapshots:snapshots.filter(Boolean).length,
      deals,
      scannedAt:new Date().toISOString()
    }, { headers:{ "cache-control":"no-store" } });
  } catch (error) {
    return Response.json({ ok:false, error:String(error), deals:[] }, { status:500 });
  }
};