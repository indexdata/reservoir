package org.folio.reservoir.server;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.LocalDateTime;
import java.util.UUID;
import org.folio.reservoir.server.entity.ClusterBuilder;

public class ClusterRecordItem {

  UUID clusterId;
  LocalDateTime datestamp;
  String oaiSet;

  ClusterRecordItem(Row row) {
    clusterId = row.getUUID("cluster_id");
    datestamp = row.getLocalDateTime("datestamp");
    oaiSet = row.getString("match_key_config_id");
  }

  Future<ClusterBuilder> populateCluster(Storage storage, SqlConnection connection,
      boolean withMetadata) {

    String q = "SELECT * FROM " + storage.getGlobalRecordTable()
        + " LEFT JOIN " + storage.getClusterRecordTable() + " ON record_id = id "
        + " WHERE cluster_id = $1";
    return connection.preparedQuery(q)
        .execute(Tuple.of(clusterId))
        .compose(rowSet -> {
          if (rowSet.size() == 0) {
            return Future.succeededFuture(null); // deleted record
          }
          ClusterBuilder cb = new ClusterBuilder(clusterId).records(rowSet);
          if (!withMetadata) {
            return Future.succeededFuture(cb);
          }
          return getClusterValues(storage, connection, clusterId, cb);
        });
  }

  private Future<ClusterBuilder> getClusterValues(Storage storage, SqlConnection conn,
      UUID clusterId, ClusterBuilder cb) {
    return conn.preparedQuery("SELECT match_value FROM " + storage.getClusterValuesTable()
          + " WHERE cluster_id = $1")
      .execute(Tuple.of(clusterId))
      .map(cb::matchValues);
  }
}
