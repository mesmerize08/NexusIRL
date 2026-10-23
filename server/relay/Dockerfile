# Moblin's remote control relay (github.com/eerimoq/moblin-remote-control-relay, MIT), built from
# a pinned commit. Go verifies the module checksums against go.sum during the build.
FROM golang:1.25-alpine AS build
ARG RELAY_COMMIT=b6d9ea2f1574113218dc49beee9ed1a2e3cc6cc5
RUN apk add --no-cache git
WORKDIR /src
RUN git init -q \
    && git remote add origin https://github.com/eerimoq/moblin-remote-control-relay.git \
    && git fetch -q --depth 1 origin "$RELAY_COMMIT" \
    && git checkout -q FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "$RELAY_COMMIT"
WORKDIR /src/backend
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/relay .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /src/LICENSE /app/LICENSE
COPY --from=build /src/frontend /app/frontend
COPY --from=build /out/relay /app/backend/relay
# The relay serves its web pages from ../frontend.
WORKDIR /app/backend
EXPOSE 8080
ENTRYPOINT ["/app/backend/relay", "-address", ":8080"]
