# whatsmeow's go.mod requires go >= 1.26.
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# No cgo anywhere: whatsmeow is pure Go and the session store is remote libsql.
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/wa-bridge ./...

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/wa-bridge /wa-bridge
EXPOSE 8080
ENTRYPOINT ["/wa-bridge"]
