"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { loadClient } = require("./harness.js");

// ---------- presence ----------

test("presence renders one entry per member", () => {
  const { client, elements } = loadClient();

  client.renderMembers("alice,bob,carol");

  const list = elements.get("memberList");
  assert.equal(list.children.length, 3);
  assert.deepEqual(list.children.map((c) => c.textContent), ["alice", "bob", "carol"]);
});

test("an empty room says so rather than rendering nothing", () => {
  const { client, elements } = loadClient();

  client.renderMembers("");

  const list = elements.get("memberList");
  assert.equal(list.children.length, 1);
  assert.equal(list.children[0].textContent, "nobody here");
});

test("a presence frame replaces the previous list rather than appending", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ status: "PRESENCE", message: "alice,bob" });
  client.handleFrame({ status: "PRESENCE", message: "alice" });

  assert.deepEqual(elements.get("memberList").children.map((c) => c.textContent), ["alice"]);
});

test("member names are set as text, never as markup", () => {
  const { client, elements } = loadClient();

  client.renderMembers("<img src=x onerror=alert(1)>");

  // usernames are server-validated as alphanumeric, but the client must not be
  // the thing relying on that
  const entry = elements.get("memberList").children[0];
  assert.equal(entry.textContent, "<img src=x onerror=alert(1)>");
  assert.equal(entry.innerHTML, "");
});

// ---------- delivery receipts ----------

test("a receipt clears the pending mark on the line it confirms", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: hello");
  line.classList.add("pending");
  client.awaitingReceipt.set("m-1", line);

  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  assert.equal(line.classList.contains("pending"), false);
  assert.equal(client.awaitingReceipt.has("m-1"), false);
});

test("a receipt records the stored id for inspection", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: hello");
  client.awaitingReceipt.set("m-1", line);

  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  const mark = line.querySelector(".stored");
  assert.ok(mark, "expected a stored marker");
  assert.equal(mark.textContent, "stored");
  assert.equal(mark.title, "message id 77");
});

test("a receipt for an unknown id is ignored rather than throwing", () => {
  const { client } = loadClient();

  client.handleFrame({ status: "DELIVERED", clientId: "never-sent", message: "1" });

  assert.equal(client.awaitingReceipt.size, 0);
});

test("a receipt does not add a line to the log", () => {
  const { client, elements } = loadClient();
  const line = client.append("OK", "alice: hello");
  client.awaitingReceipt.set("m-1", line);
  const before = elements.get("log").children.length;

  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  assert.equal(elements.get("log").children.length, before);
});

test("our own echo is suppressed while its receipt is outstanding", () => {
  const { client, elements } = loadClient();
  const line = client.append("OK", "alice: hello");
  client.awaitingReceipt.set("m-1", line);
  const before = elements.get("log").children.length;

  // the server echoes what we sent; showing it again would duplicate the line
  client.handleFrame({ status: "OK", clientId: "m-1", message: "alice: hello" });

  assert.equal(elements.get("log").children.length, before);
});

test("someone else's message is still shown", () => {
  const { client, elements } = loadClient();
  const before = elements.get("log").children.length;

  client.handleFrame({ status: "BROADCAST", message: "bob: hi" });

  assert.equal(elements.get("log").children.length, before + 1);
});

// ---------- typing ----------

test("a typing frame names the typist", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ status: "TYPING", message: "bob" });

  assert.match(elements.get("typing").textContent, /bob is typing/);
});

test("two typists are listed together", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ status: "TYPING", message: "bob" });
  client.handleFrame({ status: "TYPING", message: "carol" });

  const shown = elements.get("typing").textContent;
  assert.match(shown, /bob/);
  assert.match(shown, /carol/);
});

test("an expired typist disappears", () => {
  const { client, elements } = loadClient();
  client.handleFrame({ status: "TYPING", message: "bob" });

  // a client that stops typing never says so; the hint has to lapse on its own
  client.typists.set("bob", Date.now() - 1);
  client.renderTyping();

  assert.equal(elements.get("typing").textContent, "");
});

// ---------- malformed input ----------

test("a frame that is not JSON is reported rather than thrown", () => {
  const { client, elements } = loadClient();

  client.handleRawFrame("this is not json");

  const log = elements.get("log");
  assert.equal(log.children.length, 1);
  assert.match(log.children[0].className, /ERROR/);
});

test("a frame with no status falls back to OK", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ message: "something happened" });

  assert.match(elements.get("log").children[0].className, /OK/);
});

// ---------- redaction ----------

test("a redaction strikes through the message it names", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: delete me");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "REDACTED", message: "77" });

  assert.ok(line.classList.contains("redacted"));
  assert.equal(line.querySelector(".body").textContent, "message deleted");
});

test("a redacted line keeps its place in the log", () => {
  const { client, elements } = loadClient();
  const line = client.append("OK", "alice: delete me");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });
  const before = elements.get("log").children.length;

  client.handleFrame({ status: "REDACTED", message: "77" });

  // removing it outright would read as a bug, and replies still refer to it
  assert.equal(elements.get("log").children.length, before);
});

test("the stored marker becomes a deleted marker", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: delete me");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "REDACTED", message: "77" });

  assert.equal(line.querySelector(".stored").textContent, "deleted");
});

test("a redaction for a message we never saw is ignored", () => {
  const { client, elements } = loadClient();
  const before = elements.get("log").children.length;

  // only our own messages have known ids, so most redactions are not ours
  client.handleFrame({ status: "REDACTED", message: "999" });

  assert.equal(elements.get("log").children.length, before);
});

test("a redacted id is forgotten so a repeat does nothing", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: delete me");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "REDACTED", message: "77" });
  client.handleFrame({ status: "REDACTED", message: "77" });

  assert.equal(client.linesByStoredId.has("77"), false);
});

test("a numeric id matches the string the receipt carried", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: delete me");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: 77 });

  client.handleFrame({ status: "REDACTED", message: 77 });

  assert.ok(line.classList.contains("redacted"));
});

// ---------- edits ----------

test("an edit replaces the text of the line it names", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: original");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "EDITED", message: "77:corrected" });

  assert.equal(line.querySelector(".body").textContent, "corrected");
  assert.ok(line.classList.contains("edited"));
});

test("a colon inside the new text is preserved", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: original");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "EDITED", message: "77:see this: it still works" });

  assert.equal(line.querySelector(".body").textContent, "see this: it still works");
});

test("an edit for an unknown id is ignored", () => {
  const { client, elements } = loadClient();
  const before = elements.get("log").children.length;

  client.handleFrame({ status: "EDITED", message: "999:whatever" });

  assert.equal(elements.get("log").children.length, before);
});

test("a malformed edit frame is ignored rather than throwing", () => {
  const { client } = loadClient();

  client.handleFrame({ status: "EDITED", message: "no separator here" });
  client.handleFrame({ status: "EDITED", message: ":leading" });

  assert.ok(true, "handled without throwing");
});

test("an edited line can still be redacted afterwards", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: original");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });
  client.handleFrame({ status: "EDITED", message: "77:corrected" });

  client.handleFrame({ status: "REDACTED", message: "77" });

  assert.ok(line.classList.contains("redacted"));
  assert.equal(line.querySelector(".body").textContent, "message deleted");
});
