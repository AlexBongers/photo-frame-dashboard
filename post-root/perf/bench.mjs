// Measures the dashboard on the frame through WebView remote debugging (Node 22, no packages).
//
//   post-root/perf/bench.sh [label]
//
// Needs the kiosk started with --ez debug true and `adb forward tcp:9222 localabstract:...`;
// bench.sh does both and puts the kiosk back to normal afterwards. Read-only on the frame.
const PORT = process.env.CDP_PORT || '9222';
const label = process.argv[2] || 'run';

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
const page = targets.find((t) => t.type === 'page');
if (!page) throw new Error('no page target');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let nextId = 1;
const pending = new Map();
const listeners = [];
ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
  else listeners.forEach((l) => l(msg));
};
const send = (method, params = {}) => new Promise((res) => {
  const id = nextId++;
  pending.set(id, res);
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 300));
  return r.result?.result?.value;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };

const DEEP = `function deep(root, sel){const out=[];const walk=(n)=>{if(n.querySelectorAll){n.querySelectorAll(sel).forEach(e=>out.push(e));n.querySelectorAll('*').forEach(e=>{if(e.shadowRoot)walk(e.shadowRoot);});}};walk(root);return out;}`;

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Network.setCacheDisabled', { cacheDisabled: false });

// Optional: measure another dashboard than the one the kiosk shows (START_URL=http://host/dashboard-x/0).
if (process.env.START_URL) {
  await send('Page.navigate', { url: process.env.START_URL });
  await sleep(3000);
}

// 1. Warm reload (HTTP cache allowed): time until the dashboard cards are on screen.
const loads = [];
const reloadRuns = Number(process.env.RELOADS ?? 3);
for (let i = 0; i < reloadRuns; i++) {
  await send('Page.reload', { ignoreCache: false });
  await sleep(1500);
  const t = await evaluate(`(async()=>{${DEEP}
    const t0=performance.now();
    while(performance.now()-t0<60000){
      const v=deep(document,'hui-sections-view')[0];
      if(v && deep(document,'hui-card').length>=3) return Math.round(performance.now());
      await new Promise(r=>setTimeout(r,50));
    }
    return -1;})()`);
  loads.push(t);
  await sleep(1000);
}

// 2. Resources of the last load: how much came from the network, how much from cache.
const resources = await evaluate(`(()=>{
  const r=performance.getEntriesByType('resource');
  const net=r.filter(e=>e.transferSize>0);
  return {count:r.length, fromNetwork:net.length, networkKB:Math.round(net.reduce((s,e)=>s+e.transferSize,0)/1024),
    slowest:r.sort((a,b)=>b.duration-a.duration).slice(0,4).map(e=>[e.name.split('/').slice(-2).join('/').slice(0,40),Math.round(e.duration),e.transferSize])};})()`);
const nav = await evaluate(`(()=>{const n=performance.getEntriesByType('navigation')[0];
  return {dcl:Math.round(n.domContentLoadedEventEnd), load:Math.round(n.loadEventEnd),
    fcp:Math.round((performance.getEntriesByName('first-contentful-paint')[0]||{}).startTime||0)};})()`);

// 3. Tab switches: click each tab in turn and time until its view has rendered cards and a frame was drawn.
const names = await evaluate(`(()=>{${DEEP} return deep(document,'[role=tab]').map(t=>t.textContent.trim());})()`);
const perTab = {};
for (let round = 0; round < 3; round++) {
  for (const n of names) {
    const ms = await evaluate(`(async()=>{${DEEP}
      const tab=deep(document,'[role=tab]').find(t=>t.textContent.trim()===${JSON.stringify(n)});
      const t0=performance.now(); tab.click();
      const marker=Date.now();
      while(performance.now()-t0<10000){
        const active=deep(document,'[role=tab]').find(t=>t.hasAttribute('active')||t.getAttribute('aria-selected')==='true');
        if(active && active.textContent.trim()===${JSON.stringify(n)} && deep(document,'hui-card').length>=1){
          await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
          return Math.round(performance.now()-t0);
        }
        await new Promise(r=>requestAnimationFrame(r));
      }
      return -1;})()`);
    (perTab[n] ||= []).push(ms);
    await sleep(400);
  }
}

// 4. Where does a tab switch spend its time? Chromium's own counters, before and after.
await send('Performance.enable');
const metrics = async () => Object.fromEntries((await send('Performance.getMetrics')).result.metrics.map((m) => [m.name, m.value]));
const breakdown = {};
for (const n of names) {
  const other = names.find((x) => x !== n);
  await evaluate(`(async()=>{${DEEP} const t=deep(document,'[role=tab]').find(t=>t.textContent.trim()===${JSON.stringify(other)}); t.click(); await new Promise(r=>setTimeout(r,1500));})()`);
  const m0 = await metrics();
  await evaluate(`(async()=>{${DEEP} const t=deep(document,'[role=tab]').find(t=>t.textContent.trim()===${JSON.stringify(n)}); t.click(); await new Promise(r=>setTimeout(r,2500));})()`);
  const m1 = await metrics();
  const d = (k) => Math.round((m1[k] - m0[k]) * 1000);
  breakdown[n] = { scriptMs: d('ScriptDuration'), layoutMs: d('LayoutDuration'), styleMs: d('RecalcStyleDuration'), taskMs: d('TaskDuration'), nodesAdded: Math.round(m1.Nodes - m0.Nodes) };
  // Time until the main thread goes quiet after the tap: the page feels sluggish until then.
  await evaluate(`(async()=>{${DEEP} const t=deep(document,'[role=tab]').find(t=>t.textContent.trim()===${JSON.stringify(other)}); t.click(); await new Promise(r=>setTimeout(r,2500));})()`);
  const q0 = await metrics(); const tClick = Date.now();
  await evaluate(`(()=>{${DEEP} const t=deep(document,'[role=tab]').find(t=>t.textContent.trim()===${JSON.stringify(n)}); t.click();})()`);
  let last = (await metrics()).TaskDuration, quiet = 0;
  while (quiet < 3 && Date.now() - tClick < 12000) {
    await sleep(250);
    const cur = (await metrics()).TaskDuration;
    quiet = cur - last < 0.03 ? quiet + 1 : 0;
    last = cur;
  }
  breakdown[n].busyUntilQuietMs = Date.now() - tClick - 750;
  await sleep(2000);
  const i0 = (await metrics()).TaskDuration; await sleep(5000);
  breakdown[n].idleCpuMsPer5s = Math.round(((await metrics()).TaskDuration - i0) * 1000);
}

const mem = await evaluate(`({usedJSHeapMB: Math.round(performance.memory.usedJSHeapSize/1048576), nodes: document.getElementsByTagName('*').length})`);
const out = {
  label,
  loadToCardsMs: loads, loadMedianMs: loads.length ? median(loads.filter((x) => x > 0)) : null,
  nav, resources,
  tabSwitchMedianMs: Object.fromEntries(Object.entries(perTab).map(([k, v]) => [k, median(v.filter((x) => x > 0))])),
  tabSwitchAllMs: perTab,
  tabCostBreakdown: breakdown,
  mem,
};
console.log(JSON.stringify(out, null, 1));
ws.close();
process.exit(0);
