(function (global) {
  "use strict";

  const $ = (id) => document.getElementById(id);

  // Lines awaiting a DELIVERED frame, keyed by the id sent with them.
  const awaitingReceipt = new Map();

  // Lines whose stored id we know, so a later REDACTED can find them. Only our
  // own messages appear here: a receipt is the only thing that tells this client
  // what id the server gave a message.
  const linesByStoredId = new Map();
  const log = $("log");
  let socket = null;

  function setStatus(text, kind) {
    $("status").textContent = text;
    $("status").className = kind || "";
  }

  // Joined controls whether sending is possible: the server refuses TEXT before JOIN.
  function setJoined(joined) {
    $("connect").disabled = joined;
    $("leave").disabled = !joined;
    $("text").disabled = !joined;
    $("send").disabled = !joined;
    $("user").disabled = joined;
    $("room").disabled = joined;
    $("token").disabled = joined;
  }

  function append(status, body) {
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;

    const line = document.createElement("div");
    line.className = "line " + status;
    line.innerHTML =
      '<span class="time"></span><span class="tag"></span><span class="body"></span>';
    line.querySelector(".time").textContent = new Date().toLocaleTimeString();
    line.querySelector(".tag").textContent = status;
    line.querySelector(".body").textContent = body;
    log.appendChild(line);

    // only follow the tail if the reader was already there
    if (atBottom) log.scrollTop = log.scrollHeight;
    return line;
  }

  // Membership arrives as PRESENCE frames pushed by the server whenever someone
  // joins or leaves, so the client neither polls nor needs the REST API.
  function renderMembers(csv) {
    const list = $("memberList");
    list.innerHTML = "";

    const names = (csv || "").split(",").filter((n) => n.length > 0);
    if (names.length === 0) {
      const empty = document.createElement("li");
      empty.className = "empty";
      empty.textContent = "nobody here";
      list.appendChild(empty);
      return;
    }

    for (const name of names) {
      const item = document.createElement("li");
      item.textContent = name;
      list.appendChild(item);
    }
  }

  // ---- typing indicator ----

  // Who is composing, and when their hint expires. A typist that stops sending
  // never says "I stopped", so each name is only trusted for a short window.
  const typists = new Map();
  const TYPING_TTL_MS = 3000;
  let typingSweep = null;

  function renderTyping() {
    const now = Date.now();
    for (const [name, expiry] of typists) {
      if (expiry <= now) typists.delete(name);
    }

    const names = [...typists.keys()].sort();
    const label = names.length === 0 ? ""
      : names.length === 1 ? names[0] + " is typing…"
      : names.length === 2 ? names.join(" and ") + " are typing…"
      : "several people are typing…";

    $("typing").textContent = label;

    if (typists.size === 0 && typingSweep) {
      clearInterval(typingSweep);
      typingSweep = null;
    } else if (typists.size > 0 && !typingSweep) {
      typingSweep = setInterval(renderTyping, 500);
    }
  }

  function showTyping(username) {
    typists.set(username, Date.now() + TYPING_TTL_MS);
    renderTyping();
  }

  // Rate limited to one hint per interval: the server broadcasts every frame it
  // receives, so sending one per keystroke would flood the room.
  let lastTypingSent = 0;
  const TYPING_SEND_INTERVAL_MS = 1500;

  function notifyTyping() {
    if (!socket) return;

    const now = Date.now();
    if (now - lastTypingSent < TYPING_SEND_INTERVAL_MS) return;
    lastTypingSent = now;
    socket.send(frame("TYPING", "typing"));
  }

  // Each outgoing frame carries an id the server echoes on its reply, so a
  // reply can be tied to the message that caused it rather than to whatever
  // arrived next.
  let sequence = 0;
  function nextClientId() {
    return "c" + (++sequence);
  }

  function frame(type, text, clientId) {
    return JSON.stringify({
      userId: 1,
      username: $("user").value.trim(),
      message: text,
      timestamp: new Date().toISOString(),
      messageType: type,
      clientId: clientId || nextClientId()
    });
  }

  /**
   * Routes one raw frame from the server.
   * Named rather than inline so it can be driven directly by tests.
   */
  function handleRawFrame(data) {
    let payload;
    try {
      payload = JSON.parse(data);
    } catch (e) {
      append("ERROR", data);
      return;
    }
    handleFrame(payload);
  }

  function handleFrame(payload) {
    if (payload.status === "PRESENCE") {
      renderMembers(payload.message);
      return;
    }

    if (payload.status === "DELIVERED") {
      const line = awaitingReceipt.get(payload.clientId);
      if (line) {
        line.classList.remove("pending");
        const mark = document.createElement("span");
        mark.className = "stored";
        mark.textContent = "stored";
        mark.title = "message id " + payload.message;
        line.appendChild(mark);
        awaitingReceipt.delete(payload.clientId);
        // remember the server's id so a later REDACTED can find this line
        linesByStoredId.set(String(payload.message), line);
      }
      return;
    }

    // Our own messages are already on screen from send(); showing the echo
    // again would duplicate every line we sent.
    if (payload.clientId && awaitingReceipt.has(payload.clientId)
        && payload.status === "OK") {
      return;
    }

    if (payload.status === "EDITED") {
      // "id:new text" — the id is numeric, so the first colon is the separator
      // and any colon in the message itself is kept
      const separator = String(payload.message).indexOf(":");
      if (separator > 0) {
        const id = String(payload.message).slice(0, separator);
        const text = String(payload.message).slice(separator + 1);
        const line = linesByStoredId.get(id);
        if (line) {
          const body = line.querySelector(".body");
          if (body) body.textContent = text;
          line.classList.add("edited");
        }
      }
      return;
    }

    if (payload.status === "REDACTED") {
      const line = linesByStoredId.get(String(payload.message));
      if (line) {
        // struck through rather than removed: a line vanishing with no trace
        // reads as a bug, and the surrounding conversation still refers to it
        line.classList.add("redacted");
        const body = line.querySelector(".body");
        if (body) body.textContent = "message deleted";
        const mark = line.querySelector(".stored");
        if (mark) mark.textContent = "deleted";
        linesByStoredId.delete(String(payload.message));
      }
      return;
    }

    if (payload.status === "TYPING") {
      showTyping(payload.message);
      return;
    }

    append(payload.status || "OK", payload.message);
  }

  function connect() {
    const room = encodeURIComponent($("room").value.trim() || "1");
    const scheme = location.protocol === "https:" ? "wss" : "ws";

    // A browser cannot set headers on a WebSocket handshake, so when the server
    // requires a token it has to travel as a query parameter.
    const token = $("token").value.trim();
    const auth = token ? "?token=" + encodeURIComponent(token) : "";

    socket = new WebSocket(scheme + "://" + location.host + "/chat/" + room + auth);

    setStatus("connecting…");

    socket.onopen = () => {
      setStatus("connected", "live");
      socket.send(frame("JOIN", "Joining"));
      setJoined(true);
      $("text").focus();
    };

    socket.onmessage = (event) => handleRawFrame(event.data);

    socket.onclose = (event) => {
      // 1006 with nothing sent usually means the handshake itself was refused,
      // which is what a missing or wrong token looks like from the browser.
      setStatus(event.code === 1006 ? "refused - check the token" : "disconnected", "down");
      setJoined(false);
      renderMembers("");
      typists.clear();
      renderTyping();
      socket = null;
    };

    socket.onerror = () => setStatus("connection error", "down");
  }

  function send() {
    const text = $("text").value.trim();
    if (!text || !socket) return;

    const clientId = nextClientId();
    socket.send(frame("TEXT", text, clientId));

    // shown immediately but marked provisional: an OK means accepted, and the
    // write can still fail afterwards
    const line = append("OK", $("user").value.trim() + ": " + text);
    line.classList.add("pending");
    awaitingReceipt.set(clientId, line);

    $("text").value = "";
  }

  $("connect").addEventListener("click", connect);

  $("leave").addEventListener("click", () => {
    if (!socket) return;
    socket.send(frame("LEAVE", "Leaving"));
    socket.close();
  });

  $("send").addEventListener("click", send);
  $("text").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      send();
      return;
    }
    notifyTyping();
  });

  // ---- history search, over the REST API rather than the socket ----

  function authHeaders() {
    const token = $("token").value.trim();
    return token ? { "X-Streamline-Token": token } : {};
  }

  async function runSearch() {
    const q = $("query").value.trim();
    if (!q) return;

    const room = encodeURIComponent($("room").value.trim() || "1");
    const url = "/api/rooms/" + room + "/search?q=" + encodeURIComponent(q) + "&size=50";

    log.innerHTML = "";
    try {
      const response = await fetch(url, { headers: authHeaders() });
      if (!response.ok) {
        append("ERROR", "Search failed: " + response.status
          + (response.status === 401 ? " (token required)" : ""));
        return;
      }

      const page = await response.json();
      if (page.messages.length === 0) {
        append("OK", "No messages match \"" + q + "\"");
        return;
      }

      // results are newest first; show them oldest first so they read like chat
      for (const msg of page.messages.slice().reverse()) {
        append("HISTORY", msg.username + ": " + msg.message);
      }
      append("OK", "Found " + page.totalMessages + " match(es)");
    } catch (e) {
      append("ERROR", "Search failed: " + e.message);
    }
  }

  $("searchBtn").addEventListener("click", runSearch);
  $("query").addEventListener("keydown", (e) => {
    if (e.key === "Enter") runSearch();
  });
  $("clearBtn").addEventListener("click", () => {
    $("query").value = "";
    log.innerHTML = "";
  });

  setJoined(false);
  renderMembers("");

  // Exposed so the behaviour above can be driven by tests. The page itself
  // never touches this; it exists because the alternative is a 300-line file
  // that can only be checked by clicking through it.
  global.StreamLineClient = {
    renderMembers,
    renderTyping,
    handleFrame,
    handleRawFrame,
    runSearch,
    append,
    frame,
    nextClientId,
    typists,
    awaitingReceipt,
    linesByStoredId,
    setSocket: (s) => { socket = s; },
    getSocket: () => socket
  };
})(typeof window !== "undefined" ? window : globalThis);

