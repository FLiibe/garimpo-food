const state={deals:[],filter:"under999",category:"Todos",query:""};
const fmt=new Intl.NumberFormat("pt-BR",{style:"currency",currency:"BRL"});
const FOOD=/\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|cheeseburger|x[ -]?(burger|salada|bacon|frango))\b/i;
const BLOCK=/\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|borda|extra|condimento|dip|acompanhamento|acompanhamentos)\b/i;
const MAX_AGE=90*60*1000;

function eligible(d){
  const p=String(d.product||"");
  const price=Number(d.price);
  const at=Date.parse(d.scannedAt||"");
  return price>0&&price<=9.99&&FOOD.test(p)&&!BLOCK.test(p)&&(!Number.isFinite(at)||Date.now()-at<=MAX_AGE);
}
function category(p){
  if(/marmita|prato|refei[cç][aã]o|yakisoba/i.test(p))return"Refeições";
  if(/hamb|burger|sandu[ií]che|lanche|chicken|whopper|hot[ -]?dog|cachorro|cheeseburger/i.test(p))return"Lanches";
  if(/frango|lingui[cç]a|carne|bife|costela|calabresa/i.test(p))return"Carnes";
  if(/pizza|pastel|esfiha|coxinha|tapioca/i.test(p))return"Pizza/Pastel";
  return"Outros";
}
function freshness(d){
  const at=Date.parse(d.scannedAt||"");
  if(!Number.isFinite(at))return"Encontrado recentemente";
  const min=Math.max(0,Math.floor((Date.now()-at)/60000));
  if(min<2)return"Encontrado agora";
  if(min<60)return`Encontrado há ${min} min`;
  return"Encontrado há 1h — pode ter mudado";
}
function visibleDeals(){
  return state.deals.filter(d=>{
    if(!eligible(d))return false;
    if(state.filter==="under1"&&Number(d.price)>.99)return false;
    if(state.filter==="under5"&&Number(d.price)>4.99)return false;
    if(state.category!=="Todos"&&category(d.product)!==state.category)return false;
    if(state.query&&!((d.product+" "+d.restaurant).toLowerCase().includes(state.query)))return false;
    return true;
  }).sort((a,b)=>Number(a.price)-Number(b.price));
}
function installId(){
  let id=localStorage.getItem("garimpo_install_id");
  if(!id){id=crypto.randomUUID?crypto.randomUUID():String(Date.now())+Math.random();localStorage.setItem("garimpo_install_id",id)}
  return id;
}
async function reportMissing(d){
  state.deals=state.deals.filter(x=>!(x.product===d.product&&x.restaurant===d.restaurant));
  render();
  try{
    await fetch("/.netlify/functions/report99",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({
      product:d.product,restaurant:d.restaurant,url:d.offerUrl||d.url,installId:installId()
    })});
  }catch{}
}
function render(){
  const list=document.querySelector("#dealList"),tpl=document.querySelector("#dealTemplate"),items=visibleDeals();
  list.innerHTML="";
  if(!items.length){list.innerHTML='<div class="empty">Nenhum achado recente até R$9,99 com estes filtros.</div>';return}
  for(const d of items){
    const el=tpl.content.cloneNode(true);
    el.querySelector(".label").textContent=category(d.product);
    el.querySelector(".score").textContent=freshness(d);
    el.querySelector(".product").textContent=d.product;
    el.querySelector(".restaurant").textContent=d.restaurant||"99Food";
    el.querySelector(".price").textContent=fmt.format(d.price);
    el.querySelector(".discount").textContent="Preço encontrado agora no 99Food";
    const link=el.querySelector(".open-link");
    const u=d.offerUrl||d.url;
    link.href=`garimpo://offer?url=${encodeURIComponent(u)}&product=${encodeURIComponent(d.product)}`;
    el.querySelector(".report-link").addEventListener("click",()=>reportMissing(d));
    list.appendChild(el);
  }
}
async function load(){
  const status=document.querySelector("#statusText");
  status.textContent="Carregando comidas por até R$9,99...";
  try{
    const r=await fetch("/.netlify/functions/deals99?t="+Date.now(),{cache:"no-store"});
    const data=await r.json();
    if(!r.ok||!data.ok)throw new Error(data.error||`HTTP ${r.status}`);
    state.deals=(data.deals||[]).filter(eligible);
    status.textContent=state.deals.length?`${state.deals.length} achados recentes por até R$9,99`:"Nenhum achado recente";
    document.querySelector("#lastUpdate").textContent=data.scannedAt?new Date(data.scannedAt).toLocaleTimeString("pt-BR",{hour:"2-digit",minute:"2-digit"}):"";
    render();
  }catch(e){
    status.textContent="Não foi possível carregar os achados.";
    document.querySelector("#dealList").innerHTML=`<div class="empty">${String(e)}</div>`;
  }
}
document.querySelector("#refreshBtn").addEventListener("click",load);
document.querySelectorAll(".price-tabs .tab").forEach(b=>b.addEventListener("click",()=>{
  document.querySelectorAll(".price-tabs .tab").forEach(x=>x.classList.remove("active"));
  b.classList.add("active");state.filter=b.dataset.filter;render();
}));
document.querySelectorAll(".category-tabs .tab").forEach(b=>b.addEventListener("click",()=>{
  document.querySelectorAll(".category-tabs .tab").forEach(x=>x.classList.remove("active"));
  b.classList.add("active");state.category=b.dataset.category;render();
}));
document.querySelector("#searchInput").addEventListener("input",e=>{state.query=e.target.value.trim().toLowerCase();render()});
load();