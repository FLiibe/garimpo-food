import { getStore } from "@netlify/blobs";

const MAX_PRICE = 9.99;
const FOOD_RE = /\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|cheeseburger|x[ -]?(burger|salada|bacon|frango))\b/i;
const BLOCK_RE = /\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|borda|extra|condimento|dip|acompanhamento|acompanhamentos)\b/i;

function eligible(item) {
  const price = Number(item?.price);
  const product = String(item?.product || "");
  return price > 0 && price <= MAX_PRICE && FOOD_RE.test(product) && !BLOCK_RE.test(product);
}

function category(product) {
  const p=String(product||"");
  if (/combo/i.test(p)) return "Combo";
  if (/marmita|prato|refei[cç][aã]o|yakisoba/i.test(p)) return "Refeição";
  if (/hamb|burger|sandu[ií]che|lanche|chicken|whopper|hot[ -]?dog|cachorro/i.test(p)) return "Lanche";
  if (/frango|lingui[cç]a|carne|bife|costela|calabresa/i.test(p)) return "Carnes";
  if (/pizza|pastel|esfiha|coxinha|tapioca/i.test(p)) return "Salgados";
  if (/sushi|temaki|poke/i.test(p)) return "Japonês";
  return "Até R$9,99";
}

export default async () => {
  try {
    const store=getStore("garimpo-mobile-scans");
    const index=(await store.get("index",{type:"json"}).catch(()=>null))||[];
    const snapshots=await Promise.all(
      index.slice(0,40).map(x=>store.get("snapshot/"+x.id,{type:"json"}).catch(()=>null))
    );
    const cutoff=Date.now()-12*60*60*1000;
    const map=new Map();

    for (const s of snapshots.filter(Boolean)) {
      if (Date.parse(s.scannedAt)<cutoff) continue;
      for (const item of s.items||[]) {
        if (!eligible(item)) continue;
        const key=(s.restaurant+"|"+item.product).toLowerCase();
        const row={
          product:item.product,
          price:Number(item.price),
          restaurant:s.restaurant,
          url:s.sourceUrl,
          offerUrl:item.offerUrl||null,
          category:category(item.product),
          scannedAt:s.scannedAt
        };
        const old=map.get(key);
        if (!old || Date.parse(row.scannedAt)>Date.parse(old.scannedAt)) map.set(key,row);
      }
    }

    const deals=[...map.values()]
      .sort((a,b)=>a.price-b.price || Date.parse(b.scannedAt)-Date.parse(a.scannedAt))
      .slice(0,250);

    return Response.json({
      ok:true,
      source:"garimpo-999",
      maxPrice:MAX_PRICE,
      deals,
      scannedAt:new Date().toISOString()
    }, {headers:{"cache-control":"no-store"}});
  } catch(error) {
    return Response.json({ok:false,error:String(error),deals:[]},{status:500});
  }
};