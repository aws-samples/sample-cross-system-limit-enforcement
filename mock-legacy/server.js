'use strict';

/*
 * Mock "legacy core banking" service — stands in for the on-prem mainframe.
 *
 * Implements the contract the cloud Transfer Service calls (see
 * HttpLegacyCoreClient in the Java app):
 *
 *   GET  /api/daily-total?customerId=&date=&currency=   -> { "total": <number> }
 *   POST /api/transfers   {transferId,customerId,amount,currency} -> { "settled": bool }
 *   GET  /api/transfers/status?transferId=              -> { "status": "SETTLED"|"NOT_FOUND"|"FAILED"|"REJECTED" }
 *
 * Plus a control surface (mirrors the in-process stub) so a demo script can
 * arrange scenarios:
 *
 *   POST /control/total            {customerId,total}
 *   POST /control/next-result      {result: "SUCCESS"|"FAILURE"|"TIMEOUT"}
 *   POST /control/total-unavailable{unavailable: "true"|"false"}
 *   POST /control/settlement       {transferId,status}
 *   POST /control/reset
 *
 * Zero npm dependencies — uses only the Node standard library.
 */

const http = require('http');
const { URL } = require('url');

const PORT = parseInt(process.env.PORT || '8080', 10);

// --- Mutable demo state ------------------------------------------------------
const state = {
  legacyTotals: new Map(),      // customerId -> number
  settlements: new Map(),       // transferId -> "SETTLED"|"NOT_SETTLED"|"UNKNOWN"
  nextResult: 'SUCCESS',        // outcome for the next POST /api/transfers
  totalUnavailable: false,      // when true, daily-total returns 503 (drives fail-closed)
};

function reset() {
  state.legacyTotals.clear();
  state.settlements.clear();
  state.nextResult = 'SUCCESS';
  state.totalUnavailable = false;
}

// --- Helpers -----------------------------------------------------------------
function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 1e6) req.destroy();
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

// --- Request handling --------------------------------------------------------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const path = url.pathname;
  const method = req.method;

  try {
    // Health for k8s probes
    if (method === 'GET' && (path === '/health' || path === '/healthz')) {
      return sendJson(res, 200, { status: 'UP' });
    }

    // --- Legacy contract ---------------------------------------------------
    if (method === 'GET' && path === '/api/daily-total') {
      if (state.totalUnavailable) {
        log('daily-total -> 503 (forced unavailable)');
        return sendJson(res, 503, { error: 'legacy total unavailable' });
      }
      const customerId = url.searchParams.get('customerId') || '';
      const total = state.legacyTotals.get(customerId) || 0;
      log(`daily-total customer=${customerId} -> ${total}`);
      return sendJson(res, 200, { total });
    }

    if (method === 'POST' && path === '/api/transfers') {
      const body = await readBody(req);
      const result = state.nextResult;
      log(`execute transfer=${body.transferId} customer=${body.customerId} amount=${body.amount} -> ${result}`);
      if (result === 'TIMEOUT') {
        // Simulate a hung core call so the client's I/O timeout fires.
        // Do not respond; the connection stays open until the client times out.
        return; // intentionally no response
      }
      if (result === 'FAILURE') {
        return sendJson(res, 200, { settled: false });
      }
      // SUCCESS: record settlement and add to the customer's legacy total so the
      // running total reflects the executed transfer on subsequent reads.
      if (body.transferId) state.settlements.set(body.transferId, 'SETTLED');
      if (body.customerId && typeof body.amount !== 'undefined') {
        const prev = state.legacyTotals.get(body.customerId) || 0;
        state.legacyTotals.set(body.customerId, prev + Number(body.amount));
      }
      return sendJson(res, 200, { settled: true });
    }

    if (method === 'GET' && path === '/api/transfers/status') {
      const transferId = url.searchParams.get('transferId') || '';
      const internal = state.settlements.get(transferId);
      // Map internal stub states to the wire contract the client expects.
      let status;
      if (internal === 'SETTLED') status = 'SETTLED';
      else if (internal === 'NOT_SETTLED') status = 'FAILED';
      else status = 'NOT_FOUND'; // unknown / never seen
      log(`status transfer=${transferId} -> ${status}`);
      return sendJson(res, 200, { status });
    }

    // --- Control surface ---------------------------------------------------
    if (method === 'POST' && path === '/control/total') {
      const b = await readBody(req);
      state.legacyTotals.set(b.customerId, Number(b.total));
      return sendJson(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/control/next-result') {
      state.nextResult = (await readBody(req)).result || 'SUCCESS';
      return sendJson(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/control/total-unavailable') {
      state.totalUnavailable = String((await readBody(req)).unavailable) === 'true';
      return sendJson(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/control/settlement') {
      const b = await readBody(req);
      state.settlements.set(b.transferId, b.status);
      return sendJson(res, 200, { ok: true });
    }
    if (method === 'POST' && path === '/control/reset') {
      reset();
      return sendJson(res, 200, { ok: true });
    }

    return sendJson(res, 404, { error: 'not found', path });
  } catch (err) {
    log('error handling request', err);
    return sendJson(res, 400, { error: 'bad request' });
  }
});

server.listen(PORT, () => log(`mock legacy core listening on :${PORT}`));
