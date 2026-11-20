# Daymark

A personal habit tracker in Java with Spring Boot, Spring Security, Thymeleaf, and JDBC. The hosted app uses PostgreSQL. Local mode uses persistent H2 storage.

## Live Demo
[View Daymark – Personal Habit Tracker](https://daymark-habits.onrender.com/)

## Local demo

Install Java 17+ and Maven. From this folder run `mvn spring-boot:run`, then open http://localhost:3300. Choose **Create an account** and select your time zone. Add a daily or weekday habit, check it off, undo it, and open **Progress** for the last 28 days and streaks. Edit or archive habits from **Your habits** on Today or from Progress.

Accounts own all their habits and check-ins. The database enforces one check-in per habit per date. Streaks skip unscheduled days and an unfinished current day does not break a streak. Schedule changes apply from today onward. Earlier dates retain their previously recorded schedule. Archiving stops new check-ins.

## Render and Neon

1. Create a separate Neon PostgreSQL database for Daymark.
2. Push this folder to its own GitHub repository. In Render choose **New → Blueprint** and select the repository. Render reads render.yaml at its root.
3. Supply DATABASE_URL as a JDBC connection URL such as `jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require`. Copy the host and database name from Neon's Connect panel.
4. Supply DATABASE_USER as the Neon role and DATABASE_PASSWORD as that role's password. Keep these values in Render's environment settings.
5. Deploy. Open the Render URL and verify /health, then create an account through the app. Spring initializes the tables automatically.

Free Render services sleep when idle. The Docker runtime caps Java heap at 256 MB for the free instance.
