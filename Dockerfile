# chess-guru relay — container image for Back4App Containers, Koyeb, Fly, etc.
FROM node:20-alpine
WORKDIR /app
COPY package.json relay.js ./
ENV PORT=4100 HOST=0.0.0.0 NODE_ENV=production
EXPOSE 4100
USER node
CMD ["node", "relay.js"]
