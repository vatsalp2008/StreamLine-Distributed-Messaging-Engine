# StreamLine developer tasks.
# Run `make` or `make help` for the list.

MVN         ?= mvn
SERVER      := Server/pom.xml
LOAD_TESTER := load-tester/pom.xml
ANALYZER    := latency-analyzer/pom.xml

# Override on the command line, e.g. make bench URL=ws://host:8080 THREADS=50
URL      ?= ws://localhost:8080
THREADS  ?= 16
MESSAGES ?= 5000
ROOMS    ?= 5

BENCH_FLAGS := -Dstreamline.url=$(URL) \
               -Dstreamline.threads=$(THREADS) \
               -Dstreamline.messages=$(MESSAGES) \
               -Dstreamline.rooms=$(ROOMS)

.DEFAULT_GOAL := help
.PHONY: help build test verify run clean docker-build docker-up docker-down warmup bench latency

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

build: ## Compile every module
	$(MVN) -q clean compile -f $(SERVER)
	$(MVN) -q clean compile -f $(LOAD_TESTER)
	$(MVN) -q clean compile -f $(ANALYZER)

# Always clean: an incremental build can reuse stale classes and pass when the
# sources no longer compile from scratch.
test: ## Run the server test suite
	$(MVN) clean verify -f $(SERVER)

verify: ## Run every module's tests, as CI does
	$(MVN) clean verify -f $(SERVER)
	$(MVN) clean verify -f $(LOAD_TESTER)
	$(MVN) clean verify -f $(ANALYZER)

run: ## Start the server on :8080 with the browser client at /
	$(MVN) spring-boot:run -f $(SERVER)

clean: ## Remove build output and the local database
	$(MVN) -q clean -f $(SERVER)
	$(MVN) -q clean -f $(LOAD_TESTER)
	$(MVN) -q clean -f $(ANALYZER)
	rm -rf Server/data Result

docker-build: ## Build the server image
	docker build -t streamline-server Server

docker-up: ## Start the server in Docker
	docker compose up -d --build

docker-down: ## Stop the Docker stack and drop its volume
	docker compose down -v

warmup: ## Short warm-up run against $(URL)
	$(MVN) -q compile exec:java -f $(LOAD_TESTER) \
		-Dexec.mainClass=client.WarmUpPhase $(BENCH_FLAGS)

bench: ## Throughput benchmark against $(URL)
	$(MVN) -q compile exec:java -f $(LOAD_TESTER) \
		-Dexec.mainClass=client.MainPhase $(BENCH_FLAGS)

latency: ## Latency benchmark; writes Result/*.csv
	$(MVN) -q compile exec:java -f $(ANALYZER) \
		-Dexec.mainClass=client2.MainPhase $(BENCH_FLAGS)
