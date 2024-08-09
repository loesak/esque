FROM ubuntu:noble

ARG JAVA_VERSION=11.0.23-zulu
ARG MAVEN_VERSION=3.9.8

SHELL ["/bin/bash", "-c"]

# install prerequisists
RUN DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt install -y \
      curl git zip unzip gnupg

ARG USER=ubuntu
ARG HOME="/home/${USER}"
USER ${USER}

# install java things
RUN curl -s "https://get.sdkman.io" | bash \
    && source ${HOME}/.sdkman/bin/sdkman-init.sh \
    && sdk install java ${JAVA_VERSION} \
    && sdk install maven ${MAVEN_VERSION}
