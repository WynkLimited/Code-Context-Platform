FROM eclipse-temurin:21-jdk-noble@sha256:dc333cdac241f073941f043792f2a2f9b790bfe7cd98ef2843c08880986708de
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev gcc-aarch64-linux-gnu gcc-x86-64-linux-gnu libc6-dev-arm64-cross libc6-dev-amd64-cross && rm -rf /var/lib/apt/lists/*
