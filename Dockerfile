# Imagen para publicar el servidor de chat (por ejemplo en Render).
# 1) Compila servidor/ con Maven
FROM maven:3.9-eclipse-temurin-21 AS compilacion
WORKDIR /app
COPY servidor/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY servidor/src ./src
RUN mvn -q -B -DskipTests package

# 2) Imagen final, solo con Java y el .jar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=compilacion /app/target/servidor.jar servidor.jar
# Render indica el puerto en la variable PORT (application.properties la usa); en local es 8080
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "servidor.jar"]
