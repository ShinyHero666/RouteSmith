package com.moyuan.modelport.store;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class DatabaseDialect {
    private final boolean h2;

    public DatabaseDialect(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            this.h2 = connection.getMetaData().getDatabaseProductName()
                    .toLowerCase()
                    .contains("h2");
        } catch (Exception error) {
            throw new IllegalStateException("unable to detect gateway database dialect", error);
        }
    }

    public boolean isH2() {
        return h2;
    }
}
