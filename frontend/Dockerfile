# syntax=docker/dockerfile:1
# Contexto de build: a raiz do repositório (veja compose.yaml), para alcançar deploy/nginx/.
FROM node:22-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=build /build/dist /usr/share/nginx/html
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/proxy_headers.conf /etc/nginx/proxy_headers.conf
COPY deploy/nginx/security_headers.conf /etc/nginx/security_headers.conf
COPY deploy/nginx/spa_headers.conf /etc/nginx/spa_headers.conf
# 127.0.0.1 e não "localhost": o wget do busybox resolve localhost para ::1 em hosts com IPv6, e o nginx só escuta IPv4.
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:80/ || exit 1
