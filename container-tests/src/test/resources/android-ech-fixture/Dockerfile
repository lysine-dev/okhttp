FROM golang:1.26-alpine AS build

WORKDIR /src
COPY main.go .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /fixture main.go

FROM alpine:3.23
COPY --from=build /fixture /fixture
ENTRYPOINT ["/fixture"]
