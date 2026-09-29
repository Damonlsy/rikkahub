// RikkaHub 更新代理（Cloudflare Worker）
// 部署后 UpdateChecker.API_URL 指向 https://<你的子域>.workers.dev/releases/latest
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const OWNER = "Damonlsy";
    const REPO = "rikkahub";
    const ghHeaders = {
      "Accept": "application/vnd.github+json",
      "User-Agent": "rikkahub-update-proxy",
      "X-GitHub-Api-Version": "2022-11-28",
    };
    if (env.GITHUB_TOKEN) {
      ghHeaders.Authorization = `token ${env.GITHUB_TOKEN}`;
    }

    if (url.pathname === "/" || url.pathname === "/releases/latest") {
      const res = await fetch(
        `https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`,
        { headers: ghHeaders }
      );
      if (!res.ok) {
        return new Response(JSON.stringify({ message: "upstream error", status: res.status }), {
          status: res.status,
          headers: { "content-type": "application/json" },
        });
      }
      const data = await res.json();
      const base = url.origin;
      if (Array.isArray(data.assets)) {
        for (const a of data.assets) {
          a.browser_download_url = `${base}/download/${encodeURIComponent(a.name)}`;
        }
      }
      return new Response(JSON.stringify(data), {
        headers: { "content-type": "application/json", "cache-control": "no-store" },
      });
    }

    const m = url.pathname.match(/^\/download\/(.+)$/);
    if (m) {
      const name = decodeURIComponent(m[1]);
      const res = await fetch(
        `https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`,
        { headers: ghHeaders }
      );
      if (!res.ok) return new Response("upstream error", { status: res.status });
      const data = await res.json();
      const asset = (data.assets || []).find((a) => a.name === name);
      if (!asset) return new Response("asset not found", { status: 404 });
      const upstream = await fetch(asset.url, {
        headers: { ...ghHeaders, Accept: "application/octet-stream" },
        redirect: "follow",
      });
      if (!upstream.ok) return new Response("download failed", { status: upstream.status });
      const headers = new Headers();
      headers.set(
        "content-type",
        upstream.headers.get("content-type") || "application/vnd.android.package-archive"
      );
      headers.set("content-length", upstream.headers.get("content-length") || String(asset.size));
      headers.set("content-disposition", `attachment; filename="${name}"`);
      headers.set("cache-control", "no-store");
      return new Response(upstream.body, { headers });
    }

    return new Response("RikkaHub update proxy", { status: 200 });
  },
};
