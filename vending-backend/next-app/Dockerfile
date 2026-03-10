# --- Stage 1: Build the Next.js application ---
FROM node:20-alpine AS builder
WORKDIR /app
COPY vending-backend/next-app/package.json vending-backend/next-app/package-lock.json* ./
RUN npm ci
COPY vending-backend/next-app/ .
RUN npm run build

# --- Stage 3: Production runner ---
FROM node:20-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

# Create a non-root user for security
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

# Copy the standalone output produced by `output: "standalone"` in next.config.ts
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

USER nextjs

EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]
