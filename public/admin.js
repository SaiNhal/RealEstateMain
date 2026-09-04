const loginPanel = document.querySelector("#loginPanel");
const dashboard = document.querySelector("#dashboard");
const loginForm = document.querySelector("#loginForm");
const loginStatus = document.querySelector("#loginStatus");
const dashboardStatus = document.querySelector("#dashboardStatus");
const enquiryList = document.querySelector("#enquiryList");
const searchInput = document.querySelector("#searchInput");
const statusFilter = document.querySelector("#statusFilter");
const exportButton = document.querySelector("#exportButton");
const exportStatus = document.querySelector("#exportStatus");

const escapeHtml = (value = "") => value.replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character]);
const displayDate = (value) => new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
const statuses = ["New", "Contacted", "Qualified", "Won", "Lost"];
let searchTimer;
let csrfToken = "";

const protectedHeaders = () => csrfToken ? { "X-CSRF-Token": csrfToken } : {};

function showDashboard() {
  loginPanel.hidden = true;
  dashboard.hidden = false;
  dashboardStatus.textContent = "Login successful. Welcome back.";
  loadEnquiries();
}
function showLogin() {
  dashboard.hidden = true;
  loginPanel.hidden = false;
  dashboardStatus.textContent = "";
  loginForm.reset();
}

async function loadEnquiries() {
  dashboardStatus.textContent = "Loading lead workspace…";
  const params = new URLSearchParams();
  if (searchInput.value.trim()) params.set("q", searchInput.value.trim());
  if (statusFilter.value) params.set("status", statusFilter.value);
  try {
    const response = await fetch(`/api/admin/enquiries?${params}`, { credentials: "same-origin" });
    if (response.status === 401) return showLogin();
    const { enquiries } = await response.json();
    renderEnquiries(enquiries);
    dashboardStatus.textContent = enquiries.length ? `${enquiries.length} ${enquiries.length === 1 ? "lead" : "leads"} in view.` : "No leads match these filters.";
  } catch { dashboardStatus.textContent = "Could not load enquiries. Please refresh and try again."; }
}

function renderEnquiries(enquiries) {
  document.querySelector("#totalCount").textContent = enquiries.length;
  document.querySelector("#typeCount").textContent = new Set(enquiries.map(({ projectType }) => projectType)).size;
  document.querySelector("#latestDate").textContent = enquiries[0] ? displayDate(enquiries[0].createdAt).split(",")[0] : "—";
  if (!enquiries.length) { enquiryList.innerHTML = '<div class="empty"><span>⌁</span><h2>Your lead board is clear</h2><p>New project enquiries will appear here as soon as they are submitted.</p></div>'; return; }
  enquiryList.innerHTML = enquiries.map(leadCard).join("");
}

function leadCard(item) {
  const options = statuses.map((status) => `<option${status === item.status ? " selected" : ""}>${status}</option>`).join("");
  const noteHistory = item.notes ? item.notes.split("\n---\n").map((note) => `<li>${escapeHtml(note)}</li>`).join("") : "<li>No internal notes yet.</li>";
  return `<article class="enquiry-card" data-id="${escapeHtml(item.id)}">
    <header class="lead-summary"><div><p class="type">${escapeHtml(item.projectType)}</p><h2>${escapeHtml(item.name)}</h2></div><div class="lead-meta"><span class="status-pill status-${item.status.toLowerCase()}">${escapeHtml(item.status)}</span><time datetime="${escapeHtml(item.createdAt)}">${displayDate(item.createdAt)}</time></div></header>
    <details class="lead-profile"><summary>View full profile <span>+</span></summary>
      <div class="profile-content">
        <dl><div><dt>Phone</dt><dd><a href="tel:${escapeHtml(item.phone)}">${escapeHtml(item.phone)}</a></dd></div><div><dt>Email</dt><dd>${item.email ? `<a href="mailto:${escapeHtml(item.email)}">${escapeHtml(item.email)}</a>` : "Not provided"}</dd></div><div><dt>Location</dt><dd>${escapeHtml(item.location || "Not provided")}</dd></div><div><dt>Budget</dt><dd>${escapeHtml(item.budget || "Not specified")}</dd></div><div><dt>Timeline</dt><dd>${escapeHtml(item.timeline || "Not specified")}</dd></div></dl>
        <div class="description"><span>Project brief</span><p>${escapeHtml(item.description)}</p></div>
        <div class="note-history"><span>Internal note history</span><ul>${noteHistory}</ul></div>
        <form class="lead-management"><label><span>Lead status</span><select name="status">${options}</select></label><label class="notes"><span>Add internal note</span><textarea name="note" rows="3" placeholder="Next action, call notes, requirements…"></textarea></label><button type="submit">Save update</button><button type="button" class="delete-lead">Delete lead</button></form>
      </div>
    </details>
  </article>`;
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = loginForm.querySelector("button");
  loginStatus.textContent = "Signing in…";
  button.disabled = true;
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: new URLSearchParams(new FormData(loginForm)),
      credentials: "same-origin",
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || "Unable to sign in.");
    csrfToken = result.csrfToken || "";
    loginStatus.textContent = "Login successful. Redirecting to dashboard…";
    showDashboard();
  } catch (error) {
    loginStatus.textContent = error.message || "Unable to sign in.";
  } finally {
    button.disabled = false;
  }
});

enquiryList.addEventListener("submit", async (event) => {
  if (!event.target.matches(".lead-management")) return;
  event.preventDefault();
  const form = event.target; const button = form.querySelector("button"); const card = form.closest(".enquiry-card");
  button.disabled = true; button.textContent = "Saving…";
  try {
    const response = await fetch(`/api/admin/enquiries/${encodeURIComponent(card.dataset.id)}`, { method: "PATCH", headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8", ...protectedHeaders() }, body: new URLSearchParams(new FormData(form)) });
    const result = await response.json(); if (!response.ok) throw new Error(result.message);
    const expanded = card.querySelector(".lead-profile").open;
    card.outerHTML = leadCard(result.enquiry);
    const updatedCard = enquiryList.querySelector(`[data-id="${CSS.escape(result.enquiry.id)}"]`);
    if (expanded && updatedCard) updatedCard.querySelector(".lead-profile").open = true;
    dashboardStatus.textContent = `${result.enquiry.name}'s lead was updated and note history was saved.`;
  } catch (error) { dashboardStatus.textContent = error.message || "Changes could not be saved."; button.disabled = false; button.textContent = "Save changes"; }
});

enquiryList.addEventListener("click", async (event) => {
  const button = event.target.closest(".delete-lead");
  if (!button) return;
  const card = button.closest(".enquiry-card");
  const leadName = card.querySelector("h2").textContent;
  if (!window.confirm(`Delete ${leadName}'s lead permanently? This cannot be undone.`)) return;
  button.disabled = true;
  button.textContent = "Deleting…";
  try {
    const response = await fetch(`/api/admin/enquiries/${encodeURIComponent(card.dataset.id)}`, { method: "DELETE", headers: protectedHeaders() });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message);
    dashboardStatus.textContent = `${leadName}'s lead was permanently deleted.`;
    loadEnquiries();
  } catch (error) {
    dashboardStatus.textContent = error.message || "Lead could not be deleted.";
    button.disabled = false;
    button.textContent = "Delete lead";
  }
});

searchInput.addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(loadEnquiries, 260); });
statusFilter.addEventListener("change", loadEnquiries);
document.querySelector("#refreshButton").addEventListener("click", loadEnquiries);
exportButton.addEventListener("click", () => {
  exportStatus.textContent = "Preparing your complete lead export…";
  window.setTimeout(() => {
    exportStatus.textContent = "Download started. The CSV contains every submitted lead, including status and internal notes.";
  }, 450);
});
document.querySelector("#logoutButton").addEventListener("click", async () => { await fetch("/api/auth/logout", { method: "POST", headers: protectedHeaders() }); csrfToken = ""; showLogin(); });
fetch("/api/auth/session").then((response) => response.json()).then(({ authenticated, csrfToken: token }) => { csrfToken = token || ""; if (authenticated) showDashboard(); });
