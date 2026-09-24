const state={deals:[],filter:"under999",query:""};
const fmt=new Intl.NumberFormat("pt-BR",{style:"currency",currency:"BRL"});
const FOOD=/\b(combo|marmita|prato|refei[cç][aã]o|hamb[uú]rguer|hamburguer|burger|sandu[ií]che|lanche|chicken|whopper|frango|lingui[cç]a|carne|bife|costela|calabresa|pizza|pastel|esfiha|coxinha|hot[ -]?dog|cachorro[ -]?quente|yakisoba|sushi|temaki|poke|tapioca|cheeseburger|x[ -]?(burger|salada|bacon|frango))\b/i;
const BLOCK=/\b(molho|maionese|mayo|ketchup|mostarda|barbecue|bbq|shoyu|hashi|talher|guardanapo|embalagem|sach[eê]|adicional|adicionais|borda|extra|condimento|dip|acompanhamento|acompanhamentos)\b/i;

function eligible(d){
  const p=String(d.product||"");
  const price=Number(d.price);
  return price>0&&price<=9.99&&FOOD.test(p)&&!BLOCK.test(p);
}
function category(p){
  if(/combo/i.test(p))return"Combo";
  if(/marmita|prato|refei[cç][aã]o|yakisoba/i.test(p))return"Refeição";
  if(/hamb|burger|sandu[ií]che|lanche|chicken|whopper|hot[ -]?dog|cachorro/i.test(p))return"Lanche";
  if(/frango|lingui[cç]a|carne|bife|costela|calabresa/i.test(p))return"Carnes";
  if(/pizza|pastel|esfiha|coxinha|tapioca/i.test(p))return"Salgados";
  if(/sushi|temaki|poke/i.test(p))return"Japonês";
  return"Até R$9,99";
}
function visibleDeals(){
  return state.deals.filter(d=>{
    if(!eligible(d))return false;
    if(state.filter==="under1"&&d.price>.99)return false;
    if(state.filter==="under5"&&d.price>4.99)return false;
    if(state.query&&!((d.product+" "+d.restaurant).toLowerCase().includes(state.query)))return false;
    return true;
  }).sort((a,b)=>a.price-b.price);
}
function render(){
  const list=document.querySelector("#dealList"),tpl=document.querySelector("#dealTemplate"),items=visibleDeals();
  list.innerHTML="";
  if(!items.length){list.innerHTML='<div class="empty">Nenhum achado até R$9,99 com estes filtros.</div>';return}
  for(const d of items){
    const el=tpl.content.cloneNode(true);
    el.querySelector(".label").textContent=d.category||category(d.product);
    el.querySelector(".score").textContent="Até R$9,99";
    el.querySelector(".product").textContent=d.product;
    el.querySelector(".restaurant").textContent=d.restaurant||"99Food";
    el.querySelector(".price").textContent=fmt.format(d.price);
    el.querySelector(".discount").textContent="Preço encontrado agora";
    const link=el.querySelector(".open-link");
    const u=d.offerUrl||d.url;
    link.href=`garimpo://offer?url=${encodeURIComponent(u)}&product=${encodeURIComponent(d.product)}`;
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
    status.textContent=state.deals.length?`${state.deals.length} achados por até R$9,99`:"Nenhum achado recente";
    document.querySelector("#lastUpdate").textContent=data.scannedAt?new Date(data.scannedAt).toLocaleTimeString("pt-BR",{hour:"2-digit",minute:"2-digit"}):"";
    render();
  }catch(e){
    status.textContent="Não foi possível carregar os achados.";
    document.querySelector("#dealList").innerHTML=`<div class="empty">${String(e)}</div>`;
  }
}
document.querySelector("#refreshBtn").addEventListener("click",load);
document.querySelectorAll(".tab").forEach(b=>b.addEventListener("click",()=>{
  document.querySelectorAll(".tab").forEach(x=>x.classList.remove("active"));
  b.classList.add("active");state.filter=b.dataset.filter;render();
}));
document.querySelector("#searchInput").addEventListener("input",e=>{state.query=e.target.value.trim().toLowerCase();render()});
load();