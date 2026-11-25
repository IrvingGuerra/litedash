// Relevo de la ruta en línea para pantallas con Android 9 o anterior: el servidor de rutas de FOSSGIS
// (routing.openstreetmap.de) solo acepta TLS 1.3 y esas pantallas llegan hasta TLS 1.2. litedash.dev acepta las dos,
// así que recibe el pedido y lo repite tal cual. No guarda nada: ni el pedido ni la dirección de quien lo hizo.
//
// Solo deja pasar lo que pide la app (una ruta en auto entre dos puntos, con las mismas opciones) para que nadie lo
// use como puerta abierta al servicio de voluntarios, y guarda la misma ruta 10 minutos para pedirla una sola vez.

const UPSTREAM = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
const POINT = "-?\\d{1,3}(?:\\.\\d{1,6})?,-?\\d{1,2}(?:\\.\\d{1,6})?";
const COORDS = new RegExp(`^${POINT};${POINT}$`);
const ALLOWED = {
  overview: /^full$/,
  geometries: /^polyline6$/,
  steps: /^true$/,
  bearings: /^\d{1,3},\d{1,3};$/,
};
const CACHE_SECONDS = 600;

function reply(status, body, extra) {
  return new Response(body, {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "X-Robots-Tag": "noindex", ...extra },
  });
}

export async function onRequestGet({ request, params, waitUntil }) {
  const coords = String(params.coords || "");
  if (!COORDS.test(coords)) return reply(400, '{"code":"InvalidUrl"}');
  const asked = new URL(request.url).searchParams;
  const query = new URLSearchParams();
  for (const [name, value] of asked) {
    if (!ALLOWED[name] || !ALLOWED[name].test(value) || query.has(name)) return reply(400, '{"code":"InvalidOptions"}');
    query.set(name, value);
  }
  query.sort();
  // URLSearchParams escaparía la coma y el punto y coma de «bearings»; OSRM los quiere tal cual.
  const options = [...query].map(([name, value]) => `${name}=${value}`).join("&");
  const target = `${UPSTREAM}${coords}${options ? `?${options}` : ""}`;

  const cache = caches.default;
  const key = new Request(target);
  const kept = await cache.match(key);
  if (kept) return kept;

  let answer;
  try {
    answer = await fetch(target, {
      headers: { "User-Agent": "LiteDash relay (+https://litedash.dev)", Accept: "application/json" },
    });
  } catch (_) {
    return reply(502, '{"code":"Upstream"}');
  }
  const body = await answer.text();
  const ok = answer.status >= 200 && answer.status < 300;
  const out = reply(answer.status, body, { "Cache-Control": ok ? `public, max-age=${CACHE_SECONDS}` : "no-store" });
  if (ok) waitUntil(cache.put(key, out.clone()));
  return out;
}
