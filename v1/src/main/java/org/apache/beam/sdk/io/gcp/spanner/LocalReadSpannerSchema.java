/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.PCollectionView;

/**
 * This {@link DoFn} reads Cloud Spanner 'information_schema.*' tables to build the {@link
 * SpannerSchema}.
 */
@SuppressWarnings({
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
class LocalReadSpannerSchema extends DoFn<Void, SpannerSchema> {

  private final SpannerConfig config;

  private final PCollectionView<Dialect> dialectView;

  private transient LocalSpannerAccessor spannerAccessor;

  public LocalReadSpannerSchema(SpannerConfig config, PCollectionView<Dialect> dialectView) {
    this.config = config;
    this.dialectView = dialectView;
  }

  @Setup
  public void setup() throws Exception {
    spannerAccessor = LocalSpannerAccessor.getOrCreate(config);
  }

  @Teardown
  public void teardown() throws Exception {
    spannerAccessor.close();
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    Dialect dialect = c.sideInput(dialectView);
    SpannerSchema.Builder builder = SpannerSchema.builder(dialect);
    DatabaseClient databaseClient = spannerAccessor.getDatabaseClient();
    try (ReadOnlyTransaction tx = databaseClient.readOnlyTransaction()) {
      ResultSet resultSet = readTableInfo(tx, dialect);

      while (resultSet.next()) {
        String tableName = resultSet.getString(0);
        String columnName = resultSet.getString(1);
        String type = resultSet.getString(2);
        long cellsMutated = resultSet.getLong(3);

        if (type.equalsIgnoreCase("JSONB")) {
          type = "CHARACTER VARYING";
        }

        builder.addColumn(tableName, columnName, type, cellsMutated);
      }

      resultSet = readPrimaryKeyInfo(tx, dialect);
      while (resultSet.next()) {
        String tableName = resultSet.getString(0);
        String columnName = resultSet.getString(1);
        String ordering = resultSet.getString(2);

        builder.addKeyPart(tableName, columnName, "DESC".equalsIgnoreCase(ordering));
      }
    }
    c.output(builder.build());
  }

  private ResultSet readTableInfo(ReadOnlyTransaction tx, Dialect dialect) {
    // retrieve schema information for all tables, as well as aggregating the
    // number of indexes that cover each column. this will be used to estimate
    // the number of cells (table column plus indexes) mutated in an upsert operation
    // in order to stay below the 20k threshold
    String statement = "";
    switch (dialect) {
      case GOOGLE_STANDARD_SQL:
        statement =
            "SELECT"
                + "    c.table_name"
                + "  , c.column_name"
                + "  , c.spanner_type"
                + "  , (1 + COALESCE(t.indices, 0)) AS cells_mutated"
                + "  FROM ("
                + "    SELECT c.table_name, c.column_name, c.spanner_type, c.ordinal_position"
                + "     FROM information_schema.columns as c"
                + "     WHERE c.table_catalog = '' AND c.table_schema = '') AS c"
                + "  LEFT OUTER JOIN ("
                + "    SELECT t.table_name, t.column_name, COUNT(*) AS indices"
                + "      FROM information_schema.index_columns AS t "
                + "      WHERE t.index_name != 'PRIMARY_KEY' AND t.table_catalog = ''"
                + "      AND t.table_schema = ''"
                + "      GROUP BY t.table_name, t.column_name) AS t"
                + "  USING (table_name, column_name)"
                + "  ORDER BY c.table_name, c.ordinal_position";
        break;
      case POSTGRESQL:
        statement =
            "SELECT"
                + "    c.table_name"
                + "  , c.column_name"
                + "  , c.spanner_type"
                + "  , (1 + COALESCE(t.indices, 0)) AS cells_mutated"
                + "  FROM ("
                + "    SELECT c.table_name, c.column_name, c.spanner_type, c.ordinal_position"
                + "      FROM information_schema.columns as c"
                + "      WHERE c.table_schema NOT IN"
                + "      ('information_schema', 'spanner_sys', 'pg_catalog')) AS c"
                + "  LEFT OUTER JOIN ("
                + "    SELECT t.table_name, t.column_name, COUNT(*) AS indices"
                + "      FROM information_schema.index_columns AS t "
                + "      WHERE t.index_name != 'PRIMARY_KEY'"
                + "      AND t.table_schema NOT IN"
                + "      ('information_schema', 'spanner_sys', 'pg_catalog')"
                + "      GROUP BY t.table_name, t.column_name) AS t"
                + "  USING (table_name, column_name)"
                + "  ORDER BY c.table_name, c.ordinal_position";
        break;
      default:
        throw new IllegalArgumentException("Unrecognized dialect: " + dialect.name());
    }
    return tx.executeQuery(Statement.of(statement));
  }

  private ResultSet readPrimaryKeyInfo(ReadOnlyTransaction tx, Dialect dialect) {
    String statement = "";
    switch (dialect) {
      case GOOGLE_STANDARD_SQL:
        statement =
            "SELECT t.table_name, t.column_name, t.column_ordering"
                + " FROM information_schema.index_columns AS t "
                + " WHERE t.index_name = 'PRIMARY_KEY' AND t.table_catalog = ''"
                + " AND t.table_schema = ''"
                + " ORDER BY t.table_name, t.ordinal_position";
        break;
      case POSTGRESQL:
        statement =
            "SELECT t.table_name, t.column_name, t.column_ordering"
                + " FROM information_schema.index_columns AS t "
                + " WHERE t.index_name = 'PRIMARY_KEY'"
                + " AND t.table_schema NOT IN ('information_schema', 'spanner_sys', 'pg_catalog')"
                + " ORDER BY t.table_name, t.ordinal_position";
        break;
      default:
        throw new IllegalArgumentException("Unrecognized dialect: " + dialect.name());
    }
    return tx.executeQuery(Statement.of(statement));
  }
}
