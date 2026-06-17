
build:
	mvn clean package
bump:
	mvn versions:set -DgenerateBackupPoms=false
publish-central:
	mvn clean deploy -Pcentral
check-updates:
	mvn -e net.optionfactory:anarchitect-maven-plugin:LATEST:check-updates
