# ---- Etapa 1: build ----
# Imagen oficial de Maven con JDK 17 (multi-arch: amd64 y arm64).
# Se usa solo para compilar; no viaja a la imagen final.
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copiamos solo el pom.xml primero para aprovechar el cache de capas de Docker:
# si el pom.xml no cambia, esta capa (y la descarga de dependencias) se reutiliza
# aunque cambie el código fuente en el siguiente paso.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Ahora sí copiamos el código fuente y empaquetamos el jar (sin correr tests,
# ya que en un pipeline de CI normalmente se corren antes de construir la imagen).
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Etapa 2: runtime ----
# JRE (no JDK) sobre Ubuntu Jammy — liviana, sin herramientas de compilación,
# y con soporte multi-arch (amd64/arm64), ideal para Oracle Cloud Ampere ARM.
FROM eclipse-temurin:17-jre-jammy

# curl se usa únicamente para el HEALTHCHECK (este proyecto no tiene
# Spring Boot Actuator, así que verificamos un endpoint público existente).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Usuario sin privilegios: la app no corre como root dentro del contenedor.
RUN groupadd -r spring && useradd -r -g spring spring

WORKDIR /app

# Copiamos únicamente el jar ya compilado desde la etapa de build.
# El patrón "*.jar" no matchea "*.jar.original" (el respaldo que deja
# el plugin de Spring Boot), así que solo se copia el jar final.
COPY --from=build /app/target/*.jar app.jar

USER spring

EXPOSE 8080

ENV JAVA_OPTS=""

# Verifica que la app responda; /api/config es un endpoint público real
# de este proyecto (no requiere autenticación ni datos de entrada).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/api/config || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
