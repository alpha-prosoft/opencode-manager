.PHONY: dev dev-full docs-stack down clean logs test guide docs docs-check docs-serve docs-all

FULL_STACK = -f compose.yml -f compose.keycloak.yml -f compose.ci.yml
DOCS_STACK = $(FULL_STACK) -f compose.docs.yml

dev:
	./scripts/seed-local-repos.sh
	docker compose --profile images build opencode-dev
	docker compose up -d --build
	@echo "Agent Platform: http://localhost:8080"

dev-full:
	./scripts/seed-local-repos.sh
	docker compose --profile images build opencode-dev
	docker compose $(FULL_STACK) up -d --build
	@echo "Agent Platform with Keycloak, Gerrit and Jenkins: http://localhost:8080"

# BUILD=no starts the images there are, for hosts where compose can't build them (then build each by hand first,
# e.g. docker build --network host --build-context edd-core=../edd-core -t opencode-manager-manager .).
docs-stack: clean
	SEED_VARIANT=docs ./scripts/seed-local-repos.sh
ifeq ($(BUILD),no)
	docker compose $(DOCS_STACK) up -d --no-build
else
	docker compose $(DOCS_STACK) --profile images build opencode-dev
	docker compose $(DOCS_STACK) up -d --build
endif
	./scripts/wait-for-stack.sh
	@echo "Documentation stack with the scripted LLM: http://localhost:8080 (back to a real LLM: make clean && make dev-full)"

down:
	docker compose $(DOCS_STACK) down --remove-orphans

clean:
	docker ps -aq --filter "label=opencode-manager/session" | xargs -r docker rm -f
	docker compose $(DOCS_STACK) down --volumes --remove-orphans
	docker ps -aq --filter "label=opencode-manager/session" | xargs -r docker rm -f

logs:
	docker compose logs -f manager

test:
	cd service && clojure -M:test:unit
	cd web && clojure -M:shadow-cljs compile test

guide:
	@./scripts/stack-llm.sh
	cd web && clojure -M:guide

.venv-docs: docs/requirements.txt
	python3 -m venv .venv-docs
	.venv-docs/bin/pip install -q -r docs/requirements.txt

docs-check: .venv-docs
	.venv-docs/bin/python scripts/check-docs.py

docs: docs-check
	.venv-docs/bin/mkdocs build --strict

docs-serve: .venv-docs
	.venv-docs/bin/mkdocs serve

docs-all: docs-stack guide docs
