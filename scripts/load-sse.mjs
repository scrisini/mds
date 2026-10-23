// Opens many Server-Sent Events subscriptions against a running instance and reports what arrived.
// Used for README "Capacity"; read the server side from /actuator/metrics while it runs.
//
//   node scripts/load-sse.mjs <subscribers> <rate> <seconds> [base-url]
//   node scripts/load-sse.mjs 1000 10 60 http://localhost:8080
//
// Subscriptions are spread evenly over the pairs in PAIRS. Needs Node 18 or later; no packages.
import http from 'node:http';

const PAIRS = ['BTC-USDT', 'ETH-USDT', 'BTC-USD', 'ETH-USD'];
const [subscribers, rate, seconds, base = 'http://localhost:8080'] = process.argv.slice(2);
if (!subscribers || !rate || !seconds) {
  console.error('usage: node scripts/load-sse.mjs <subscribers> <rate> <seconds> [base-url]');
  process.exit(2);
}
const url = new URL(base);
const agent = new http.Agent({ keepAlive: true, maxSockets: Infinity });
let events = 0, bytes = 0, open = 0, errors = 0;
const requests = [];
for (let i = 0; i < Number(subscribers); i++) {
  const request = http.get({ host: url.hostname, port: url.port, agent,
      path: `/api/v1/stream/prices/${PAIRS[i % PAIRS.length]}?rate=${rate}` }, response => {
    if (response.statusCode !== 200) { errors++; response.resume(); return; }
    open++;
    response.on('data', chunk => {
      bytes += chunk.length;
      const text = chunk.toString();
      for (let at = text.indexOf('event:price'); at !== -1; at = text.indexOf('event:price', at + 1)) events++;
    });
  });
  request.on('error', () => errors++);
  requests.push(request);
}
setTimeout(() => {
  console.log(JSON.stringify({ subscribers: Number(subscribers), rate, seconds: Number(seconds), open, errors,
      eventsPerSec: +(events / seconds).toFixed(1), kbPerSec: +(bytes / 1024 / seconds).toFixed(1) }));
  requests.forEach(r => r.destroy());
  process.exit(0);
}, Number(seconds) * 1000);
