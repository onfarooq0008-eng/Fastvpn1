// Minimal in-memory sliding-window rate limiter -- no extra dependency needed
// for this. Fine for a single-process API like this one; if you ever run
// multiple API instances behind a load balancer, swap this for a shared
// store (e.g. Redis) since each process would otherwise count separately.
const buckets = new Map(); // ip -> array of request timestamps (ms)

/**
 * @param {number} maxRequests  how many requests are allowed...
 * @param {number} windowMs     ...within this many milliseconds
 */
function rateLimit(maxRequests, windowMs) {
  return (req, res, next) => {
    const ip = req.ip || req.socket?.remoteAddress || 'unknown';
    const now = Date.now();
    const timestamps = (buckets.get(ip) || []).filter((t) => now - t < windowMs);

    if (timestamps.length >= maxRequests) {
      return res.status(429).json({ error: 'too many requests, slow down' });
    }

    timestamps.push(now);
    buckets.set(ip, timestamps);
    next();
  };
}

// Periodic cleanup so the map doesn't grow forever from one-off visitors.
setInterval(() => {
  const now = Date.now();
  for (const [ip, timestamps] of buckets.entries()) {
    const fresh = timestamps.filter((t) => now - t < 10 * 60 * 1000);
    if (fresh.length === 0) buckets.delete(ip);
    else buckets.set(ip, fresh);
  }
}, 5 * 60 * 1000).unref();

module.exports = { rateLimit };
