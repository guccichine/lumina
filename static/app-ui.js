function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&", "<": "<", ">": ">", '"': """, "'": "&#39;" }[c]));
}
function toast(msg) {
  const el = $("toast");
  el.hidden = false;
  el.textContent = msg;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, 2800);
}
function openLightbox(id) {
  state.lightIndex = state.photos.findIndex((p) => p.id === id);
  if (state.lightIndex < 0) return;
  paintLightbox();
  $("lightbox").showModal();
}
function paintLightbox() {
  const photo = state.photos[state.lightIndex];
  if (!photo) return;
  $("light-img").src = `/api/files/${photo.id}`;
  $("light-img").alt = photo.filename;
  $("light-name").textContent = photo.filename;
  const when = monthLabel(photo);
  const why = (photo.reasons || []).slice(0, 3).join(" · ");
  $("light-info").textContent = `${when} · ${photo.rel_path}${why ? " · " + why : ""}`;
  $("light-type").innerHTML = TYPES.map(([id, label]) => `<option value="${id}" ${id === photo.type ? "selected" : ""}>${label}</option>`).join("");
}
async function confirmDeleteType(album) {
  if (!await ask(`Delete ${album.count} ${album.label.toLowerCase()} photo${album.count === 1 ? "" : "s"}?`, "They move to Trash. You can restore them from there.")) return;
  const data = await api("/api/delete", { method: "POST", body: JSON.stringify({ type: album.id }) });
  applyState(data); toast(`Moved ${data.deleted?.length || 0} to Trash`); render();
}
async function confirmEmptyTrash() {
  if (!await ask("Empty trash?", "This permanently deletes everything in Trash.")) return;
  const data = await api("/api/empty-trash", { method: "POST", body: "{}" });
  applyState(data); toast("Trash emptied"); render();
}
function ask(title, body) {
  return new Promise((resolve) => {
    $("modal-title").textContent = title; $("modal-body").textContent = body;
    const modal = $("modal"), ok = $("modal-ok"), cancel = $("modal-cancel");
    const done = (val) => { ok.onclick = cancel.onclick = null; modal.close(); resolve(val); };
    ok.onclick = () => done(true); cancel.onclick = () => done(false); modal.showModal();
  });
}
async function deleteSelected() {
  const ids = [...state.selected];
  if (!ids.length) return;
  if (!await ask(`Delete ${ids.length} photo${ids.length === 1 ? "" : "s"}?`, "They move to Trash.")) return;
  const data = await api("/api/delete", { method: "POST", body: JSON.stringify({ ids }) });
  state.selected.clear();
  applyState(await api("/api/state?" + (state.view === "trash" ? "trash=1" : "")));
  toast(`Moved ${data.deleted?.length || 0} to Trash`); render();
}
$("grid").addEventListener("click", (e) => {
  const check = e.target.closest("[data-check]");
  const tile = e.target.closest(".tile");
  if (check) { e.stopPropagation(); toggleSelect(check.dataset.check); return; }
  if (tile) openLightbox(tile.dataset.id);
});
$("grid").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.classList.contains("tile")) openLightbox(e.target.dataset.id);
  if ((e.key === "x" || e.key === " ") && e.target.classList.contains("tile")) { e.preventDefault(); toggleSelect(e.target.dataset.id); }
});
function toggleSelect(id) {
  if (state.selected.has(id)) state.selected.delete(id); else state.selected.add(id);
  renderSelectBar();
  document.querySelectorAll(".tile").forEach((el) => el.classList.toggle("selected", state.selected.has(el.dataset.id)));
}
$("album-list").addEventListener("click", (e) => {
  const btn = e.target.closest("[data-album]"); if (!btn) return;
  state.view = "album"; state.type = btn.dataset.album; state.selected.clear(); writeHash();
});
document.querySelector(".sidebar").addEventListener("click", (e) => {
  const btn = e.target.closest("[data-view]"); if (!btn) return;
  state.view = btn.dataset.view; state.type = null; state.selected.clear(); writeHash();
});
$("chips").addEventListener("click", (e) => {
  const chip = e.target.closest("[data-chip]"); if (!chip) return;
  state.view = "album"; state.type = chip.dataset.chip === state.type ? null : chip.dataset.chip;
  if (!state.type) state.view = "all"; writeHash();
});
$("search-form").addEventListener("submit", (e) => e.preventDefault());
$("search").addEventListener("input", debounce(() => { state.query = $("search").value.trim(); writeHash(); }, 180));
$("search-clear").addEventListener("click", () => { state.query = ""; writeHash(); });
$("btn-select-all").onclick = () => { state.photos.forEach((p) => state.selected.add(p.id)); render(); };
$("btn-clear-sel").onclick = () => { state.selected.clear(); render(); };
$("btn-delete-sel").onclick = deleteSelected;
$("btn-sort").onclick = async () => {
  $("btn-sort").disabled = true;
  try { const data = await api("/api/sort", { method: "POST", body: "{}" }); applyState(data); toast(`Filed ${data.moved || 0} photos into type folders`); render(); }
  finally { $("btn-sort").disabled = false; }
};
$("btn-upload").onclick = () => $("file-input").click();
$("file-input").addEventListener("change", async (e) => {
  const files = [...e.target.files]; if (!files.length) return; await uploadFiles(files); e.target.value = "";
});
async function uploadFiles(files) {
  const form = new FormData(); files.forEach((f) => form.append("files", f, f.name));
  const data = await api("/api/upload", { method: "POST", body: form });
  applyState(data); toast(`Added ${data.uploaded?.length || files.length} photo${files.length === 1 ? "" : "s"}`); render();
}
$("btn-path").onclick = () => { $("path-input").value = state.library; $("path-modal").showModal(); };
$("path-cancel").onclick = () => $("path-modal").close();
$("path-save").onclick = async () => {
  const path = $("path-input").value.trim(); if (!path) return;
  const data = await api("/api/library", { method: "POST", body: JSON.stringify({ path }) });
  applyState(data); $("path-modal").close(); toast("Library folder updated"); render();
};
$("light-close").onclick = () => $("lightbox").close();
$("light-prev").onclick = () => { if (state.lightIndex > 0) { state.lightIndex -= 1; paintLightbox(); } };
$("light-next").onclick = () => { if (state.lightIndex < state.photos.length - 1) { state.lightIndex += 1; paintLightbox(); } };
$("light-type").onchange = async (e) => {
  const photo = state.photos[state.lightIndex];
  const updated = await api(`/api/photos/${photo.id}`, { method: "PATCH", body: JSON.stringify({ type: e.target.value }) });
  Object.assign(photo, updated); toast(`Moved to ${TYPES.find((t) => t[0] === updated.type)?.[1]}`); await load();
};
$("light-delete").onclick = async () => {
  const photo = state.photos[state.lightIndex]; if (!photo) return;
  if (!await ask("Delete this photo?", "It moves to Trash.")) return;
  await api("/api/delete", { method: "POST", body: JSON.stringify({ ids: [photo.id] }) });
  $("lightbox").close(); await load(); toast("Moved to Trash");
};
document.addEventListener("keydown", (e) => {
  const tag = document.activeElement?.tagName;
  if (e.key === "/" && tag !== "INPUT" && tag !== "SELECT") { e.preventDefault(); $("search").focus(); }
  if (e.key === "Escape" && !$("lightbox").open && !$("modal").open) {
    state.selected.clear(); renderSelectBar();
    document.querySelectorAll(".tile.selected").forEach((el) => el.classList.remove("selected"));
  }
  if ((e.key === "Delete" || e.key === "Backspace") && state.selected.size && tag !== "INPUT") { e.preventDefault(); deleteSelected(); }
  if ($("lightbox").open) { if (e.key === "ArrowLeft") $("light-prev").click(); if (e.key === "ArrowRight") $("light-next").click(); }
});
["dragenter", "dragover"].forEach((ev) => document.addEventListener(ev, (e) => { e.preventDefault(); document.body.classList.add("dragging"); }));
["dragleave", "drop"].forEach((ev) => document.addEventListener(ev, (e) => { e.preventDefault(); document.body.classList.remove("dragging"); }));
document.addEventListener("drop", async (e) => {
  const files = [...(e.dataTransfer?.files || [])].filter((f) => f.type.startsWith("image/"));
  if (files.length) await uploadFiles(files);
});
window.addEventListener("hashchange", () => { routeFromHash(); load(); });
function debounce(fn, ms) { let t; return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); }; }
let refined = false;
async function refineVisuals() {
  if (refined || !state.photos.length) return;
  refined = true;
  try {
    const items = [];
    for (const photo of state.photos.slice(0, 80)) {
      if (photo.source === "manual") continue;
      try { items.push({ id: photo.id, features: await sampleFeatures(`/api/thumbs/${photo.id}`) }); } catch {}
    }
    if (!items.length) return;
    await api("/api/refine", { method: "POST", body: JSON.stringify({ items }) });
    await load();
  } catch (err) { console.warn("visual refine skipped", err); }
}
function sampleFeatures(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const size = 64, canvas = document.createElement("canvas");
      canvas.width = size; canvas.height = size;
      const ctx = canvas.getContext("2d", { willReadFrequently: true });
      ctx.drawImage(img, 0, 0, size, size);
      const { data } = ctx.getImageData(0, 0, size, size);
      let brightness = 0, sat = 0, white = 0, dark = 0, green = 0, blue = 0, warm = 0, skin = 0, edge = 0;
      const n = size * size;
      for (let i = 0; i < data.length; i += 4) {
        const r = data[i], g = data[i + 1], b = data[i + 2];
        const max = Math.max(r, g, b), min = Math.min(r, g, b);
        const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        brightness += lum; sat += max ? (max - min) / max : 0;
        if (lum > 235) white += 1; if (lum < 40) dark += 1;
        if (g > r + 12 && g > b) green += 1; if (b > r + 8 && b > g) blue += 1;
        if (r > g + 8 && r > b) warm += 1;
        if (r > 80 && g > 40 && b > 20 && r > g && g > b && r - b > 20) skin += 1;
        if (i / 4 >= size) { const up = i - size * 4; edge += Math.abs(lum - (0.2126 * data[up] + 0.7152 * data[up + 1] + 0.0722 * data[up + 2])) / 255; }
      }
      resolve({ brightness: brightness / n, saturation: sat / n, whiteFrac: white / n, darkFrac: dark / n, greenFrac: green / n, blueFrac: blue / n, warmFrac: warm / n, skinFrac: skin / n, edge: edge / n });
    };
    img.onerror = reject; img.src = src;
  });
}
