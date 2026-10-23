# syntax=docker/dockerfile:1
FROM node:20-alpine AS runtime
WORKDIR /app

# No dependencies — stdlib only. Copy the single-file server.
COPY server.js ./

# Run as the built-in non-root node user
USER node

EXPOSE 8080
ENV PORT=8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD ["/bin/sh", "-c", "wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1"]

ENTRYPOINT ["node", "server.js"]
