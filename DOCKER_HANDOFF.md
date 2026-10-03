# Handoff — Dockerización y despliegue del portafolio

Última actualización: 2026-10-03.

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

## Deploy en Render — ✅ COMPLETADO Y EN PRODUCCIÓN (2026-10-03)

URLs reales:
- Backend: `https://portafolioback-3eju.onrender.com`
- Frontend: `https://portafoliofront-xehs.onrender.com`

### Frontend — Static Site
- Se creó como **Static Site manual** (no "Blueprint"), así que `render.yaml` del repo **no se aplica solo** — el build command y publish directory se cargaron a mano en el dashboard, y las reglas de rewrite también se cargaron a mano en la pestaña **Redirects/Rewrites** (ver gotcha de `:splat` abajo).
- Build command: `npm ci && npx ng build --configuration production`. Publish directory: `./dist/portafolio/browser`.
- Reglas de rewrite activas (en este orden, en el dashboard):
  1. `/api/*` → `https://portafolioback-3eju.onrender.com/api/*` (Rewrite)
  2. `/*` → `/index.html` (Rewrite, fallback del Angular Router)
- `environment.prod.ts` se sacó del `.gitignore` y ya está commiteado (sin secretos, `apiUrl: ''` a propósito) — **sin esto el build en Render falla** porque `angular.json` lo necesita vía `fileReplacements`.
- No se necesita ninguna env var en el Static Site del frontend.

### Backend — Web Service (Docker)
- Render construyó la imagen directo desde el `Dockerfile` del repo, sin pasar por Docker Hub.
- Env vars actuales en el dashboard de Render: `CONTACT_RECIPIENT`, `CONTACT_WHATSAPP`, `CONTACT_EMAIL_DISPLAY`, `CORS_ALLOWED_ORIGINS`, `HEALTH_CHECK_TOKEN`, `BREVO_API_KEY`, `BREVO_SENDER_EMAIL`. **Ya NO existen `MAIL_USERNAME`/`MAIL_PASSWORD`** (ver migración a Brevo abajo).
- **Gotcha importante:** cambiar una env var en el dashboard de Render **no redespliega el servicio solo** — hay que forzar **Manual Deploy → Deploy latest commit** después de guardar, o el proceso corriendo sigue con los valores viejos.

### Correo de contacto: migrado de SMTP a Brevo (API HTTPS)
- **Problema encontrado:** Render bloquea tráfico saliente a los puertos SMTP 25/465/587 en el plan free (política oficial desde sep-2025, para prevenir spam). `JavaMailSender` contra `smtp.gmail.com:587` colgaba ~2 minutos y terminaba en `500`.
- **Solución:** `ContactService` ahora llama a la API transaccional de Brevo (`https://api.brevo.com/v3/smtp/email`) vía `java.net.http.HttpClient` (sin dependencias nuevas, Jackson ya viene con `spring-boot-starter-web`). Viaja por HTTPS/443, que no está bloqueado.
- Se quitó `spring-boot-starter-mail` del `pom.xml`.
- Cuenta Brevo (plan free, 300 emails/día): remitente verificado = `d.alzate598@gmail.com`. Requiere verificar número de teléfono antes de poder enviar (aviso que sale en el dashboard de Brevo la primera vez).
- Warning esperado y sin solución fácil: Brevo avisa que el remitente no cumple los nuevos requisitos de DKIM/DMARC de Google/Yahoo porque es un Gmail, no un dominio propio — bajo riesgo de ir a spam ocasionalmente, no bloquea el envío. Se resolvería solo con dominio propio + DNS.
- **Gotcha de seguridad:** una API key de Brevo quedó expuesta sin querer en una captura de pantalla durante el setup — se revocó y se generó una nueva. Recordar: las API keys nunca deben pegarse en capturas ni en el chat, solo copiarse directo al campo de destino.

### Protección anti-bot en el formulario de contacto (honeypot + timing)
- `ContactRequest` tiene dos campos nuevos no-sensibles: `website` (honeypot, string) y `elapsedMs` (Long).
- Frontend: input `website` invisible (`position: absolute; left: -9999px`, `aria-hidden`, `tabindex="-1"`) que un humano nunca ve ni llena; `elapsedMs` = tiempo entre que se abre el modal y se envía el form.
- Backend (`ContactController.looksLikeBot`): si `website` viene con texto, o `elapsedMs < 1500`, se responde `200 {"status":"sent"}` (éxito falso, no delata la trampa) pero **nunca se llama a `contactService.send()`** — no gasta cupo de Brevo ni llega correo falso.
- Complementa (no reemplaza) el rate-limiter por IP que ya existía.
- **Verificado end-to-end en producción (2026-10-03):** petición real (website vacío, elapsedMs alto) → `200` en ~1s, correo llegó a Gmail. Petición "bot" (honeypot relleno) → `200` en ~0.3s, **no** llegó correo. La diferencia de tiempo confirma que la petición bot corta antes de llamar a Brevo. Probado también desde el formulario real del frontend (no solo curl): mensaje "¡Mensaje enviado!" en pantalla y correo recibido.

### Keep-alive (ya no se duerme en el free tier)
- `GET /api/health/ping` con header `X-Health-Token` (constant-time compare) — ver `HealthController.java`.
- Configurado en **cron-job.org**: GET cada 10 min a `https://portafolioback-3eju.onrender.com/api/health/ping` con header `X-Health-Token: <valor>`. Probado con "ejecución de prueba" → `200 OK` en ~240ms.
- `HEALTH_CHECK_TOKEN` actual generado con `openssl rand -hex 24` y cargado tanto en Render como en cron-job.org.

### Otros fixes de seguridad aplicados antes del primer deploy
- Rate-limiter (`ContactController.resolveClientIp`): tomaba el **primer** valor de `X-Forwarded-For` (falsificable por el cliente); ahora toma el **último** (el que añade el proxy de confianza de Render).
- `ContactRequest.name`: ahora rechaza `\r`/`\n` con `@Pattern` — prevenía inyección de cabeceras en el `Subject` del correo (relevante incluso con Brevo, defensa en profundidad).
- `CorsConfig`: `CORS_ALLOWED_ORIGINS` se parsea separando por coma antes de pasarlo a Spring (antes, con múltiples orígenes separados por coma, no matcheaba ninguno).
- **Gotcha de sintaxis Render vs Netlify:** el wildcard de rewrite en Render es `*` tanto en `source` como en `destination` — `:splat` es sintaxis de **Netlify** y causaba que Render reenviara el path literal `/api/:splat` al backend (404). Ya corregido en `render.yaml` y en el dashboard.

### Responsive del frontend (2026-10-03)
- El sitio no tenía **ninguna** media query — el nav se montaba encima del logo en mobile, y las grillas de 2/3 columnas (hero, about, servicios, proyectos) no colapsaban, cortando contenido a los costados en vez de apilarse (detectado con capturas reales de celular).
- Arreglado: menú hamburguesa en `nav.component` bajo 860px; breakpoints para colapsar cada grid a 1-2 columnas según ancho en `hero`, `about`, `services`, `projects`. `stack.component` ya era responsive (`grid-template-columns: repeat(auto-fill, minmax(200px,1fr))`), no se tocó.
- Se subió el presupuesto de CSS por componente en `angular.json` (`anyComponentStyle`: 2kb→4kb warning, 4kb→8kb error) porque el CSS responsive nuevo superaba el límite original.
- **Confirmado por el usuario en su celular (2026-10-03):** se ve bien contra la URL real de Render.

## Pendiente / próximos pasos

**Roadmap confirmado por el usuario (2026-10-03), en este orden:**
1. Ajustes de diseño al portafolio actual — **responsive ya resuelto** (ver arriba), puede haber más ajustes pendientes.
2. Separar el portafolio en **dos proyectos independientes** — uno de ellos recibirá el stack completo para desplegar en servidor propio con Docker.
3. Aprender a desplegar **bases de datos** en ese contexto dockerizado (Postgres/MySQL en contenedor, volúmenes, backups — sin decidir aún).
4. Montar **Harbor** (registry de contenedores open-source, self-hosted) en infraestructura propia.
5. Empezar a subir imágenes y contenedores al servidor usando ese Harbor propio.

Esto es la continuación directa del plan de VM pausado más abajo — cuando se retome la VM, Harbor probablemente reemplace o complemente el uso de Docker Hub (`jarvisai68`) como registry.

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
- Render bloquea los puertos SMTP (25/465/587) salientes en el plan free — cualquier librería de correo que use SMTP crudo (JavaMail, nodemailer con SMTP transport, etc.) cuelga ~2 min y falla. Solución: usar la API HTTPS del proveedor de correo (Brevo, Resend, SendGrid...), nunca SMTP directo, si el backend corre en Render free.
- Cambiar una env var en el dashboard de Render **no redespliega el servicio automáticamente** — hay que forzar "Manual Deploy → Deploy latest commit" después de guardarla, si no el proceso sigue corriendo con el valor viejo.
- El wildcard de rewrite en Render es `*` en `source` Y en `destination` (p. ej. `/api/*` → `.../api/*`). `:splat` es sintaxis de Netlify, no de Render, y causa que Render reenvíe el placeholder literal en vez de sustituirlo.
- Un Static Site creado a mano (no "Blueprint") en Render **ignora el `render.yaml` del repo** — las reglas de redirects/rewrites hay que cargarlas también a mano en la pestaña correspondiente del dashboard.
- Nunca pegar una API key en una captura de pantalla ni en un chat — si pasa, tratarla como comprometida y revocarla de inmediato, aunque el canal parezca privado.
