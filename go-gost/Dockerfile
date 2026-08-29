FROM golang:1.23-bookworm AS builder

WORKDIR /src
COPY . .

RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /tmp/tms-node .

FROM debian:bookworm-slim

ARG TARGETARCH
ARG SINGBOX_VERSION=1.13.12

ENV TMS_AGENT_CONFIG=/etc/gost/config.json \
    SINGBOX_BIN=/usr/local/bin/sing-box \
    SINGBOX_RUNTIME_DOWNLOAD=false

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl dsniff iproute2 procps \
    && rm -rf /var/lib/apt/lists/* \
    && TARGET_ARCH="$TARGETARCH" \
    && if [ -z "$TARGET_ARCH" ]; then TARGET_ARCH="$(dpkg --print-architecture 2>/dev/null || echo amd64)"; fi \
    && case "$TARGET_ARCH" in \
         amd64) SB_ARCH=amd64 ;; \
         arm64) SB_ARCH=arm64 ;; \
         *) echo "Unsupported Docker architecture: $TARGET_ARCH" >&2; exit 1 ;; \
       esac \
    && SB_URL="https://github.com/SagerNet/sing-box/releases/download/v$SINGBOX_VERSION/sing-box-$SINGBOX_VERSION-linux-$SB_ARCH.tar.gz" \
    && mkdir -p /tmp/sing-box \
    && curl -fsSL --retry 5 --retry-all-errors --connect-timeout 15 "$SB_URL" -o /tmp/sing-box.tar.gz \
    && tar -xzf /tmp/sing-box.tar.gz -C /tmp/sing-box --strip-components=1 \
    && install -m 0755 /tmp/sing-box/sing-box /usr/local/bin/sing-box \
    && rm -rf /tmp/sing-box /tmp/sing-box.tar.gz

COPY --from=builder /tmp/tms-node /usr/local/bin/tms-node
COPY entrypoint.sh /usr/local/bin/entrypoint.sh

RUN chmod 0755 /usr/local/bin/tms-node /usr/local/bin/entrypoint.sh \
    && mkdir -p /etc/gost

WORKDIR /etc/gost
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
