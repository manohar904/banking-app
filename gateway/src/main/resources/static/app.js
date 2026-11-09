"use strict";

const CUSTOMER_PAGE_SIZE = 20;
const TRANSACTION_PAGE_SIZE = 10;
const AUDIT_PAGE_SIZE = 25;
const AUDIT_SOURCES = { accounts: "Accounts and money", customers: "Customers", auth: "Sign-ins and passwords" };
// Matches the API: at most 17 whole digits and 2 decimals.
const AMOUNT_PATTERN = /^\d{1,17}(\.\d{1,2})?$/;

const state = {};

/** Also run at sign-in and sign-out, so one user's data is never left for the next. */
function resetState() {
  Object.assign(state, {
    user: null,
    customers: { content: [], page: null },
    customerPage: 0,
    customer: null,
    accounts: [],
    account: null,
    transactions: { content: [], page: null },
    transactionPage: 0,
    detailError: null,
    audit: null,
  });
}

resetState();

const isStaff = () => state.user?.role === "STAFF";

// Bumped on every navigation, so a slow response for an old selection is ignored.
let selectionVersion = 0;

const $ = (id) => document.getElementById(id);

class ApiError extends Error {
  constructor(message, status, fieldErrors) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors ?? {};
  }
}

const MONEY_FIELDS = new Set(["balance", "amount", "balanceAfter"]);

/**
 * Keeps money as the digits the server sent. As a JavaScript number, a large balance
 * would be rounded and could show the wrong cents. Older browsers without context.source
 * fall back to a number, which is exact for ordinary balances.
 */
function keepExactMoney(key, value, context) {
  return MONEY_FIELDS.has(key) && context?.source ? context.source : value;
}

/** The server sets this cookie and checks that writes send the value back in a header. */
function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

/** randomUUID only exists on https and localhost, so other addresses build one from random bytes. */
function newIdempotencyKey() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function api(path, options = {}) {
  const init = { method: options.method ?? "GET", headers: { Accept: "application/json" } };
  if (options.body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(options.body);
  }
  if (options.form !== undefined) {
    init.body = new URLSearchParams(options.form);
  }
  if (init.method !== "GET") {
    init.headers["X-XSRF-TOKEN"] = csrfToken();
  }
  // Sign-in and sign-out are handled by the gateway itself; every other write gets a key, so it
  // can be sent again when no answer came back without being carried out twice.
  const canRepeat = init.method !== "GET" && !path.startsWith("/api/auth/log");
  if (canRepeat) {
    init.headers["Idempotency-Key"] = newIdempotencyKey();
  }

  let response;
  for (let attempt = 1; ; attempt++) {
    try {
      response = await fetch(path, init);
    } catch {
      response = null;
    }
    const noAnswer = response === null || response.status === 502 || response.status === 504;
    if (!canRepeat || !noAnswer || attempt === 3) break;
    await new Promise((resolve) => setTimeout(resolve, attempt * 1000));
  }
  if (response === null) {
    throw new ApiError(canRepeat
      ? "Could not reach the server, so this may or may not have been applied. Check before trying again."
      : "Could not reach the server. Check that it is running.", 0);
  }

  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text, keepExactMoney) : null;
  } catch {
    // Handled below: an error without a JSON body gets the generic message.
  }
  if (response.status === 401 && !options.expectSignedOut) {
    showLogin("Your session has ended. Please sign in again.");
  }
  if (!response.ok) {
    throw new ApiError(data?.detail ?? `The request failed (${response.status}).`, response.status, data?.errors);
  }
  return data;
}

/** Builds elements with textContent, so API data can never be interpreted as HTML. */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "class") node.className = value;
    else if (key === "onclick" || key === "onsubmit") node[key] = value;
    else if (key === "disabled") node.disabled = value;
    else if (value !== undefined && value !== null) node.setAttribute(key, value);
  }
  node.append(...children.filter((child) => child !== null && child !== undefined));
  return node;
}

// The API has no currency field, so amounts are shown without a symbol.
const moneyFormat = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const titleCase = (word) => word.charAt(0) + word.slice(1).toLowerCase();

let toastTimer;
function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $("toast").hidden = true; }, 4000);
}

function pager(page, onChange) {
  if (!page || page.totalPages <= 1) return null;
  return el("div", { class: "pager" },
    el("button", { type: "button", disabled: page.number === 0, onclick: () => onChange(page.number - 1) }, "Previous"),
    el("span", { class: "muted" }, `Page ${page.number + 1} of ${page.totalPages}`),
    el("button", { type: "button", disabled: page.number + 1 >= page.totalPages, onclick: () => onChange(page.number + 1) }, "Next"));
}

/* ---------- Loading ---------- */

async function loadCustomers(page = state.customerPage) {
  state.customers = await api(`/api/customers?page=${page}&size=${CUSTOMER_PAGE_SIZE}`);
  state.customerPage = page;
  renderCustomers();
}

/** Stepping back after the last row of the last page was deleted. */
async function reloadCustomersAfterDelete() {
  await loadCustomers();
  const { page } = state.customers;
  if (state.customers.content.length === 0 && page.totalPages > 0) {
    await loadCustomers(page.totalPages - 1);
  }
}

/** Each service keeps its own log, so one is shown at a time. That also keeps real page numbers. */
async function openAuditLog(source = "accounts", page = 0) {
  const version = ++selectionVersion;
  let audit = { source, entries: { content: [], page: null }, actors: {} };
  let failure = null;
  try {
    const entries = await api(`/api/${source}/audit?page=${page}&size=${AUDIT_PAGE_SIZE}`);
    const ids = [...new Set(entries.content.map((entry) => entry.actorUserId).filter((id) => id !== null))];
    const actors = ids.length ? await api(`/api/auth/audit/actors?ids=${ids.join(",")}`) : {};
    audit = { source, entries, actors };
  } catch (error) {
    failure = error.message;
  }
  if (version !== selectionVersion) return;
  state.audit = audit;
  state.customer = null;
  state.account = null;
  state.detailError = failure;
  renderCustomers();
  renderDetail();
}

function closeAuditLog() {
  selectionVersion++;
  state.audit = null;
  state.detailError = null;
  renderDetail();
}

async function selectCustomer(id) {
  state.audit = null;
  const version = ++selectionVersion;
  try {
    const [customer, accounts] = await Promise.all([
      api(`/api/customers/${id}`),
      fetchAllAccounts(id),
    ]);
    if (version !== selectionVersion) return;
    state.customer = customer;
    state.accounts = accounts;
    state.account = null;
    state.detailError = null;
  } catch (error) {
    if (version !== selectionVersion) return;
    state.customer = null;
    state.account = null;
    state.detailError = error.message;
  }
  renderCustomers();
  renderDetail();
}

async function fetchAllAccounts(customerId) {
  const accounts = [];
  let page = 0;
  let totalPages = 1;
  while (page < totalPages) {
    const result = await api(`/api/accounts?customerId=${customerId}&page=${page}&size=100`);
    accounts.push(...result.content);
    totalPages = result.page.totalPages;
    page++;
  }
  return accounts;
}

async function selectAccount(id, transactionPage = 0) {
  const version = ++selectionVersion;
  try {
    const [account, transactions] = await Promise.all([
      api(`/api/accounts/${id}`),
      api(`/api/accounts/${id}/transactions?page=${transactionPage}&size=${TRANSACTION_PAGE_SIZE}`),
    ]);
    if (version !== selectionVersion) return;
    state.account = account;
    state.transactions = transactions;
    state.transactionPage = transactionPage;
    state.accounts = state.accounts.map((existing) => (existing.id === account.id ? account : existing));
    state.detailError = null;
  } catch (error) {
    if (version !== selectionVersion) return;
    state.detailError = error.message;
  }
  renderDetail();
}

/* ---------- Rendering ---------- */

/** Initials on a colour that stays the same for each customer. */
function avatar(customer, extraClass = "") {
  const initials = `${customer.firstName.charAt(0)}${customer.lastName.charAt(0)}`.toUpperCase();
  return el("span", { class: `avatar avatar-${customer.id % 6} ${extraClass}`, "aria-hidden": "true" }, initials);
}

function welcomePanel() {
  if (!isStaff()) return el("div", { class: "panel" }, el("p", { class: "empty" }, "Nothing to show yet."));
  return el("div", { class: "panel welcome" },
    el("h2", {}, "Welcome back"),
    el("p", {}, "Pick a customer on the left to see their accounts, or add a new one."),
    el("div", { class: "welcome-tiles" },
      el("div", { class: "welcome-tile tile-customers" },
        el("strong", {}, "Customers"), el("span", {}, "Open accounts and manage sign-ins.")),
      el("div", { class: "welcome-tile tile-money" },
        el("strong", {}, "Money"), el("span", {}, "Deposit, withdraw and transfer.")),
      el("div", { class: "welcome-tile tile-audit" },
        el("strong", {}, "Audit log"), el("span", {}, "See who changed what, and when."))));
}

function auditBadgeClass(action) {
  if (action.startsWith("MONEY") || action.startsWith("TRANSFER")) return "badge-money";
  if (action.startsWith("LOGIN") || action.startsWith("PASSWORD")) return "badge-security";
  return "badge-records";
}

function renderCustomers() {
  const list = $("customer-list");
  const { content, page } = state.customers;
  if (content.length === 0) {
    list.replaceChildren(el("li", { class: "empty" }, "No customers yet."));
  } else {
    list.replaceChildren(...content.map((customer) => el("li", {},
      el("button", {
        type: "button",
        class: "list-button",
        "aria-current": state.customer?.id === customer.id ? "true" : "false",
        onclick: () => selectCustomer(customer.id),
      }, avatar(customer), el("span", { class: "person-text" },
        `${customer.firstName} ${customer.lastName}`, el("small", {}, customer.email))))));
  }
  const controls = pager(page, (next) => loadCustomers(next).catch((error) => toast(error.message)));
  $("customer-pager").replaceChildren(...(controls ? [controls] : []));
}

function renderDetail() {
  const detail = $("detail");
  const error = state.detailError ? el("p", { class: "banner-error", role: "alert" }, state.detailError) : null;
  if (state.audit) {
    detail.replaceChildren(...[error, auditPanel()].filter(Boolean));
    return;
  }
  if (!state.customer) {
    detail.replaceChildren(...[error, welcomePanel()].filter(Boolean));
    return;
  }
  detail.replaceChildren(...[error, customerPanel(), state.account ? accountPanel() : null].filter(Boolean));
}

function customerPanel() {
  const { customer, accounts } = state;
  const accountItems = accounts.map((account) => el("li", {},
    el("button", {
      type: "button",
      class: `account-button account-${account.type.toLowerCase()}`,
      "aria-current": state.account?.id === account.id ? "true" : "false",
      onclick: () => selectAccount(account.id),
    },
    el("span", { class: "account-name" },
      el("span", { class: "account-icon", "aria-hidden": "true" }, account.type.charAt(0)),
      el("span", {}, titleCase(account.type), el("small", { class: "number muted" }, account.accountNumber))),
    el("span", { class: "money" }, moneyFormat.format(account.balance)))));

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", { class: "person" },
        avatar(customer, "avatar-large"),
        el("div", { class: "person-text" },
          el("h2", {}, `${customer.firstName} ${customer.lastName}`),
          el("p", { class: "muted" }, customer.email))),
      isStaff() ? el("div", { class: "button-row" },
        el("button", { type: "button", onclick: () => openCustomerDialog(customer) }, "Edit"),
        el("button", { type: "button", onclick: openSetLoginDialog }, "Set password"),
        el("button", { type: "button", class: "danger", onclick: confirmDeleteCustomer }, "Delete")) : null),
    el("div", { class: "panel-head spaced-top" },
      el("h3", {}, "Accounts"),
      isStaff() ? el("button", { type: "button", onclick: openAccountDialog }, "New account") : null),
    accounts.length
      ? el("ul", { class: "account-list" }, ...accountItems)
      : el("p", { class: "empty" }, "This customer has no accounts yet."));
}

function auditActor(entry, actors) {
  if (entry.actorUserId === null) return titleCase(entry.actorRole);
  const who = actors[entry.actorUserId] ?? `User ${entry.actorUserId}`;
  return `${who} (${entry.actorRole.toLowerCase()})`;
}

/** For example { balanceAfter: "5.00" } becomes "balance after: 5.00". */
function auditDetails(details) {
  return Object.entries(details)
    .map(([key, value]) => `${key.replace(/[A-Z]/g, (letter) => ` ${letter.toLowerCase()}`)}: ${value}`)
    .join(", ");
}

function auditPanel() {
  const { source, entries, actors } = state.audit;
  const select = el("select", { name: "source", "aria-label": "Which log" },
    ...Object.entries(AUDIT_SOURCES).map(([value, label]) => el("option", { value }, label)));
  select.value = source;
  select.onchange = () => openAuditLog(select.value);

  const rows = entries.content.map((entry) => el("tr", {},
    el("td", {}, dateFormat.format(new Date(entry.occurredAt))),
    el("td", {}, auditActor(entry, actors)),
    el("td", {}, el("span", { class: `badge ${auditBadgeClass(entry.action)}` }, titleCase(entry.action.replaceAll("_", " ")))),
    el("td", { class: "number" }, entry.targetId),
    el("td", { class: "wrap" }, auditDetails(entry.details))));

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, "Audit log"),
        el("p", { class: "muted" }, "Who changed what, newest first.")),
      el("button", { type: "button", onclick: closeAuditLog }, "Close")),
    el("label", { class: "audit-source" }, "Show", select),
    rows.length
      ? el("div", { class: "table-wrap" }, el("table", {},
          el("thead", {}, el("tr", {},
            el("th", { scope: "col" }, "Date"),
            el("th", { scope: "col" }, "Who"),
            el("th", { scope: "col" }, "Action"),
            el("th", { scope: "col" }, "Record"),
            el("th", { scope: "col" }, "Details"))),
          el("tbody", {}, ...rows)))
      : el("p", { class: "empty" }, "Nothing recorded here yet."),
    pager(entries.page, (next) => openAuditLog(source, next)));
}

function entryLabel(entry) {
  if (entry.type === "TRANSFER_OUT") return `Transfer to ${entry.counterpartyAccountNumber}`;
  if (entry.type === "TRANSFER_IN") return `Transfer from ${entry.counterpartyAccountNumber}`;
  return titleCase(entry.type);
}

function accountPanel() {
  const { account, transactions } = state;
  const amount = el("input", {
    name: "amount", inputmode: "decimal", autocomplete: "off", placeholder: "0.00", "aria-label": "Amount",
  });
  const amountError = el("span", { class: "field-error", role: "alert" });
  const deposit = el("button", { type: "submit", class: "primary" }, "Deposit");
  const withdraw = el("button", { type: "button" }, "Withdraw");
  const toAccount = el("input", {
    name: "toAccountNumber", inputmode: "numeric", autocomplete: "off", maxlength: "12", placeholder: "12 digits",
    "aria-label": "To account number",
  });
  const transfer = el("button", { type: "button" }, "Transfer");

  const submit = async (operation) => {
    const value = amount.value.trim();
    if (!AMOUNT_PATTERN.test(value) || Number(value) <= 0) {
      amountError.textContent = "Enter an amount above zero, with at most 2 decimals.";
      return;
    }
    const toAccountNumber = toAccount.value.trim();
    if (operation === "transfer" && !/^\d{12}$/.test(toAccountNumber)) {
      amountError.textContent = "Enter the 12-digit number of the account to transfer to.";
      return;
    }
    amountError.textContent = "";
    deposit.disabled = true;
    withdraw.disabled = true;
    transfer.disabled = true;
    try {
      // Sent as a string, because a JSON number could lose cents as a JavaScript double.
      await api(`/api/accounts/${account.id}/${operation}`, {
        method: "POST",
        body: operation === "transfer" ? { toAccountNumber, amount: value } : { amount: value },
      });
      toast({ deposit: "Deposit completed.", withdraw: "Withdrawal completed.", transfer: "Transfer completed." }[operation]);
      await selectAccount(account.id);
      return;
    } catch (error) {
      amountError.textContent = error.fieldErrors.amount ?? error.fieldErrors.toAccountNumber ?? error.message;
    }
    deposit.disabled = false;
    withdraw.disabled = false;
    transfer.disabled = false;
  };
  withdraw.onclick = () => submit("withdraw");
  transfer.onclick = () => submit("transfer");

  const rows = transactions.content.map((entry) => {
    const isCredit = entry.type === "DEPOSIT" || entry.type === "TRANSFER_IN";
    return el("tr", {},
      el("td", {}, dateFormat.format(new Date(entry.createdAt))),
      el("td", {}, el("span", { class: "txn" },
        el("span", { class: `txn-icon ${isCredit ? "txn-in" : "txn-out"}`, "aria-hidden": "true" }, isCredit ? "↓" : "↑"),
        entryLabel(entry))),
      el("td", { class: `right number${isCredit ? " credit" : ""}` },
        `${isCredit ? "+" : "−"}${moneyFormat.format(entry.amount)}`),
      el("td", { class: "right number" }, moneyFormat.format(entry.balanceAfter)));
  });

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, `${titleCase(account.type)} account`),
        el("p", { class: "muted number" }, account.accountNumber)),
      isStaff() ? el("button", { type: "button", class: "danger", onclick: confirmDeleteAccount }, "Delete account") : null),
    el("div", { class: `balance-card balance-${account.type.toLowerCase()}` },
      el("p", { class: "balance-label" }, "Balance"),
      el("p", { class: "balance money" }, moneyFormat.format(account.balance))),
    isStaff() ? el("form", {
      class: "money-form",
      novalidate: "",
      onsubmit: (event) => { event.preventDefault(); submit("deposit"); },
    }, el("label", {}, "Amount", amount), deposit, withdraw, el("label", {}, "To account", toAccount), transfer) : null,
    isStaff() ? amountError : null,
    el("h3", { class: "spaced-top" }, "Transactions"),
    rows.length
      ? el("div", { class: "table-wrap" }, el("table", {},
          el("thead", {}, el("tr", {},
            el("th", { scope: "col" }, "Date"),
            el("th", { scope: "col" }, "Type"),
            el("th", { scope: "col", class: "right" }, "Amount"),
            el("th", { scope: "col", class: "right" }, "Balance"))),
          el("tbody", {}, ...rows)))
      : el("p", { class: "empty" }, "No transactions yet."),
    pager(transactions.page, (next) => selectAccount(account.id, next)));
}

/* ---------- Dialogs ---------- */

function showFormError(form, message) {
  const banner = form.querySelector("[data-form-error]");
  banner.textContent = message ?? "";
  banner.hidden = !message;
}

function clearFieldErrors(form) {
  form.querySelectorAll("[data-error-for]").forEach((node) => { node.textContent = ""; });
}

/** Runs a dialog's action, keeping the dialog open with the error if it fails. */
async function submitDialog(dialog, form, action) {
  const submit = form.querySelector('[type="submit"]');
  submit.disabled = true;
  showFormError(form, null);
  clearFieldErrors(form);
  try {
    await action();
    dialog.close();
  } catch (error) {
    const fields = Object.entries(error.fieldErrors ?? {});
    for (const [field, message] of fields) {
      const target = form.querySelector(`[data-error-for="${field}"]`);
      if (target) target.textContent = message;
    }
    if (fields.length === 0) showFormError(form, error.message);
  } finally {
    submit.disabled = false;
  }
}

let editingCustomerId = null;

function openCustomerDialog(customer = null) {
  const form = $("customer-form");
  editingCustomerId = customer?.id ?? null;
  $("customer-dialog-title").textContent = customer ? "Edit customer" : "New customer";
  form.elements.firstName.value = customer?.firstName ?? "";
  form.elements.lastName.value = customer?.lastName ?? "";
  form.elements.email.value = customer?.email ?? "";
  showFormError(form, null);
  clearFieldErrors(form);
  $("customer-dialog").showModal();
}

function openAccountDialog() {
  const form = $("account-form");
  form.reset();
  showFormError(form, null);
  $("account-dialog").showModal();
}

function openSetLoginDialog() {
  const form = $("set-login-form");
  form.reset();
  $("set-login-message").textContent =
    `${state.customer.firstName} will sign in as ${state.customer.email}. Use at least 12 characters and share it securely.`;
  showFormError(form, null);
  clearFieldErrors(form);
  $("set-login-dialog").showModal();
}

function openPasswordDialog() {
  const form = $("password-form");
  form.reset();
  showFormError(form, null);
  clearFieldErrors(form);
  $("password-dialog").showModal();
}

let confirmAction = null;

function openConfirm(title, message, action) {
  $("confirm-title").textContent = title;
  $("confirm-message").textContent = message;
  showFormError($("confirm-form"), null);
  confirmAction = action;
  $("confirm-dialog").showModal();
}

function confirmDeleteCustomer() {
  const { customer, accounts } = state;
  if (accounts.length > 0) {
    // The API only reports a generic conflict here, so explain the actual rule first.
    state.detailError = "This customer still has accounts, so they cannot be deleted.";
    renderDetail();
    return;
  }
  openConfirm("Delete customer", `Delete ${customer.firstName} ${customer.lastName}? This cannot be undone.`, async () => {
    await api(`/api/customers/${customer.id}`, { method: "DELETE" });
    selectionVersion++;
    state.customer = null;
    state.account = null;
    state.detailError = null;
    await reloadCustomersAfterDelete();
    renderDetail();
    toast("Customer deleted.");
  });
}

function confirmDeleteAccount() {
  const { account } = state;
  openConfirm("Delete account", `Delete account ${account.accountNumber}? This cannot be undone.`, async () => {
    await api(`/api/accounts/${account.id}`, { method: "DELETE" });
    state.detailError = null;
    await selectCustomer(account.customerId);
    toast("Account deleted.");
  });
}

/* ---------- Signing in and out ---------- */

function showLogin(message = null) {
  selectionVersion++;
  resetState();
  document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
  $("detail").replaceChildren();
  $("customer-list").replaceChildren();
  $("customer-pager").replaceChildren();
  $("app").hidden = true;
  $("user-menu").hidden = true;
  document.body.classList.remove("customer-view");

  const form = $("login-form");
  form.elements.password.value = "";
  showFormError(form, message);
  $("login-view").hidden = false;
  form.elements.email.focus();
}

async function showApp(user) {
  resetState();
  state.user = user;
  $("login-view").hidden = true;
  $("app").hidden = false;
  $("user-menu").hidden = false;
  $("user-email").textContent = user.email;
  $("new-customer").hidden = !isStaff();
  $("audit-log").hidden = !isStaff();
  $("sidebar").hidden = !isStaff();
  document.body.classList.toggle("customer-view", !isStaff());

  if (isStaff()) {
    renderDetail();
    await loadCustomers();
  } else {
    await selectCustomer(user.customerId);
  }
}

async function start() {
  let user;
  try {
    user = await api("/api/auth/me", { expectSignedOut: true });
  } catch (error) {
    showLogin(error.status === 401 ? null : error.message);
    return;
  }
  await showApp(user);
}

$("login-form").onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target;
  const submit = form.querySelector('[type="submit"]');
  submit.disabled = true;
  showFormError(form, null);
  try {
    await api("/api/auth/login", {
      method: "POST",
      expectSignedOut: true,
      form: { email: form.elements.email.value.trim(), password: form.elements.password.value },
    });
    form.reset();
    await start();
  } catch (error) {
    showFormError(form, error.message);
    form.elements.password.value = "";
  } finally {
    submit.disabled = false;
  }
};

$("audit-log").onclick = () => openAuditLog();

$("sign-out").onclick = async () => {
  try {
    await api("/api/auth/logout", { method: "POST" });
    // A full reload leaves nothing from this session in memory.
    window.location.reload();
  } catch (error) {
    toast(error.message);
  }
};

/* ---------- Setup ---------- */

$("new-customer").onclick = () => openCustomerDialog();
$("change-password").onclick = openPasswordDialog;

$("set-login-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("set-login-dialog"), form, async () => {
    await api(`/api/customers/${state.customer.id}/login`, {
      method: "PUT",
      body: { password: form.elements.password.value },
    });
    form.reset();
    toast("Password saved.");
  });
};

$("password-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("password-dialog"), form, async () => {
    await api("/api/auth/password", {
      method: "POST",
      body: {
        currentPassword: form.elements.currentPassword.value,
        newPassword: form.elements.newPassword.value,
      },
    });
    form.reset();
    toast("Password changed.");
  });
};

document.querySelectorAll("dialog [data-cancel]").forEach((button) => {
  button.onclick = () => button.closest("dialog").close();
});

$("customer-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  const body = {
    firstName: form.elements.firstName.value.trim(),
    lastName: form.elements.lastName.value.trim(),
    email: form.elements.email.value.trim(),
  };
  submitDialog($("customer-dialog"), form, async () => {
    const saved = editingCustomerId === null
      ? await api("/api/customers", { method: "POST", body })
      : await api(`/api/customers/${editingCustomerId}`, { method: "PUT", body });
    await loadCustomers();
    if (editingCustomerId === null) {
      await selectCustomer(saved.id);
    } else {
      state.customer = saved;
      state.detailError = null;
      renderCustomers();
      renderDetail();
    }
    toast("Customer saved.");
  });
};

$("account-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("account-dialog"), form, async () => {
    const created = await api("/api/accounts", {
      method: "POST",
      body: { customerId: state.customer.id, type: form.elements.type.value },
    });
    await selectCustomer(state.customer.id);
    await selectAccount(created.id);
    toast("Account opened.");
  });
};

$("confirm-form").onsubmit = (event) => {
  event.preventDefault();
  submitDialog($("confirm-dialog"), event.target, () => confirmAction());
};

$("forgot-password").onclick = () => {
  const form = $("forgot-form");
  form.reset();
  form.elements.email.value = $("login-form").elements.email.value.trim();
  showFormError(form, null);
  clearFieldErrors(form);
  $("forgot-dialog").showModal();
};

$("forgot-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("forgot-dialog"), form, async () => {
    await api("/api/auth/password-reset", {
      method: "POST",
      expectSignedOut: true,
      body: { email: form.elements.email.value.trim() },
    });
    toast("If that email has an account, a reset link is on its way. It works for 30 minutes.");
  });
};

// The link's token is in the fragment. It is removed from the address bar straight away, so it
// doesn't stay in the browser's history.
const resetLink = window.location.hash.match(/^#reset=([\w-]+)$/);
if (resetLink) history.replaceState(null, "", window.location.pathname);

$("reset-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("reset-dialog"), form, async () => {
    await api("/api/auth/password-reset/confirm", {
      method: "POST",
      expectSignedOut: true,
      body: { token: resetLink[1], newPassword: form.elements.newPassword.value },
    });
    form.reset();
    toast("Password changed. You can sign in with it now.");
  });
};

/** After start(), because showing the sign-in page closes any open dialog. */
function openResetDialogFromLink() {
  if (resetLink) $("reset-dialog").showModal();
}

start().then(openResetDialogFromLink).catch((error) => {
  state.detailError = error.message;
  renderDetail();
});
