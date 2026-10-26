# Throwaway Jenkins for the end-to-end test: the same LTS line as jenkins-config, only the plugins the
# library needs, and Node so the fixture projects can build on the controller (the library defaults to `agent any`).
FROM jenkins/jenkins:2.580.1-lts-jdk21

USER root
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm git \
    && rm -rf /var/lib/apt/lists/*
USER jenkins

# Local file:// Git repos (library, fixtures) are refused by default; this is a throwaway test controller.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc.yaml
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
