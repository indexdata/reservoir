package org.folio.reservoir.module.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.reactivex.core.http.HttpHeaders;

public class ModuleScripts {

  final static String TEST_SCRIPT_1 = """
    export function transform(clusterStr) {
      let cluster = JSON.parse(clusterStr);
      let recs = cluster.records;
      //merge all marc recs
      const out = {};
      out.leader = 'new leader';
      out.fields = [];
      for (let i = 0; i < recs.length; i++) {
        let rec = recs[i];
        let marc = rec.payload.marc;
        //collect all marc fields
        out.fields.push(...marc.fields);
        //stamp with custom 999 for each member
        let f999 =
        {
          '999' :
          {
            'ind1': '1',
            'ind2': '0',
            'subfields': [
              {'i': rec.globalId },
              {'l': rec.localId },
              {'s': rec.sourceId }
            ]
          }
        };
        out.fields.push(f999);
      }
      return JSON.stringify(out);
    }
    """;

  final static String TEST_SCRIPT_RETURNS_INT = """
    export function transform(clusterStr) {
      return 1;
    }
    """;

  final static String TEST_SCRIPT_THROW = """
    export function transform(clusterStr) {
      throw 'Error';
    }
    """;

  final static String TEST_SCRIPT_BAD_JSON = """
    export function transform(clusterStr) {
      return '{';
    }
    """;

  final static String TEST_SCRIPT_EMPTY = """
    export function transform(clusterStr) {
      return '{}';
    }
    """;

  final static String TEST_SCRIPT_MK_ISBN = """
    export function matchkey(x) {
      let identifiers = JSON.parse(x).identifiers;
      const isbn = [];
      for (let i = 0; i < identifiers.length; i++) {
        isbn.push(identifiers[i].isbn);
      }
      return isbn;
    }
    """;

  static void respondPlain(RoutingContext ctx, String script) {
    HttpServerResponse response = ctx.response();
    response.setStatusCode(200);
    response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
    response.end(script);
  }

  public static Future<HttpServer> serveModules(Vertx vertx, int port)  {
    Router router = Router.router(vertx);
    router.get("/lib/marc-transformer.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_1));
    router.get("/lib/returns-int.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_RETURNS_INT));
    router.get("/lib/throw.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_THROW));
    router.get("/lib/bad-json.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_BAD_JSON));
    router.get("/lib/empty.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_EMPTY));
    router.get("/lib/matchkey-isbn.mjs").handler(ctx -> respondPlain(ctx, TEST_SCRIPT_MK_ISBN));
    HttpServer httpServer = vertx.createHttpServer();
    return httpServer.requestHandler(router).listen(port);
  }
}
