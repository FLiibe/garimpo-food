import { getStore } from "@netlify/blobs";
import { createHash } from "node:crypto";

function clean(value, max=180) {
  return String(value ?? "").replace(/\s+/g," ").trim().slice(0,max);
}

export default async (req) => {
  if (req.method !== "POST") {
    return Response.json({ok:false,error:"Use POST."},{status:405});
  }

  try {
    const body=await req.json();
    const product=clean(body.product,120);
    const restaurant=clean(body.restaurant,120);
    const installId=clean(body.installId,80);
    const url=clean(body.url,500);
    if (!product || !restaurant || !installId) {
      return Response.json({ok:false,error:"Dados incompletos."},{status:400});
    }

    const id=createHash("sha256")
      .update((restaurant+"|"+product).toLowerCase())
      .digest("hex")
      .slice(0,32);

    const store=getStore("garimpo-missing-reports");
    const key="report/"+id;
    const current=(await store.get(key,{type:"json"}).catch(()=>null))||[];
    const cutoff=Date.now()-2*60*60*1000;

    const recent=current.filter(x=>Number(x.at)>=cutoff && x.installId!==installId);
    recent.push({installId,at:Date.now(),url});
    await store.setJSON(key,recent.slice(-20));

    return Response.json({ok:true,reports:recent.length});
  } catch(error) {
    return Response.json({ok:false,error:String(error)},{status:500});
  }
};