package com.indexdata.reservoir.server;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import io.vertx.sqlclient.Tuple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.okapi.common.XOkapiHeaders;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xml.sax.SAXException;

@RunWith(VertxUnitRunner.class)
public class MainVerticleTest extends TestBase {
  private final static Logger log = LogManager.getLogger("MainVerticleTest");

  static final String PMH_CLIENT_ID = "1";
  static final String SOURCE_ID_1 = "SOURCE-1";
  static final String SOURCE_ID_2 = "SOURCE-2";
  static final int UNUSED_PORT = 9233;

  @After
  public void after() {
    deleteIssnMatchKey();
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/oai")
        .then()
        .statusCode(204);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/issn");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/isbn");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/pmh-clients/" + PMH_CLIENT_ID);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .delete("/reservoir/pmh-clients/" + PMH_CLIENT_ID);
  }


  public class SruVerify {

    List<String> identifiers = new LinkedList<>();
    List<String> errors = new LinkedList<>();
    int numberOfRecords = 0;
    String response;

    public SruVerify(String s) throws XMLStreamException {
        InputStream stream = new ByteArrayInputStream(s.getBytes());
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);

        int level = 0;
        while (xmlStreamReader.hasNext()) {
          int event = xmlStreamReader.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            level++;
            String elem = xmlStreamReader.getLocalName();
            if (level == 1) {
              response = elem;
            } else if ("numberOfRecords".equals(elem)) {
              if (level != 2 && level <= 4) {
                throw new IllegalStateException("numberOfRecords not at level 2");
              }
              event = xmlStreamReader.next();
              if (event == XMLStreamConstants.CHARACTERS) {
                numberOfRecords = Integer.parseInt(xmlStreamReader.getText());
              }
            } else if ("diagnostic".equals(elem)) {
              if (level < 3) {
                throw new IllegalStateException("diagnostic not at level 3");
              }
            } else if ("message".equals(elem)) {
              if (level < 4) {
                throw new IllegalStateException("message not at level 4");
              }
              event = xmlStreamReader.next();
              if (event == XMLStreamConstants.CHARACTERS) {
                errors.add(xmlStreamReader.getText());
              }
            } else if ("records".equals(elem)) {
              if (level != 2 && level <= 4 ) {
                throw new IllegalStateException("records not at level 2");
              }
            } else if ("record".equals(elem)) {
              if (level != 3 && level <= 4) {
                throw new IllegalStateException("record not at level 3");
              }
            } else if ("recordData".equals(elem)) {
              if (level < 4) {
                throw new IllegalStateException("recordData not at level 4");
              }
              identifiers.add("1");
            }
          }
          if (event == XMLStreamConstants.END_ELEMENT) {
            level--;
          }
        }
      }
    }

  @Test
  public void testMainPage() {
    ModuleVersionReporter m = new ModuleVersionReporter("com.indexdata/reservoir-server");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir")
        .then().statusCode(200)
        .header("Content-Type", is("application/json"))
        .body("revision", is(m.getCommitId()))
        .body("links.clusters", endsWith("/reservoir/clusters"));
  }

  @Test
  public void testAdminHealth() {
    RestAssured.given()
        .baseUri(MODULE_URL)
        .get("/admin/health")
        .then().statusCode(200)
        .header("Content-Type", is("text/plain"));
  }

  @Test
  public void testHealthz() {
    RestAssured.given()
        .baseUri(MODULE_URL)
        .get("/healthz")
        .then().statusCode(200)
        .header("Content-Type", is("text/plain"));
  }

  @Test
  public void testGetUploadForm() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/upload-form")
        .then()
        .statusCode(200)
        .header("Content-Type", is("text/html;charset=UTF-8"));
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/upload-form/")
        .then()
        .statusCode(200)
        .header("Content-Type", is("text/html;charset=UTF-8"));
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/upload-form/index.html")
        .then()
        .statusCode(200)
        .header("Content-Type", is("text/html;charset=UTF-8"));
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/upload-form/index.js")
        .then()
        .statusCode(200)
        .header("Content-Type", is("text/javascript;charset=UTF-8"));
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/upload-form/notexist.html")
        .then()
        .statusCode(404);
  }

  @Test
  public void testGetGlobalRecordsUnknownTenant() {
    String tenant = "unknowntenant";
    RestAssured.given()
        .baseUri(MODULE_URL)
        .header(XOkapiHeaders.TENANT, tenant)
        .get("/reservoir/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(is("ERROR: relation \"unknowntenant_mod_reservoir.global_records\" does not exist (42P01)"));
  }

  @Test
  public void testGetGlobalRecordsBadCqlField() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "foo=bar")
        .get("/reservoir/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(is("Unsupported CQL index: foo"));
  }

  @Test
  public void testBadTenantName() {
    String tenant = "1234"; // bad tenant name!

    String sourceId = "SOURCE-1";
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "1"))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .baseUri(MODULE_URL)
        .header(XOkapiHeaders.TENANT, tenant)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(containsString("X-Okapi-Tenant header must match"));
  }

  @Test
  public void ingestTitleUnknownTenant() {
    String sourceId = "SOURCE-1";
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("marcPayload", new JsonObject().put("leader", "00914naa  2200337   450 "))
            .put("inventoryPayload", new JsonObject().put("isbn", "1"))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, "unknowntenant")
        .baseUri(MODULE_URL)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(400)
        .header("Content-Type", is("text/plain"))
        .body(containsString(" does not exist (42P01)"));
  }

  @Test
  public void matchKeysNonExistingMethod() {
    JsonObject matchKey = new JsonObject()
        .put("id", "xx")
        .put("method", "other")
        .put("params", new JsonObject());

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(Matchers.is("Non-existing method 'other'"));
  }

    @Test
    public void matchKeysNonExistingMatcherModule() {
        JsonObject matchKey = new JsonObject()
            .put("id", "xx")
            .put("matcher", "not-exists");

        RestAssured.given()
            .header(XOkapiHeaders.TENANT, TENANT_1)
            .header("Content-Type", "application/json")
            .body(matchKey.encode())
            .post("/reservoir/config/matchkeys")
            .then().statusCode(400)
            .contentType("text/plain")
            .body(Matchers.is("Matcher module 'not-exists' does not exist"));
    }

    @Test
    public void testMatchKeysExistingMatcherModuleWithInvocation() {
        JsonObject module = new JsonObject()
            .put("id", "exists")
            .put("type", "jsonpath")
            .put("script", "$.marc");

        RestAssured.given()
            .header(XOkapiHeaders.TENANT, TENANT_1)
            .header("Content-Type", "application/json")
            .body(module.encode())
            .post("/reservoir/config/modules")
            .then()
            .statusCode(201)
            .contentType("application/json");

        JsonObject matchKey = new JsonObject()
            .put("id", "works")
            .put("matcher", "exists::function");

        RestAssured.given()
            .header(XOkapiHeaders.TENANT, TENANT_1)
            .header("Content-Type", "application/json")
            .body(matchKey.encode())
            .post("/reservoir/config/matchkeys")
            .then()
            .statusCode(201)
            .contentType("application/json");

        RestAssured.given()
            .header(XOkapiHeaders.TENANT, TENANT_1)
            .header("Content-Type", "application/json")
            .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
            .then().statusCode(204);

        RestAssured.given()
            .header(XOkapiHeaders.TENANT, TENANT_1)
            .header("Content-Type", "application/json")
            .delete("/reservoir/config/modules/" + module.getString("id"))
            .then().statusCode(204);
    }

  @Test
  public void testMatchkeysCrudWithMethod() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "none")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", is(empty()))
        .body("resultInfo.totalRecords", is(nullValue()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "foo")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("Instance does not match any of [\"exact\",\"none\"]"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "exact")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", is(empty()))
        .body("resultInfo.totalRecords", is(0));

    JsonObject matchKey = new JsonObject()
        .put("id", "10a")
        .put("method", "jsonpath")
        .put("params", new JsonObject().put("marc", "$.fields.010.subfields[*].a"))
        .put("update", "ingest");

    JsonObject matchKeyOut = new JsonObject()
        .put("id", "10a")
        .put("matcher", null)
        .put("method", "jsonpath")
        .put("params", new JsonObject().put("marc", "$.fields.010.subfields[*].a"))
        .put("update", "ingest");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is("MatchKey " + matchKeyOut.getString("id") + " not found"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(matchKey.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("duplicate key value violates unique constraint"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(matchKeyOut.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", hasSize(1))
        .body("matchKeys[0].id", is(matchKeyOut.getString("id")))
        .body("matchKeys[0].method", is(matchKeyOut.getString("method")))
        .body("matchKeys[0].update", is(matchKeyOut.getString("update")))
        .body("matchKeys[0].matcher", is(matchKeyOut.getString("matcher")));
        // should really check that params are same

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys?query=method=" + matchKeyOut.getString("method"))
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", hasSize(1))
        .body("matchKeys[0].id", is(matchKeyOut.getString("id")))
        .body("matchKeys[0].method", is(matchKeyOut.getString("method")))
        .body("matchKeys[0].update", is(matchKeyOut.getString("update")));

    matchKey.put("update", "manual");
    matchKeyOut.put("update", "manual");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(matchKeyOut.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);
  }

  @Test
  public void testMatchkeyCrudWithMatcher() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "none")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", is(empty()))
        .body("resultInfo.totalRecords", is(nullValue()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "foo")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("Instance does not match any of [\"exact\",\"none\"]"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "exact")
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", is(empty()))
        .body("resultInfo.totalRecords", is(0));

    JsonObject matchKey = new JsonObject()
        .put("id", "10a")
        .put("matcher", "matcher-10a")
        .put("update", "ingest");

    JsonObject matchKeyOut = new JsonObject()
        .put("id", "10a")
        .put("matcher", "matcher-10a")
        .put("method", null)
        .put("params", null)
        .put("update", "ingest");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is("MatchKey " + matchKeyOut.getString("id") + " not found"));

    JsonObject module10a = new JsonObject()
        .put("id", "matcher-10a")
        .put("type", "jsonpath")
        .put("script", "$.marc.fields.010.subfields[*].a");

    JsonObject module10aOut = new JsonObject()
        .put("id", "matcher-10a")
        .put("type", "jsonpath")
        .put("url", null)
        .put("function", null)
        .put("script", "$.marc.fields.010.subfields[*].a");

    //post module first
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module10a.encode())
        .post("/reservoir/config/modules")
        .then()
        .statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(module10aOut.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(matchKey.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("duplicate key value violates unique constraint"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(matchKeyOut.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys")
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", hasSize(1))
        .body("matchKeys[0].id", is(matchKeyOut.getString("id")))
        .body("matchKeys[0].method", is(matchKeyOut.getString("method")))
        .body("matchKeys[0].update", is(matchKeyOut.getString("update")))
        .body("matchKeys[0].matcher", is(matchKeyOut.getString("matcher")));
        // should really check that params are same

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys?query=matcher=" + matchKeyOut.getString("matcher"))
        .then().statusCode(200)
        .contentType("application/json")
        .body("matchKeys", hasSize(1))
        .body("matchKeys[0].id", is(matchKeyOut.getString("id")))
        .body("matchKeys[0].method", is(matchKeyOut.getString("method")))
        .body("matchKeys[0].update", is(matchKeyOut.getString("update")));

    matchKey.put("update", "manual");
    matchKeyOut.put("update", "manual");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(matchKeyOut.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);

    //delete the module
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/modules/" + module10a.getString("id"))
        .then().statusCode(204);
  }

  @Test
  public void ingestRecordsPayloadMissing() {
    String sourceId = "SOURCE-1";
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01"));
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(400)
        .body(containsString("payload required"));
  }

  @Test
  public void ingestRecordsLocalIdMissing() {
    String sourceId = "SOURCE-1";
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("payload", new JsonObject()));
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(400)
        .body(containsString("localId required"));
  }

  @Test
  public void ingestRecords() {
    String sourceId = "SOURCE-1";
    JsonArray records = new JsonArray()
        .add(new JsonObject()
            .put("localId", "HRID01")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", "1")))
        )
        .add(new JsonObject()
            .put("localId", "HRID02")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", "2")))
        );
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(200);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("items", hasSize(2))
        .body("resultInfo.totalRecords", is(2));

    String res = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + sourceId)
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("items", hasSize(2))
        .body("resultInfo.totalRecords", is(nullValue()))
        .body("items[0].sourceId", is(sourceId))
        .body("items[1].sourceId", is(sourceId))
        .extract().body().asString();
    JsonObject jsonResponse = new JsonObject(res);
    String globalId = jsonResponse.getJsonArray("items").getJsonObject(0).getString("globalId");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/records/" + globalId)
        .then().statusCode(200)
        .body("sourceId", is(sourceId));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/records/" + UUID.randomUUID())
        .then().statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + UUID.randomUUID())
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("items", hasSize(0))
        .body("resultInfo.totalRecords", is(0));

    for (int idx = 0; idx < records.size(); idx++) {
      JsonObject sharedRecord = records.getJsonObject(idx);
      JsonObject payload = sharedRecord.getJsonObject("payload");
      RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .header("Content-Type", "application/json")
          .param("count", "exact")
          .param("query", "localId==" + sharedRecord.getString("localId"))
          .get("/reservoir/records")
          .then().statusCode(200)
          .body("items", hasSize(1))
          .body("items[0].localId", is(sharedRecord.getString("localId")))
          .body("items[0].payload.marc.leader", is(payload.getJsonObject("marc").getString("leader")))
          .body("items[0].payload.inventory.isbn", is(payload.getJsonObject("inventory").getString("isbn")))
          .body("items[0].sourceId", is(sourceId))
          .body("resultInfo.totalRecords", is(1));
    }

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);


  }

  static String verifyOaiResponseRuntime(String s, String verb, List<String> identifiers, int length, JsonArray expRecords) {
    try {
      return verifyOaiResponse(s, verb, identifiers, length, expRecords);
    } catch (XMLStreamException|IOException|SAXException e) {
      throw new RuntimeException(e);
    }
  }

  static String verifyOaiResponse(String s, String verb, List<String> identifiers, int length, JsonArray expRecords)
      throws XMLStreamException, IOException, SAXException {
    InputStream stream = new ByteArrayInputStream(s.getBytes());
    Source source = new StreamSource(stream);
    oaiSchemaValidator.validate(source);

    stream = new ByteArrayInputStream(s.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    String path = "";
    boolean foundEnvelope = false;
    String resumptionToken = null;

    int offset = 0;
    int level = 0;
    int numRecsOrDels = 0;
    JsonArray records = new JsonArray();
    JsonObject record = null;
    JsonArray fields = null;
    JsonArray subfields = null;
    while (xmlStreamReader.hasNext()) {
      int event = xmlStreamReader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        level++;
        String elem = xmlStreamReader.getLocalName();
        path = path + "/" + elem;
        if (level == 2 && elem.equals(verb)) {
          foundEnvelope = true;
        }
        if ("identifier".equals(elem) && xmlStreamReader.hasNext()) {
          event = xmlStreamReader.next();
          if (event == XMLStreamConstants.CHARACTERS) {
            identifiers.add(xmlStreamReader.getText());
          }
        }
        if (level == 3 && ("record".equals(elem) || "header".equals(elem))) {
          //ListRecords has 'record' while ListIdentifiers has 'header'
          offset++;
        }
        if (level == 4 && "header".equals(elem)) {
          String status = xmlStreamReader.getAttributeValue(null, "status");
          if ("deleted".equals(status)) {
            numRecsOrDels++;
          }
        }
        if (level == 3 && "resumptionToken".equals(elem)) {
          event = xmlStreamReader.next();
          if (event == XMLStreamConstants.CHARACTERS) {
            resumptionToken = xmlStreamReader.getText();
          }
        }
        if (level == 5 && "record".equals(elem)) {
          numRecsOrDels++;
          record = new JsonObject();
          fields = new JsonArray();
          record.put("fields", fields);
          records.add(record);
        }
        if (level == 6 && "leader".equals(elem)) {
          event = xmlStreamReader.next();
          String leader = null;
          if (event == XMLStreamConstants.CHARACTERS) {
            leader = xmlStreamReader.getText();
          }
          record.put("leader", leader);
        }
        if (level == 6 && "datafield".equals(elem)) {
          String tag = xmlStreamReader.getAttributeValue(null, "tag");
          String ind1 = xmlStreamReader.getAttributeValue(null, "ind1");
          String ind2 = xmlStreamReader.getAttributeValue(null, "ind2");
          subfields = new JsonArray();
          fields.add(new JsonObject()
            .put(tag, new JsonObject()
              .put("ind1", ind1)
              .put("ind2", ind2)
              .put("subfields", subfields)));
        }
        if (level == 7 && "subfield".equals(elem)) {
          String code = xmlStreamReader.getAttributeValue(null, "code");
          String value = null;
          event = xmlStreamReader.next();
          if (event == XMLStreamConstants.CHARACTERS) {
            value = xmlStreamReader.getText();
          }
          subfields.add(new JsonObject().put(code, value));
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        int off = path.lastIndexOf('/');
        path = path.substring(0, off);
        level--;
      }
    }
    if (length != -1) {
      Assert.assertEquals(s, length, offset);
    }
    if (length > 0) {
      Assert.assertTrue(s, foundEnvelope);
    }
    if (length != -1 && verb.equals("ListRecords")) {
      Assert.assertEquals(s, length, numRecsOrDels);
      if (expRecords != null) {
        verifyMarc(expRecords , records);
      }
    }
    return resumptionToken;
  }

  static void verifyMarc(JsonArray expMarcs, JsonArray actMarcs) {
    Assert.assertEquals("size", expMarcs.size(), actMarcs.size());
    for (int i=0; i < expMarcs.size(); i++) {
      JsonObject expMarc = expMarcs.getJsonObject(i);
      JsonObject actMarc = actMarcs.getJsonObject(i);
      Assert.assertEquals("leader",
        expMarc.getString("leader"),
        actMarc.getString("leader"));
      JsonArray expFields = expMarc.getJsonArray("fields");
      JsonArray actFields = actMarc.getJsonArray("fields");
      Assert.assertNotNull("["+i+"] expected 'fields' not null", expFields);
      Assert.assertNotNull("["+i+"] actual 'fields' not null", actFields);
      Assert.assertEquals("["+i+"] 'fields' size", expFields.size(), actFields.size());
      for (int j=0; j < expFields.size(); j++) {
        JsonObject expField = expFields.getJsonObject(j);
        JsonObject actField = actFields.getJsonObject(j);
        for (String expFieldName : expField.getMap().keySet()) {
          JsonObject expFieldValue = expField.getJsonObject(expFieldName);
          JsonObject actFieldValue = actField.getJsonObject(expFieldName);
          Assert.assertEquals("["+i+"] "+expFieldName + "/ind1",
            expFieldValue.getString("ind1"),
            actFieldValue.getString("ind1")
          );
          Assert.assertEquals("["+i+"] "+expFieldName + "/ind2",
            expFieldValue.getString("ind2"),
            actFieldValue.getString("ind2")
          );
          JsonArray expSubFields = expFieldValue.getJsonArray("subfields");
          JsonArray actSubFields = actFieldValue.getJsonArray("subfields");
          Assert.assertNotNull("["+i+"] "+expFieldName+" expected subfields not null", expSubFields);
          Assert.assertNotNull("["+i+"] "+expFieldName+" actual subfields not null", actSubFields);
          Assert.assertEquals("["+i+"] "+expFieldName+"/'subfields' size", expSubFields.size(), actSubFields.size());
          for (int k=0; k < expSubFields.size(); k++) {
            JsonObject expSubField = expSubFields.getJsonObject(k);
            JsonObject actSubField = actSubFields.getJsonObject(k);
            for (String expSubFieldName : expSubField.getMap().keySet()) {
              String expSubFieldValue = expSubField.getString(expSubFieldName);
              String actSubFieldValue = actSubField.getString(expSubFieldName);
              if (!"DO_NOT_ASSERT".equals(expSubFieldValue)) {
                Assert.assertEquals("["+i+"] "+expFieldName + "/" + expSubFieldName,
                  expSubFieldValue,
                  actSubFieldValue);
              }
            }
          }
        }
      }
    }
  }

  /**
   * Check that each records in each cluster contains exactly set of localIds.
   *
   * @param s        cluster response
   * @param localIds expected localId values for each cluster
   */
  static void verifyClusterResponse(String s, List<List<String>> localIds) {
    List<Set<String>> foundIds = new ArrayList<>();
    JsonObject clusterResponse = new JsonObject(s);
    JsonArray items = clusterResponse.getJsonArray("items");
    for (int i = 0; i < items.size(); i++) {
      JsonArray records = items.getJsonObject(i).getJsonArray("records");
      Set<String> ids = new HashSet<>();
      for (int j = 0; j < records.size(); j++) {
        String localId = records.getJsonObject(j).getString("localId");
        Assert.assertTrue(ids.add(localId)); // repeated localId values would be wrong.
      }
      foundIds.add(ids);
    }
    for (List<String> idList : localIds) {
      String candidate = idList.get(0); // just use first on to find the cluster
      for (Set<String> foundId : foundIds) {
        if (foundId.contains(candidate)) {
          for (String id : idList) {
            Assert.assertTrue(s, foundId.remove(id));
          }
          Assert.assertTrue(s, foundId.isEmpty());
          break;
        }
      }
    }
    for (Set<String> foundId : foundIds) {
      Assert.assertTrue(s, foundId.isEmpty());
    }
  }

  static void ingestRecords(JsonArray records, String sourceId) {
    ingestRecords(records, sourceId, 1);
  }

  static void ingestRecords(JsonArray records, String sourceId, int sourceVersion) {
    JsonObject request = new JsonObject()
        .put("sourceId", sourceId)
        .put("sourceVersion", sourceVersion)
        .put("records", records);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(request.encode())
        .put("/reservoir/records")
        .then().statusCode(200);
  }

  @Test
  public void testMatchKeysIngest() {
    JsonObject matchKey = createIsbnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("2").add("3")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "matchValue=3")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .body("items[0].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S102")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "localId=S101")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .body("items[0].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101")));

    JsonObject obj = new JsonObject(s);
    String globalId = obj.getJsonArray("items").getJsonObject(0)
        .getJsonArray("records").getJsonObject(0).getString("globalId");
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "globalId=" + globalId)
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .body("items[0].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101")));

    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);
  }

  @Test
  public void testTouchClusters() {
    createIsbnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("2").add("3")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    ingestRecords(records1, SOURCE_ID_2);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_2)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .queryParam("query", "matchkeyId=isbn AND sourceId=wrong")
        .post("/reservoir/clusters/touch")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("count", is(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/clusters/touch")
        .then()
        .statusCode(400)
        .body(is("query too broad, must at least contain 'matchkeyId' and 'sourceId'"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .queryParam("query", "sourceId=not-enough")
        .post("/reservoir/clusters/touch")
        .then()
        .statusCode(400)
        .body(is("query too broad, must at least contain 'matchkeyId' and 'sourceId'"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .queryParam("query", "matchkeyId=not-enough")
        .post("/reservoir/clusters/touch")
        .then()
        .statusCode(400)
        .body(is("query too broad, must at least contain 'matchkeyId' and 'sourceId'"));

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(2))
        .body("items[1].records", hasSize(2))
        .extract().body().asString();

    String datestamp1 = new JsonObject(s).getJsonArray("items").getJsonObject(0).getString("datestamp");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .queryParam("query", "matchkeyId=isbn AND sourceId=" + SOURCE_ID_1)
        .post("/reservoir/clusters/touch")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("count", is(2));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(2))
        .body("items[1].records", hasSize(2))
        .extract().body().asString();

    String datestamp2 = new JsonObject(s).getJsonArray("items").getJsonObject(0).getString("datestamp");

    assertThat(datestamp2, greaterThan(datestamp1));
  }

  @Test
  public void testMatchKeyIdMissing() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/clusters")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("does not contain the required query parameter matchkeyid"));
  }

  @Test
  public void testMatchKeyIdNotFound() {
    String id = UUID.randomUUID().toString();
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("matchkeyid", id)
        .header("Content-Type", "application/json")
        .get("/reservoir/clusters")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(is("MatchKey " + id + " not found"));
  }

  JsonObject createIssnMatchKey() {
    return createIssnMatchKey("ingest");
  }

  JsonObject createIssnMatchKey(String update) {
    JsonObject issnMatcher = new JsonObject()
        .put("id", "issn-matcher")
        .put("type", "jsonpath")
        .put("script", "$.inventory.issn[*]");

    JsonObject issnMatcherOut = new JsonObject()
        .put("id", "issn-matcher")
        .put("type", "jsonpath")
        .put("url", null)
        .put("function", null)
        .put("script", "$.inventory.issn[*]");

    //post module first
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(issnMatcher.encode())
        .post("/reservoir/config/modules")
        .then()
        //.statusCode(201)
        //.contentType("application/json")
        .body(Matchers.is(issnMatcherOut.encode()));

    JsonObject matchKey = new JsonObject()
        .put("id", "issn")
        .put("matcher", "issn-matcher")
        .put("update", update);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(matchKey.encode()));

    return matchKey;
  }

  void deleteIssnMatchKey() {
    RestAssured.given()
      .header(XOkapiHeaders.TENANT, TENANT_1)
      .delete("/reservoir/config/modules/issn-matcher")
      .then().statusCode(anyOf(is(204), is(404)));

    RestAssured.given()
      .header(XOkapiHeaders.TENANT, TENANT_1)
      .delete("/reservoir/config/matchkeys/issn")
      .then().statusCode(anyOf(is(204), is(404)));
  }

  JsonObject createIsbnMatchKey() {
    return createIsbnMatchKey(null);
  }

  JsonObject createIsbnMatchKey(String updateValue) {
    JsonObject matchKey = new JsonObject()
        .put("id", "isbn")
        .put("method", "jsonpath")
        .put("params", new JsonObject().put("expr", "$.inventory.isbn[*]"));

    if (updateValue != null) {
      matchKey.put("update", updateValue);
    }
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .post("/reservoir/config/matchkeys")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(matchKey.encode()));
    return matchKey;
  }

  @Test
  public void testClustersSameKey() {
    createIssnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("issn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("issn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("issn", new JsonArray().add("1")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "issn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S102", "S103")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);
  }

  @Test
  public void testClustersLargeKey() {
    createIssnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("issn", new JsonArray().add("1".repeat(3600))))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "issn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

  }

  @Test
  public void testClustersMove() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/clusters")
        .then().statusCode(400);

    createIsbnMatchKey();
    createIssnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2").add("3"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        );
    log.info("phase 1: insert two separate isbn recs, but one with issn");
    ingestRecords(records1, SOURCE_ID_1);

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "issn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .body("items[0].records", hasSize(2))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S102")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    String clusterId = new JsonObject(s).getJsonArray("items").getJsonObject(0).getString("clusterId");
    String datestamp = new JsonObject(s).getJsonArray("items").getJsonObject(0).getString("datestamp");
    Assert.assertNotNull(datestamp);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "clusterId=" + clusterId)
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .body("items[0].records", hasSize(1))
        .body("items[0].clusterId", is(clusterId))
        .body("items[0].datestamp", is(datestamp));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/clusters/" + clusterId)
        .then().statusCode(200)
        .contentType("application/json")
        .body("records", hasSize(1))
        .body("clusterId", is(clusterId));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/clusters/" + UUID.randomUUID())
        .then().statusCode(404);

    log.info("phase 2: S101 from 1 to 4");
    records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("4")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "issn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    log.info("phase 3: S101 from 4 to 3");
    records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("3")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S102")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/isbn")
        .then().statusCode(204);

  }

  @Test
  public void testDeleteGlobalRecords() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + UUID.randomUUID())
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/records")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(is("Must specify query for delete records"));

    JsonObject matchKey = createIsbnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("2")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("resultInfo.totalRecords", is(2));

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "localId=S102")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("resultInfo.totalRecords", is(1));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceVersion=1")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("resultInfo.totalRecords", is(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);
  }

  @Test
  public void testEmptyMatchKeys() {
    JsonObject matchKey = createIsbnMatchKey();

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray()))
            )
        )
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray()))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .body("resultInfo.totalRecords", is(3));

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(3))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102"), List.of("S103")));

    records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S103"), List.of("S102")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);
  }

  @Test
  public void testMatchKeysInitIssn() {
    JsonObject matchKey = createIssnMatchKey("manual");

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                .put("inventory", new JsonObject().put("issn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("issn", new JsonArray().add("2")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    // populate first time
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id") + "/initialize")
        .then().statusCode(200)
        .contentType("application/json")
        .body("totalRecords", is(2))
    ;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", matchKey.getString("id"))
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
  }

  @Test
  public void testMatchKeysManual() {
    JsonObject matchKey = createIsbnMatchKey("manual");

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0102   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("2")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    // populate first time
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id") + "/initialize")
        .then().statusCode(200)
        .contentType("application/json")
        .body("totalRecords", is(2))
    ;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2));

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .body("items[0].records", hasSize(1))
        .body("items[1].records", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101"), List.of("S102")));

    String sourceId2 = "SOURCE-2";
    JsonArray records2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S201")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0201   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("3")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S202")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0202   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1").add("2").add("3").add("4")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S203")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0203   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("5").add("6")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S204")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0204   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("5")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S205")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  0204   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("4")))
            )
        );
    ingestRecords(records2, sourceId2);

    // populate again with both sources
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id") + "/initialize")
        .then().statusCode(200)
        .contentType("application/json")
        .body("totalRecords", is(7))
    ;

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(2))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S102", "S201", "S202", "S205"), List.of("S203", "S204")));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("matchkeyid", "isbn")
        .param("query", "sourceId=" + SOURCE_ID_1 + " and sourceVersion = 1")
        .get("/reservoir/clusters")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1))
        .extract().body().asString();
    verifyClusterResponse(s, List.of(List.of("S101", "S102", "S201", "S202", "S205")));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "localId=S101 and sourceId=" + SOURCE_ID_1)
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "localId==notfound")
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/" + matchKey.getString("id"))
        .then().statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(matchKey.encode())
        .put("/reservoir/config/matchkeys/" + matchKey.getString("id") + "/initialize")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(is("MatchKey isbn not found"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);
  }

  @Test
  public void testOaiDiagnostics() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<error code=\"badVerb\">missing verb</error>"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "noop")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<error code=\"badVerb\">noop</error>"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "GetRecord")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("error code=\"badArgument\">missing identifier</error>"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "badmetadataprefix")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<error code=\"cannotDisseminateFormat\">only metadataPrefix &quot;marcxml&quot; supported</error>"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<error code=\"badArgument\">set &quot;null&quot; not found</error>"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<error code=\"badArgument\">set &quot;isbn&quot; not found</error>"));
  }

  @Test
  public void testIdentify() throws XMLStreamException, IOException, SAXException {
    vertx.getOrCreateContext().config().put("adminEmail", "admin@indexdata.com");
    List<String> identifiers = new LinkedList<>();
    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "Identify")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "Identify", identifiers, 0, null);
  }

  @Test
  public void testCodeModulesCRUD() {
    //GET empty list no count
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/modules")
        .then().statusCode(200)
        .contentType("application/json")
        .body("modules", is(empty()))
        .body("resultInfo.totalRecords", is(nullValue()));

    //GET empty list with count
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("count", "exact")
        .get("/reservoir/config/modules")
        .then().statusCode(200)
        .contentType("application/json")
        .body("modules", is(empty()))
        .body("resultInfo.totalRecords", is(0));

    //POST item with bad url and nothing should be created
    CodeModuleEntity badModule = new CodeModuleEntity("empty", "no-type", "url", "transform", "no script");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(badModule.asJson().encode())
        .post("/reservoir/config/modules")
        .then().statusCode(400);

    CodeModuleEntity module = new CodeModuleEntity("empty", "javascript",
            "http://localhost:" + CODE_MODULES_PORT + "/lib/empty.mjs",
            "transform",
            "");

    //GET not found item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/modules/" + module.getId())
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is("Module " + module.getId() + " not found"));

    //reload - not found item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .put("/reservoir/config/modules/" + module.getId() + "/reload")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is("Module " + module.getId() + " not found"));

    //POST item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module.asJson().encode())
        .post("/reservoir/config/modules")
        .then()
        .statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(module.asJson().encode()));

    //POST same item again
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module.asJson().encode())
        .post("/reservoir/config/modules")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(containsString("duplicate key value violates unique constraint"));

    //GET posted item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/modules/" + module.getId())
        .then().statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(module.asJson().encode()));
    // reload existing module
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .put("/reservoir/config/modules/" + module.getId() + "/reload")
        .then().statusCode(204);

    //GET item and validate it
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/modules")
        .then().statusCode(200)
        .contentType("application/json")
        .body("modules", hasSize(1))
        .body("modules[0].id", is(module.getId()))
        .body("modules[0].url", is(module.getUrl()))
        .body("modules[0].function", is(module.getFunction()));

    //GET search item and validate
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/modules?query=function=" + module.getFunction())
        .then().statusCode(200)
        .contentType("application/json")
        .body("modules", hasSize(1))
        .body("modules[0].id", is(module.getId()))
        .body("modules[0].url", is(module.getUrl()))
        .body("modules[0].function", is(module.getFunction()));

    //DELETE item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/modules/" + module.getId())
        .then().statusCode(204);

    //DELETE item again
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/modules/" + module.getId())
        .then().statusCode(404);

    //GET deleted item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .get("/reservoir/config/modules/" + module.getId())
        .then().statusCode(404);

    //PUT item to not existing
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module.asJson().encode())
        .put("/reservoir/config/modules/" + module.getId())
        .then()
        .statusCode(404);

    //POST item again
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module.asJson().encode())
        .post("/reservoir/config/modules")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(module.asJson().encode()));

    //PUT item to existing
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(module.asJson().encode())
        .put("/reservoir/config/modules/" + module.getId())
        .then()
        .statusCode(204);

    //DELETE item
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .delete("/reservoir/config/modules/" + module.getId())
        .then().statusCode(204);

  }

  @Test
  public void testOaiConfigRU() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/oai")
        .then()
        .statusCode(404);

    JsonObject oaiConfig = new JsonObject()
        .put("baseURL", "localhost")
        .put("adminEmail", "admin@localhost")
        .put("transformer", "transform-marc")
        .put("repositoryName", "Reservoir OAI server");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfig.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/oai")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body(Matchers.is(oaiConfig.encode()));

    oaiConfig.put("badProperty", "not allower");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfig.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(400);
  }

  @Test
  @java.lang.SuppressWarnings("squid:S5961") // Test methods should not contain too many assertions
  public void testSru() throws XMLStreamException, IOException, SAXException {
    createIsbnMatchKey();
    createIssnMatchKey();
    JsonArray ingest1a = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  2200337   450 ")
                    .put("fields", new JsonArray()
                        .add(new JsonObject()
                            .put("999", new JsonObject()
                                .put("ind1", " ")
                                .put("ind2", " ")
                                .put("subfields", new JsonArray()
                                    .add(new JsonObject()
                                        .put("a", "S101a")
                                    )
                                    .add(new JsonObject()
                                        .put("b", "S101b")
                                    )
                                )
                            )
                        )
                    )
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        );
    JsonArray ingest1b = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  2200337   450 ")
                    .put("fields", new JsonArray()
                        .add(new JsonObject()
                            .put("999", new JsonObject()
                                .put("ind1", " ")
                                .put("ind2", " ")
                                .put("subfields", new JsonArray()
                                    .add(new JsonObject()
                                        .put("a", "S102a")
                                    )
                                    .add(new JsonObject()
                                        .put("b", "S102b")
                                    )
                                )
                            )
                        )
                    )
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2").add("3"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        );
    //post records individually, otherwise the order of clusters and records in clusters is non-deterministic
    ingestRecords(ingest1a, SOURCE_ID_1, 2);
    ingestRecords(ingest1b, SOURCE_ID_1, 2);

    // use OAI to get record identifiers out
    String s = RestAssured.given()
      .header(XOkapiHeaders.TENANT, TENANT_1)
      .param("set", "isbn")
      .param("verb", "ListRecords")
      .param("metadataPrefix", "marcxml")
      .get("/reservoir/oai")
      .then().statusCode(200)
      .contentType("text/xml")
      .extract().body().asString();

    List<String> identifiers = new LinkedList<>();
    verifyOaiResponse(s, "ListRecords", identifiers, 2, null);

    // SRU tests

    // explain
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    SruVerify sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("explainResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(0));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("version", "1.1")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("explainResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(1));
    assertThat(sruVerify.errors.get(0), is("Unsupported version"));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("version", "2.0")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("explainResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(0));

    // CQL syntax error
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "xd=")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("searchRetrieveResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(1));
    assertThat(sruVerify.errors.get(0), is("Query syntax error"));

    // unsupported SRU version
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "xd=1")
        .param("version", "1.2")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("searchRetrieveResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(1));
    assertThat(sruVerify.errors.get(0), is("Unsupported version"));

    // unsupported record schema
        s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "xd=1")
        .param("version", "2.0")
        .param("recordSchema", "mods")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("searchRetrieveResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(1));
    assertThat(sruVerify.errors.get(0), is("Unknown schema for retrieval"));

    // search for one record
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "rec.id=" + identifiers.get(0).substring(4))
        .param("startRecord", "1")
        .param("maximumRecord", "1")
        .param("recordSchema", "marcxml")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    assertThat(s, containsString("numberOfRecords>1<"));
    assertThat(s, containsString("<subfield code=\"s\">SOURCE-1</subfield>"));


    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("searchRetrieveResponse"));
    assertThat(sruVerify.numberOfRecords, is(1));
    assertThat(sruVerify.identifiers, hasSize(1));
    assertThat(sruVerify.errors, hasSize(0));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "cql.AllRecords=true")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.numberOfRecords, is(3));
    assertThat(sruVerify.identifiers, hasSize(3));
    assertThat(sruVerify.errors, hasSize(0));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "cql.AllRecords=true")
        .param("startRecord", "3")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.numberOfRecords, is(3));
    assertThat(sruVerify.identifiers, hasSize(1));
    assertThat(sruVerify.errors, hasSize(0));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "cql.AllRecords=true")
        .param("maximumRecords", "2")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.numberOfRecords, is(3));
    assertThat(sruVerify.identifiers, hasSize(2));
    assertThat(sruVerify.errors, hasSize(0));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("query", "cql.AllRecords=true")
        .param("maximumRecords", "x")
        .get("/reservoir/sru")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    sruVerify = new SruVerify(s);
    assertThat(sruVerify.response, is("searchRetrieveResponse"));
    assertThat(sruVerify.numberOfRecords, is(0));
    assertThat(sruVerify.identifiers, hasSize(0));
    assertThat(sruVerify.errors, hasSize(1));
    assertThat(sruVerify.errors.get(0), is("Unsupported parameter value"));
  }

  @Test
  @java.lang.SuppressWarnings("squid:S5961") // Test methods should not contain too many assertions
  public void testOaiSimple() throws XMLStreamException, IOException, SAXException {
    createIsbnMatchKey();

    List<String> identifiers = new LinkedList<>();
    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 0, null);

    createIssnMatchKey();

    JsonArray ingest1a = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  2200337   450 ")
                    .put("fields", new JsonArray()
                        .add(new JsonObject()
                            .put("999", new JsonObject()
                                .put("ind1", " ")
                                .put("ind2", " ")
                                .put("subfields", new JsonArray()
                                    .add(new JsonObject()
                                        .put("a", "S101a")
                                    )
                                    .add(new JsonObject()
                                        .put("b", "S101b")
                                    )
                                )
                            )
                        )
                    )
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        );
    JsonArray ingest1b = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  2200337   450 ")
                    .put("fields", new JsonArray()
                        .add(new JsonObject()
                            .put("999", new JsonObject()
                                .put("ind1", " ")
                                .put("ind2", " ")
                                .put("subfields", new JsonArray()
                                    .add(new JsonObject()
                                        .put("a", "S102a")
                                    )
                                    .add(new JsonObject()
                                        .put("b", "S102b")
                                    )
                                )
                            )
                        )
                    )
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2").add("3"))
                    .put("issn", new JsonArray().add("01"))
                )
            )
        );
    //post records individually, otherwise the order of clusters and records in clusters is non-deterministic
    ingestRecords(ingest1a, SOURCE_ID_1, 2);
    ingestRecords(ingest1b, SOURCE_ID_1, 2);

    JsonArray expectedIssn = new JsonArray()
        .add(new JsonObject()
            .put("leader", "00914naa  2200337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "01"))
                            .add(new JsonObject().put("l", "S101"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "2"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "2"))
                        )
                    )
                )
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", " ")
                        .put("ind2", " ")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject()
                                .put("a", "S101a")
                            )
                            .add(new JsonObject()
                                .put("b", "S101b")
                            )
                            .add(new JsonObject()
                                .put("a", "S102a")
                            )
                            .add(new JsonObject()
                                .put("b", "S102b")
                            )
                        )
                    )
                )
            )
        );

    JsonArray expectedIsbn = new JsonArray()
        .add(new JsonObject()
            .put("leader", "00914naa  2200337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "1"))
                            .add(new JsonObject().put("l", "S101"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "2"))
                        )
                    )
                )
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", " ")
                        .put("ind2", " ")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject()
                                .put("a", "S101a")
                            )
                            .add(new JsonObject()
                                .put("b", "S101b")
                            )
                        )
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("leader", "00914naa  2200337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "2"))
                            .add(new JsonObject().put("m", "3"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "2"))
                        )
                    )
                )
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", " ")
                        .put("ind2", " ")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject()
                                .put("a", "S102a")
                            )
                            .add(new JsonObject()
                                .put("b", "S102b")
                            )
                        )
                    )
                )
            )
        );

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 1, expectedIssn);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListIdentifiers")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListIdentifiers", identifiers, 1, expectedIssn);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "GetRecord")
        .param("metadataPrefix", "marcxml")
        .param("identifier", identifiers.get(0))
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "GetRecord", identifiers, 1, expectedIssn);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "GetRecord")
        .param("metadataPrefix", "marcxml")
        .param("identifier", UUID.randomUUID().toString())
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("idDoesNotExist"));

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 2, expectedIsbn);

    //configure transformers
    for (String m : List.of("marc-transformer", "empty", "throw")) {
      CodeModuleEntity module = new CodeModuleEntity(
          m, "javascript", "http://localhost:" + CODE_MODULES_PORT + "/lib/" + m + ".mjs", null,  "");

      //POST module configuration
      RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .header("Content-Type", "application/json")
          .body(module.asJson(true).encode())
          .post("/reservoir/config/modules")
          .then()
          .statusCode(201)
          .contentType("application/json")
          .body(Matchers.is(module.asJson().encode()));
    }

    //PUT oai configuration
    JsonObject oaiConfig = new JsonObject()
        .put("transformer", "marc-transformer::transform");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfig.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    JsonArray expectedIssn2 = new JsonArray()
        .add(new JsonObject()
            .put("leader", "new leader")
            .put("fields", new JsonArray()
                  .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", " ")
                        .put("ind2", " ")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject()
                              .put("a", "S101a")
                            )
                            .add(new JsonObject()
                              .put("b", "S101b")
                            )
                        )
                    )
                  )
                  .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                        .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                        .add(new JsonObject().put("l", "S101"))
                        .add(new JsonObject().put("s", "SOURCE-1"))
                        )
                    )
                  )
                  .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", " ")
                        .put("ind2", " ")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject()
                              .put("a", "S102a")
                            )
                            .add(new JsonObject()
                              .put("b", "S102b")
                            )
                        )
                    )
                  )
                  .add(new JsonObject()
                    .put("999", new JsonObject()
                      .put("ind1", "1")
                      .put("ind2", "0")
                      .put("subfields", new JsonArray()
                        .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                        .add(new JsonObject().put("l", "S102"))
                        .add(new JsonObject().put("s", "SOURCE-1"))
                      )
                    )
                  )
            )
        );


    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 1, expectedIssn2);

    oaiConfig = new JsonObject()
        .put("transformer", "empty::transform");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfig.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    verifyOaiResponse(s, "ListRecords", identifiers, 1, null);

    oaiConfig = new JsonObject()
        .put("transformer", "throw::transform");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfig.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("<!-- Failed to produce record"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "GetRecord")
        .param("metadataPrefix", "marcxml")
        .param("identifier", identifiers.get(0))
        .get("/reservoir/oai")
        .then().statusCode(500)
        .contentType("text/plain")
        .body(containsString("Error"));

    // OAI config with unknown transformer
    JsonObject oaiConfigBadTransformer = new JsonObject()
        .put("transformer", "doesnotexist");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfigBadTransformer.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "issn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(500)
        .contentType("text/plain")
        .body(is("Transformer module 'doesnotexist' not found"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "GetRecord")
        .param("metadataPrefix", "marcxml")
        .param("identifier", identifiers.get(0))
        .get("/reservoir/oai")
        .then().statusCode(500)
        .contentType("text/plain")
        .body(is("Transformer module 'doesnotexist' not found"));

    //PUT disable the transformer
    JsonObject oaiConfigOff = new JsonObject();

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiConfigOff.encode())
        .put("/reservoir/config/oai")
        .then()
        .statusCode(204);

    JsonArray ingest2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1").add("2"))
                    .put("issn", new JsonArray().add("02"))
                    .put("holdingsRecords", new JsonArray().add(new JsonObject()
                            .put("permanentLocationDeref", "S103")
                        )
                    )
                )
            )
        );
    ingestRecords(ingest2, SOURCE_ID_1);


    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then()
        .statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 2, null);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListIdentifiers")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListIdentifiers", identifiers, 2, null);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/isbn")
        .then().statusCode(204);
  }

  @Test
  public void testOaiDatestamp() throws XMLStreamException, InterruptedException, IOException, SAXException {
    String time0 = Instant.now(Clock.systemUTC()).minusSeconds(1L).truncatedTo(ChronoUnit.SECONDS).toString();
    String time1 = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS).toString();

    createIsbnMatchKey();

    List<String> identifiers = new LinkedList<>();
    String s;

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("2")))
            )
        )
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("3")))
            )

        );


    ingestRecords(records1, SOURCE_ID_1);
    String time2 = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS).toString();
    TimeUnit.SECONDS.sleep(1);
    String time3 = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS).toString();

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", time1)
        .param("until", time2)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 3, null);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("until", time0)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 0, null);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", time3)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 0, null);

    ingestRecords(records1, SOURCE_ID_1);
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", time3)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 3, null);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", "xxxx")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .body(containsString("error code=\"badArgument\">bad from"));

    TimeUnit.SECONDS.sleep(1);
    String time4 = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS).toString();

    JsonArray records2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S103")
            .put("delete", true)
        );

    ingestRecords(records2, SOURCE_ID_1);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", time4)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 1, null);

    TimeUnit.SECONDS.sleep(1);
    String time5 = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS).toString();

    records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S104")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject().put("isbn", new JsonArray().add("1").add("2")))
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("from", time5)
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    verifyOaiResponse(s, "ListRecords", identifiers, 2, null);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/isbn")
        .then().statusCode(204);
  }

  @Test
  public void testOaiResumptionToken() throws XMLStreamException, IOException, SAXException {
    createIsbnMatchKey();

    for (int i = 0; i < 10; i++) {
      JsonArray records1 = new JsonArray()
          .add(new JsonObject()
              .put("localId", "S" + i)
              .put("payload", new JsonObject()
                  .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                  .put("inventory", new JsonObject().put("isbn", new JsonArray().add(Integer.toString(i))))
              )
          );
      ingestRecords(records1, SOURCE_ID_1);
    }
    List<String> identifiers = new LinkedList<>();

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("limit", "2")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    int iter;
    for (iter = 1; iter < 10; iter++) {
      String token = verifyOaiResponse(s, "ListRecords", identifiers, -1, null);
      if (token == null) {
        break;
      }
      ResumptionToken tokenClass = new ResumptionToken(token);
      log.info("token {}", tokenClass.toString());
      Assert.assertEquals("isbn", tokenClass.getSet());
      s = RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .param("verb", "ListRecords")
          .param("limit", "2")
          .param("resumptionToken", token)
          .get("/reservoir/oai")
          .then().statusCode(200)
          .contentType("text/xml")
          .extract().body().asString();
    }
    Assert.assertEquals(5, iter);
    Assert.assertEquals(10, identifiers.size());
  }

  @Test
  public void testOaiResumptionToken2(TestContext context) {
    createIsbnMatchKey();

    for (int i = 0; i < 10; i++) {
      JsonArray records1 = new JsonArray()
          .add(new JsonObject()
              .put("localId", "S" + i)
              .put("payload", new JsonObject()
                  .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                  .put("inventory", new JsonObject().put("isbn", new JsonArray().add(Integer.toString(i))))
              )
          );
      ingestRecords(records1, SOURCE_ID_1);
    }
    List<String> identifiers = new LinkedList<>();

    Async async = context.async();
    Storage storage = new Storage(vertx, TENANT_1, HttpMethod.PUT);
    storage.getPool().preparedQuery("UPDATE " + storage.getClusterMetaTable()
            + " SET datestamp = $1")
        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC)))
        .onComplete(context.asyncAssertSuccess(h -> {
          async.complete();
        }));

    async.await();

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("limit", "2")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    int iter;
    ResumptionToken firstToken = null;
    for (iter = 1; iter < 10; iter++) {
      String token = verifyOaiResponseRuntime(s, "ListRecords", identifiers, -1, null);
      if (token == null) {
        break;
      }
      ResumptionToken resumptionToken = new ResumptionToken(token);
      if (iter == 1) {
        firstToken = resumptionToken;
      }
      log.info("token {}", resumptionToken.toString());
      Assert.assertEquals("isbn", resumptionToken.getSet());
      s = RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .param("verb", "ListRecords")
          .param("limit", "2")
          .param("resumptionToken", token)
          .get("/reservoir/oai")
          .then().statusCode(200)
          .contentType("text/xml")
          .extract().body().asString();
    }
    Assert.assertEquals(5, iter);
    Assert.assertEquals(10, identifiers.size());

    identifiers.clear();
    firstToken.setId(null); // make it a legacy token without id
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("verb", "ListRecords")
        .param("limit", "3")
        .param("resumptionToken", firstToken.encode())
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();

    verifyOaiResponseRuntime(s, "ListRecords", identifiers, -1, null);
    Assert.assertEquals(3, identifiers.size());
  }

  @Test
  public void testOaiSourceVersions() {
    List<String> identifiers = new LinkedList<>();
    String s;

    createIsbnMatchKey();

    int v1 = 55;

    JsonArray ingest2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S100")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  " + v1 + "00337   450 ")
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                )
            )
        );
    ingestRecords(ingest2, SOURCE_ID_1, v1);

    ingest2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  " + v1 + "00337   450 ")
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2"))
                )
            )
        );
    ingestRecords(ingest2, SOURCE_ID_1, v1);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    log.info("OAI 1 = {}", s);

    JsonArray expectedIsbn = new JsonArray()
        .add(new JsonObject()
            .put("leader", "00914naa  5500337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "1"))
                            .add(new JsonObject().put("l", "S100"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "55"))
                        )
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("leader", "00914naa  5500337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "2"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "55"))
                        )
                    )
                )
            )
        )
        ;

    verifyOaiResponseRuntime(s, "ListRecords", identifiers, 2, expectedIsbn);

    int v2 = 56;

    JsonArray ingest1b = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S100")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  " + v2 + "00337   450 ")
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                )
            )
        );
    ingestRecords(ingest1b, SOURCE_ID_1, v2);

    ingest1b = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject()
                    .put("leader", "00914naa  " + v2 + "00337   450 ")
                )
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("3"))
                )
            )
        );
    ingestRecords(ingest1b, SOURCE_ID_1, v2);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    log.info("OAI 2 = {}", s);

    expectedIsbn = new JsonArray()
        .add(new JsonObject()
            .put("leader", "00914naa  5500337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "2"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "55"))
                        )
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("leader", "00914naa  5600337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "1"))
                            .add(new JsonObject().put("l", "S100"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "56"))
                        )
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("leader", "00914naa  5600337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "3"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "56"))
                        )
                    )
                )
            )
        );

    verifyOaiResponseRuntime(s, "ListRecords", identifiers, 3, expectedIsbn);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId=" + SOURCE_ID_1 + " AND sourceVersion = " + v1)
        .delete("/reservoir/records")
        .then().statusCode(204);

    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .param("set", "isbn")
        .param("verb", "ListRecords")
        .param("metadataPrefix", "marcxml")
        .get("/reservoir/oai")
        .then().statusCode(200)
        .contentType("text/xml")
        .extract().body().asString();
    log.info("OAI 3 = {}", s);
    expectedIsbn = new JsonArray()
        .add(new JsonObject()
            .put("leader", "00914naa  5600337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "1"))
                            .add(new JsonObject().put("l", "S100"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "56"))
                        )
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("leader", "00914naa  5600337   450 ")
            .put("fields", new JsonArray()
                .add(new JsonObject()
                    .put("999", new JsonObject()
                        .put("ind1", "1")
                        .put("ind2", "0")
                        .put("subfields", new JsonArray()
                            .add(new JsonObject().put("i", "DO_NOT_ASSERT"))
                            .add(new JsonObject().put("m", "3"))
                            .add(new JsonObject().put("l", "S102"))
                            .add(new JsonObject().put("s", "SOURCE-1"))
                            .add(new JsonObject().put("v", "56"))
                        )
                    )
                )
            )
        );
    verifyOaiResponseRuntime(s, "ListRecords", identifiers, 3, expectedIsbn);
  }

  @Test
  public void upgradeDb(TestContext context) throws IOException {
    String nextModule = MODULE_PREFIX + "-1.0.1";
    String md = Files.readString(Path.of("../descriptors/ModuleDescriptor-template.json"))
        .replace("${artifactId}", MODULE_PREFIX)
        .replace("${version}", "1.0.1");

    Future<Void> f = Future.succeededFuture();

    f = f.compose(t ->
        webClient.postAbs(OKAPI_URL + "/_/proxy/modules")
            .sendJsonObject(new JsonObject(md))
            .expecting(HttpResponseExpectation.SC_CREATED)
            .mapEmpty());

    f = f.compose(t ->
        webClient.postAbs(OKAPI_URL + "/_/discovery/modules")
            .sendJsonObject(new JsonObject()
                .put("instId", nextModule)
                .put("srvcId", nextModule)
                .put("url", MODULE_URL))
            .expecting(HttpResponseExpectation.SC_CREATED)
            .mapEmpty());

    f = f.compose(t -> webClient.postAbs(OKAPI_URL + "/_/proxy/tenants/" + TENANT_1 + "/install")
        .sendJson(new JsonArray().add(new JsonObject()
            .put("from", MODULE_ID)
            .put("id", nextModule)
            .put("action", "enable")))
        .expecting(HttpResponseExpectation.SC_OK)
        .mapEmpty());

    f.onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testMatchKeyStats() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys/isbn/stats")
        .then().statusCode(404);

    createIsbnMatchKey();

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys/isbn/stats")
        .then().statusCode(200)
        .contentType("application/json")
        .body("recordsTotal", is(0))
        .body("clustersTotal", is(0))
    ;

    JsonArray records1 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("1"))
                )
            )
        )
        .add(new JsonObject()
            .put("localId", "S102")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2").add("3"))
                )
            )
        )
        .add(new JsonObject()
            .put("localId", "S103")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("2").add("4"))
                )
            )
        )
        .add(new JsonObject()
            .put("localId", "S104")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("5")
                    )
                )
            )
        )
        .add(new JsonObject()
            .put("localId", "S105")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject())
            )
        )
        .add(new JsonObject()
            .put("localId", "S106")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject())
            )
        );
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys/isbn/stats")
        .then().statusCode(200)
        .contentType("application/json")
        .body("recordsTotal", is(6))
        .body("clustersTotal", is(5))
        .body("matchValuesPerCluster.0", is(2))
        .body("matchValuesPerCluster.1", is(2))
        .body("matchValuesPerCluster.3", is(1))
        .body("recordsPerCluster.1", is(4))
        .body("recordsPerCluster.2", is(1))
        .body("recordsPerClusterSample.1", hasSize(3))
        .body("recordsPerClusterSample.2", hasSize(1))
    ;

    String sourceId2 = "SOURCE-2";
    JsonArray records2 = new JsonArray()
        .add(new JsonObject()
            .put("localId", "S101")
            .put("payload", new JsonObject()
                .put("marc", new JsonObject().put("leader", "00914naa  2200337   450 "))
                .put("inventory", new JsonObject()
                    .put("isbn", new JsonArray().add("6"))
                )
            )
        );
    ingestRecords(records2, sourceId2);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys/isbn/stats")
        .then().statusCode(200)
        .contentType("application/json")
        .body("recordsTotal", is(7))
        .body("clustersTotal", is(6))
        .body("matchValuesPerCluster.0", is(2))
        .body("matchValuesPerCluster.1", is(3))
        .body("matchValuesPerCluster.3", is(1))
        .body("recordsPerCluster.1", is(5))
        .body("recordsPerCluster.2", is(1))
        .body("recordsPerClusterSample.1", hasSize(3))
        .body("recordsPerClusterSample.2", hasSize(1))
    ;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "sourceId = " + sourceId2)
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/config/matchkeys/isbn/stats")
        .then().statusCode(200)
        .contentType("application/json")
        .body("recordsTotal", is(6))
        .body("clustersTotal", is(5))
        .body("matchValuesPerCluster.0", is(2))
        .body("matchValuesPerCluster.1", is(2))
        .body("matchValuesPerCluster.3", is(1))
        .body("recordsPerCluster.1", is(4))
        .body("recordsPerCluster.2", is(1))
    ;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .param("query", "cql.allRecords=true")
        .delete("/reservoir/records")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/config/matchkeys/isbn")
        .then().statusCode(204);
  }

  @Test
  public void oaiPmhClientCRUD() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients")
        .then().statusCode(200)
        .contentType("application/json")
        .body("resultInfo.totalRecords", is(0));

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", OKAPI_URL + " /reservoir/oai")
        .put("set", "set-1")
        .put("sourceId", "source-1")
        .put("sourceVersion", 17)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .put("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(400);

    String s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(200)
        .contentType("application/json")
        .extract().body().asString();
    Assert.assertEquals(new JsonObject(s), oaiPmhClient);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].id", is(PMH_CLIENT_ID))
        .body("items[0].url", is(oaiPmhClient.getString("url")))
        .body("resultInfo.totalRecords", is(1));

    oaiPmhClient.put("url", "http://foo.bar");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .put("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].id", is(PMH_CLIENT_ID))
        .body("items[0].sourceVersion", is(17))
        .body("items[0].url", is(oaiPmhClient.getString("url")))
        .body("resultInfo.totalRecords", is(1));

    oaiPmhClient.put("sourceId", "source-2");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .put("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(200)
        .contentType("application/json")
        .extract().body().asString();
    Assert.assertEquals(new JsonObject(s), oaiPmhClient);

    oaiPmhClient.put("set", "set-2");
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .put("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);
    oaiPmhClient.put("sourceVersion", 18);
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(200)
        .contentType("application/json")
        .extract().body().asString();
    Assert.assertEquals(new JsonObject(s), oaiPmhClient);

    oaiPmhClient.put("sourceVersion", 2);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .put("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);
    s = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(200)
        .contentType("application/json")
        .extract().body().asString();
    Assert.assertEquals(new JsonObject(s), oaiPmhClient);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));
  }

  boolean harvestCompleted(String tenant, String pmhClientId) {
    String response = RestAssured.given()
        .header(XOkapiHeaders.TENANT, tenant)
        .get("/reservoir/pmh-clients/" + pmhClientId + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .extract().body().asString();
    JsonObject res = new JsonObject(response);
    return "idle".equals(res.getJsonArray("items").getJsonObject(0).getString("status"));
  }

  @Test
  public void oaiPmhClientJobs() {
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/stop")
        .then().statusCode(404)
        .contentType("text/plain")
        .body(Matchers.is(PMH_CLIENT_ID));


    JsonObject oaiPmhClient = new JsonObject()
        .put("url", "http://localhost:" + MODULE_PORT + "/reservoir/oai")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("set", "set1")
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].lastTotalRecords", is(nullValue()))
        .body("items[0].totalRequests", is(0))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/stop")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(is("not running"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].lastTotalRecords", is(0))
        .body("items[0].lastActiveTimestamp", endsWith("Z"))
        .body("items[0].lastStartedTimestamp", endsWith("Z"))
        .body("items[0].totalRequests", is(1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/stop")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(is("not running"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .delete("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(204);
  }

  @Test
  public void moveFromDate() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    String nowShort = now.format(DateTimeFormatter.ISO_LOCAL_DATE);
    String nowLong = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    LocalDateTime dayAgo = now.minusDays(1L);
    String dayAgoShort = dayAgo.format(DateTimeFormatter.ISO_LOCAL_DATE);
    String dayAgoLong = dayAgo.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    String dayAgoSecondLater = dayAgo.plusSeconds(1L).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    LocalDateTime hourAgo = now.minusHours(1L);
    String hourAgoLong = hourAgo.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    String hourAgoSecondLater = hourAgo.plusSeconds(1L).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    JsonObject config = new JsonObject();
    //date granularity, yesterday from moves 1 day
    config.put("from", dayAgoShort);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals(nowShort, config.getString("from"));
    //time granularity, yesterday from moves 1 sec
    config = new JsonObject();
    config.put("from", dayAgoLong);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals(dayAgoSecondLater, config.getString("from"));
    //date granularity, today from doesn't move
    config.put("from", nowShort);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals(nowShort, config.getString("from"));
    //time granularity, now from doesn't move
    config.put("from", nowLong);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals(nowLong, config.getString("from"));
    //time granularity, hour ago moves 1 sec
    config.put("from", hourAgoLong);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals(hourAgoSecondLater, config.getString("from"));

    config = new JsonObject();
    String from = "2022-07-13T11:42:59Z";
    config.put("from", from);
    OaiPmhClientService.moveFromDate(config);
    Assert.assertEquals("2022-07-13T11:43:00Z", config.getString("from"));
  }

  /**
   * Create records in TENANT1 and harvest them to TENANT2.
   */
  @Test
  public void oaiPmhClientFetch()  {
    createIsbnMatchKey();

    JsonArray records1 = new JsonArray();
    for (int i = 0; i < 10; i++) {
          records1.add(new JsonObject()
              .put("localId", "S" + i)
              .put("payload", new JsonObject()
                  .put("marc", new JsonObject().put("leader", "00914naa  0101   450 "))
                  .put("inventory", new JsonObject().put("isbn", new JsonArray().add(Integer.toString(i))))
              )
          );
    }
    ingestRecords(records1, SOURCE_ID_1);

    /* harvest records from tenant1 and store them in tenant2 */
    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MODULE_URL + "/reservoir/oai")
        .put("set", "isbn")
        .put("params", new JsonObject().put("limit", "4"))
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_2, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].lastTotalRecords", is(10))
        .body("items[0].lastRunningTime", startsWith("0 days 00 hrs 00 mins 0"))
        .body("items[0].lastRecsPerSec", greaterThanOrEqualTo(10))
        .body("items[0].totalDeleted", is(0))
        .body("items[0].totalInserted", is(10))
        .body("items[0].totalUpdated", is(0))
        .body("items[0].totalRecords", is(10))
        .body("items[0].totalRequests", is(3)) // 4 + 4 + 2 : 3 requests with limit 4
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].error", is(nullValue()))
        .body("items[0].config.resumptionToken", is(nullValue()))
        .body("items[0].config.from", hasLength(20))
        .body("items[0].config.until", is(nullValue()))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID)
        .then().statusCode(200)
        .contentType("application/json")
        .body("resumptionToken", is(nullValue()))
        .body("from", hasLength(20))
        .body("until", is(nullValue()))
        .body("sourceId", is(SOURCE_ID_1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("resultInfo.totalRecords", is(10));

    records1 = new JsonArray();
    for (int i = 0; i < 3; i++) {
      records1.add(new JsonObject()
              .put("localId", "S" + i)
              .put("delete", true)
          );
    }
    ingestRecords(records1, SOURCE_ID_1);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_2, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalDeleted", is(3))
        .body("items[0].totalInserted", is(10))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.resumptionToken", is(nullValue()))
        .body("items[0].config.from", hasLength(20))
        .body("items[0].config.until", is(nullValue()))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_2)
        .param("count", "exact")
        .get("/reservoir/records")
        .then().statusCode(200)
        .contentType("application/json")
        .body("resultInfo.totalRecords", is(7));
  }

  @Test
  public void oaiPmhClientNoServer() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", "http://localhost:" + UNUSED_PORT + "/reservoir/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(0))
        .body("items[0].error", containsString("localhost"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientConnectionClosed() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", "http://localhost:" + NET_PORT + "/mock/oai")
        .put("set", "isbn")
        .put("sourceId", SOURCE_ID_1)
        .put("waitRetries", 1)
        .put("numberRetries", 1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(0))
        .body("items[0].error", anyOf(is("Connection was closed"), is("Connection reset")))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientHttpStatus() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = "mock error";
    mockContentType = "text/plain";
    mockStatus = 400;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(1))
        .body("items[0].error", containsString("HTTP status 400: mock error"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientHttpBadXml() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = "<foo";
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(1))
        .body("items[0].error", containsString("Incomplete input"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientExceptionInIngest() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords><record>
    <metadata><record></record></metadata>
  </record>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(1))
        .body("items[0].error", is("localId required when parsing record null"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientHttpBadMetadata() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords><record>
    <metadata><foo/></metadata>
  </record>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(1))
        .body("items[0].error", is("Bad marcxml element: foo"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(3))
        .body("items[0].error", is(nullValue()))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientSameResumptionToken() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(2))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }


  @Test
  public void oaiPmhClientStop() {
    createIsbnMatchKey();

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/stop")
        .then().statusCode(404)
        .body(is(PMH_CLIENT_ID));

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/stop")
        .then().statusCode(400)
        .body(containsString("not running"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", greaterThanOrEqualTo(1))
        .body("items[0].lastTotalRecords", greaterThanOrEqualTo(0))
        .body("items[0].lastRunningTime", startsWith("0 days 00 hrs 00 mins 0"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientAllIllegal() {
    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", "_all");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(400)
        .contentType("text/plain")
        .body(is("Invalid value for OAI PMH client identifier: _all"));
  }

  @Test
  public void oaiPmhClientAll() {
    createIsbnMatchKey();

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/_all/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items", hasSize(0));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/_all/stop")
        .then().statusCode(204);

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
<?xml version="1.0"?>
<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
  <responseDate>2022-06-09T09:54:45Z</responseDate>
  <request verb="ListRecords" set="isbn" metadataPrefix="marc21">https://localhost/mock/oai</request>
  <ListRecords>
  <resumptionToken>MzM5OzE7Ozt2MS4w</resumptionToken></ListRecords>
  </OAI-PMH>
        """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/_all/stop")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/_all/start")
        .then().statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/_all/stop")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/_all/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", greaterThanOrEqualTo(1))
        .body("items[0].lastTotalRecords", greaterThanOrEqualTo(0))
        .body("items[0].lastRunningTime", startsWith("0 days 00 hrs 00 mins 0"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

  @Test
  public void oaiPmhClientHttpOaiError() {
    createIsbnMatchKey();

    JsonObject oaiPmhClient = new JsonObject()
        .put("url", MOCK_URL + "/mock/oai")
        .put("set", "isbn")
        .put("headers", new JsonObject().put(XOkapiHeaders.TENANT, TENANT_1))
        .put("sourceId", SOURCE_ID_1)
        .put("id", PMH_CLIENT_ID);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header("Content-Type", "application/json")
        .body(oaiPmhClient.encode())
        .post("/reservoir/pmh-clients")
        .then().statusCode(201)
        .contentType("application/json")
        .body(Matchers.is(oaiPmhClient.encode()));

    mockBody = """
      <?xml version="1.0" encoding="UTF-8"?>
      <OAI-PMH xsi:schemaLocation='http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd'
        xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'
        xmlns='http://www.openarchives.org/OAI/2.0/'>
        <responseDate>2022-10-17T18:52:44Z</responseDate>
        <request metadataPrefix='marc21' set='reshare' verb='ListRecords'>https://arcadiau.bywatersolutions.com/opac/oai.pl</request>
        <error code='cannotDisseminateFormat'>Dissemination as &apos;marc21&apos; is not supported</error>
      </OAI-PMH>
      """;
    mockContentType = "text/xml";
    mockStatus = 200;

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .post("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/start")
        .then().statusCode(204);

    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> harvestCompleted(TENANT_1, PMH_CLIENT_ID));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + PMH_CLIENT_ID + "/status")
        .then().statusCode(200)
        .contentType("application/json")
        .body("items[0].status", is("idle"))
        .body("items[0].totalRecords", is(0))
        .body("items[0].totalRequests", is(1))
        .body("items[0].error", is("cannotDisseminateFormat: Dissemination as 'marc21' is not supported"))
        .body("items[0].config.id", is(PMH_CLIENT_ID))
        .body("items[0].config.sourceId", is(SOURCE_ID_1));
  }

}
