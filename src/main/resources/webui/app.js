const state = {
    bootstrap: null,
    session: null,
    selectedNode: null,
    activeView: "overview"
};

const elements = {
    loginCard: document.getElementById("login-card"),
    loginForm: document.getElementById("login-form"),
    loginMessage: document.getElementById("login-message"),
    appShell: document.getElementById("app-shell"),
    summaryCards: document.getElementById("summary-cards"),
    roadmapList: document.getElementById("roadmap-list"),
    fileTree: document.getElementById("file-tree"),
    fileDetailName: document.getElementById("file-detail-name"),
    fileDetail: document.getElementById("file-detail"),
    adminUsers: document.getElementById("admin-users"),
    adminGovernance: document.getElementById("admin-governance"),
    auditTimeline: document.getElementById("audit-timeline"),
    sessionBadge: document.getElementById("session-badge"),
    summaryBrief: document.getElementById("summary-brief"),
    activeViewTitle: document.getElementById("active-view-title"),
    platformBadges: document.getElementById("platform-badges"),
    heroTitle: document.getElementById("hero-title"),
    heroTagline: document.getElementById("hero-tagline"),
    refreshButton: document.getElementById("refresh-button"),
    navItems: Array.from(document.querySelectorAll(".nav-item")),
    panels: Array.from(document.querySelectorAll(".panel"))
};

document.addEventListener("DOMContentLoaded", async () => {
    bindEvents();
    await refreshBootstrap();
    restoreSession();
});

function bindEvents() {
    elements.loginForm.addEventListener("submit", handleLogin);
    elements.refreshButton.addEventListener("click", refreshBootstrap);
    elements.navItems.forEach((item) => {
        item.addEventListener("click", () => setView(item.dataset.view));
    });
}

async function handleLogin(event) {
    event.preventDefault();
    const formData = new FormData(elements.loginForm);
    const username = String(formData.get("username") || "").trim();
    const password = String(formData.get("password") || "").trim();

    elements.loginMessage.textContent = "Verification des acces...";
    elements.loginMessage.classList.remove("error");

    try {
        const response = await fetch("/api/ui/login", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ username, password })
        });

        const payload = await response.json();
        if (!response.ok || !payload.ok) {
            throw new Error(payload.message || "Connexion refusee.");
        }

        state.session = payload;
        localStorage.setItem("crochet-demo-session", JSON.stringify(payload));
        elements.loginMessage.textContent = "Acces valide. Bienvenue a bord.";
        showApp();
        renderSession();
    } catch (error) {
        elements.loginMessage.textContent = error.message;
        elements.loginMessage.classList.add("error");
    }
}

function restoreSession() {
    const raw = localStorage.getItem("crochet-demo-session");
    if (!raw) {
        return;
    }

    try {
        state.session = JSON.parse(raw);
        showApp();
        renderSession();
    } catch (error) {
        localStorage.removeItem("crochet-demo-session");
    }
}

async function refreshBootstrap() {
    const response = await fetch("/api/ui/bootstrap");
    state.bootstrap = await response.json();

    renderBranding();
    renderSummary();
    renderRoadmap();
    renderExplorer();
    renderAdmin();
    renderAudit();
    renderSession();
}

function showApp() {
    elements.loginCard.classList.add("hidden");
    elements.appShell.classList.remove("hidden");
}

function setView(view) {
    state.activeView = view;
    elements.navItems.forEach((item) => item.classList.toggle("active", item.dataset.view === view));
    elements.panels.forEach((panel) => panel.classList.toggle("active", panel.dataset.panel === view));

    const labels = {
        overview: "Vue d'ensemble",
        files: "Explorateur",
        admin: "Administration",
        audit: "Audit"
    };
    elements.activeViewTitle.textContent = labels[view] || "Plateforme";
}

function renderBranding() {
    const branding = state.bootstrap.branding;
    elements.heroTitle.textContent = branding.title;
    elements.heroTagline.textContent = branding.subtitle;
}

function renderSummary() {
    const summary = state.bootstrap.summary;
    elements.summaryCards.innerHTML = summary.cards.map((card) => `
        <article class="metric-card reveal">
            <p class="eyebrow">${escapeHtml(card.label)}</p>
            <span class="metric-value">${escapeHtml(card.value)}</span>
            <p class="metric-note">${escapeHtml(card.note)}</p>
        </article>
    `).join("");

    elements.platformBadges.innerHTML = `
        ${badge(`Mode ${summary.authMode}`, "sea")}
        ${badge(`SFTP ${summary.sftpPort}`, "brass")}
        ${badge(`Web ${summary.webPort}`, "sea")}
        ${badge(`${summary.usersCount} comptes`, "brass")}
    `;

    elements.summaryBrief.textContent =
        `${summary.filesCount} fichiers, ${summary.foldersCount} dossiers, ${summary.auditCount} evenements d'audit.`;
}

function renderRoadmap() {
    const roadmap = state.bootstrap.roadmap || [];
    elements.roadmapList.innerHTML = roadmap.map((item) => `
        <li class="roadmap-item">
            <strong>${escapeHtml(item.title)}</strong>
            <p>${escapeHtml(item.detail)}</p>
        </li>
    `).join("");
}

function renderExplorer() {
    const entries = state.bootstrap.explorer.entries || [];
    if (!state.selectedNode && entries.length > 0) {
        state.selectedNode = entries[0];
    }

    elements.fileTree.innerHTML = entries.length
        ? `<div class="tree-root">${entries.map(renderNode).join("")}</div>`
        : "<p>Aucun depot visible pour le moment.</p>";

    if (state.selectedNode) {
        renderDetail(state.selectedNode);
    }

    elements.fileTree.querySelectorAll(".tree-row").forEach((button) => {
        button.addEventListener("click", () => {
            const nodePath = button.dataset.path;
            const node = findNodeByPath(entries, nodePath);
            if (!node) {
                return;
            }
            state.selectedNode = node;
            renderExplorer();
        });
    });
}

function renderNode(node) {
    const selected = state.selectedNode && state.selectedNode.path === node.path;
    const children = Array.isArray(node.children) ? node.children : [];
    return `
        <div class="tree-node">
            <button class="tree-row ${selected ? "selected" : ""}" data-path="${escapeHtml(node.path)}" type="button">
                <div>
                    <strong>${iconFor(node.kind)} ${escapeHtml(node.name)}</strong>
                    <div class="tree-meta">
                        <span>${escapeHtml(node.kind)}</span>
                        <span>${escapeHtml(formatSize(node.size))}</span>
                    </div>
                </div>
                <span>${node.childrenCount || 0}</span>
            </button>
            ${children.length ? `<div class="tree-children">${children.map(renderNode).join("")}</div>` : ""}
        </div>
    `;
}

function renderDetail(node) {
    elements.fileDetailName.textContent = node.name;
    const entries = [
        ["Chemin", node.path],
        ["Type", node.kind],
        ["Taille", formatSize(node.size)],
        ["Modifie", formatDate(node.updatedAt)],
        ["Proprietaire", node.owner || "n/a"],
        ["Masque", node.hidden ? "Oui" : "Non"],
        ["Enfants", String(node.childrenCount || 0)]
    ];

    elements.fileDetail.innerHTML = entries.map(([label, value]) => `
        <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(value)}</dd>
        </div>
    `).join("");
}

function renderAdmin() {
    const admin = state.bootstrap.admin;
    elements.adminUsers.innerHTML = admin.users.map((user) => `
        <article class="user-card">
            <div class="user-card-header">
                <div>
                    <strong>${escapeHtml(user.username)}</strong>
                    <p>${escapeHtml(user.home)}</p>
                </div>
                ${badge(user.status, user.status === "Provisionne" ? "sea" : "ember")}
            </div>
            <div class="tag-row">
                ${badge(user.role, "brass")}
                ${badge(user.group, "sea")}
                ${badge(user.auth, "sea")}
                ${badge(`authorized_keys ${user.authorizedKeys}`, user.authorizedKeys === "Present" ? "brass" : "ember")}
            </div>
            <div class="tag-row">
                ${user.rights.map((right) => badge(right, "sea")).join("")}
            </div>
        </article>
    `).join("");

    const groups = admin.groups.map((group) => `
        <article class="gov-card">
            <strong>${escapeHtml(group.name)}</strong>
            <p>${escapeHtml(group.purpose)}</p>
        </article>
    `).join("");
    const roles = admin.roles.map((role) => `
        <article class="gov-card">
            <strong>${escapeHtml(role.name)}</strong>
            <p>${escapeHtml(role.coverage)}</p>
        </article>
    `).join("");
    elements.adminGovernance.innerHTML = groups + roles;
}

function renderAudit() {
    const events = state.bootstrap.audit.events || [];
    elements.auditTimeline.innerHTML = events.length
        ? events.map((event) => `
            <article class="audit-item">
                <div class="audit-top">
                    <div>
                        <strong>${escapeHtml(event.action)}</strong>
                        <div class="audit-meta">
                            <span>${escapeHtml(event.actor)}</span>
                            <span>${escapeHtml(event.channel)}</span>
                            <span>${escapeHtml(formatDate(event.timestamp))}</span>
                        </div>
                    </div>
                    <span class="audit-outcome ${escapeHtml(event.outcome.toLowerCase())}">${escapeHtml(event.outcome)}</span>
                </div>
                <p>${escapeHtml(event.detail)}</p>
                <div class="audit-meta">
                    <span>Cible</span>
                    <span>${escapeHtml(event.target)}</span>
                </div>
            </article>
        `).join("")
        : "<p>Aucun evenement d'audit disponible.</p>";
}

function renderSession() {
    if (!state.session) {
        elements.sessionBadge.textContent = "Non connecte";
        return;
    }
    elements.sessionBadge.textContent = `${state.session.username} - ${state.session.role}`;
}

function findNodeByPath(nodes, path) {
    for (const node of nodes) {
        if (node.path === path) {
            return node;
        }
        if (Array.isArray(node.children)) {
            const found = findNodeByPath(node.children, path);
            if (found) {
                return found;
            }
        }
    }
    return null;
}

function badge(label, variant) {
    return `<span class="pill ${variant}">${escapeHtml(label)}</span>`;
}

function iconFor(kind) {
    if (kind === "directory") {
        return "[DIR]";
    }
    if (kind === "file") {
        return "[FILE]";
    }
    return "[?]";
}

function formatSize(value) {
    const size = Number(value || 0);
    if (size < 1024) {
        return `${size} o`;
    }
    if (size < 1024 * 1024) {
        return `${(size / 1024).toFixed(1)} Ko`;
    }
    return `${(size / (1024 * 1024)).toFixed(1)} Mo`;
}

function formatDate(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString("fr-FR");
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
