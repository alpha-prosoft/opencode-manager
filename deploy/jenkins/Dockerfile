# Local Jenkins for compose.ci.yml: configuration as code, the pipeline plugins, Python for the demo service's
# tests and a poller that builds every new Gerrit patch set. Not for production use.
FROM jenkins/jenkins:2.580.2-lts-jdk21
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY gerrit-poll.py /usr/local/bin/gerrit-poll
RUN chmod 0755 /usr/local/bin/gerrit-poll
USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY casc.yaml /var/jenkins_conf/casc.yaml
COPY jobs.groovy /var/jenkins_conf/jobs.groovy
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
ENV CASC_JENKINS_CONFIG=/var/jenkins_conf/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
