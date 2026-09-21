DC=docker compose
DCS=$(DC) exec -T adapter
MAVEN_RELEASE=$(DCS) sh -c 'test -n "$$MAVEN_GPG_KEY_BASE64" || exit 1; \
	MAVEN_GPG_KEY="$$(printf %s "$$MAVEN_GPG_KEY_BASE64" | base64 -d)" mvn -P release -s .mvn/release-settings.xml $(1)'

.env:
	sed -e "s/{DEV_UID}/$(shell if [ "$(shell uname)" = "Linux" ]; then echo $(shell id -u); else echo '1001'; fi)/g" \
		-e "s/{DEV_GID}/$(shell if [ "$(shell uname)" = "Linux" ]; then echo $(shell id -g); else echo '1001'; fi)/g" \
		.env.dist > .env;

init: docker-up-force install

docker-compose.ci.yml:
	# Comment out any port forwarding
	sed -r 's/^(\s+ports:)$$/#\1/g' docker-compose.yaml > docker-compose.ci.yml

docker-up-force: .env
	$(DC) build --pull
	$(DC) up -d --force-recreate --remove-orphans

docker-down-clean: .env
	$(DC) down -v

install:
	$(DCS) mvn -q dependency:resolve

outdated:
	$(DCS) mvn org.codehaus.mojo:versions-maven-plugin:2.18.0:display-dependency-updates -DprocessDependencyManagement=false \
		"-Dmaven.version.ignore=(?i).*[-.](alpha|beta|rc|cr|m|milestone|preview|ea|snapshot)[-.0-9]*"

lint:
	$(DCS) mvn -q -Plint spotless:check test-compile

lint-fix:
	$(DCS) mvn -q spotless:apply

unit:
	$(DCS) mvn -q test

fasttest: lint unit

localtest:
	mvn -q spotless:apply
	mvn -q -Plint test

test: docker-up-force install fasttest docker-down-clean

release-check:
	$(call MAVEN_RELEASE,clean verify)

release:
	$(call MAVEN_RELEASE,clean deploy)
