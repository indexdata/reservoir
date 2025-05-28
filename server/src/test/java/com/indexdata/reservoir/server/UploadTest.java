package org.folio.reservoir.server;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.multipart.MultipartForm;
import org.folio.okapi.common.XOkapiHeaders;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

@RunWith(VertxUnitRunner.class)
public class UploadTest extends TestBase {

  Buffer marc3marcBuffer;

  Buffer marc3xmlBuffer;

  Buffer marc1xmlBuffer;

  Buffer marc3NoIdXmlBuffer;

  private static final String PERM_PREFIX = "reservoir-upload.source";

  @Before
  public void before(TestContext context) {
    FileSystem fileSystem = vertx.fileSystem();
    fileSystem
        .readFile("src/test/resources/marc3.marc")
        .onSuccess(x -> marc3marcBuffer = x)
        .compose(x -> fileSystem.readFile("src/test/resources/marc3.xml"))
        .onSuccess(x -> marc3xmlBuffer = x)
        .compose(x -> fileSystem.readFile("src/test/resources/marc1-delete.xml"))
        .onSuccess(x -> marc1xmlBuffer = x)
        .compose(x -> fileSystem.readFile("src/test/resources/marc3-no-id.xml"))
        .onSuccess(x -> marc3NoIdXmlBuffer = x)
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadOctetStream(TestContext context) {
    MultipartForm body = MultipartForm.create()
        .binaryFileUpload("records", "tiny.mrc", Buffer.buffer("01234"), "application/octet-stream");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "path")
        .sendMultipartForm(body)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("Premature end of file encountered"));
        }));
  }

  @Test
  public void uploadNonFormOctetStream(TestContext context) {
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader("Content-Type", "application/octet-stream")
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "path")
        .sendBuffer(Buffer.buffer("1234"))
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("Premature end of file encountered"));
        }));
  }

  @Test
  public void uploadNonFormIso2709WithIngest(TestContext context) {
    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, "badtenant")
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendBuffer(marc3marcBuffer)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), containsString("does not exist (42P01)"));
        }));
  }

  @Test
  public void uploadNonFormNoContentType(TestContext context) {
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "path")
        .sendBuffer(Buffer.buffer("1234"))
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("Premature end of file encountered"));
        }));
  }

  @Test
  public void uploadIso2709WithIngest(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc")
        .binaryFileUpload("records", "marc1-delete.xml", marc1xmlBuffer,  "text/xml");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("xmlFixing", "true")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .compose(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("processed"), is(3));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("ignored"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("inserted"), is(3));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("updated"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("deleted"), is(0));
          assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("processed"), is(1));
          assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("ignored"), is(0));
          assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("inserted"), is(0));
          assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("updated"), is(0));
          assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("deleted"), is(1));
          return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-1\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(2));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadIso2709WithBadTenant(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc")
        .binaryFileUpload("records", "marc1-delete.xml", marc1xmlBuffer,  "text/xml");

    // go straight to module without Okapi intercepting
    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, "badtenant")
        .addQueryParam("xmlFixing", "true")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), containsString("does not exist (42P01)"));
        }));
  }

  @Test
  public void uploadIso2709WithoutIngest(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("ingest", "false")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("processed"), is(3));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("ignored"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("inserted"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("updated"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("deleted"), is(0));
        }));
  }

  @Test
  public void uploadNonFormIso2709WithoutIngest(TestContext context) {
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .putHeader("Content-Type", "application/octet-stream")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("ingest", "false")
        .addQueryParam("fileName", "marc3.mrc")
        .sendBuffer(marc3marcBuffer)
        .onComplete(context.asyncAssertSuccess(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("processed"), is(3));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("ignored"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("inserted"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("updated"), is(0));
          assertThat(responseBody.getJsonObject("marc3.mrc").getInteger("deleted"), is(0));
        }));
  }

  @Test
  public void uploadMarcXmlDelete(TestContext context) {
    MultipartForm requestForm1 = MultipartForm.create()
        .binaryFileUpload("records", "marc3.xml", marc3xmlBuffer,  "text/xml");
    MultipartForm requestForm2 = MultipartForm.create()
        .binaryFileUpload("records", "marc1-delete.xml", marc1xmlBuffer,  "text/xml");

    // upload 3 new records
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-2" + "\"]")
        .addQueryParam("xmlFixing", "false")
        .addQueryParam("sourceId", "SOURCE-2")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath",  "$.marc.fields[*].001")
        .sendMultipartForm(requestForm1)
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("processed"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("ignored"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("inserted"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("updated"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("deleted"), is(0));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-2\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(3));
          return null;
        })
        .compose(c1 ->
            // upload 1 "delete" record
            webClient.postAbs(OKAPI_URL + "/reservoir/upload")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-2" + "\"]")
                .addQueryParam("xmlFixing", "true")
                .addQueryParam("sourceId", "SOURCE-2")
                .addQueryParam("sourceVersion", "1")
                .addQueryParam("localIdPath",  "$.marc.fields[*].001")
                .sendMultipartForm(requestForm2))
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("processed"), is(1));
            assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("ignored"), is(0));
            assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("inserted"), is(0));
            assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("updated"), is(0));
            assertThat(responseBody.getJsonObject("marc1-delete.xml").getInteger("deleted"), is(1));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-2\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(2));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadMarcXmlTwice(TestContext context) {
    MultipartForm requestForm1 = MultipartForm.create()
        .binaryFileUpload("records", "marc3.xml", marc3xmlBuffer,  "text/xml");

    // upload 3 new records
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-5" + "\"]")
        .addQueryParam("xmlFixing", "false")
        .addQueryParam("sourceId", "SOURCE-5")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath",  "$.marc.fields[*].001")
        .sendMultipartForm(requestForm1)
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("processed"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("ignored"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("inserted"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("updated"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("deleted"), is(0));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-5\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(3));
          return null;
        })
        .compose(c1 ->
            // upload 1 "delete" record
            webClient.postAbs(OKAPI_URL + "/reservoir/upload")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-5" + "\"]")
                .addQueryParam("xmlFixing", "true")
                .addQueryParam("sourceId", "SOURCE-5")
                .addQueryParam("sourceVersion", "1")
                .addQueryParam("localIdPath",  "$.marc.fields[*].001")
                .sendMultipartForm(requestForm1))
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("processed"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("ignored"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("inserted"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("updated"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("deleted"), is(0));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-5\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(3));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadMarcXmlNoId(TestContext context) {
    MultipartForm requestForm1 = MultipartForm.create()
        .binaryFileUpload("records", "marc3-no-id.xml", marc3NoIdXmlBuffer,  "text/xml");

    // upload 3 new records, but one without 001
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-3" + "\"]")
        .addQueryParam("sourceId", "SOURCE-3")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm1)
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc3-no-id.xml").getInteger("processed"), is(3));
            assertThat(responseBody.getJsonObject("marc3-no-id.xml").getInteger("ignored"), is(1));
            assertThat(responseBody.getJsonObject("marc3-no-id.xml").getInteger("inserted"), is(2));
            assertThat(responseBody.getJsonObject("marc3-no-id.xml").getInteger("updated"), is(0));
            assertThat(responseBody.getJsonObject("marc3-no-id.xml").getInteger("deleted"), is(0));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-3\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(2));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadMarcXmlWithIdButPathGivesEmpty(TestContext context) {
    MultipartForm requestForm1 = MultipartForm.create()
        .binaryFileUpload("records", "marc3.xml", marc3xmlBuffer,  "text/xml");

    // upload 3 new records, but provide idPath that returns empty
    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-4" + "\"]")
        .addQueryParam("sourceId", "SOURCE-4")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "empty")
        .sendMultipartForm(requestForm1)
        .compose(res -> {
            JsonObject responseBody = res.bodyAsJsonObject();
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("processed"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("ignored"), is(3));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("inserted"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("updated"), is(0));
            assertThat(responseBody.getJsonObject("marc3.xml").getInteger("deleted"), is(0));
            return webClient.getAbs(OKAPI_URL + "/reservoir/records")
                .addQueryParam("query", "sourceId = \"SOURCE-4\"")
                .expect(ResponsePredicate.SC_OK)
                .putHeader(XOkapiHeaders.TENANT, TENANT_1)
                .send();
        })
        .map(res -> {
          JsonObject responseBody = res.bodyAsJsonObject();
          assertThat(responseBody.getJsonArray("items").size(), is(0));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadMarcXmlWithIdMalformedPath(TestContext context) {
    MultipartForm requestForm1 = MultipartForm.create()
        .binaryFileUpload("records", "marc3.xml", marc3xmlBuffer,  "text/xml");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-4" + "\"]")
        .addQueryParam("sourceId", "SOURCE-4")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "empt[y")
        .sendMultipartForm(requestForm1)
        .map(res -> {
          String responseBody = res.bodyAsString();
          assertThat(responseBody, startsWith("malformed 'localIdPath'"));
          return null;
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void uploadPdf(TestContext context) {
    WebClient webClient = WebClient.create(vertx);

    MultipartForm body = MultipartForm.create()
        .binaryFileUpload("records", "records.mrc", Buffer.buffer("0"),  "application/pdf");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "path")
        .sendMultipartForm(body)
        .onComplete(context.asyncAssertSuccess(res ->
            assertThat(res.bodyAsString(), containsString("Unsupported content-type: application/pdf"))
        ));
  }

  @Test
  public void uploadRaw(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_OK)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .addQueryParam("localIdPath", "path")
        .addQueryParam("raw", "true")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res ->
            assertThat(res.bodyAsString(), is("{}"))
        ));
  }

  @Test
  public void uploadMissingSourceId(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(OKAPI_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.TENANT, TENANT_1)
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("sourceId is a required parameter"));
        }));
  }

  @Test
  public void uploadMissingTenantAllSourcesPerm(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"reservoir-upload.all-sources\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("X-Okapi-Tenant header is missing"));
        }));
  }


  @Test
  public void uploadMissingTenant(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .putHeader(XOkapiHeaders.PERMISSIONS, "[\"" + PERM_PREFIX + "." + "SOURCE-1" + "\"]")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("X-Okapi-Tenant header is missing"));
        }));
  }

  @Test
  public void uploadMissingPermission(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_FORBIDDEN)
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("Insufficient permissions to upload records for source 'SOURCE-1'"));
        }));
  }

  @Test
  public void uploadMalformedPermission(TestContext context) {
    MultipartForm requestForm = MultipartForm.create()
        .binaryFileUpload("records", "marc3.mrc", marc3marcBuffer,  "application/marc");

    webClient.postAbs(MODULE_URL + "/reservoir/upload")
        .expect(ResponsePredicate.SC_FORBIDDEN)
        .putHeader(XOkapiHeaders.PERMISSIONS, "reservoir-upload.all-sources")
        .addQueryParam("sourceId", "SOURCE-1")
        .addQueryParam("sourceVersion", "1")
        .sendMultipartForm(requestForm)
        .onComplete(context.asyncAssertSuccess(res -> {
          assertThat(res.bodyAsString(), is("Cannot verify permissions to upload records for source 'SOURCE-1'"));
        }));
  }

}
