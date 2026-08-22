const TYPES = [
  ["people", "People"],
  ["animals", "Animals"],
  ["nature", "Nature"],
  ["food", "Food"],
  ["vehicles", "Vehicles"],
  ["screenshots", "Screenshots"],
  ["documents", "Documents"],
  ["night", "Night"],
  ["graphics", "Graphics"],
  ["other", "Other"],
];

const state = {
  photos: [],
  albums: [],
  total: 0,
  trashCount: 0,
  library: "",
  organized: false,
  view: "all",
  type: null,
  query: "",
  selected: new Set(),
  lightIndex: -1,
  accounts: { google: {}, samsung: {} },
};

const $ = (id) => document.getElementById(id);

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: opts.body instanceof FormData ? {} : { "Content-Type": "application/json" },
    ...opts,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

function routeFromHash() {
  const hash = location.hash.replace(/^#/, "") || "/";
  const [path, qs] = hash.split("?");
  const params = new URLSearchParams(qs || "");
  if (params.get("q")) state.query = params.get("q");
  if (path.startsWith("/album/")) {
    state.view = "album";
    state.type = path.split("/")[2];
  } else if (path.startsWith("/trash")) {
    state.view = "trash";
    state.type = null;
  } else {
    state.view = "all";
    state.type = null;
  }
}

function writeHash() {
  let path = "/";
  if (state.view === "album" && state.type) path = `/album/${state.type}`;
  if (state.view === "trash") path = "/trash";
  const params = new URLSearchParams();
  if (state.query) params.set("q", state.query);
  location.hash = path + (params.toString() ? `?${params}` : "");
}

async function load() {
  const qs = new URLSearchParams();
  if (state.query) qs.set("q", state.query);
  if (state.view === "album" && state.type) qs.set("type", state.type);
  if (state.view === "trash") qs.set("trash", "1");
  const data = await api("/api/state?" + qs.toString());
  applyState(data);
  render();
  refineVisuals();
}

function applyState(data) {
  state.photos = data.photos || [];
  state.albums = data.albums || [];
  state.total = data.total || 0;
  state.trashCount = data.trash_count || 0;
  state.library = data.library || "";
  state.organized = !!data.organized;
  if (data.accounts) state.accounts = data.accounts;
  const keep = new Set(state.photos.map((p) => p.id));
  state.selected = new Set([...state.selected].filter((id) => keep.has(id)));
}

function render() {
  $("count-all").textContent = state.total;
  $("count-trash").textContent = state.trashCount;
  $("library-path").textContent = state.library;
  $("search").value = state.query;
  $("search-clear").hidden = !state.query;
  document.querySelectorAll(".nav-item").forEach((el) => {
    const view = el.dataset.view;
    el.classList.toggle("active", (view === "all" && state.view === "all") || (view === "trash" && state.view === "trash"));
  });
  $("album-list").innerHTML = state.albums.map((a) => {
    const on = state.view === "album" && state.type === a.id;
    const cover = a.cover ? `<img class="cover-dot" src="/api/thumbs/${a.cover}" alt="">` : `<span class="cover-dot"></span>`;
    return `<button class="nav-item ${on ? "active" : ""}" data-album="${a.id}">${cover}<span>${a.label}</span><em>${a.count}</em></button>`;
  }).join("");
  const album = state.albums.find((a) => a.id === state.type);
  const titles = {
    all: ["All photos", `${state.total} photo${state.total === 1 ? "" : "s"} · types update as Lumina looks at each file`],
    album: [album ? album.label : "Album", album ? `${album.count} in this folder` : ""],
    trash: ["Trash", "Deleted photos sit here until you empty trash"],
  };
  const t = titles[state.view];
  $("view-title").textContent = state.query ? `Results for “${state.query}”` : t[0];
  $("view-sub").textContent = t[1];
  const actions = $("view-actions");
  actions.innerHTML = "";
  if (state.view === "album" && album && album.count) {
    const btn = document.createElement("button");
    btn.className = "danger";
    btn.textContent = `Delete all ${album.label.toLowerCase()}`;
    btn.onclick = () => confirmDeleteType(album);
    actions.appendChild(btn);
  }
  if (state.view === "trash" && state.trashCount) {
    const btn = document.createElement("button");
    btn.className = "danger";
    btn.textContent = "Empty trash";
    btn.onclick = () => confirmEmptyTrash();
    actions.appendChild(btn);
  }
  const chips = $("chips");
  if (state.query) {
    chips.hidden = false;
    chips.innerHTML = TYPES.map(([id, label]) => {
      const on = state.type === id;
      return `<button type="button" class="chip ${on ? "on" : ""}" data-chip="${id}">${label}</button>`;
    }).join("");
  } else chips.hidden = true;
  renderGrid();
  renderSelectBar();
}

function monthLabel(photo) {
  const raw = photo.taken_at || "";
  let d = null;
  if (raw) {
    const iso = raw.replace(/^(\d{4}):(\d{2}):(\d{2})/, "$1-$2-$3").replace(" ", "T");
    d = new Date(iso);
  }
  if (!d || Number.isNaN(d.getTime())) d = new Date((photo.mtime || 0) * 1000);
  if (Number.isNaN(d.getTime())) return "Unknown date";
  return d.toLocaleString(undefined, { month: "long", year: "numeric" });
}

function renderGrid() {
  const root = $("grid");
  if (!state.photos.length) {
    root.innerHTML = `<div class="empty"><h2>${state.query ? "No matching photos" : state.view === "trash" ? "Trash is empty" : "Drop photos here"}</h2><p>${state.query ? "Try a type name like screenshots, pizza, or a date." : "Upload images, or keep the demo library. Lumina files them into type folders."}</p></div>`;
    return;
  }
  const groups = new Map();
  for (const photo of state.photos) {
    const key = monthLabel(photo);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(photo);
  }
  const bits = [];
  for (const [label, photos] of groups) {
    bits.push(`<h2 class="month">${label}</h2><div class="grid">`);
    for (const photo of photos) {
      const selected = state.selected.has(photo.id) ? "selected" : "";
      const typeLabel = TYPES.find((t) => t[0] === photo.type)?.[1] || "Other";
      bits.push(`<article class="tile ${selected}" data-id="${photo.id}" tabindex="0"><img src="/api/thumbs/${photo.id}" alt="" loading="lazy"><span class="check" data-check="${photo.id}"></span><span class="badge">${typeLabel}</span></article>`);
    }
    bits.push("</div>");
  }
  root.innerHTML = bits.join("");
}

function renderSelectBar() {
  const bar = $("select-bar");
  const n = state.selected.size;
  bar.hidden = n === 0;
  $("select-count").textContent = `${n} selected`;
  let push = $("btn-push-sel");
  if (!push) {
    push = document.createElement("button");
    push.id = "btn-push-sel";
    push.type = "button";
    push.className = "ghost";
    push.textContent = "Upload to Google";
    $("select-bar").appendChild(push);
    push.onclick = pushSelectedToGoogle;
  }
  push.hidden = !state.accounts?.google?.connected;
}
