"use strict";

/**
 * Minimal stand-ins for the browser APIs the client uses.
 *
 * Deliberately hand-written rather than pulling in jsdom: the client touches a
 * small, fixed slice of the DOM, and a dependency-free harness keeps `make
 * test-js` runnable anywhere node is installed, including CI, with no install
 * step.
 */

class ClassList {
  constructor(element) {
    this.element = element;
    this.names = new Set();
  }
  add(...names) { names.forEach((n) => this.names.add(n)); }
  remove(...names) { names.forEach((n) => this.names.delete(n)); }
  contains(name) { return this.names.has(name); }
  toString() { return [...this.names].join(" "); }
}

class Element {
  constructor(tag) {
    this.tagName = (tag || "div").toUpperCase();
    this.children = [];
    this.classList = new ClassList(this);
    this.style = {};
    this.attributes = {};
    this._textContent = "";
    this._innerHTML = "";
    this.value = "";
    this.disabled = false;
    this.title = "";
    this.listeners = {};
    this.scrollTop = 0;
    this.scrollHeight = 0;
    this.clientHeight = 0;
  }

  get className() { return this.classList.toString(); }
  set className(value) {
    this.classList = new ClassList(this);
    String(value).split(/\s+/).filter(Boolean).forEach((n) => this.classList.add(n));
  }

  get textContent() {
    if (this.children.length === 0) return this._textContent;
    return this.children.map((c) => c.textContent).join("");
  }
  set textContent(value) {
    this._textContent = String(value);
    this.children = [];
  }

  get innerHTML() { return this._innerHTML; }
  /**
   * Only the client's own markup is parsed here: a handful of sibling spans.
   * Anything else would need a real parser, which is out of scope.
   */
  set innerHTML(value) {
    this._innerHTML = value;
    this.children = [];
    const spanPattern = /<span class="([^"]*)"><\/span>/g;
    let match;
    while ((match = spanPattern.exec(value)) !== null) {
      const span = new Element("span");
      span.className = match[1];
      this.children.push(span);
    }
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  querySelector(selector) {
    const wanted = selector.replace(/^\./, "");
    for (const child of this.children) {
      if (child.classList.contains(wanted)) return child;
      const nested = child.querySelector ? child.querySelector(selector) : null;
      if (nested) return nested;
    }
    return null;
  }

  querySelectorAll(selector) {
    const wanted = selector.replace(/^\./, "");
    const found = [];
    for (const child of this.children) {
      if (child.classList.contains(wanted)) found.push(child);
      if (child.querySelectorAll) found.push(...child.querySelectorAll(selector));
    }
    return found;
  }

  addEventListener(type, handler) {
    (this.listeners[type] = this.listeners[type] || []).push(handler);
  }

  /** Fires a listener the client registered, as a real interaction would. */
  dispatch(type, event) {
    (this.listeners[type] || []).forEach((h) => h(event || {}));
  }

  focus() { this.focused = true; }
}

/** A stand-in socket that records what the client sent. */
class FakeSocket {
  constructor(url) {
    this.url = url;
    this.sent = [];
    this.closed = false;
    FakeSocket.last = this;
  }
  send(data) { this.sent.push(data); }
  close() { this.closed = true; if (this.onclose) this.onclose({ code: 1000 }); }
  /** Simulates the server pushing a frame. */
  receive(payload) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(payload) });
  }
  open() { if (this.onopen) this.onopen(); }
}

/**
 * Builds the element ids the client looks up, loads it, and returns handles.
 */
function loadClient() {
  const elements = new Map();
  const ids = ["status", "connect", "leave", "text", "send", "user", "room", "token",
    "log", "memberList", "typing", "query", "searchBtn", "clearBtn"];
  ids.forEach((id) => elements.set(id, new Element("div")));

  elements.get("user").value = "alice";
  elements.get("room").value = "1";
  elements.get("token").value = "";

  const document = {
    getElementById: (id) => elements.get(id) || null,
    createElement: (tag) => new Element(tag)
  };

  const fetchCalls = [];
  const global = {
    document,
    location: { protocol: "http:", host: "localhost:8080" },
    WebSocket: FakeSocket,
    setInterval: () => 0,
    clearInterval: () => {},
    setTimeout: (fn) => { fn(); return 0; },
    clearTimeout: () => {},
    Date,
    fetch: (url, options) => {
      fetchCalls.push({ url, options });
      return Promise.resolve(global.__fetchResponse
        || { ok: true, status: 200, json: () => Promise.resolve({ messages: [], totalMessages: 0 }) });
    }
  };

  const source = require("fs").readFileSync(
    require("path").join(__dirname, "../../main/resources/static/app.js"), "utf8");

  // The client closes over `document`, `location`, `WebSocket` and `fetch`, so
  // they are supplied as parameters rather than by mutating real globals.
  // "window" is bound so the client's own `typeof window !== "undefined"` check
  // picks this object rather than node's real globalThis, which would leak state
  // between loads.
  const factory = new Function("window", "document", "location", "WebSocket",
    "setInterval", "clearInterval", "setTimeout", "fetch",
    source + "\nreturn window.StreamLineClient;");

  const client = factory(global, document, global.location, FakeSocket,
    global.setInterval, global.clearInterval, global.setTimeout, global.fetch);

  return { client, elements, global, fetchCalls, FakeSocket };
}

module.exports = { loadClient, Element, FakeSocket };
