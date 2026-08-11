"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { loadClient } = require("./harness.js");

function join(handles) {
  handles.elements.get("connect").dispatch("click");
  return handles.FakeSocket.last;
}

test("the composer is disabled before joining", () => {
  const { elements } = loadClient();

  // the server refuses TEXT before JOIN, so offering the box would invite an error
  assert.equal(elements.get("text").disabled, true);
  assert.equal(elements.get("send").disabled, true);
  assert.equal(elements.get("leave").disabled, true);
});

test("joining enables the composer", () => {
  const handles = loadClient();

  join(handles).open();

  assert.equal(handles.elements.get("text").disabled, false);
  assert.equal(handles.elements.get("send").disabled, false);
  assert.equal(handles.elements.get("leave").disabled, false);
});

test("joining locks the identity fields", () => {
  const handles = loadClient();

  join(handles).open();

  // the session is bound to the username it joined with; letting these change
  // would only produce refusals
  assert.equal(handles.elements.get("user").disabled, true);
  assert.equal(handles.elements.get("room").disabled, true);
  assert.equal(handles.elements.get("token").disabled, true);
});

test("closing releases the identity fields again", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();

  socket.onclose({ code: 1000 });

  assert.equal(handles.elements.get("user").disabled, false);
  assert.equal(handles.elements.get("room").disabled, false);
  assert.equal(handles.elements.get("connect").disabled, false);
});

test("the status reads connected once the socket opens", () => {
  const handles = loadClient();

  join(handles).open();

  assert.equal(handles.elements.get("status").textContent, "connected");
  assert.equal(handles.elements.get("status").className, "live");
});

test("a connection error is shown as such", () => {
  const handles = loadClient();
  const socket = join(handles);

  socket.onerror();

  assert.match(handles.elements.get("status").textContent, /error/);
  assert.equal(handles.elements.get("status").className, "down");
});

test("pressing Enter in the composer sends", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.elements.get("text").value = "typed and entered";
  const before = socket.sent.length;

  handles.elements.get("text").dispatch("keydown", { key: "Enter" });

  assert.equal(socket.sent.length, before + 1);
  assert.match(socket.sent[socket.sent.length - 1], /typed and entered/);
});

test("other keys do not send the message", () => {
  const handles = loadClient();
  const socket = join(handles);
  socket.open();
  handles.elements.get("text").value = "half typed";

  handles.elements.get("text").dispatch("keydown", { key: "a" });

  // a keypress does emit a TYPING hint; what must not happen is the message
  // being sent before the author finished it
  const texts = socket.sent
    .map((raw) => JSON.parse(raw))
    .filter((f) => f.messageType === "TEXT");
  assert.equal(texts.length, 0);
});

test("pressing Enter in the search box searches", () => {
  const handles = loadClient();
  handles.elements.get("query").value = "term";

  handles.elements.get("query").dispatch("keydown", { key: "Enter" });

  assert.equal(handles.fetchCalls.length, 1);
});

test("sending is ignored when there is no socket", () => {
  const handles = loadClient();
  handles.elements.get("text").value = "nowhere to go";

  // must not throw when never connected
  handles.elements.get("send").dispatch("click");

  assert.equal(handles.elements.get("text").value, "nowhere to go");
});

test("leaving without a socket is ignored", () => {
  const handles = loadClient();

  handles.elements.get("leave").dispatch("click");

  assert.equal(handles.FakeSocket.last, undefined);
});
