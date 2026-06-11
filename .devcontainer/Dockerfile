FROM ubuntu:noble

ARG JAVA_VERSION=21.0.5-zulu
ARG KOTLIN_VERSION=2.4.0

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
    && sdk install kotlin ${KOTLIN_VERSION}

# install gh cli
RUN DEBIAN_FRONTEND=noninteractive \
    && (type -p wget >/dev/null || (apt update && apt install wget -y)) \
	&& mkdir -p -m 755 /etc/apt/keyrings out=$(mktemp)  \
    && wget -nv -O$out https://cli.github.com/packages/githubcli-archive-keyring.gpg \
	&& cat $out | tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
	&& chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
	&& mkdir -p -m 755 /etc/apt/sources.list.d \
	&& "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
	&& apt update \
	&& apt install gh -y

# install claude code
ENV PATH="${HOME}/.local/bin:${PATH}"
RUN curl -fsSL https://claude.ai/install.sh | bash
