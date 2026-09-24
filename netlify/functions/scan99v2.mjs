import * as cheerio from "cheerio";

const RESTAURANTS = [
  ["https://99app.com/99food/sao-paulo/nosso-pastel-jardim-colonia/5764608520268546182/", "Nosso Pastel - Jardim Colônia"],
  ["https://99app.com/99food/sao-paulo/pizzamix/5764608324042231643/", "Pizzamix (Cohab 2)"],
  ["https://99app.com/99food/sao-paulo/padaria-panzzine/5764608328454639378/", "Padaria Panzzine"]
];

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function fetchHtml(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 9000);
  try {
    const r = await fetch(url, {
      signal: controller.signal,
      redirect: "follow",
      headers: {
        "user-agent": "Mozilla/5.0 (Linux; Android 16; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
        "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "accept-language": "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.6",
        "upgrade-insecure-requests": "1"
      }
    });
    const text = await r.text();
    return { ok:r.ok, status:r.status, text, type:r.headers.get("content-type")||"" };
  } finally {
    clearTimeout(timer);
  }
}

function parse(doc,url,restaurant){
  const $=cheerio.load(doc);
  const deals=[];
  const seen=new Set();

  $("h3,h4,[class*='title'],[class*='name']").each((_,el)=>{
    const title=$(el).text().replace(/\s+/g," ").trim();
    if(!title || title.length>140) return;
    let node=$(el);
    for(let i=0;i<6 && node.length;i++,node=node.parent()){
      const text=node.text().replace(/\s+/g," ").trim();
      const m=text.match(/R\$\s*([\d.]+,\d{2})/);
      if(!m) continue;
      const price=Number(m[1].replace(/\./g,"").replace(",","."));
      if(!Number.isFinite(price)||price<=0||price>500) break;
      const key=title.toLowerCase()+"|"+price;
      if(seen.has(key)) break;
      seen.add(key);
      let score=20;
      if(price<=1) score=80;
      else if(price<=3) score=65;
      else if(price<=5) score=50;
      else if(price<=10) score=35;
      deals.push({product:title,restaurant,price,score,discount:0,referencePrice:null,url});
      break;
    }
  });

  if(!deals.length){
    $("body *").each((_,el)=>{
      const text=$(el).clone().children().remove().end().text().replace(/\s+/g," ").trim();
      const m=text.match(/R\$\s*([\d.]+,\d{2})/);
      if(!m || text.length<3 || text.length>180) return;
      const price=Number(m[1].replace(/\./g,"").replace(",","."));
      if(!Number.isFinite(price)||price<=0||price>500) return;
      const product=text.replace(/R\$\s*[\d.]+,\d{2}.*/,"").trim();
      if(!product) return;
      const key=product.toLowerCase()+"|"+price;
      if(seen.has(key)) return;
      seen.add(key);
      deals.push({product,restaurant,price,score:price<=1?80:price<=5?50:25,discount:0,referencePrice:null,url});
    });
  }
  return deals.slice(0,60);
}

export default async () => {
  const scannedAt=new Date().toISOString();
  const deals=[];
  const checks=[];

  for(const [url,name] of RESTAURANTS){
    try{
      const page=await fetchHtml(url);
      checks.push({restaurant:name,status:page.status,bytes:page.text.length,contentType:page.type});
      if(page.ok) deals.push(...parse(page.text,url,name));
    }catch(e){
      checks.push({restaurant:name,error:e?.name==="AbortError"?"timeout":String(e)});
    }
    await sleep(1200);
  }

  if(!deals.length){
    return Response.json({
      ok:false,
      stage:"direct-restaurant-test",
      error:"Netlify não conseguiu ler nenhum menu 99Food.",
      checks,
      deals:[],
      scannedAt
    },{headers:{"cache-control":"no-store"}});
  }

  return Response.json({
    ok:true,
    stage:"complete-v2",
    checks,
    deals:deals.sort((a,b)=>b.score-a.score||a.price-b.price).slice(0,120),
    scannedAt
  },{headers:{"cache-control":"no-store"}});
};