# Handoff — Dockerización y despliegue del portafolio

Última actualización: 2026-09-26.

## Cambio de plan (importante)

El plan original era VM propia (Oracle Cloud, Ampere ARM64) con Docker +
`nginx-proxy` como reverse-proxy. **Crear la cuenta gratuita de Oracle Cloud
dio error** y no se ha podido resolver todavía, así que **se pivotó
temporalmente a Render** (front como "Static Site", back como "Web Service"
con su propio Dockerfile) mientras se consigue servidor propio.

**Nada del trabajo de Docker se perdió ni se descartó** — `Dockerfile`,
`nginx.conf` y todo `nginx-proxy/` quedan intactos y comentados como
"para cuando se retome la VM". Ver sección "Deploy temporal en Render" más
abajo para lo específico de este pivote.

Este archivo resume lo que se avanzó en la "dockerización" de los 3
proyectos originales y qué falta para tenerlo corriendo en producción, ya
sea en Render (corto plazo) o en la VM (plan original, pausado).

## Proyectos involucrados

| Proyecto | Ruta local | Rol |
|---|---|---|
| Backend | `C:\desarrollos\portafolioBack` | API Spring Boot (Java 17, Maven) |
| Frontend | `C:\desarrollos\portafolioFront` | SPA Angular 17 |
| Proxy central | `C:\desarrollos\nginx-proxy` | Nginx — enruta por dominio hacia front/back (solo VM, plan pausado) |

Repos en GitHub (usuario `danielg598`):
- Backend: `github.com/danielg598/portafolioBack` (remote `origin` ya configurado).
- Frontend: repo separado (confirmar nombre exacto si hace falta — no se ha usado `git` sobre `portafolioFront` en esta sesión).

## Docker Hub

Usuario: **jarvisai68**. Las 3 imágenes ya están construidas y subidas,
multi-arquitectura (`linux/amd64` + `linux/arm64`, necesario para la VM
Ampere ARM):

- `jarvisai68/portafolio-back:latest`
- `jarvisai68/portafolio-front:latest`
- `jarvisai68/nginx-proxy:latest`

Verificado en https://hub.docker.com/u/jarvisai68 (las 3 aparecen listadas).

Comando usado para cada una (parado dentro de la carpeta del proyecto):
```powershell
docker buildx build --platform linux/amd64,linux/arm64 -t jarvisai68/<nombre-imagen>:latest --push .
```
Requiere: `docker login` una vez, y un builder multi-arch creado una vez
(`docker buildx create --name multiarch --use` + `docker buildx inspect --bootstrap`).

**Nota:** con `--push` y multi-plataforma, la imagen NO aparece en
`docker images` local — se sube directo al registry. Es normal, no es un error.

## Backend (`portafolioBack`)

- `Dockerfile`: build multi-stage —
  - Etapa 1: `maven:3.9.9-eclipse-temurin-17` (compila con `mvn package -DskipTests`, cachea dependencias copiando `pom.xml` antes que `src/`).
  - Etapa 2: `eclipse-temurin:17-jre-jammy` (solo JRE), usuario no-root `spring`, `HEALTHCHECK` con `curl` contra `GET /api/config` (endpoint público real; el proyecto no tiene Spring Boot Actuator).
- `.dockerignore` creado.
- `.env` (no versionado, ver `.gitignore`) con credenciales reales de Gmail:
  - `MAIL_USERNAME` / `MAIL_PASSWORD` → **importante**: `MAIL_PASSWORD` debe ser un **App Password** de Gmail (Cuenta Google → Seguridad → Verificación en 2 pasos → Contraseñas de aplicaciones), la contraseña normal de la cuenta da `535-5.7.8 Authentication failed`.
  - `CONTACT_RECIPIENT`, `CONTACT_WHATSAPP`, `CONTACT_EMAIL_DISPLAY`, `CORS_ALLOWED_ORIGINS`.
- Ya probado localmente con `docker run --env-file .env` → arranca y responde `(healthy)`.
- Pendiente en producción: subir estas variables reales vía **Infisical** (mencionado por el usuario) en vez de un `.env` plano en la VM.

## Frontend (`portafolioFront`)

- Angular 17 con el builder nuevo (`@angular-devkit/build-angular:application`): el build de producción sale en **`dist/portafolio/browser/`** (con subcarpeta `/browser`, no `dist/portafolio` directo) — importante para el `COPY --from=build` del Dockerfile.
- `src/environments/environment.prod.ts` **no existía** y rompía cualquier build de producción (`angular.json` lo referencia en `fileReplacements`). Se creó con `apiUrl: ''` (string vacío a propósito: en producción front y back quedan bajo el mismo dominio, y el proxy central reenvía `/api/` al backend — las llamadas del front quedan como rutas relativas).
- `Dockerfile`: build multi-stage — `node:20-alpine` (`npm ci` + `ng build --configuration production`) → `nginx:1.27-alpine` sirviendo solo estáticos.
- `nginx.conf`: solo sirve archivos estáticos + fallback SPA (`try_files ... /index.html`) para que las rutas de Angular no den 404 al recargar. **Ya NO** hace proxy de `/api/` (eso se movió al proxy central — se simplificó después de decidir la arquitectura con nginx-proxy).
- `.dockerignore` creado.
- Se agregó un formulario de contacto (modal) en `contact.component.*` que llama a `POST /api/contact` del backend (`src/app/services/contact-api.service.ts`). Traducciones ES/EN agregadas en `lang.service.ts`.

## Proxy central (`nginx-proxy`) — arquitectura elegida: **Opción B**

Se evaluaron dos formas de correr el proxy:
- **Opción A** (descartada): imagen oficial `nginx:alpine` sin modificar + montar `conf.d/` como volumen desde archivos sueltos en el servidor. Más simple, pero requiere tocar archivos directo en la VM.
- **Opción B** (la elegida, igual al patrón que usan en la empresa del usuario): imagen propia con la config **horneada dentro** vía `Dockerfile` (`COPY conf.d/ /etc/nginx/conf.d/`), construida y subida a Docker Hub igual que front/back. Más "inmutable": la VM solo hace `docker pull`, nunca edita configs a mano.

Estructura actual:
```
nginx-proxy/
├── Dockerfile                 # FROM nginx:1.27-alpine + COPY conf.d/
├── .dockerignore
├── docker-compose.yml         # servicio nginx-proxy (image: jarvisai68/nginx-proxy:latest) + certbot
├── conf.d/
│   ├── portafolio.conf        # server_name PLACEHOLDER: "portafolio.tudominio.com"
│   └── _plantilla-nuevo-proyecto.conf.example
├── certbot/www/ , certbot/conf/   # volúmenes para Let's Encrypt (vacíos hasta emitir certs)
└── .gitignore                 # no versiona certs reales
```

Cómo enruta `conf.d/portafolio.conf` (todo bajo un solo dominio):
- `location /.well-known/acme-challenge/` → webroot de certbot.
- `location /api/` → `proxy_pass http://backend:8080/api/;` (directo al backend, sin pasar por el nginx del frontend).
- `location /` → `proxy_pass http://frontend:80;`.
- Bloque `server { listen 443 ssl; ... }` ya escrito pero **comentado**, listo para descomentar una vez existan certificados.

Todos los contenedores (nginx-proxy, frontend, backend) se comunican por nombre de contenedor porque comparten la red Docker externa **`edge`** (creada por `nginx-proxy/docker-compose.yml`, `networks.edge.name: edge`).

**Importante (consecuencia de Opción B):** agregar un dominio/proyecto nuevo, o cambiar cualquier `.conf`, ya NO es solo editar un archivo y recargar — hay que reconstruir y volver a subir la imagen:
```powershell
cd C:\desarrollos\nginx-proxy
docker buildx build --platform linux/amd64,linux/arm64 -t jarvisai68/nginx-proxy:latest --push .
```
y luego en la VM: `docker compose pull nginx-proxy && docker compose up -d nginx-proxy`.

Todo esto (front + back + proxy, los 3 juntos) se probó localmente con Docker corriendo en la misma red `edge`: `GET /api/config` a través del proxy devuelve 200 con el JSON real del backend, y una ruta random de Angular devuelve 200 (fallback SPA funcionando).

## Deploy temporal en Render (plan actual, corto plazo)

### Frontend — Static Site
- `portafolioFront/render.yaml` creado. Build command: `npm ci && npx ng build --configuration production`. `staticPublishPath: ./dist/portafolio/browser`.
- Dos reglas de `routes` (el orden importa, Render usa la primera que matchee):
  1. `rewrite /api/*` → **placeholder** `https://mi-backend.onrender.com/api/:splat` — **hay que reemplazar esto por la URL real del backend una vez desplegado en Render.**
  2. `rewrite /*` → `/index.html` (fallback del Angular Router).
- `environment.prod.ts` sigue con `apiUrl: ''` (no necesita cambios): el navegador siempre pide rutas relativas al mismo origen; quién resuelve `/api/*` hacia el backend real es transparente (antes Nginx, ahora el rewrite de Render).
- Render NO usa el `Dockerfile`/`nginx.conf` de este proyecto para el Static Site — se dejaron intactos (con un comentario aclaratorio arriba de cada uno) para cuando se retome la VM.

### Backend — Web Service (Docker)
- Render puede construir directo desde el `Dockerfile` del repo (conecta el repo de GitHub, elige entorno "Docker", detecta el Dockerfile solo) — **no hace falta Docker Hub para esto**, Render construye la imagen en su propia infra.
- Variables de entorno a cargar manualmente en el dashboard de Render (Environment), mismos nombres que en `.env` local: `MAIL_USERNAME`, `MAIL_PASSWORD`, `CONTACT_RECIPIENT`, `CONTACT_WHATSAPP`, `CONTACT_EMAIL_DISPLAY`, `CORS_ALLOWED_ORIGINS`, `HEALTH_CHECK_TOKEN`.
- Auto-deploy en cada `git push` a `main` viene activado por defecto al conectar el repo (Settings → Auto-Deploy).

### Endpoint keep-alive (evita que Render duerma el free tier)
- Nuevo: `HealthController.java` → `GET /api/health/ping`.
- No toca BD ni nada pesado. Requiere header `X-Health-Token`, comparado con `MessageDigest.isEqual` (constant-time) contra `app.health.token` (`application.yml`) ← `${HEALTH_CHECK_TOKEN}`.
- Sin header o token incorrecto → `401`. Correcto → `200` con `{"status":"UP","timestamp":"..."}`.
- Probado localmente con Docker: los 3 casos (sin header / token malo / token correcto) devuelven lo esperado.
- `.env` local ya tiene un `HEALTH_CHECK_TOKEN` generado (`openssl rand -hex 24`). **En Render hay que crear la misma variable `HEALTH_CHECK_TOKEN`** con un valor secreto (puede ser el mismo u otro nuevo).
- Configurar en **cron-job.org** (o similar) un GET cada 10 min a `https://tu-backend.onrender.com/api/health/ping` con el header `X-Health-Token: <valor>`.

## Pendiente / próximos pasos

**Inmediato (Render):**
1. Hay cambios sin commitear en `portafolioBack` (Dockerfile, HealthController, ajustes a `application.yml`, `.env.example`, este mismo handoff) — falta hacer `git add` + `commit` + `push` a `origin/main`. **Confirmado que `.env` real sigue en `.gitignore`, no se sube.**
2. Crear el Web Service en Render conectado al repo del backend, configurar las env vars (lista arriba), esperar el primer deploy.
3. Copiar la URL pública que asigne Render al backend.
4. Editar `portafolioFront/render.yaml`: reemplazar el placeholder `https://mi-backend.onrender.com` por esa URL real.
5. Actualizar `CORS_ALLOWED_ORIGINS` en las env vars del backend en Render, apuntando a la URL del frontend en Render (si no, el navegador bloqueará las peticiones por CORS).
6. Crear el Static Site en Render para el frontend (conectar su repo — confirmar nombre exacto del repo de `portafolioFront`).
7. Configurar el cron externo (cron-job.org) contra `/api/health/ping` con el `HEALTH_CHECK_TOKEN`.

**Pausado (VM propia — retomar cuando se resuelva Oracle Cloud):**
1. Resolver el error al crear la cuenta gratuita de Oracle Cloud (no se ha diagnosticado el error puntual todavía).
2. Falta crear un `docker-compose.yml` para el proyecto portafolio (frontend + backend juntos), usando `image: jarvisai68/portafolio-front:latest` y `image: jarvisai68/portafolio-back:latest` (no `build:`, ya que las imágenes están en Docker Hub), uniéndose a la red externa `edge`. Aún no se ha creado.
3. Migrar las variables de entorno del backend (`.env`) a Infisical para producción, en vez de un archivo plano en la VM (idea mencionada por el usuario, no implementada todavía).
4. Una vez creada la VM: instalar Docker, copiar `nginx-proxy/` (git clone o scp) — el `docker-compose.yml` y la carpeta `certbot/` bastan, `conf.d/` ya no hace falta copiarla porque está horneada en la imagen.
5. Reemplazar `portafolio.tudominio.com` por el dominio real en `conf.d/portafolio.conf`, reconstruir y subir la imagen de `nginx-proxy` con el dominio correcto.
6. Levantar `nginx-proxy` primero (crea la red `edge`), luego el compose del proyecto portafolio.
7. Emitir certificado con certbot (comando ya documentado en los comentarios de `portafolio.conf`), descomentar el bloque HTTPS, redirigir HTTP→HTTPS.

## Gotchas encontrados (para no repetir)

- El error `Could not resolve placeholder 'CONTACT_WHATSAPP'` al arrancar el backend era por falta del archivo `.env` (solo existía `.env.example`).
- Gmail rechaza la contraseña normal de la cuenta para SMTP (`535-5.7.8`) — se necesita App Password.
- El builder por defecto de `buildx` (`driver: docker`) no soporta bien `--push` multi-plataforma; se creó un builder dedicado `multiarch` (`driver: docker-container`).
- En Git Bash (Windows) los mounts de volumen con rutas estilo `/c/...` a veces se traducen mal (MSYS path mangling) y montan carpetas vacías o crean carpetas espurias (pasó con `nginx-proxy/conf.d;C`, ya eliminada). Usar rutas estilo `C:\...` en el flag `-v` evita el problema, o `MSYS_NO_PATHCONV=1` antes del comando.
