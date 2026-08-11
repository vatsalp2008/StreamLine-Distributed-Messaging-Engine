"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { loadClient } = require("./harness.js");

/** Clicks Join and returns the socket the client opened. */
function join(handles, { room, token } = {}) {
  const { elements, FakeSocket } = handles;
  if (room !== undefined) elements.get("room").value = room;
  if (token !== undefined) elements.get("token").value = token;

  elements.get("connect").dispatch("click");
  return FakeSocket.last;
}

// ---------- connecting ----------

test("joining opens a socket for the chosen room", () => {
  const handles = loadClient();

  const socket = join(handles, { room: "general" });

  assert.match(socket.url, /\/chat\/general$/);
});

test("the room name is escaped into the path", () => {
  const handles = loadClient();

  const socket = join(handles, { room: "a room" });

  assert.match(socket.url, /a%20room/);
});

test("a token is sent as a query parameter", () => {
  const handles = loadClient();

  // a browser cannot set headers on a handshake, so this is the only route
  const socket = join(handles, { token: "s3cret" });

  assert.match(socket.url, /\?token=s3cret$/);
});

test("no token means no query string", () => {
  const handles = loadClient();

  const socket = join(handles, { token: "" });

  assert.equal(socket.url.includes("?"), false);
});

test("a token needing escaping is encoded", () => {
  const handles = loadClient();

  const socket = join(handles, { token: "a b&c" });

  assert.match(socket.url, /token=a%20b%26c/);
});

test("opening the socket sends a JOIN", () => {
  const handles = loadClient();
  const socket = join(handles);

  socket.open();

  assert.equal(socket.sent.length, 1);
  const frame = JSON.parse(socket.sent[0]);
  assert.equal(frame.messageType, "JOIN");
  assert.equal(frame.username, "alice");
});

test("every frame carries a correlation id", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();

  const frame = JSON.parse(socket.sent[0]);
  assert.ok(frame.clientId, "expected a clientId on the JOIN");
});

test("correlation ids are not reused", () => {
  const { client } = loadClient();

  const ids = new Set([client.nextClientId(), client.nextClientId(), client.nextClientId()]);

  assert.equal(ids.size, 3);
});

// ---------- sending ----------

test("sending a message writes it to the socket and clears the box", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.elements.get("text").value = "hello there";

  handles.elements.get("send").dispatch("click");

  const sent = JSON.parse(socket.sent[socket.sent.length - 1]);
  assert.equal(sent.messageType, "TEXT");
  assert.equal(sent.message, "hello there");
  assert.equal(handles.elements.get("text").value, "");
});

test("a sent message appears immediately, marked provisional", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.elements.get("text").value = "hello";

  handles.elements.get("send").dispatch("click");

  const log = handles.elements.get("log");
  const line = log.children[log.children.length - 1];
  // an OK only means accepted; the line stays dimmed until the receipt lands
  assert.ok(line.classList.contains("pending"));
});

test("a sent message is tracked until its receipt arrives", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.elements.get("text").value = "hello";
  handles.elements.get("send").dispatch("click");

  const sent = JSON.parse(socket.sent[socket.sent.length - 1]);
  assert.ok(handles.client.awaitingReceipt.has(sent.clientId));

  handles.client.handleFrame({ status: "DELIVERED", clientId: sent.clientId, message: "5" });
  assert.equal(handles.client.awaitingReceipt.has(sent.clientId), false);
});

test("an empty message is not sent", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  const before = socket.sent.length;
  handles.elements.get("text").value = "   ";

  handles.elements.get("send").dispatch("click");

  assert.equal(socket.sent.length, before);
});

// ---------- disconnecting ----------

test("a refused handshake is reported as such", () => {
  const handles = loadClient();
  const socket = join(handles);

  // the browser reports a rejected upgrade as a 1006 close with no frames
  socket.onclose({ code: 1006 });

  assert.match(handles.elements.get("status").textContent, /token/);
});

test("a normal close reads as disconnected", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();

  socket.onclose({ code: 1000 });

  assert.equal(handles.elements.get("status").textContent, "disconnected");
});

test("closing clears the member list and typing hints", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.client.handleFrame({ status: "PRESENCE", message: "alice,bob" });
  handles.client.handleFrame({ status: "TYPING", message: "bob" });

  socket.onclose({ code: 1000 });

  assert.equal(handles.elements.get("memberList").children[0].textContent, "nobody here");
  assert.equal(handles.elements.get("typing").textContent, "");
});

test("leaving sends a LEAVE and closes", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();

  handles.elements.get("leave").dispatch("click");

  const sent = JSON.parse(socket.sent[socket.sent.length - 1]);
  assert.equal(sent.messageType, "LEAVE");
  assert.ok(socket.closed);
});
