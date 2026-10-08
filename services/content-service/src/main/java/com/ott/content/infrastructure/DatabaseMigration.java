package com.ott.content.infrastructure;

import org.flywaydb.core.Flyway;

/** Deployment-only entry point: one owned schema, no application schedulers or listeners. */
public final class DatabaseMigration {
  private DatabaseMigration() {}

  public static void main(String[] args) {
    Flyway.configure()
        .dataSource(
            required("MIGRATION_DB_URL"),
            required("MIGRATION_DB_USER"),
            required("MIGRATION_DB_PASSWORD"))
        .locations("classpath:db/content")
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    return value;
  }
}
