import { getStore } from "@netlify/blobs";

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
      .flatMap(s => (s.items || []).map(item => ({
        ...item,
        scannedAt: s.scannedAt,
        restaurant: s.restaurant,
        url: s.sourceUrl
      })))
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