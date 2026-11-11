"use strict";

const CUSTOMER_PAGE_SIZE = 20;
const TRANSACTION_PAGE_SIZE = 10;
const AUDIT_PAGE_SIZE = 25;
const HELP_PAGE_SIZE = 20;
const AUDIT_SOURCES = { accounts: "Accounts, money and loans", customers: "Customers and help", auth: "Sign-ins and passwords" };
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
    allowance: null,
    transactions: { content: [], page: null },
    transactionPage: 0,
    detailError: null,
    audit: null,
    findAccount: false,
    help: null,
    loans: [],
    loan: null,
    loanBoard: null,
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

const MONEY_FIELDS = new Set(["balance", "amount", "balanceAfter", "dailyLimit", "sentToday", "remainingToday",
  "principal", "monthlyPayment", "remainingPrincipal", "nextPaymentAmount", "totalRepayable", "totalInterest",
  "interest", "remainingAfter", "minAmount", "maxAmount"]);

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
function toast(message, kind = "success") {
  const isError = kind === "error";
  const banner = $("toast");
  banner.className = `toast toast-${kind}`;
  banner.replaceChildren(el("span", { class: "toast-icon", "aria-hidden": "true" }, isError ? "!" : "✓"), message);
  banner.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { banner.hidden = true; }, isError ? 10000 : 7000);
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
  state.findAccount = false;
  state.help = null;
  state.loanBoard = null;
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

/** Back to the welcome screen. Only staff have one; a customer's own page is all they see. */
function closeCustomer() {
  selectionVersion++;
  state.customer = null;
  state.account = null;
  state.loan = null;
  state.detailError = null;
  renderCustomers();
  renderDetail();
}

async function selectCustomer(id) {
  state.findAccount = false;
  state.audit = null;
  state.help = null;
  state.loanBoard = null;
  const version = ++selectionVersion;
  try {
    const [customer, accounts, loans] = await Promise.all([
      api(`/api/customers/${id}`),
      fetchAllAccounts(id),
      fetchLoans(id),
    ]);
    if (version !== selectionVersion) return;
    state.customer = customer;
    state.accounts = accounts;
    state.loans = loans;
    state.account = null;
    state.loan = null;
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
    const [account, transactions, allowance] = await Promise.all([
      api(`/api/accounts/${id}`),
      api(`/api/accounts/${id}/transactions?page=${transactionPage}&size=${TRANSACTION_PAGE_SIZE}`),
      api(`/api/accounts/${id}/transfer-allowance`),
    ]);
    if (version !== selectionVersion) return;
    state.account = account;
    state.loan = null;
    state.allowance = allowance;
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
      el("button", { type: "button", class: "welcome-tile tile-customers", onclick: openFirstCustomer },
        el("strong", {}, "Customers"), el("span", {}, "Open accounts and manage sign-ins.")),
      el("button", { type: "button", class: "welcome-tile tile-money", onclick: openFindAccount },
        el("strong", {}, "Money"), el("span", {}, "Deposit, withdraw and transfer.")),
      el("button", { type: "button", class: "welcome-tile tile-loans", onclick: () => openLoanBoard() },
        el("strong", {}, "Loans"), el("span", {}, "Decide on requests and follow repayments.")),
      el("button", { type: "button", class: "welcome-tile tile-audit", onclick: () => openAuditLog() },
        el("strong", {}, "Audit log"), el("span", {}, "See who changed what, and when.")),
      el("button", { type: "button", class: "welcome-tile tile-help", onclick: () => openHelp() },
        el("strong", {}, "Help requests"), el("span", {}, "Answer customers' questions."))));
}

/** The Customers tile: the first customer in the list, or the New customer form if there are none. */
function openFirstCustomer() {
  const first = state.customers.content[0];
  if (!first) {
    openCustomerDialog();
    return;
  }
  selectCustomer(first.id).then(() => $("customer-list").querySelector("button")?.focus());
}

/** The Money tile: money is moved on an account's page, so this finds the account first. */
function openFindAccount() {
  selectionVersion++;
  state.findAccount = true;
  state.audit = null;
  state.help = null;
  state.loanBoard = null;
  state.customer = null;
  state.account = null;
  state.detailError = null;
  renderCustomers();
  renderDetail();
  $("detail").querySelector("input")?.focus();
}

function closeFindAccount() {
  state.findAccount = false;
  renderDetail();
}

function findAccountPanel() {
  const number = el("input", {
    name: "accountNumber", inputmode: "numeric", autocomplete: "off", maxlength: "12", placeholder: "12 digits",
    "aria-label": "Account number",
  });
  const error = el("span", { class: "field-error", role: "alert" });
  const open = el("button", { type: "submit", class: "primary" }, "Open account");

  const submit = async () => {
    const value = number.value.replace(/\s/g, "");
    if (!/^\d{12}$/.test(value)) {
      error.textContent = "Enter the 12-digit account number.";
      return;
    }
    error.textContent = "";
    open.disabled = true;
    try {
      const account = await api(`/api/accounts/by-number/${value}`);
      state.findAccount = false;
      await selectCustomer(account.customerId);
      await selectAccount(account.id);
      return;
    } catch (failure) {
      error.textContent = failure.status === 404 ? "No account has that number." : failure.message;
    }
    open.disabled = false;
  };

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, "Money"),
        el("p", { class: "muted" }, "Find the account to deposit into, withdraw from or transfer from.")),
      el("button", { type: "button", onclick: closeFindAccount }, "Close")),
    el("form", {
      class: "money-form",
      novalidate: "",
      onsubmit: (event) => { event.preventDefault(); submit(); },
    }, el("label", {}, "Account number", number), open),
    error,
    el("p", { class: "muted" }, "Or pick a customer on the left to see all of their accounts."));
}

function auditBadgeClass(action) {
  if (action.startsWith("MONEY") || action.startsWith("TRANSFER") || action.startsWith("LOAN")) return "badge-money";
  if (action.startsWith("LOGIN") || action.startsWith("PASSWORD")) return "badge-security";
  if (action.startsWith("HELP")) return "badge-records";
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
  const controls = pager(page, (next) => loadCustomers(next).catch((error) => toast(error.message, "error")));
  $("customer-pager").replaceChildren(...(controls ? [controls] : []));
}

function renderDetail() {
  const detail = $("detail");
  const error = state.detailError ? el("p", { class: "banner-error", role: "alert" }, state.detailError) : null;
  if (state.help) {
    detail.replaceChildren(...[error, helpPanel()].filter(Boolean));
    return;
  }
  if (state.loanBoard) {
    detail.replaceChildren(...[error, loanBoardPanel()].filter(Boolean));
    return;
  }
  if (state.audit) {
    detail.replaceChildren(...[error, auditPanel()].filter(Boolean));
    return;
  }
  if (!state.customer && state.findAccount) {
    detail.replaceChildren(...[error, findAccountPanel()].filter(Boolean));
    return;
  }
  if (!state.customer) {
    detail.replaceChildren(...[error, welcomePanel()].filter(Boolean));
    return;
  }
  detail.replaceChildren(...[error, customerPanel(), state.loan ? loanPanel() : state.account ? accountPanel() : null].filter(Boolean));
}

/** Everyone can see the setting. Only staff can change it. */
function transactionEmailsRow(customer) {
  const enabled = customer.transactionEmailsEnabled;
  const toggle = async () => {
    try {
      state.customer = await api(`/api/customers/${customer.id}/transaction-emails`, {
        method: "PUT",
        body: { enabled: !enabled },
      });
      state.detailError = null;
      toast(enabled ? "Transaction emails turned off." : "Transaction emails turned on.");
    } catch (error) {
      state.detailError = error.message;
    }
    renderDetail();
  };
  return el("div", { class: "notify-row" },
    el("div", {},
      el("strong", {}, "Transaction emails "),
      el("span", { class: `badge ${enabled ? "badge-money" : "badge-off"}` }, enabled ? "On" : "Off"),
      el("p", { class: "muted" }, enabled
        ? `An email goes to ${customer.email} for every deposit, withdrawal, transfer and loan payment.`
        : "No emails are sent when money moves.")),
    isStaff() ? el("button", { type: "button", onclick: toggle }, enabled ? "Turn off" : "Turn on") : null);
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
        el("button", { type: "button", class: "danger", onclick: confirmDeleteCustomer }, "Delete"),
        el("button", { type: "button", onclick: closeCustomer }, "Close")) : null),
    transactionEmailsRow(customer),
    el("div", { class: "panel-head spaced-top" },
      el("h3", {}, "Accounts"),
      isStaff() ? el("button", { type: "button", onclick: openAccountDialog }, "New account") : null),
    accounts.length
      ? el("ul", { class: "account-list" }, ...accountItems)
      : el("p", { class: "empty" }, "This customer has no accounts yet."),
    ...loansSection());
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
  if (entry.type === "LOAN_PAYOUT") return "Loan paid in";
  if (entry.type === "LOAN_REPAYMENT") return "Loan repayment";
  return titleCase(entry.type);
}

/** Customers have a daily limit on the transfers they send themselves; staff transfers have none. */
function allowanceText({ dailyLimit, remainingToday }) {
  const remaining = moneyFormat.format(remainingToday);
  const limit = moneyFormat.format(dailyLimit);
  if (isStaff()) {
    return `This customer can still send ${remaining} of their ${limit} daily limit themselves today. Transfers made by staff have no limit.`;
  }
  return Number(remainingToday) > 0
    ? `You can send up to ${remaining} more today. Your daily transfer limit is ${limit}.`
    : `You have used today's transfer limit of ${limit}. You can send more tomorrow.`;
}

function accountPanel() {
  const { account, transactions, allowance } = state;
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
  // Customers may only send transfers, so for them Transfer is the main button.
  const transfer = el("button", { type: isStaff() ? "button" : "submit", class: isStaff() ? "" : "primary" }, "Transfer");

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
    const isCredit = ["DEPOSIT", "TRANSFER_IN", "LOAN_PAYOUT"].includes(entry.type);
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
    isStaff()
      ? el("form", {
          class: "money-form",
          novalidate: "",
          onsubmit: (event) => { event.preventDefault(); submit("deposit"); },
        }, el("label", {}, "Amount", amount), deposit, withdraw, el("label", {}, "To account", toAccount), transfer)
      : el("form", {
          class: "money-form",
          novalidate: "",
          onsubmit: (event) => { event.preventDefault(); submit("transfer"); },
        }, el("label", {}, "To account", toAccount), el("label", {}, "Amount", amount), transfer),
    amountError,
    allowance ? el("p", { class: "muted allowance" }, allowanceText(allowance)) : null,
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

/* ---------- Help ---------- */

const HELP_FILTERS = {
  WAITING_FOR_BANK: "Waiting for an answer",
  ANSWERED: "Answered",
  CLOSED: "Closed",
  ALL: "All",
};

/** The same question reads differently for each side: "waiting" means waiting for the bank. */
function helpStatusBadge(status) {
  const text = {
    WAITING_FOR_BANK: isStaff() ? "Needs an answer" : "Waiting for the bank",
    ANSWERED: "Answered",
    CLOSED: "Closed",
  }[status];
  const kind = { WAITING_FOR_BANK: "badge-security", ANSWERED: "badge-money", CLOSED: "badge-off" }[status];
  return el("span", { class: `badge ${kind}` }, text);
}

const FAQ = [
  ["How do I send money to someone?",
    "Open your account, enter the 12-digit number of the account you are paying and the amount, and press Transfer. The money arrives straight away."],
  ["How much can I send in a day?",
    "Your daily transfer limit, and how much of it is left today, are shown under the transfer form. The limit starts again at midnight."],
  ["I forgot my password.",
    "Sign out, then choose \"Forgot password?\" on the sign-in page. We email you a link that works once, for 30 minutes."],
  ["How do I change my password?",
    "Choose \"Change password\" at the top of the page. Use at least 12 characters."],
  ["How do I open another account?",
    "The bank opens accounts for you. Ask us below and say whether you would like a checking or a savings account."],
  ["Why didn't I get an email about a payment?",
    "Your page shows whether transaction emails are on. They can take a few minutes, and may land in your spam folder."],
  ["I think someone else knows my password.",
    "Change it straight away with \"Change password\", then tell us below so we can check your accounts."],
];

async function openHelp(filter = state.help?.filter ?? "WAITING_FOR_BANK", page = 0) {
  state.findAccount = false;
  state.audit = null;
  state.loanBoard = null;
  const version = ++selectionVersion;
  const status = isStaff() && filter !== "ALL" ? `&status=${filter}` : "";
  let requests = { content: [], page: null };
  let failure = null;
  try {
    requests = await api(`/api/help-requests?page=${page}&size=${HELP_PAGE_SIZE}${status}`);
  } catch (error) {
    failure = error.message;
  }
  if (version !== selectionVersion) return;
  state.help = { filter, page, requests, request: null };
  if (isStaff()) {
    state.customer = null;
    state.account = null;
  }
  state.detailError = failure;
  renderCustomers();
  renderDetail();
}

async function openHelpRequest(id) {
  const version = selectionVersion;
  try {
    const request = await api(`/api/help-requests/${id}`);
    if (version !== selectionVersion || !state.help) return;
    state.help.request = request;
    state.detailError = null;
  } catch (error) {
    state.detailError = error.message;
  }
  renderDetail();
}

/** A customer goes back to their own page; staff to the welcome screen. */
function closeHelp() {
  selectionVersion++;
  state.help = null;
  state.detailError = null;
  renderDetail();
}

function backToHelpList() {
  openHelp(state.help.filter, state.help.page);
}

function helpPanel() {
  const { request } = state.help;
  return request ? helpConversationPanel(request) : helpOverviewPanel();
}

function helpOverviewPanel() {
  const { requests, filter } = state.help;
  const items = requests.content.map((request) => el("li", {},
    el("button", { type: "button", class: "help-item", onclick: () => openHelpRequest(request.id) },
      el("span", {},
        request.subject,
        el("small", {}, [
          isStaff() ? request.customerName ?? `Customer ${request.customerId}` : null,
          `last activity ${dateFormat.format(new Date(request.updatedAt))}`,
        ].filter(Boolean).join(" · "))),
      helpStatusBadge(request.status))));
  const list = items.length
    ? el("ul", { class: "help-list" }, ...items)
    : el("p", { class: "empty" }, isStaff() ? "No questions here." : "You haven't asked us anything yet.");
  const pages = pager(requests.page, (next) => openHelp(filter, next));

  if (isStaff()) {
    const select = el("select", { name: "filter", "aria-label": "Which questions" },
      ...Object.entries(HELP_FILTERS).map(([value, label]) => el("option", { value }, label)));
    select.value = filter;
    select.onchange = () => openHelp(select.value);
    return el("div", { class: "panel" },
      el("div", { class: "panel-head" },
        el("div", {},
          el("h2", {}, "Help requests"),
          el("p", { class: "muted" }, "Customers' questions, most recent activity first.")),
        el("button", { type: "button", onclick: closeHelp }, "Close")),
      el("label", { class: "help-filter" }, "Show", select),
      list,
      pages);
  }

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, "Help"),
        el("p", { class: "muted" }, "Answers to common questions, or ask us your own.")),
      el("button", { type: "button", onclick: closeHelp }, "Close")),
    el("h3", { class: "spaced-top" }, "Common questions"),
    el("div", { class: "faq" }, ...FAQ.map(([question, answer]) =>
      el("details", {}, el("summary", {}, question), el("p", {}, answer)))),
    el("h3", { class: "spaced-top" }, "Ask us a question"),
    el("p", { class: "muted" }, "We answer here, and you'll see our reply on this page. Never send us your password."),
    askForm(),
    el("h3", { class: "spaced-top" }, "Your questions"),
    list,
    pages);
}

function askForm() {
  const subject = el("input", { name: "subject", maxlength: "120", autocomplete: "off" });
  const subjectError = el("span", { class: "field-error" });
  const message = el("textarea", { name: "message", maxlength: "2000", rows: "4" });
  const messageError = el("span", { class: "field-error" });
  const formError = el("p", { class: "form-error", role: "alert", hidden: "" });
  const send = el("button", { type: "submit", class: "primary" }, "Send question");

  const submit = async () => {
    subjectError.textContent = subject.value.trim() ? "" : "Say what it is about.";
    messageError.textContent = message.value.trim() ? "" : "Write your question.";
    formError.hidden = true;
    if (subjectError.textContent || messageError.textContent) return;
    send.disabled = true;
    try {
      const created = await api("/api/help-requests", {
        method: "POST",
        body: { subject: subject.value.trim(), message: message.value.trim() },
      });
      toast("Question sent. We'll answer here.");
      await openHelp();
      await openHelpRequest(created.id);
      return;
    } catch (error) {
      subjectError.textContent = error.fieldErrors.subject ?? "";
      messageError.textContent = error.fieldErrors.message ?? "";
      if (!error.fieldErrors.subject && !error.fieldErrors.message) {
        formError.textContent = error.message;
        formError.hidden = false;
      }
    }
    send.disabled = false;
  };

  return el("form", { class: "help-form", novalidate: "", onsubmit: (event) => { event.preventDefault(); submit(); } },
    formError,
    el("label", {}, "Subject", subject, subjectError),
    el("label", {}, "Your question", message, messageError),
    el("div", { class: "actions" }, send));
}

function helpConversationPanel(request) {
  const closed = request.status === "CLOSED";
  const messages = request.messages.map((message) => {
    const customer = isStaff() ? request.customerName ?? "Customer" : "You";
    const author = message.fromBank ? (isStaff() ? "Bank staff" : "The bank") : customer;
    return el("div", { class: `message ${message.fromBank ? "message-from-bank" : "message-from-customer"}` },
      el("small", {}, `${author} · ${dateFormat.format(new Date(message.createdAt))}`),
      el("p", {}, message.body));
  });

  const reply = el("textarea", { name: "message", maxlength: "2000", rows: "3" });
  const replyError = el("span", { class: "field-error", role: "alert" });
  const send = el("button", { type: "submit", class: "primary" }, isStaff() ? "Send answer" : "Send");

  const submit = async () => {
    if (!reply.value.trim()) {
      replyError.textContent = "Write a message first.";
      return;
    }
    replyError.textContent = "";
    send.disabled = true;
    try {
      state.help.request = await api(`/api/help-requests/${request.id}/messages`, {
        method: "POST",
        body: { message: reply.value.trim() },
      });
      state.detailError = null;
      toast("Message sent.");
      renderDetail();
      return;
    } catch (error) {
      replyError.textContent = error.fieldErrors.message ?? error.message;
    }
    send.disabled = false;
  };

  const close = () => openConfirm("Close question", "Close this question? Nobody can reply to it after that.", async () => {
    state.help.request = await api(`/api/help-requests/${request.id}/close`, { method: "POST" });
    state.detailError = null;
    renderDetail();
    toast("Question closed.");
  }, "Close question");

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, request.subject),
        el("p", { class: "muted" },
          isStaff() && request.customerName ? `${request.customerName} · ` : "",
          helpStatusBadge(request.status))),
      el("div", { class: "button-row" },
        isStaff() ? el("button", { type: "button", onclick: () => selectCustomer(request.customerId) }, "Open customer") : null,
        closed ? null : el("button", { type: "button", class: "danger", onclick: close }, "Close question"),
        el("button", { type: "button", onclick: backToHelpList }, "Back"))),
    el("div", { class: "conversation" }, ...messages),
    closed
      ? el("p", { class: "empty" }, isStaff() ? "This question is closed." : "This question is closed. Ask a new one if you need more help.")
      : el("form", { class: "help-form", novalidate: "", onsubmit: (event) => { event.preventDefault(); submit(); } },
          el("label", {}, isStaff() ? "Answer" : "Reply", reply, replyError),
          el("div", { class: "actions" }, send)));
}

/* ---------- Loans ---------- */

const LOAN_FILTERS = {
  REQUESTED: "Waiting for a decision",
  ACTIVE: "Being repaid",
  ALL: "All",
};

const LOAN_STATUS = {
  REQUESTED: ["Waiting for a decision", "badge-security"],
  ACTIVE: ["Being repaid", "badge-money"],
  PAID_OFF: ["Paid off", "badge-records"],
  REJECTED: ["Not approved", "badge-off"],
  CANCELLED: ["Cancelled", "badge-off"],
};

function loanStatusBadge(loan) {
  if (loan.status === "ACTIVE" && loan.overdue) return el("span", { class: "badge badge-danger" }, "Payment overdue");
  const [text, kind] = LOAN_STATUS[loan.status];
  return el("span", { class: `badge ${kind}` }, text);
}

const dayFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });
/** A due date is a calendar day, not a moment, so it is shown without converting time zones. */
const formatDay = (isoDate) => dayFormat.format(new Date(`${isoDate}T12:00:00`));

async function fetchLoans(customerId) {
  try {
    return (await api(`/api/loans?customerId=${customerId}&size=50`)).content;
  } catch {
    // The rest of the customer's page still works without loans.
    return [];
  }
}

async function selectLoan(id) {
  const version = ++selectionVersion;
  try {
    const loan = await api(`/api/loans/${id}`);
    if (version !== selectionVersion) return;
    state.loan = loan;
    state.account = null;
    state.loans = state.loans.map((existing) => (existing.id === loan.id ? loan : existing));
    state.detailError = null;
  } catch (error) {
    if (version !== selectionVersion) return;
    state.detailError = error.message;
  }
  renderDetail();
}

/** After a loan changes money, the customer's balances change too. */
async function refreshAfterLoanChange(loan) {
  const [accounts, loans] = await Promise.all([fetchAllAccounts(loan.customerId), fetchLoans(loan.customerId)]);
  state.accounts = accounts;
  state.loans = loans;
  state.loan = loan;
  state.detailError = null;
  renderDetail();
}

function loanItems() {
  return state.loans.map((loan) => el("li", {},
    el("button", {
      type: "button",
      class: "account-button account-loan",
      "aria-current": state.loan?.id === loan.id ? "true" : "false",
      onclick: () => selectLoan(loan.id),
    },
    el("span", { class: "account-name" },
      el("span", { class: "account-icon", "aria-hidden": "true" }, "L"),
      el("span", {}, `Loan of ${moneyFormat.format(loan.principal)}`,
        el("small", { class: "muted" }, `${loan.termMonths} months · ${loan.purpose}`))),
    loan.status === "ACTIVE"
      ? el("span", { class: "loan-owed" }, el("span", { class: "money" }, moneyFormat.format(loan.remainingPrincipal)),
          el("small", { class: "muted" }, "still owed"))
      : loanStatusBadge(loan))));
}

function loansSection() {
  const canApply = !isStaff() && state.accounts.length > 0;
  return [
    el("div", { class: "panel-head spaced-top" },
      el("h3", {}, "Loans"),
      canApply ? el("button", { type: "button", onclick: openLoanDialog }, "Apply for a loan") : null),
    state.loans.length
      ? el("ul", { class: "account-list" }, ...loanItems())
      : el("p", { class: "empty" }, isStaff() ? "This customer has no loans." : "You have no loans."),
  ];
}

function loanFact(label, value) {
  return el("div", { class: "fact" }, el("dt", {}, label), el("dd", {}, value));
}

function loanPanel() {
  const { loan } = state;
  const active = loan.status === "ACTIVE";
  const facts = el("dl", { class: "facts" },
    loanFact("Borrowed", el("span", { class: "money" }, moneyFormat.format(loan.principal))),
    loanFact("Interest", `${Number(loan.annualRate).toFixed(2)}% a year`),
    loanFact("Monthly payment", el("span", { class: "money" }, moneyFormat.format(loan.monthlyPayment))),
    loanFact("Length", `${loan.termMonths} months`),
    loanFact("Paid into", el("span", { class: "number" }, loan.accountNumber ?? `account ${loan.accountId}`)),
    loanFact("Asked for", dateFormat.format(new Date(loan.requestedAt))),
    loan.status === "ACTIVE" || loan.status === "PAID_OFF"
      ? loanFact("Payments made", `${loan.paymentsMade} of ${loan.termMonths}`) : null);

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, `Loan for ${loan.purpose}`),
        el("p", { class: "muted" }, loanStatusBadge(loan))),
      el("button", { type: "button", onclick: closeLoan }, "Close")),
    active ? el("div", { class: "balance-card balance-loan" },
      el("p", { class: "balance-label" }, "Still owed"),
      el("p", { class: "balance money" }, moneyFormat.format(loan.remainingPrincipal)),
      el("p", { class: "balance-label" }, loan.overdue
        ? `Next payment of ${moneyFormat.format(loan.nextPaymentAmount)} was due on ${formatDay(loan.nextPaymentDue)}.`
        : `Next payment: ${moneyFormat.format(loan.nextPaymentAmount)} by ${formatDay(loan.nextPaymentDue)}.`)) : null,
    loan.status === "REJECTED" && loan.decisionNote
      ? el("p", { class: "notify-row" }, el("span", {}, el("strong", {}, "Reason: "), loan.decisionNote)) : null,
    loanActions(loan),
    facts,
    loan.repayments?.length ? repaymentTable(loan.repayments) : null);
}

function closeLoan() {
  selectionVersion++;
  state.loan = null;
  state.detailError = null;
  renderDetail();
}

function loanActions(loan) {
  const error = el("span", { class: "field-error", role: "alert" });
  const run = async (button, path, body, message) => {
    button.disabled = true;
    error.textContent = "";
    try {
      const updated = await api(`/api/loans/${loan.id}/${path}`, { method: "POST", body });
      toast(message);
      await refreshAfterLoanChange(updated);
      return;
    } catch (failure) {
      error.textContent = failure.fieldErrors.note ?? failure.message;
    }
    button.disabled = false;
  };

  if (loan.status === "REQUESTED" && isStaff()) {
    const approve = el("button", { type: "button", class: "primary" }, `Approve and pay ${moneyFormat.format(loan.principal)}`);
    approve.onclick = () => run(approve, "approve", undefined, "Loan approved and paid in.");
    const note = el("input", { name: "note", maxlength: "300", autocomplete: "off", placeholder: "Optional, shown to the customer" });
    const reject = el("button", { type: "submit", class: "danger" }, "Reject");
    return el("div", {},
      el("div", { class: "money-form" }, approve),
      el("form", {
        class: "money-form",
        novalidate: "",
        onsubmit: (event) => { event.preventDefault(); run(reject, "reject", { note: note.value.trim() || null }, "Loan rejected."); },
      }, el("label", {}, "Reason for rejecting", note), reject),
      error);
  }
  if (loan.status === "REQUESTED") {
    const cancel = el("button", { type: "button", class: "danger" }, "Cancel request");
    cancel.onclick = () => run(cancel, "cancel", undefined, "Loan request cancelled.");
    return el("div", {},
      el("p", { class: "muted" }, "We will look at your request and answer here."),
      el("div", { class: "button-row" }, cancel),
      error);
  }
  if (loan.status === "ACTIVE") {
    const repay = el("button", { type: "button", class: "primary" }, `Pay ${moneyFormat.format(loan.nextPaymentAmount)} now`);
    repay.onclick = () => run(repay, "repay", undefined, "Repayment made.");
    const payOff = el("button", { type: "button" }, `Pay off all ${moneyFormat.format(loan.remainingPrincipal)}`);
    payOff.onclick = () => openConfirm("Pay off loan",
      `Pay off the whole loan now? ${moneyFormat.format(loan.remainingPrincipal)} will be taken from account ${loan.accountNumber}. There is no fee for paying early.`,
      async () => {
        const updated = await api(`/api/loans/${loan.id}/pay-off`, { method: "POST" });
        toast("Loan paid off.");
        await refreshAfterLoanChange(updated);
      }, "Pay off");
    return el("div", {},
      el("div", { class: "money-form" }, repay, payOff),
      el("p", { class: "muted allowance" }, `Payments are taken from account ${loan.accountNumber}.`),
      error);
  }
  return null;
}

function repaymentTable(repayments) {
  const rows = [...repayments].reverse().map((repayment) => el("tr", {},
    el("td", {}, dateFormat.format(new Date(repayment.createdAt))),
    el("td", { class: "right number" }, moneyFormat.format(repayment.amount)),
    el("td", { class: "right number" }, moneyFormat.format(repayment.interest)),
    el("td", { class: "right number" }, moneyFormat.format(repayment.remainingAfter))));
  return el("div", {},
    el("h3", { class: "spaced-top" }, "Repayments"),
    el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {},
        el("th", { scope: "col" }, "Date"),
        el("th", { scope: "col", class: "right" }, "Paid"),
        el("th", { scope: "col", class: "right" }, "Of which interest"),
        el("th", { scope: "col", class: "right" }, "Still owed"))),
      el("tbody", {}, ...rows))));
}

/* The application form */

let loanOffer = null;

async function openLoanDialog() {
  const form = $("loan-form");
  form.reset();
  showFormError(form, null);
  clearFieldErrors(form);
  form.elements.accountId.replaceChildren(...state.accounts.map((account) =>
    el("option", { value: account.id }, `${titleCase(account.type)} ${account.accountNumber}`)));
  $("loan-quote").textContent = "";
  try {
    loanOffer ??= await api("/api/loans/offer");
    $("loan-limits").textContent = `You can borrow ${moneyFormat.format(loanOffer.minAmount)} to ${moneyFormat.format(loanOffer.maxAmount)}, `
      + `over ${loanOffer.minMonths} to ${loanOffer.maxMonths} months, at ${Number(loanOffer.annualRate).toFixed(2)}% a year.`;
  } catch (error) {
    $("loan-limits").textContent = "";
  }
  $("loan-dialog").showModal();
}

let quoteTimer;
let quoteVersion = 0;

/** Shows what the loan would cost as the customer types, a moment after they stop. */
function updateLoanQuote() {
  clearTimeout(quoteTimer);
  const form = $("loan-form");
  const amount = form.elements.amount.value.trim();
  const months = form.elements.months.value.trim();
  const target = $("loan-quote");
  if (!AMOUNT_PATTERN.test(amount) || !/^\d{1,3}$/.test(months)) {
    target.textContent = "";
    return;
  }
  const version = ++quoteVersion;
  quoteTimer = setTimeout(async () => {
    try {
      const quote = await api(`/api/loans/quote?amount=${amount}&months=${months}`);
      if (version !== quoteVersion) return;
      target.textContent = `${moneyFormat.format(quote.monthlyPayment)} a month for ${quote.months} months. `
        + `You would repay ${moneyFormat.format(quote.totalRepayable)} in all, of which ${moneyFormat.format(quote.totalInterest)} is interest.`;
    } catch (error) {
      if (version === quoteVersion) target.textContent = error.message;
    }
  }, 300);
}

/* Staff's list of loans across all customers */

async function openLoanBoard(filter = state.loanBoard?.filter ?? "REQUESTED", page = 0) {
  state.findAccount = false;
  state.audit = null;
  state.help = null;
  const version = ++selectionVersion;
  const status = filter === "ALL" ? "" : `&status=${filter}`;
  let loans = { content: [], page: null };
  const names = {};
  let failure = null;
  try {
    loans = await api(`/api/loans?page=${page}&size=${HELP_PAGE_SIZE}${status}`);
    // Loans know the customer's id only; names live in customer-service.
    const ids = [...new Set(loans.content.map((loan) => loan.customerId))];
    await Promise.all(ids.map(async (id) => {
      try {
        const customer = await api(`/api/customers/${id}`);
        names[id] = `${customer.firstName} ${customer.lastName}`;
      } catch {
        names[id] = `Customer ${id}`;
      }
    }));
  } catch (error) {
    failure = error.message;
  }
  if (version !== selectionVersion) return;
  state.loanBoard = { filter, page, loans, names };
  state.customer = null;
  state.account = null;
  state.loan = null;
  state.detailError = failure;
  renderCustomers();
  renderDetail();
}

function closeLoanBoard() {
  selectionVersion++;
  state.loanBoard = null;
  state.detailError = null;
  renderDetail();
}

function loanBoardPanel() {
  const { filter, loans, names } = state.loanBoard;
  const select = el("select", { name: "filter", "aria-label": "Which loans" },
    ...Object.entries(LOAN_FILTERS).map(([value, label]) => el("option", { value }, label)));
  select.value = filter;
  select.onchange = () => openLoanBoard(select.value);

  const items = loans.content.map((loan) => el("li", {},
    el("button", {
      type: "button",
      class: "help-item",
      onclick: async () => {
        await selectCustomer(loan.customerId);
        await selectLoan(loan.id);
      },
    },
    el("span", {},
      `${names[loan.customerId]}: ${moneyFormat.format(loan.principal)} over ${loan.termMonths} months`,
      el("small", {}, `${loan.purpose} · asked ${dateFormat.format(new Date(loan.requestedAt))}`)),
    loanStatusBadge(loan))));

  return el("div", { class: "panel" },
    el("div", { class: "panel-head" },
      el("div", {},
        el("h2", {}, "Loans"),
        el("p", { class: "muted" }, "Requests to decide, and loans being repaid. Newest first.")),
      el("button", { type: "button", onclick: closeLoanBoard }, "Close")),
    el("label", { class: "help-filter" }, "Show", select),
    items.length ? el("ul", { class: "help-list" }, ...items) : el("p", { class: "empty" }, "No loans here."),
    pager(loans.page, (next) => openLoanBoard(filter, next)));
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

function openConfirm(title, message, action, confirmLabel = "Delete") {
  $("confirm-title").textContent = title;
  $("confirm-form").querySelector('[type="submit"]').textContent = confirmLabel;
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
$("help").onclick = () => openHelp();

$("sign-out").onclick = async () => {
  try {
    await api("/api/auth/logout", { method: "POST" });
    // A full reload leaves nothing from this session in memory.
    window.location.reload();
  } catch (error) {
    toast(error.message, "error");
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

$("loan-form").oninput = updateLoanQuote;

$("loan-form").onsubmit = (event) => {
  event.preventDefault();
  const form = event.target;
  submitDialog($("loan-dialog"), form, async () => {
    const months = form.elements.months.value.trim();
    const created = await api("/api/loans", {
      method: "POST",
      // The amount goes as a string, like other amounts, so no cents are lost.
      body: {
        accountId: Number(form.elements.accountId.value),
        amount: form.elements.amount.value.trim(),
        months: /^\d+$/.test(months) ? Number(months) : null,
        purpose: form.elements.purpose.value.trim(),
      },
    });
    await refreshAfterLoanChange(created);
    toast("Loan request sent. We'll answer here.");
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
