"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { loadClient } = require("./harness.js");

/** Stubs the next fetch response and runs a search. */
async function search(handles, { query, room, token, response }) {
  const { elements, global } = handles;
  elements.get("query").value = query;
  if (room !== undefined) elements.get("room").value = room;
  if (token !== undefined) elements.get("token").value = token;
  global.__fetchResponse = response;

  await handles.client.runSearch();
}

function okResponse(body) {
  return { ok: true, status: 200, json: () => Promise.resolve(body) };
}

test("a search calls the room's search endpoint", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "build", room: "general",
    response: okResponse({ messages: [], totalMessages: 0 })
  });

  const call = handles.fetchCalls[0];
  assert.match(call.url, /^\/api\/rooms\/general\/search\?q=build/);
});

test("the query is escaped", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "a b&c", response: okResponse({ messages: [], totalMessages: 0 })
  });

  assert.match(handles.fetchCalls[0].url, /q=a%20b%26c/);
});

test("a token is sent as a header, not a query parameter", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "x", token: "s3cret",
    response: okResponse({ messages: [], totalMessages: 0 })
  });

  // unlike the socket, an XHR can set headers, so the token stays out of the URL
  const call = handles.fetchCalls[0];
  assert.equal(call.options.headers["X-Streamline-Token"], "s3cret");
  assert.equal(call.url.includes("s3cret"), false);
});

test("no token means no auth header", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "x", token: "", response: okResponse({ messages: [], totalMessages: 0 })
  });

  assert.equal(handles.fetchCalls[0].options.headers["X-Streamline-Token"], undefined);
});

test("an empty query is not sent", async () => {
  const handles = loadClient();

  await search(handles, { query: "   ", response: okResponse({}) });

  assert.equal(handles.fetchCalls.length, 0);
});

test("results are listed oldest first so they read like chat", async () => {
  const handles = loadClient();

  // the API returns newest first
  await search(handles, {
    query: "x",
    response: okResponse({
      totalMessages: 2,
      messages: [
        { username: "bob", message: "second" },
        { username: "alice", message: "first" }
      ]
    })
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.match(lines[0], /alice: first/);
  assert.match(lines[1], /bob: second/);
});

test("a search replaces whatever was on screen", async () => {
  const handles = loadClient();
  handles.client.append("OK", "earlier line");

  await search(handles, {
    query: "x",
    response: okResponse({ totalMessages: 1, messages: [{ username: "a", message: "hit" }] })
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.equal(lines.some((l) => l.includes("earlier line")), false);
});

test("no matches is stated rather than left blank", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "nothing", response: okResponse({ totalMessages: 0, messages: [] })
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.ok(lines.some((l) => l.includes("No messages match")));
});

test("the total is reported alongside the results", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "x",
    response: okResponse({ totalMessages: 7, messages: [{ username: "a", message: "hit" }] })
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.ok(lines.some((l) => l.includes("7")));
});

test("an unauthorised search says the token is the problem", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "x", response: { ok: false, status: 401, json: () => Promise.resolve({}) }
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.ok(lines.some((l) => l.includes("token required")));
});

test("a server error is reported with its status", async () => {
  const handles = loadClient();

  await search(handles, {
    query: "x", response: { ok: false, status: 500, json: () => Promise.resolve({}) }
  });

  const lines = handles.elements.get("log").children.map((c) => c.textContent);
  assert.ok(lines.some((l) => l.includes("500")));
});

test("clearing empties the query and the log", () => {
  const handles = loadClient();
  handles.elements.get("query").value = "something";
  handles.client.append("OK", "a line");

  handles.elements.get("clearBtn").dispatch("click");

  assert.equal(handles.elements.get("query").value, "");
  assert.equal(handles.elements.get("log").children.length, 0);
});
