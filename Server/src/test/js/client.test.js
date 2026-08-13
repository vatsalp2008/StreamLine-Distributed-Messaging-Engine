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

test("a redacted line is not resurrected by a later edit", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: original");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });
  client.handleFrame({ status: "REDACTED", message: "77" });

  // the id is forgotten on redaction, so a stray edit finds nothing
  client.handleFrame({ status: "EDITED", message: "77:back again" });

  assert.equal(line.querySelector(".body").textContent, "message deleted");
});

test("editing twice keeps the latest text", () => {
  const { client } = loadClient();
  const line = client.append("OK", "alice: original");
  client.awaitingReceipt.set("m-1", line);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "77" });

  client.handleFrame({ status: "EDITED", message: "77:first correction" });
  client.handleFrame({ status: "EDITED", message: "77:second correction" });

  assert.equal(line.querySelector(".body").textContent, "second correction");
});

test("an edit applies to the right line when several are tracked", () => {
  const { client } = loadClient();
  const first = client.append("OK", "alice: one");
  const second = client.append("OK", "alice: two");
  client.awaitingReceipt.set("m-1", first);
  client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: "10" });
  client.awaitingReceipt.set("m-2", second);
  client.handleFrame({ status: "DELIVERED", clientId: "m-2", message: "11" });

  client.handleFrame({ status: "EDITED", message: "11:only the second" });

  assert.equal(first.querySelector(".body").textContent, "alice: one");
  assert.equal(second.querySelector(".body").textContent, "only the second");
});

// ---------- acting on our own messages ----------

/** Finds a control by its label, so adding one does not shift the others. */
function control(line, label) {
  return line.querySelector(".actions").children.find((c) => c.textContent === label);
}

/** Sends a message and confirms it, returning the line and its stored id. */
function ownConfirmedLine(handles, storedId) {
  const line = handles.client.append("OK", "alice: mine");
  handles.client.awaitingReceipt.set("m-1", line);
  handles.client.handleFrame({ status: "DELIVERED", clientId: "m-1", message: storedId });
  return line;
}

test("controls appear on our own message once it is confirmed", () => {
  const handles = loadClient();

  const line = ownConfirmedLine(handles, "77");

  const actions = line.querySelector(".actions");
  assert.ok(actions, "expected message controls");
  // order-independent: a new control should not break this
  assert.deepEqual(actions.children.map((c) => c.textContent).sort(),
    ["delete", "edit", "react"]);
});

test("someone else's message gets no controls", () => {
  const handles = loadClient();

  handles.client.handleFrame({ status: "BROADCAST", message: "bob: theirs" });

  // we never learn the id of another client's message, so there is nothing to act on
  const log = handles.elements.get("log");
  const line = log.children[log.children.length - 1];
  assert.equal(line.querySelector(".actions"), null);
});

test("delete calls the endpoint for that message", async () => {
  const handles = loadClient();
  handles.elements.get("room").value = "general";
  const line = ownConfirmedLine(handles, "77");

  control(line, "delete").dispatch("click");
  await new Promise((resolve) => setTimeout(resolve, 0));

  const call = handles.fetchCalls[handles.fetchCalls.length - 1];
  assert.equal(call.url, "/api/rooms/general/messages/77");
  assert.equal(call.options.method, "DELETE");
});

test("delete sends the token when one is set", async () => {
  const handles = loadClient();
  handles.elements.get("token").value = "s3cret";
  const line = ownConfirmedLine(handles, "77");

  control(line, "delete").dispatch("click");
  await new Promise((resolve) => setTimeout(resolve, 0));

  const call = handles.fetchCalls[handles.fetchCalls.length - 1];
  assert.equal(call.options.headers["X-Streamline-Token"], "s3cret");
});

test("a redacted line hides its controls", () => {
  const handles = loadClient();
  const line = ownConfirmedLine(handles, "77");

  handles.client.handleFrame({ status: "REDACTED", message: "77" });

  // the CSS hides them; the class is what the test can assert on
  assert.ok(line.classList.contains("redacted"));
});

// ---------- messages we did not send ----------

test("a replayed history line becomes identifiable", () => {
  const { client } = loadClient();

  client.handleFrame({ status: "HISTORY", message: "bob: earlier", messageId: 42 });

  assert.ok(client.linesByStoredId.has("42"));
});

test("someone else's message stays identifiable after an edit", () => {
  const { client } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: earlier", messageId: 42 });

  // this is the whole point: previously only our own messages had known ids
  client.handleFrame({ status: "EDITED", message: "42:bob corrected it", messageId: 42 });

  // an edit does not consume the id; only a redaction does, so a message can be
  // edited more than once and then deleted
  assert.ok(client.linesByStoredId.has("42"));
});

test("an edit applies to a replayed history line", () => {
  const { client, elements } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: earlier", messageId: 42 });
  const line = elements.get("log").children[0];

  client.handleFrame({ status: "EDITED", message: "42:bob corrected it", messageId: 42 });

  assert.equal(line.querySelector(".body").textContent, "bob corrected it");
  assert.ok(line.classList.contains("edited"));
});

test("a redaction applies to a replayed history line", () => {
  const { client, elements } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: earlier", messageId: 42 });
  const line = elements.get("log").children[0];

  client.handleFrame({ status: "REDACTED", message: "42", messageId: 42 });

  assert.ok(line.classList.contains("redacted"));
  assert.equal(line.querySelector(".body").textContent, "message deleted");
});

test("the id field is preferred over the packed body", () => {
  const { client, elements } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: earlier", messageId: 42 });
  const line = elements.get("log").children[0];

  // a body whose text itself starts with digits and a colon must not mislead
  client.handleFrame({ status: "EDITED", message: "42:99: not an id", messageId: 42 });

  assert.equal(line.querySelector(".body").textContent, "99: not an id");
});

test("a frame with no id is still displayed", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ status: "BROADCAST", message: "bob: live message" });

  assert.equal(elements.get("log").children.length, 1);
  assert.equal(client.linesByStoredId.size, 0);
});

test("a message edited before we connected is marked in history", () => {
  const { client, elements } = loadClient();

  client.handleFrame({
    status: "HISTORY", message: "bob: corrected earlier",
    messageId: 42, editedAt: "2026-08-13T10:00:00Z"
  });

  // otherwise the marker only ever survived for edits seen live
  assert.ok(elements.get("log").children[0].classList.contains("edited"));
});

test("an unedited history message is not marked", () => {
  const { client, elements } = loadClient();

  client.handleFrame({ status: "HISTORY", message: "bob: original", messageId: 42 });

  assert.equal(elements.get("log").children[0].classList.contains("edited"), false);
});

// ---------- reactions ----------

test("a reactions frame draws one chip per emoji", () => {
  const { client } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: hi", messageId: 42 });

  client.handleFrame({ status: "REACTIONS", message: "thumbsup:2,heart:1", messageId: 42 });

  const chips = client.linesByStoredId.get("42").querySelector(".reactions").children;
  assert.deepEqual(chips.map((c) => c.textContent), ["thumbsup 2", "heart 1"]);
});

test("a later reactions frame replaces the earlier chips", () => {
  const { client } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: hi", messageId: 42 });
  client.handleFrame({ status: "REACTIONS", message: "thumbsup:2", messageId: 42 });

  // the server sends the whole set each time, so this must replace not append
  client.handleFrame({ status: "REACTIONS", message: "thumbsup:1", messageId: 42 });

  const chips = client.linesByStoredId.get("42").querySelector(".reactions").children;
  assert.deepEqual(chips.map((c) => c.textContent), ["thumbsup 1"]);
});

test("clearing every reaction leaves no chips", () => {
  const { client } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: hi", messageId: 42 });
  client.handleFrame({ status: "REACTIONS", message: "thumbsup:1", messageId: 42 });

  client.handleFrame({ status: "REACTIONS", message: "", messageId: 42 });

  assert.equal(
    client.linesByStoredId.get("42").querySelector(".reactions").children.length, 0);
});

test("a reactions frame for an unknown message is ignored", () => {
  const { client, elements } = loadClient();
  const before = elements.get("log").children.length;

  client.handleFrame({ status: "REACTIONS", message: "thumbsup:1", messageId: 999 });

  assert.equal(elements.get("log").children.length, before);
});

test("clicking a chip tries to take the reaction back first", async () => {
  const handles = loadClient();
  handles.elements.get("room").value = "general";
  handles.client.handleFrame({ status: "HISTORY", message: "bob: hi", messageId: 42 });
  handles.client.handleFrame({ status: "REACTIONS", message: "thumbsup:1", messageId: 42 });

  const chip = handles.client.linesByStoredId.get("42")
    .querySelector(".reactions").children[0];
  chip.dispatch("click");
  await new Promise((resolve) => setTimeout(resolve, 0));

  // DELETE first, so a second click on your own reaction removes it
  const call = handles.fetchCalls[0];
  assert.equal(call.url, "/api/rooms/general/messages/42/reactions");
  assert.equal(call.options.method, "DELETE");
});

test("an emoji containing a colon is still parsed", () => {
  const { client } = loadClient();
  client.handleFrame({ status: "HISTORY", message: "bob: hi", messageId: 42 });

  // the count is after the last colon, so a colon in the emoji survives
  client.handleFrame({ status: "REACTIONS", message: ":wink::3", messageId: 42 });

  const chips = client.linesByStoredId.get("42").querySelector(".reactions").children;
  assert.deepEqual(chips.map((c) => c.textContent), [":wink: 3"]);
});
