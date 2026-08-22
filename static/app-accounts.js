$("btn-accounts").onclick = () => {
  paintAccounts();
  $("accounts-modal").showModal();
};
$("accounts-close").onclick = () => $("accounts-modal").close();

function paintAccounts() {
  const g = state.accounts?.google || {};
  const s = state.accounts?.samsung || {};
  $("google-pill").textContent = g.connected ? (g.email || "Connected") : "Not connected";
  $("google-pill").classList.toggle("on", !!g.connected);
  $("google-copy").textContent = g.connected
    ? `Signed in as ${g.name || g.email}. Sync down pulls Google Photos into Lumina; upload sends filed photos into a Lumina album.`
    : "Connect a Google account to sync this library with Google Photos.";
  $("google-redirect").textContent = state.accounts?.redirect_uri || "";
  $("samsung-pill").textContent = s.connected ? "Folder connected" : "Not connected";
  $("samsung-pill").classList.toggle("on", !!s.connected);
  $("samsung-copy").textContent = s.note || "";
  $("samsung-folder").value = s.folder || "";
  const apk = state.accounts?.apk_url;
  const repo = state.accounts?.repo_url;
  $("apk-note").innerHTML = repo
    ? `Android APK: <a href="${apk}" target="_blank" rel="noopener">download the latest build</a> from <a href="${repo}" target="_blank" rel="noopener">github.com/guccichine/lumina</a>.`
    : "";
}

$("google-save").onclick = async () => {
  state.accounts = await api("/api/accounts/google", {
    method: "POST",
    body: JSON.stringify({
      client_id: $("google-client-id").value.trim(),
      client_secret: $("google-client-secret").value.trim(),
    }),
  });
  paintAccounts();
  toast("Saved Google client");
};
$("google-signin").onclick = () => {
  location.href = "/api/auth/google/start";
};
$("google-out").onclick = async () => {
  state.accounts = await api("/api/accounts/google/disconnect", { method: "POST", body: "{}" });
  paintAccounts();
  toast("Disconnected Google Photos");
};
$("google-pull").onclick = async () => {
  toast("Pulling from Google Photos…");
  const data = await api("/api/sync/google/pull", { method: "POST", body: JSON.stringify({ limit: 40 }) });
  applyState(data);
  paintAccounts();
  render();
  toast(`Synced down ${data.pulled || 0} photos`);
};
$("google-push").onclick = async () => {
  toast("Uploading to Google Photos…");
  const data = await api("/api/sync/google/push", { method: "POST", body: JSON.stringify({ limit: 30 }) });
  applyState(data);
  paintAccounts();
  toast(`Uploaded ${data.pushed || 0} photos to Google Photos`);
};
$("samsung-save").onclick = async () => {
  state.accounts = await api("/api/accounts/samsung", {
    method: "POST",
    body: JSON.stringify({ folder: $("samsung-folder").value.trim() }),
  });
  paintAccounts();
  toast("Saved Samsung Gallery folder");
};
$("samsung-import").onclick = async () => {
  toast("Importing Samsung Gallery…");
  const data = await api("/api/sync/samsung", { method: "POST", body: "{}" });
  applyState(data);
  paintAccounts();
  render();
  toast(`Imported ${data.imported || 0} photos`);
};

async function pushSelectedToGoogle() {
  const ids = [...state.selected];
  if (!ids.length) return;
  toast("Uploading selection to Google Photos…");
  const data = await api("/api/sync/google/push", { method: "POST", body: JSON.stringify({ ids, limit: ids.length }) });
  toast(`Uploaded ${data.pushed || 0} photos to Google Photos`);
}

routeFromHash();
load().then(() => {
  const params = new URLSearchParams(location.search);
  if (params.get("connected") === "google") {
    toast("Signed in to Google Photos");
    history.replaceState({}, "", "/");
    $("accounts-modal").showModal();
    paintAccounts();
  }
  if (params.get("auth_error")) {
    toast("Google sign-in didn’t finish: " + params.get("auth_error"));
    history.replaceState({}, "", "/");
  }
}).catch((err) => toast(err.message));
