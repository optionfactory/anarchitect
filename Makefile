
build:
	mvn clean package
bump:
	mvn versions:set -DgenerateBackupPoms=false
publish-central:
	mvn clean deploy -Pcentral
check-updates:
	mvn org.codehaus.mojo:versions-maven-plugin:2.16.2:display-dependency-updates  -Dmaven.version.ignore='.*-.*'
	mvn org.codehaus.mojo:versions-maven-plugin:2.16.2:display-plugin-updates -Dmaven.version.ignore='.*-.*'

