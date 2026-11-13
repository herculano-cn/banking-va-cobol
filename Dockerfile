FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        coreutils \
        gnucobol3 \
        gawk \
        make \
        sed \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

# Build the local mock batch, using the Makefile's default data when no .dat is supplied.
RUN make

ENTRYPOINT ["/app/scripts/extrato-cli.sh"]
