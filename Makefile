# StreamLine developer tasks.
# Run `make` or `make help` for the list.

MVN         ?= mvn
SERVER      := Server/pom.xml
COMMON      := bench-common/pom.xml
LOAD_TESTER := load-tester/pom.xml
ANALYZER    := latency-analyzer/pom.xml

# Override on the command line, e.g. make bench URL=ws://host:8080 THREADS=50
URL      ?= ws://localhost:8080

# Java used to run the packaged server. The jar targets 21, so override this if
# the JDK on PATH is older: make smoke JAVA=/path/to/jdk21/bin/java
JAVA     ?= java

# Smoke run: small enough to be quick, large enough that a protocol regression shows up
SMOKE_PORT     ?= 18099

# Used by check-attribution to ask GitHub how it attributes our commits.
GH_REPO        ?= vatsalp2008/StreamLine-Distributed-Messaging-Engine
SMOKE_THREADS  ?= 4
SMOKE_MESSAGES ?= 200
SMOKE_DIR      ?= $(CURDIR)/target/smoke
THREADS  ?= 16
MESSAGES ?= 5000
ROOMS    ?= 5

BENCH_FLAGS := -Dstreamline.url=$(URL) \
               -Dstreamline.threads=$(THREADS) \
               -Dstreamline.messages=$(MESSAGES) \
               -Dstreamline.rooms=$(ROOMS)

.DEFAULT_GOAL := help
.PHONY: help build test verify run clean common docker-build docker-up docker-down warmup bench latency check-attribution

# The two benchmark clients depend on streamline-bench-common through the local
# repository, so it has to be installed before either of them will resolve.
common: ## Install the shared benchmark module
	$(MVN) -q clean install -DskipTests -f $(COMMON)

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

build: common ## Compile every module
	$(MVN) -q clean compile -f $(SERVER)
	$(MVN) -q clean compile -f $(LOAD_TESTER)
	$(MVN) -q clean compile -f $(ANALYZER)

# Always clean: an incremental build can reuse stale classes and pass when the
# sources no longer compile from scratch.
test: ## Run the server test suite
	$(MVN) clean verify -f $(SERVER)

verify: ## Run every module's tests, as CI does
	$(MVN) clean verify -f $(SERVER)
	$(MVN) clean install -f $(COMMON)
	$(MVN) clean verify -f $(LOAD_TESTER)
	$(MVN) clean verify -f $(ANALYZER)

run: ## Start the server on :8080 with the browser client at /
	$(MVN) spring-boot:run -f $(SERVER)

clean: ## Remove build output and the local database
	$(MVN) -q clean -f $(SERVER)
	$(MVN) -q clean -f $(COMMON)
	$(MVN) -q clean -f $(LOAD_TESTER)
	$(MVN) -q clean -f $(ANALYZER)
	rm -rf Server/data Result

docker-build: ## Build the server image
	docker build -t streamline-server Server

docker-up: ## Start the server in Docker
	docker compose up -d --build

docker-down: ## Stop the Docker stack and drop its volume
	docker compose down -v

warmup: common ## Short warm-up run against $(URL)
	$(MVN) -q compile exec:java -f $(LOAD_TESTER) \
		-Dexec.mainClass=client.WarmUpPhase $(BENCH_FLAGS)

bench: common ## Throughput benchmark against $(URL)
	$(MVN) -q compile exec:java -f $(LOAD_TESTER) \
		-Dexec.mainClass=client.MainPhase $(BENCH_FLAGS)

smoke: common ## Start the server, run a short benchmark, assert every message was accepted
	@echo "==> packaging server"
	@$(MVN) -q -f Server/pom.xml clean package -DskipTests
	@echo "==> starting server on :$(SMOKE_PORT)"
	@rm -rf $(SMOKE_DIR) && mkdir -p $(SMOKE_DIR)
	@# Refuse to run if the port is taken. Otherwise the health check below is
	@# answered by whatever is already listening and the smoke test "passes"
	@# without ever exercising the build under test.
	@if lsof -ti :$(SMOKE_PORT) >/dev/null 2>&1; then \
		echo "SMOKE FAILED: port $(SMOKE_PORT) is already in use by PID $$(lsof -ti :$(SMOKE_PORT) | tr '\n' ' ')"; \
		echo "--- stop it first, or run with a different SMOKE_PORT"; \
		exit 1; \
	fi
	@# exec so the recorded pid is the JVM itself; backgrounding the surrounding
	@# shell records the wrapper instead, and killing that leaves the server up.
	@# Receipts on: they add a second frame per message, which is exactly the
	@# kind of protocol change a client can mishandle. Running the smoke test
	@# without them would leave that path unexercised end to end.
	@( cd $(SMOKE_DIR) && exec env SERVER_PORT=$(SMOKE_PORT) RECEIPTS_ENABLED=true \
		$(JAVA) -jar $(CURDIR)/Server/target/streamline-server-0.0.1-SNAPSHOT.jar \
		> server.log 2>&1 ) & echo $$! > $(SMOKE_DIR)/server.pid
	@# The trailing 'true' matters: without it the loop exits with curl's status
	@# when the server never comes up, and make aborts before the check below
	@# can explain why.
	@for i in $$(seq 1 60); do \
		sleep 1; \
		curl -sf http://localhost:$(SMOKE_PORT)/health >/dev/null 2>&1 && break; \
	done; true
	@# Fail here rather than letting the benchmark run against nothing and
	@# reporting a confusing "0 accepted" further down.
	@if ! curl -sf http://localhost:$(SMOKE_PORT)/health >/dev/null 2>&1; then \
		echo "SMOKE FAILED: the server never became healthy on :$(SMOKE_PORT)"; \
		echo "--- last lines of $(SMOKE_DIR)/server.log ---"; \
		tail -15 $(SMOKE_DIR)/server.log 2>/dev/null; \
		if grep -q UnsupportedClassVersionError $(SMOKE_DIR)/server.log 2>/dev/null; then \
			echo "--- the server needs Java 21 or newer; '$(JAVA)' is older."; \
			echo "--- retry with: make smoke JAVA=/path/to/jdk21+/bin/java"; \
		fi; \
		kill $$(cat $(SMOKE_DIR)/server.pid) 2>/dev/null || true; \
		exit 1; \
	fi
	@echo "==> running benchmark"
	@$(MAKE) --no-print-directory warmup URL=ws://localhost:$(SMOKE_PORT) \
		THREADS=$(SMOKE_THREADS) MESSAGES=$(SMOKE_MESSAGES) ROOMS=2 \
		> $(SMOKE_DIR)/bench.log 2>&1 || true
	@grep -E "Successful messages|Failed messages" $(SMOKE_DIR)/bench.log || true
	@sleep 2
	@curl -s http://localhost:$(SMOKE_PORT)/actuator/metrics/streamline.receipts.sent \
		| sed -n 's/.*"value":\([0-9.]*\).*/\1/p' > $(SMOKE_DIR)/receipts.txt 2>/dev/null || true
	@kill $$(cat $(SMOKE_DIR)/server.pid) 2>/dev/null || true
	@for i in $$(seq 1 20); do \
		lsof -ti :$(SMOKE_PORT) >/dev/null 2>&1 || break; \
		sleep 1; \
	done
	@failed=$$(grep -oE "Failed messages: [0-9]+" $(SMOKE_DIR)/bench.log | grep -oE "[0-9]+"); \
	sent=$$(grep -oE "Successful messages sent: [0-9]+" $(SMOKE_DIR)/bench.log | grep -oE "[0-9]+"); \
	if [ "$$failed" != "0" ] || [ "$$sent" != "$(SMOKE_MESSAGES)" ]; then \
		echo "SMOKE FAILED: $$sent/$(SMOKE_MESSAGES) accepted, $$failed failed"; \
		exit 1; \
	fi
	@echo "SMOKE OK: $(SMOKE_MESSAGES)/$(SMOKE_MESSAGES) messages accepted"
	@echo "==> receipts confirmed: $$(cat $(SMOKE_DIR)/receipts.txt 2>/dev/null || echo unknown)"

latency: common ## Latency benchmark; writes Result/*.csv
	$(MVN) -q compile exec:java -f $(ANALYZER) \
		-Dexec.mainClass=client2.MainPhase $(BENCH_FLAGS)

check-attribution: ## Verify recent commits will count toward the GitHub contribution graph
	@echo "==> local identity"
	@echo "    $$(git config user.name) <$$(git config user.email)>"
	@echo "==> how GitHub attributes the last 5 commits on the default branch"
	@gh api "repos/$(GH_REPO)/commits?per_page=5" \
		--jq '.[] | "    \(.sha[0:7])  \(.commit.author.date)  \(.author.login // "UNATTRIBUTED")"' \
		2>/dev/null || { echo "    (gh CLI unavailable or not authenticated)"; exit 0; }
	@if gh api "repos/$(GH_REPO)/commits?per_page=5" --jq '.[].author.login' 2>/dev/null | grep -q '^$$'; then \
		echo ""; \
		echo "    WARNING: a commit is unattributed. Its author email is not linked to"; \
		echo "    the GitHub account, so it will not appear on the contribution graph."; \
		echo "    Add the address at github.com/settings/emails, or commit with one"; \
		echo "    that is already linked."; \
	fi
