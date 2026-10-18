# 前端镜像：node 构建 dist → nginx 托管静态资源并反代 /api
FROM node:20-alpine AS build
WORKDIR /app
# vite 构建峰值内存，16GB 宿主机保守值
ENV NODE_OPTIONS=--max-old-space-size=1536
# 先拷锁文件建 npm 缓存层
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY resources/docker/app/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
