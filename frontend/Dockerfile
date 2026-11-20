# 构建阶段：Node 20 + vue-tsc 类型检查 + vite 生产构建
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
# 可选：通过构建参数使用 npm 镜像（默认官方源）
ARG NPM_REGISTRY=https://registry.npmjs.org
RUN npm ci --registry=${NPM_REGISTRY}

COPY . .
RUN npm run build

# 运行阶段：nginx 托管静态资源，并把 /api 反代到后端
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
