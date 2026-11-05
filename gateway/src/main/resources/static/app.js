"use strict";

const CUSTOMER_PAGE_SIZE = 20;
const TRANSACTION_PAGE_SIZE = 10;
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

  let response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new ApiError("Could not reach the server. Check that it is running.", 0);
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

async function selectCustomer(id) {
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
      }, `${customer.firstName} ${customer.lastName}`, el("small", {}, customer.email)))));
  }
  const controls = pager(page, (next) => loadCustomers(next).catch((error) => toast(error.message)));
  $("customer-pager").replaceChildren(...(controls ? [controls] : []));
}

function renderDetail() {
  const detail = $("detail");
  const error = state.detailError ? el("p", { class: "banner-error", role: "alert" }, state.detailError) : null;
  if (!state.customer) {
    detail.replaceChildren(...[error, el("div", { class: "panel" },
      el("p", { class: "empty" }, "Select a customer, or create a new one."))].filter(Boolean));
    return;
  }
  detail.replaceChildren(...[error, customerPanel(), state.account ? accountPanel() : null].filter(Boolean));
}

function customerPanel() {
  const { customer, accounts } = state;
  const accountItems = accounts.map((account) => el("li", {},
    el("button", {
      type: "button",
      class: "account-button",
      "aria-current": state.account?.id === account.id ? "true" : "false",
      onclick: () => selectAccount(account.id),
    },
    el("span", {}, `${titleCase(account.type)} `, el("span", { class: "number muted" }, account.accountNumber)),
    el("span", { class: "money" }, moneyFormat.format(account.balance)))));

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, `${customer.firstName} ${customer.lastName}`),
        el("p", { class: "muted" }, customer.email)),
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

function accountPanel() {
  const { account, transactions } = state;
  const amount = el("input", {
    name: "amount", inputmode: "decimal", autocomplete: "off", placeholder: "0.00", "aria-label": "Amount",
  });
  const amountError = el("span", { class: "field-error", role: "alert" });
  const deposit = el("button", { type: "submit", class: "primary" }, "Deposit");
  const withdraw = el("button", { type: "button" }, "Withdraw");

  const submit = async (operation) => {
    const value = amount.value.trim();
    if (!AMOUNT_PATTERN.test(value) || Number(value) <= 0) {
      amountError.textContent = "Enter an amount above zero, with at most 2 decimals.";
      return;
    }
    amountError.textContent = "";
    deposit.disabled = true;
    withdraw.disabled = true;
    try {
      // Sent as a string, because a JSON number could lose cents as a JavaScript double.
      await api(`/api/accounts/${account.id}/${operation}`, { method: "POST", body: { amount: value } });
      toast(operation === "deposit" ? "Deposit completed." : "Withdrawal completed.");
      await selectAccount(account.id);
      return;
    } catch (error) {
      amountError.textContent = error.fieldErrors.amount ?? error.message;
    }
    deposit.disabled = false;
    withdraw.disabled = false;
  };
  withdraw.onclick = () => submit("withdraw");

  const rows = transactions.content.map((entry) => {
    const isDeposit = entry.type === "DEPOSIT";
    return el("tr", {},
      el("td", {}, dateFormat.format(new Date(entry.createdAt))),
      el("td", {}, titleCase(entry.type)),
      el("td", { class: `right number${isDeposit ? " credit" : ""}` },
        `${isDeposit ? "+" : "−"}${moneyFormat.format(entry.amount)}`),
      el("td", { class: "right number" }, moneyFormat.format(entry.balanceAfter)));
  });

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, `${titleCase(account.type)} account`),
        el("p", { class: "muted number" }, account.accountNumber)),
      isStaff() ? el("button", { type: "button", class: "danger", onclick: confirmDeleteAccount }, "Delete account") : null),
    el("p", { class: "balance money" }, moneyFormat.format(account.balance)),
    isStaff() ? el("form", {
      class: "money-form",
      novalidate: "",
      onsubmit: (event) => { event.preventDefault(); submit("deposit"); },
    }, el("label", {}, "Amount", amount), deposit, withdraw) : null,
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

start().catch((error) => {
  state.detailError = error.message;
  renderDetail();
});
