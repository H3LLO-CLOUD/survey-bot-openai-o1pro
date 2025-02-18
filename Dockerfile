FROM sbtscala/scala-sbt:eclipse-temurin-21.0.5_11_1.10.7_3.6.2 AS build
WORKDIR /opt/app
COPY . /opt/app
RUN sbt clean compile stage

FROM eclipse-temurin:21 AS production
WORKDIR /home/app
COPY --from=build /opt/app/target/universal/stage /home/app
COPY src/main/resources/prod.conf /home/app/prod.conf
CMD ["/home/app/bin/survey-bot", "-Dconfig.file=/home/app/prod.conf"]
